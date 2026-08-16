package dev.degoogle.app.ui

import dev.degoogle.app.domain.DeviceState
import dev.degoogle.app.domain.CompatibilityDecision
import dev.degoogle.app.domain.SystemFacts

/** Uma linha de log de uma operação (passos do backend, visíveis na UI). */
data class StepLog(
    val ok: Boolean?,
    val text: String,
)

/** Navegação simples (sem dependência de navigation-compose). */
enum class Screen {
    HOME,
    DIAGNOSTICS,
    BACKUP,
    SETTINGS,
}

data class UiState(
    val refreshing: Boolean = true,
    val facts: SystemFacts = SystemFacts.EMPTY,
    val compatibility: CompatibilityDecision = CompatibilityDecision.EMPTY,
    val recoveryRequired: Boolean = false,
    val state: DeviceState = DeviceState.NO_ROOT,
    val operationInProgress: Boolean = false,
    val steps: List<StepLog> = emptyList(),
    val error: String? = null,
    /** true quando a primeira instalação precisa do modal de configuração. */
    val needsSetupPrompt: Boolean = false,
    /** Resultado do último soft reboot (para fallback). */
    val softRebootSupported: Boolean = true,
) {
    companion object {
        val INITIAL = UiState()
    }
}
