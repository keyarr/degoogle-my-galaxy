package dev.degoogle.app.domain

/**
 * Máquina de estados do aparelho.
 *
 * O estado é SEMPRE derivado do estado real do sistema (fatos coletados pelo
 * backend `probe`). Nunca deve ser persistido como fonte de verdade.
 */
enum class DeviceState {
    NO_ROOT,
    UNSUPPORTED,
    STOCK,
    PREPARING,
    PREPARED,
    MICROG_BOOTED,
    MICROG_NEEDS_SETUP,
    MICROG_ACTIVE,
    MICROG_ACTIVE_BACKED_UP,
    RESTORE_PREPARED,
    ERROR,
}

/** Ações que a UI pode oferecer dependendo do estado. */
enum class AppAction(val label: String) {
    DEGOOGLE("DeGoogle"),
    SOFT_REBOOT("Soft Reboot"),
    CANCEL_AND_RESTORE("Cancelar e restaurar"),
    OPEN_MICROG("Abrir microG"),
    CREATE_BACKUP("Criar backup"),
    UPDATE_BACKUP("Atualizar backup"),
    RESTORE_GOOGLE("Restaurar Google"),
    VIEW_DIAGNOSTICS("Diagnóstico"),
    NONE(""),
}
