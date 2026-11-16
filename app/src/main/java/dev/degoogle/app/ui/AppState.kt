package dev.degoogle.app.ui

import dev.degoogle.app.domain.DeviceState
import dev.degoogle.app.domain.CompatibilityDecision
import dev.degoogle.app.domain.SystemFacts
import dev.degoogle.app.domain.OperationMode

/** Uma linha de log de uma operação (passos do backend, visíveis na UI). */
data class StepLog(
    val ok: Boolean?,
    val text: String,
)

enum class CheckStatus { PENDING, CONFIRMED, FAILED }

data class DeviceCheck(
    val id: String,
    val status: CheckStatus = CheckStatus.PENDING,
)

internal val INITIAL_DEVICE_CHECKS = listOf(
    "root",
    "device_properties",
    "transaction_snapshot",
    "package_locations",
    "mounts",
    "gms_details",
    "transaction_masks",
    "mount_namespace",
    "bind_mount",
    "selinux_mode",
    "selinux_context",
    "signature_spoofing",
    "post_boot_validation",
    "transaction_reconciliation",
    "compatibility",
).map(::DeviceCheck)

/** Navegação simples (sem dependência de navigation-compose). */
enum class Screen {
    HOME,
    DIAGNOSTICS,
    BACKUP,
}

data class UiState(
    val refreshing: Boolean = true,
    val hasResult: Boolean = false,
    val resultCurrent: Boolean = false,
    val checks: List<DeviceCheck> = INITIAL_DEVICE_CHECKS,
    val facts: SystemFacts = SystemFacts.EMPTY,
    val compatibility: CompatibilityDecision = CompatibilityDecision.EMPTY,
    val recoveryRequired: Boolean = false,
    val recoveryMessage: String? = null,
    val recoveryDetail: String? = null,
    val state: DeviceState = DeviceState.NO_ROOT,
    val operationInProgress: Boolean = false,
    val steps: List<StepLog> = emptyList(),
    val error: String? = null,
    /** true quando a primeira instalação precisa do modal de configuração. */
    val needsSetupPrompt: Boolean = false,
    /**
     * Aviso informativo (dispensável): a instalação terminou corretamente,
     * mas o boot atual veio de um reboot de emergência (Rescue Party).
     */
    val rescuePartyNotice: Boolean = false,
    /** Resultado do último soft reboot (para fallback). */
    val softRebootSupported: Boolean = true,
    val selectedMode: OperationMode? = null,
    val modeDialogVisible: Boolean = false,
    val fallbackRebootOptIn: Boolean = false,
) {
    companion object {
        val INITIAL = UiState()
    }
}
