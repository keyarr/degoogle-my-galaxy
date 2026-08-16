package dev.degoogle.app.ui

import android.app.Application
import java.io.File
import java.util.UUID
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.degoogle.app.backup.BackupManager
import dev.degoogle.app.data.Prefs
import dev.degoogle.app.domain.DeviceProfile
import dev.degoogle.app.domain.DeviceProfiles
import dev.degoogle.app.domain.DeviceState
import dev.degoogle.app.domain.CompatibilityEngine
import dev.degoogle.app.domain.KnownGoodDatabase
import dev.degoogle.app.domain.TransactionJournalStore
import dev.degoogle.app.domain.TransactionState
import dev.degoogle.app.domain.StateDetector
import dev.degoogle.app.domain.SystemFacts
import dev.degoogle.app.microg.ApkValidator
import dev.degoogle.app.microg.MicrogManager
import dev.degoogle.app.microg.ReleaseRepository
import dev.degoogle.app.reboot.BackendRebootController
import dev.degoogle.app.reboot.RebootController
import dev.degoogle.app.root.BackendInstaller
import dev.degoogle.app.root.BackendRunner
import dev.degoogle.app.root.SuRootExecutor
import dev.degoogle.app.security.SignatureSpoofingProbe
import dev.degoogle.app.domain.Capability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import dev.degoogle.app.R
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel central. O estado da UI é sempre derivado do estado REAL do
 * sistema (via `probe`); nenhuma flag interna vira fonte de verdade.
 */
class AppViewModel(private val app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val transactionJournal = TransactionJournalStore(
        File(app.filesDir, "transaction/journal.json"),
    )
    private val knownGoodDatabase: KnownGoodDatabase = runCatching {
        app.assets.open("compatibility/known_good.json").bufferedReader().use {
            KnownGoodDatabase.fromJson(it.readText())
        }
    }.getOrElse { KnownGoodDatabase.fallback() }

    private val executor = SuRootExecutor()
    private val signatureSpoofingProbe = SignatureSpoofingProbe(app.packageManager)
    private val backendPath = BackendInstaller(app).ensureInstalled()

    private val backend = backendPath?.let {
        BackendRunner(
            executor = executor,
            backendPath = it.absolutePath,
            experimentalOptIn = { experimentalOptIn.value },
            transactionBase = File(app.filesDir, "transaction").absolutePath,
            operationId = { activeOperationId ?: transactionJournal.read()?.operationId },
            onProgress = ::appendOperationLog,
        )
    }
    private val microg = backend?.let {
        MicrogManager(
            context = app,
            executor = executor,
            backend = it,
            validator = ApkValidator(app),
            releases = ReleaseRepository(),
            onStep = ::appendOperationLog,
        )
    }
    private val backupManager = backend?.let { BackupManager(executor) }
    private val reboot: RebootController? = backend?.let { BackendRebootController(it) }

    private val _ui = MutableStateFlow(UiState.INITIAL)
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _notificationsEnabled = MutableStateFlow(true)
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

    private val _experimentalOptIn = MutableStateFlow(false)
    val experimentalOptIn: StateFlow<Boolean> = _experimentalOptIn.asStateFlow()

    private var currentProfile: DeviceProfile? = null
    private var activeOperationId: String? = null

    /**
     * Continua a mesma transação depois de o processo/app ser recriado. Um
     * rollback ou uma validação pós-boot não pode receber um operationId vazio
     * só porque o ViewModel perdeu a memória volátil.
     */
    private fun operationIdForCurrentTransaction(): String {
        activeOperationId?.takeIf { it.isNotBlank() }?.let { return it }
        val persisted = transactionJournal.read()?.operationId?.takeIf { it.isNotBlank() }
        val id = persisted ?: UUID.randomUUID().toString()
        activeOperationId = id
        return id
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _notificationsEnabled.value = prefs.notificationsEnabled.first()
            _experimentalOptIn.value = prefs.experimentalOptIn.first()
        }
        refresh(clearError = true)
    }

    /**
     * Re-deriva o estado real do sistema. Chamar após qualquer operação.
     * Por padrão PRESERVA o [UiState.error] da operação anterior (para o
     * usuário ver o que falhou); use [clearError] = true no arranque.
     */
    fun refresh(clearError: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            _ui.update { it.copy(refreshing = true, error = if (clearError) null else it.error) }

            if (!executor.isRootAvailable()) {
                _ui.update { it.copy(refreshing = false, state = DeviceState.NO_ROOT) }
                return@launch
            }
            val backend = backend ?: run {
                _ui.update {
                    it.copy(refreshing = false, state = DeviceState.ERROR, error = "Backend não instalado")
                }
                return@launch
            }

            val probe = backend.probe()
            if (!probe.raw.succeeded) {
                _ui.update {
                    it.copy(refreshing = false, state = DeviceState.ERROR, error = "probe falhou: ${probe.raw.stderr}")
                }
                return@launch
            }
            val facts = probe.facts.copy(
                // O backend detecta módulos e emite evidência, mas a prova
                // decisiva precisa ocorrer via PackageManager no processo do
                // app, que é o caminho efetivamente usado pelas aplicações.
                capabilityResults = probe.facts.capabilityResults + (
                    Capability.SIGNATURE_SPOOFING to signatureSpoofingProbe.check(
                        probe.facts.capabilityResults[Capability.SIGNATURE_SPOOFING],
                    )
                ),
            )
            currentProfile = DeviceProfiles.matching(facts.manufacturer, facts.model, facts.androidSdk)
            val state = StateDetector.detect(facts, currentProfile)
            val compatibility = CompatibilityEngine.evaluate(facts, knownGoodDatabase)
            val journal = transactionJournal.read()
            val stockConfirmed = state == DeviceState.STOCK || (
                !facts.mountGms && !facts.mountGsf && !facts.mountStore &&
                    facts.gmsPath != null && facts.gsfPath != null && facts.storePath != null
                )
            if (stockConfirmed && journal?.state in setOf(
                    TransactionState.ROLLBACK_RUNNING,
                    TransactionState.ROLLBACK_REQUIRED,
                    TransactionState.GMS_UNMOUNTED,
                    TransactionState.GSF_UNMOUNTED,
                    TransactionState.STORE_UNMOUNTED,
                    TransactionState.REINDEX_PENDING,
                )
            ) {
                journal?.let {
                    transactionJournal.update(
                        operationId = it.operationId,
                        fingerprint = facts.fingerprint,
                        state = TransactionState.RESTORED,
                        detail = "estado stock confirmado após rollback",
                    )
                }
            }
            val recoveryRequired = transactionJournal.requiresRecovery()

            // Auxiliar: prompt de configuração na primeira instalação.
            val promptSeen = prefs.setupPromptSeen.firstOrNull() ?: false
            val needsPrompt = state == DeviceState.MICROG_ACTIVE && !facts.backupPresent && !promptSeen

            _ui.update {
                it.copy(
                    refreshing = false,
                    facts = facts,
                    state = state,
                    compatibility = compatibility,
                    recoveryRequired = recoveryRequired,
                    needsSetupPrompt = needsPrompt,
                )
            }
        }
    }

    /** Fluxo DeGoogle: STOCK → PREPARED (download + validação + máscaras). */
    fun degoogle() {
        val m = microg ?: return
        val decision = _ui.value.compatibility
        val experimental = _experimentalOptIn.value
        val allowed = decision.canExecuteNormally ||
            (experimental && decision.canExecuteExperimental)
        if (!allowed) {
            val message = when {
                decision.compatibility == dev.degoogle.app.domain.DeviceCompatibility.PROBABLY_SUPPORTED && !experimental ->
                    app.getString(R.string.compat_err_not_homologated)
                decision.compatibility == dev.degoogle.app.domain.DeviceCompatibility.SUPPORTED ->
                    app.getString(R.string.compat_err_preflight_blocked)
                else ->
                    app.getString(R.string.compat_err_execution_blocked, decision.compatibility.name)
            }
            _ui.update { it.copy(error = message, steps = listOf(StepLog(false, message))) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            activeOperationId = UUID.randomUUID().toString()
            transactionJournal.update(
                operationId = operationIdForCurrentTransaction(),
                fingerprint = _ui.value.facts.fingerprint,
                state = TransactionState.PREFLIGHT_OK,
                detail = "compatibility engine authorized; backend preflight pending",
            )
            _ui.update {
                it.copy(
                    operationInProgress = true,
                    steps = listOf(StepLog(null, app.getString(R.string.op_preparing_env))),
                    error = null,
                )
            }
            val result = m.prepareNewSession()
            transactionJournal.update(
                operationId = operationIdForCurrentTransaction(),
                fingerprint = _ui.value.facts.fingerprint,
                state = if (result.succeeded) TransactionState.STORE_MASKED else TransactionState.FAILED,
                detail = result.message,
            )
            _ui.update {
                it.copy(
                    operationInProgress = false,
                    steps = (it.steps + StepLog(
                        result.succeeded,
                        if (result.succeeded) {
                            app.getString(R.string.op_prepared_success)
                        } else {
                            result.message
                        },
                    )).takeLast(MAX_OPERATION_LOG_LINES),
                    error = if (result.succeeded) null else result.message,
                )
            }
            refresh()
        }
    }

    /** Executa a fase final pós-boot (MICROG_BOOTED → ativo). */
    fun finalizeIfNeeded() {
        val m = microg ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _ui.update {
                it.copy(
                    operationInProgress = true,
                    steps = listOf(StepLog(null, app.getString(R.string.op_validating_post_boot))),
                    error = null,
                )
            }
            // Como no install do MicroG Session, restaura os dados antes dos
            // grants/appops. Isso mantém o GMS parado enquanto a identidade FCM
            // é recolocada e evita finalizar uma instalação sem o backup.
            val probe = backend?.probe()
            val hasBackup = probe?.raw?.succeeded == true && probe.facts.backupPresent
            val restoreOk = !hasBackup || m.restoreBackup()
            val ok = restoreOk && m.finalize()
            transactionJournal.update(
                operationId = operationIdForCurrentTransaction(),
                fingerprint = _ui.value.facts.fingerprint,
                state = if (ok) TransactionState.COMMITTED else TransactionState.ROLLBACK_REQUIRED,
                detail = if (ok) "post-boot validation completed" else "finalize or post-boot validation failed",
            )
            _ui.update {
                it.copy(
                    operationInProgress = false,
                    steps = (it.steps + StepLog(
                        ok,
                        when {
                            !restoreOk -> app.getString(R.string.op_restore_backup_failed)
                            !ok -> app.getString(R.string.op_post_boot_failed)
                            else -> app.getString(R.string.op_post_boot_success)
                        },
                    )).takeLast(MAX_OPERATION_LOG_LINES),
                    error = when {
                        !restoreOk -> app.getString(R.string.op_restore_backup_failed)
                        !ok -> app.getString(R.string.op_finalize_failed)
                        else -> it.error
                    },
                )
            }
            refresh()
        }
    }

    fun createBackup() {
        val m = microg ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _ui.update {
                it.copy(
                    operationInProgress = true,
                    steps = listOf(StepLog(null, app.getString(R.string.op_creating_backup))),
                    error = null,
                )
            }
            val ok = m.createBackup()
            _ui.update {
                it.copy(
                    operationInProgress = false,
                    steps = (it.steps + StepLog(
                        ok,
                        if (ok) {
                            app.getString(R.string.op_backup_success)
                        } else {
                            app.getString(R.string.op_backup_failed)
                        },
                    )).takeLast(MAX_OPERATION_LOG_LINES),
                    error = if (ok) null else app.getString(R.string.op_backup_failed),
                )
            }
            refresh()
        }
    }

    /** Restaura manualmente o backup local no formato do MicroG Session. */
    fun restoreBackup() {
        val m = microg ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _ui.update {
                it.copy(
                    operationInProgress = true,
                    steps = listOf(StepLog(null, app.getString(R.string.op_restoring_backup))),
                    error = null,
                )
            }
            val ok = m.restoreBackup()
            _ui.update {
                it.copy(
                    operationInProgress = false,
                    steps = (it.steps + StepLog(
                        ok,
                        if (ok) {
                            app.getString(R.string.op_restore_success)
                        } else {
                            app.getString(R.string.op_restore_backup_failed)
                        },
                    )).takeLast(MAX_OPERATION_LOG_LINES),
                    error = if (ok) null else app.getString(R.string.op_restore_backup_failed),
                )
            }
            refresh()
        }
    }

    /**
     * Recuperação do estado ERROR com GMS/vending em /data/app: remove os
     * updates (volta ao base) e desabilita a Play Store para evitar re-update.
     */
    fun removeGmsUpdate() {
        val b = backend ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _ui.update {
                it.copy(
                    operationInProgress = true,
                    steps = listOf(StepLog(null, app.getString(R.string.op_removing_updates))),
                    error = null,
                )
            }
            val ok = b.cleanup().succeeded
            _ui.update {
                it.copy(
                    operationInProgress = false,
                    steps = (it.steps + StepLog(
                        ok,
                        if (ok) app.getString(R.string.op_updates_removed) else app.getString(R.string.op_updates_remove_failed),
                    )).takeLast(MAX_OPERATION_LOG_LINES),
                    error = if (ok) null else app.getString(R.string.op_updates_remove_failed),
                )
            }
            refresh()
        }
    }

    fun deleteBackup() {
        val bm = backupManager ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _ui.update {
                it.copy(
                    operationInProgress = true,
                    steps = listOf(StepLog(null, app.getString(R.string.op_deleting_backup))),
                    error = null,
                )
            }
            val ok = bm.delete()
            _ui.update {
                it.copy(
                    operationInProgress = false,
                    steps = (it.steps + StepLog(
                        ok,
                        if (ok) app.getString(R.string.op_backup_deleted) else app.getString(R.string.op_delete_backup_failed),
                    )).takeLast(MAX_OPERATION_LOG_LINES),
                    error = if (ok) null else app.getString(R.string.op_delete_backup_failed),
                )
            }
            refresh()
        }
    }

    fun restoreGoogle(wipeData: Boolean = true) {
        val m = microg ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _ui.update {
                it.copy(
                    operationInProgress = true,
                    steps = listOf(StepLog(null, app.getString(R.string.op_rollback_starting))),
                    error = null,
                )
            }
            val ok = m.restoreStock(wipeData)
            transactionJournal.update(
                operationId = operationIdForCurrentTransaction(),
                fingerprint = _ui.value.facts.fingerprint,
                state = if (ok) TransactionState.REINDEX_PENDING else TransactionState.ROLLBACK_REQUIRED,
                detail = if (ok) {
                    "rollback prepared; Package Manager will reindex after reboot"
                } else {
                    "restore-stock failed"
                },
            )
            _ui.update {
                it.copy(
                    operationInProgress = false,
                    steps = (it.steps + StepLog(
                        ok,
                        if (ok) app.getString(R.string.op_rollback_prepared) else app.getString(R.string.op_rollback_failed),
                    )).takeLast(MAX_OPERATION_LOG_LINES),
                    error = if (ok) null else app.getString(R.string.op_rollback_failed),
                )
            }
            refresh()
        }
    }

    /**
     * Soft reboot (userspace) — o único reboot permitido. Aparelhos com root
     * via exploit perdem root e microG num kernel reboot; por isso NUNCA há
     * fallback para reboot completo aqui.
     */
    fun softReboot() {
        val r = reboot ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _ui.update {
                it.copy(
                    operationInProgress = true,
                    steps = listOf(StepLog(null, "Iniciando soft reboot…")),
                    error = null,
                )
            }
            prefs.markOperationPending("finalize")
            transactionJournal.update(
                operationId = operationIdForCurrentTransaction(),
                fingerprint = _ui.value.facts.fingerprint,
                state = TransactionState.REBOOT_REQUESTED,
                detail = "soft reboot solicitado; não assumir sucesso até pós-boot",
            )
            val ok = r.softReboot()
            if (!ok) {
                transactionJournal.update(
                    operationId = operationIdForCurrentTransaction(),
                    fingerprint = _ui.value.facts.fingerprint,
                    state = TransactionState.FAILED,
                    detail = "soft reboot recusado",
                )
            }
            _ui.update {
                it.copy(
                    operationInProgress = false,
                    steps = (it.steps + StepLog(
                        ok,
                        if (ok) "Soft reboot solicitado; aguardando validação pós-boot." else "Soft reboot recusado.",
                    )).takeLast(MAX_OPERATION_LOG_LINES),
                    softRebootSupported = ok,
                )
            }
            if (!ok) {
                _ui.update {
                    it.copy(
                        error = "Soft reboot não suportado neste build. Reinicie o framework pelo seu " +
                            "gerenciador de root — nunca use reboot completo (perde root e microG).",
                    )
                }
            }
            refresh()
        }
    }

    fun dismissSetupPrompt() {
        viewModelScope.launch(Dispatchers.IO) { prefs.markSetupPromptSeen() }
        _ui.update { it.copy(needsSetupPrompt = false) }
    }

    /** Liga/desliga as notificações de operação pendente (pós-boot). */
    fun setNotificationsEnabled(enabled: Boolean) {
        _notificationsEnabled.value = enabled
        viewModelScope.launch(Dispatchers.IO) { prefs.setNotificationsEnabled(enabled) }
    }

    /** Habilita o opt-in; a decisão final continua sendo do CompatibilityEngine. */
    fun setExperimentalOptIn(enabled: Boolean) {
        _experimentalOptIn.value = enabled
        viewModelScope.launch(Dispatchers.IO) { prefs.setExperimentalOptIn(enabled) }
        refresh()
    }

    fun clearError() = _ui.update { it.copy(error = null) }

    private fun appendOperationLog(text: String) = appendOperationLog(null, text)

    private fun appendOperationLog(ok: Boolean?, text: String) {
        val line = text.trim().replace('\u0000', ' ').takeIf { it.isNotBlank() } ?: return
        _ui.update {
            it.copy(
                steps = (it.steps + StepLog(ok, line)).takeLast(MAX_OPERATION_LOG_LINES),
            )
        }
    }

    fun logStep(ok: Boolean?, text: String) = appendOperationLog(ok, text)

    private companion object {
        const val MAX_OPERATION_LOG_LINES = 24
    }
}
