package dev.degoogle.app.domain

import androidx.annotation.StringRes
import dev.degoogle.app.R

/**
 * Máquina de estados do aparelho.
 *
 * O estado é SEMPRE derivado do estado real do sistema (fatos coletados pelo
 * backend `probe`). Nunca deve ser persistido como fonte de verdade.
 */
enum class DeviceState(@StringRes val labelRes: Int) {
    NO_ROOT(R.string.state_no_root),
    UNSUPPORTED(R.string.state_unsupported),
    STOCK(R.string.state_stock),
    PREPARING(R.string.state_preparing),
    PREPARED(R.string.state_prepared),
    MICROG_BOOTED(R.string.state_microg_booted),
    MICROG_NEEDS_SETUP(R.string.state_microg_needs_setup),
    MICROG_ACTIVE(R.string.state_microg_active),
    MICROG_ACTIVE_BACKED_UP(R.string.state_microg_active_backed_up),
    RESTORE_PREPARED(R.string.state_restore_prepared),
    ERROR(R.string.state_error);
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
