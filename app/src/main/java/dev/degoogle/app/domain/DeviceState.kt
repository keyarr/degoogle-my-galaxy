package dev.degoogle.app.domain

/**
 * Máquina de estados do aparelho.
 *
 * O estado é SEMPRE derivado do estado real do sistema (fatos coletados pelo
 * backend `probe`). Nunca deve ser persistido como fonte de verdade.
 */
enum class DeviceState(val displayName: String) {
    NO_ROOT("Sem Root"),
    UNSUPPORTED("Não Suportado"),
    STOCK("Google Stock"),
    PREPARING("Preparando"),
    PREPARED("Preparado"),
    MICROG_BOOTED("microG Inicializado"),
    MICROG_NEEDS_SETUP("Configuração Necessária"),
    MICROG_ACTIVE("microG Ativo"),
    MICROG_ACTIVE_BACKED_UP("microG Ativo com Backup"),
    RESTORE_PREPARED("Restauração Preparada"),
    ERROR("Erro");
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
