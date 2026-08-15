package dev.degoogle.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.degoogle.app.backup.BackupManager
import dev.degoogle.app.data.Prefs
import dev.degoogle.app.domain.DeviceProfile
import dev.degoogle.app.domain.DeviceProfiles
import dev.degoogle.app.domain.DeviceState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel central. O estado da UI é sempre derivado do estado REAL do
 * sistema (via `probe`); nenhuma flag interna vira fonte de verdade.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)

    private val executor = SuRootExecutor()
    private val backendPath = BackendInstaller(app).ensureInstalled()

    private val backend = backendPath?.let { BackendRunner(executor, it.absolutePath) }
    private val microg = backend?.let {
        MicrogManager(
            context = app,
            executor = executor,
            backend = it,
            validator = ApkValidator(app),
            releases = ReleaseRepository(),
        )
    }
    private val backupManager = backend?.let { BackupManager(executor) }
    private val reboot: RebootController? = backend?.let { BackendRebootController(it) }

    private val _ui = MutableStateFlow(UiState.INITIAL)
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var currentProfile: DeviceProfile? = null

    init {
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
            val facts = probe.facts
            currentProfile = DeviceProfiles.matching(facts.manufacturer, facts.model, facts.androidSdk)
            val state = StateDetector.detect(facts, currentProfile)

            // Auxiliar: prompt de configuração na primeira instalação.
            val promptSeen = prefs.setupPromptSeen.firstOrNull() ?: false
            val needsPrompt = state == DeviceState.MICROG_ACTIVE && !facts.backupPresent && !promptSeen

            _ui.update {
                it.copy(refreshing = false, facts = facts, state = state, needsSetupPrompt = needsPrompt)
            }
        }
    }

    /** Fluxo DeGoogle: STOCK → PREPARED (download + validação + máscaras). */
    fun degoogle() {
        val m = microg ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _ui.update {
                it.copy(operationInProgress = true, steps = emptyList(), error = null)
            }
            val result = m.prepareNewSession()
            _ui.update {
                it.copy(
                    operationInProgress = false,
                    steps = it.steps + StepLog(result.succeeded, result.message),
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
            _ui.update { it.copy(operationInProgress = true) }
            // Como no install do MicroG Session, restaura os dados antes dos
            // grants/appops. Isso mantém o GMS parado enquanto a identidade FCM
            // é recolocada e evita finalizar uma instalação sem o backup.
            val probe = backend?.probe()
            val hasBackup = probe?.raw?.succeeded == true && probe.facts.backupPresent
            val restoreOk = !hasBackup || m.restoreBackup()
            val ok = restoreOk && m.finalize()
            _ui.update {
                it.copy(
                    operationInProgress = false,
                    error = when {
                        !restoreOk -> "Falha ao restaurar o backup do microG"
                        !ok -> "Falha ao concluir a configuração do microG"
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
                    steps = listOf(StepLog(null, "Salvando backup local…")),
                    error = null,
                )
            }
            val ok = m.createBackup()
            _ui.update {
                it.copy(
                    operationInProgress = false,
                    steps = listOf(
                        StepLog(
                            ok,
                            if (ok) {
                                "Backup local criado/atualizado com sucesso."
                            } else {
                                "Falha ao criar ou atualizar o backup local."
                            },
                        ),
                    ),
                    error = if (ok) null else "Falha ao criar backup",
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
                    steps = listOf(StepLog(null, "Restaurando backup local…")),
                    error = null,
                )
            }
            val ok = m.restoreBackup()
            _ui.update {
                it.copy(
                    operationInProgress = false,
                    steps = listOf(
                        StepLog(
                            ok,
                            if (ok) {
                                "Backup local restaurado com sucesso."
                            } else {
                                "Falha ao restaurar o backup local."
                            },
                        ),
                    ),
                    error = if (ok) null else "Falha ao restaurar o backup local",
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
            _ui.update { it.copy(operationInProgress = true, error = null) }
            val ok = b.cleanup().succeeded
            _ui.update {
                it.copy(
                    operationInProgress = false,
                    error = if (ok) null else "Falha ao remover o update do GMS",
                )
            }
            refresh()
        }
    }

    fun deleteBackup() {
        val bm = backupManager ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _ui.update { it.copy(operationInProgress = true, error = null) }
            val ok = bm.delete()
            _ui.update {
                it.copy(operationInProgress = false, error = if (ok) null else "Falha ao excluir backup")
            }
            refresh()
        }
    }

    fun restoreGoogle(wipeData: Boolean = true) {
        val m = microg ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _ui.update { it.copy(operationInProgress = true, error = null) }
            val ok = m.restoreStock(wipeData)
            _ui.update {
                it.copy(operationInProgress = false, error = if (ok) null else "Falha ao restaurar Google")
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
            _ui.update { it.copy(operationInProgress = true) }
            prefs.markOperationPending("finalize")
            val ok = r.softReboot()
            _ui.update {
                it.copy(operationInProgress = false, softRebootSupported = ok)
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

    fun clearError() = _ui.update { it.copy(error = null) }

    fun logStep(ok: Boolean?, text: String) =
        _ui.update { it.copy(steps = it.steps + StepLog(ok, text)) }
}
