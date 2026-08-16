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
import dev.degoogle.app.reboot.SoftRebootFailure
import dev.degoogle.app.reboot.SoftRebootResult
import dev.degoogle.app.recovery.AutoRecoveryAction
import dev.degoogle.app.recovery.AutoRecoveryCoordinator
import dev.degoogle.app.recovery.AutoRecoveryStatus
import dev.degoogle.app.recovery.RecoveryPolicy
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ViewModel central. O estado da UI é sempre derivado do estado REAL do
 * sistema (via `probe`); nenhuma flag interna vira fonte de verdade.
 */
class AppViewModel(private val app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val transactionJournal = TransactionJournalStore.forAppFiles(app.filesDir)
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
    private val automaticRecovery = backend?.let { AutoRecoveryCoordinator(it) }
    private val automaticRecoveryStarted = AtomicBoolean(false)

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
                    it.copy(
                        refreshing = false,
                        state = DeviceState.ERROR,
                        error = app.getString(R.string.error_backend_not_installed),
                    )
                }
                return@launch
            }

            val probe = backend.probe()
            if (!probe.raw.succeeded) {
                _ui.update {
                    it.copy(
                        refreshing = false,
                        state = DeviceState.ERROR,
                        error = app.getString(
                            R.string.error_probe_failed,
                            probe.raw.stderr,
                        ),
                    )
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
            val recoveryAssessment = RecoveryPolicy.assess(facts, currentProfile)

            // Um reboot completo pode apagar os mounts sem reindexar o PM. O
            // app não deixa esse estado esperando uma ação manual: o backend
            // faz rollback idempotente, preserva o backup e solicita somente
            // soft reboot. O AtomicBoolean impede loop dentro do mesmo
            // processo; após um novo boot o diagnóstico começa novamente.
            if (automaticRecovery != null &&
                recoveryAssessment.action != AutoRecoveryAction.NONE &&
                automaticRecoveryStarted.compareAndSet(false, true)
            ) {
                _ui.update {
                    it.copy(
                        refreshing = false,
                        facts = facts,
                        state = state,
                        compatibility = compatibility,
                        recoveryRequired = true,
                        operationInProgress = true,
                        steps = (it.steps + StepLog(
                            null,
                            app.getString(R.string.op_auto_recovery_starting_log),
                        )).takeLast(MAX_OPERATION_LOG_LINES),
                        error = null,
                    )
                }

                val result = automaticRecovery.runIfNeeded(wipeData = true)
                when (result.status) {
                    AutoRecoveryStatus.REBOOT_REQUESTED -> {
                        transactionJournal.update(
                            operationId = operationIdForCurrentTransaction(),
                            fingerprint = facts.fingerprint,
                            state = TransactionState.REBOOT_REQUESTED,
                            detail = "recuperação automática solicitou soft reboot",
                        )
                        _ui.update {
                            it.copy(
                                operationInProgress = false,
                                steps = (it.steps + StepLog(
                                    true,
                                    app.getString(R.string.op_auto_recovery_reboot_requested_log),
                                )).takeLast(MAX_OPERATION_LOG_LINES),
                            )
                        }
                    }
                    AutoRecoveryStatus.CLEANED -> {
                        _ui.update {
                            it.copy(
                                operationInProgress = false,
                                steps = (it.steps + StepLog(
                                    true,
                                    app.getString(R.string.op_auto_recovery_success_log),
                                )).takeLast(MAX_OPERATION_LOG_LINES),
                            )
                        }
                        refresh(clearError = true)
                    }
                    AutoRecoveryStatus.FAILED -> {
                        transactionJournal.update(
                            operationId = operationIdForCurrentTransaction(),
                            fingerprint = facts.fingerprint,
                            state = TransactionState.ROLLBACK_REQUIRED,
                            detail = result.message,
                        )
                        _ui.update {
                            it.copy(
                                operationInProgress = false,
                                recoveryRequired = true,
                                steps = (it.steps + StepLog(
                                    false,
                                    app.getString(R.string.op_auto_recovery_failed_log),
                                )).takeLast(MAX_OPERATION_LOG_LINES),
                                error = localizedAutoRecoveryFailure(result.stderr),
                            )
                        }
                    }
                    AutoRecoveryStatus.NOT_NEEDED -> Unit
                }
                return@launch
            }

            val journal = transactionJournal.read()
            val stockConfirmed = state == DeviceState.STOCK
            if (stockConfirmed && journal?.state in setOf(
                    TransactionState.ROLLBACK_RUNNING,
                    TransactionState.ROLLBACK_REQUIRED,
                    TransactionState.REBOOT_REQUESTED,
                    TransactionState.PACKAGE_CACHE_INVALIDATED,
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
                prefs.clearPendingOperation()
            }
            // STORE_MASKED/PREPARED é o estado normal entre a preparação e o
            // soft reboot. Uma tentativa de reboot recusada não desfaz as
            // máscaras; portanto não pode transformar PREPARED em "transação
            // incompleta" só porque o journal registrou a recusa.
            val recoveryRequired = transactionJournal.requiresRecovery() &&
                state != DeviceState.PREPARED

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
            val stateBeforeReboot = _ui.value.state
            val journalStateBeforeReboot = transactionJournal.read()?.state
            _ui.update {
                it.copy(
                    operationInProgress = true,
                    steps = listOf(StepLog(null, app.getString(R.string.op_soft_reboot_started_log))),
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
            val result = r.softReboot()
            val ok = result.succeeded
            if (!ok) {
                val stateAfterRefusal = when {
                    stateBeforeReboot == DeviceState.PREPARED -> TransactionState.STORE_MASKED
                    stateBeforeReboot == DeviceState.RESTORE_PREPARED -> TransactionState.REINDEX_PENDING
                    journalStateBeforeReboot != null && journalStateBeforeReboot != TransactionState.REBOOT_REQUESTED ->
                        journalStateBeforeReboot
                    else -> TransactionState.FAILED
                }
                transactionJournal.update(
                    operationId = operationIdForCurrentTransaction(),
                    fingerprint = _ui.value.facts.fingerprint,
                    state = stateAfterRefusal,
                    detail = "soft reboot recusado",
                )
                // A refused request never started a reboot. Do not leave a
                // stale finalize notification pending for the next boot.
                prefs.clearPendingOperation()
            }
            _ui.update {
                it.copy(
                    operationInProgress = false,
                    steps = (it.steps + StepLog(
                        ok,
                        if (ok) {
                            app.getString(R.string.op_soft_reboot_requested_log)
                        } else {
                            softRebootFailureMessage(result)
                        },
                    )).takeLast(MAX_OPERATION_LOG_LINES),
                    softRebootSupported = when (result.failure) {
                        SoftRebootFailure.UNSUPPORTED -> false
                        else -> if (ok) true else it.softRebootSupported
                    },
                )
            }
            if (!ok) {
                _ui.update {
                    it.copy(
                        error = softRebootFailureMessage(result),
                    )
                }
            }
            refresh()
        }
    }

    private fun softRebootFailureMessage(result: SoftRebootResult): String {
        return when (result.failure) {
            SoftRebootFailure.COOLDOWN -> app.getString(R.string.op_soft_reboot_cooldown_log)
            SoftRebootFailure.UNSUPPORTED -> app.getString(R.string.op_soft_reboot_unsupported_log)
            SoftRebootFailure.FAILED, null -> {
                val detail = result.detail.lineSequence()
                    .map(String::trim)
                    .lastOrNull(String::isNotEmpty)
                if (detail.isNullOrBlank()) {
                    app.getString(R.string.op_soft_reboot_failed_log)
                } else {
                    app.getString(R.string.op_soft_reboot_failed_detail_log, detail)
                }
            }
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
        val line = localizeProgressLine(text.trim().replace('\u0000', ' '))
            .takeIf { it.isNotBlank() } ?: return
        _ui.update {
            it.copy(
                steps = (it.steps + StepLog(ok, line)).takeLast(MAX_OPERATION_LOG_LINES),
            )
        }
    }

    fun logStep(ok: Boolean?, text: String) = appendOperationLog(ok, text)

    private fun localizedAutoRecoveryFailure(stderr: String): String {
        val detail = stderr.lineSequence()
            .map(String::trim)
            .lastOrNull(String::isNotEmpty)
            ?.let(::localizeProgressLine)
            .orEmpty()
        return if (detail.isBlank()) {
            app.getString(R.string.op_auto_recovery_failed_log)
        } else {
            app.getString(R.string.op_auto_recovery_failed_detail_log, detail)
        }
    }

    private fun localizeProgressLine(raw: String): String {
        if (raw.isBlank()) return raw
        val normalized = raw.trim()

        // O backend também é executável diretamente pelo root e, por isso,
        // ainda emite algumas mensagens históricas em português. Normalize os
        // marcos operacionais em recursos do app para que o live log respeite
        // o idioma selecionado e não duplique textos em caixa alta.
        when {
            normalized.startsWith("PREPARE CONCLUÍDO") ->
                return app.getString(R.string.op_prepared_success)
            normalized.startsWith("FINALIZE CONCLUÍDO") ->
                return app.getString(R.string.op_post_boot_success)
            normalized == "BACKUP CONCLUÍDO." ->
                return app.getString(R.string.op_backup_success)
            normalized == "RESTAURAÇÃO CONCLUÍDA." ->
                return app.getString(R.string.op_restore_success)
            normalized.startsWith("RESTORE-STOCK PREPARADO") ->
                return app.getString(R.string.op_rollback_prepared)
            normalized.startsWith("Resíduos conhecidos das máscaras removidos") ->
                return app.getString(R.string.op_auto_recovery_success_log)
            normalized.startsWith("Solicitando soft reboot") ->
                return app.getString(R.string.op_soft_reboot_requesting_log)
            normalized.startsWith("Play Store desabilitada") ->
                return app.getString(R.string.backend_store_disabled)
            normalized.startsWith("[1/3] Aplicando máscaras") ->
                return app.getString(R.string.op_applying_masks)
            normalized.startsWith("[2/3] SELinux") ->
                return app.getString(R.string.backend_selinux_restorecon)
            normalized.startsWith("[3/3] Verificação") ->
                return app.getString(R.string.backend_verification)
            normalized.startsWith("mascarado:") ->
                return app.getString(
                    R.string.backend_masked_target,
                    normalized.substringAfter(':').trim(),
                )
            normalized.contains("já mascarado por nós") ->
                return app.getString(
                    R.string.backend_already_masked,
                    normalized.substringBefore(" já mascarado por nós").trim(),
                )
            normalized.startsWith("contexto GMS ok:") ->
                return app.getString(
                    R.string.backend_gms_context_ok,
                    normalized.substringAfter(':').trim(),
                )
            normalized.startsWith("GSF : mascarado (vazio)") ->
                return app.getString(R.string.backend_gsf_masked_empty)
            normalized.startsWith("GMS :") ->
                return app.getString(
                    R.string.backend_gms_listing,
                    normalized.substringAfter(':').trim(),
                )
            normalized.startsWith("Store:") ->
                return app.getString(
                    R.string.backend_store_listing,
                    normalized.substringAfter(':').trim(),
                )
        }

        val language = app.resources.configuration.locales[0].language
        if (language.startsWith("pt")) return raw

        return when {
            normalized.contains("já houve um soft reboot automático recentemente") ->
                app.getString(R.string.op_soft_reboot_cooldown_log)
            normalized.contains("soft reboot não é suportado nesta build") ->
                app.getString(R.string.op_soft_reboot_unsupported_log)
            normalized.startsWith("ERRO: cleanup do ambiente falhou") ->
                app.getString(R.string.backend_cleanup_failed)
            normalized.startsWith("não foi possível desabilitar a Play Store") ->
                app.getString(R.string.backend_store_disable_failed)
            normalized.startsWith("AVISO:") ->
                normalized.replaceFirst("AVISO:", "WARNING:")
            normalized.startsWith("ERRO:") ->
                normalized.replaceFirst("ERRO:", "ERROR:")
            normalized.startsWith("FALHA:") ->
                normalized.replaceFirst("FALHA:", "FAILURE:")
            else -> raw
        }
    }

    private companion object {
        const val MAX_OPERATION_LOG_LINES = 24
    }
}
