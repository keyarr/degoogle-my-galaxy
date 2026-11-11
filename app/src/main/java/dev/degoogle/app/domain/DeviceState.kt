package dev.degoogle.app.domain

import androidx.annotation.StringRes
import dev.degoogle.app.R

/**
 * Device state machine.
 *
 * State is ALWAYS derived from actual system state (facts collected by the
 * backend `probe`). It must never be persisted as the source of truth.
 */
enum class DeviceState(@StringRes val labelRes: Int) {
    NO_ROOT(R.string.state_no_root),
    UNSUPPORTED(R.string.state_unsupported),
    STOCK(R.string.state_stock),
    PREPARING(R.string.state_preparing),
    PREPARED(R.string.state_prepared),
    DEGOOGLE_PREPARED(R.string.state_degoogle_prepared),
    DEGOOGLE_ACTIVE(R.string.state_degoogle_active),
    MICROG_BOOTED(R.string.state_microg_booted),
    MICROG_NEEDS_SETUP(R.string.state_microg_needs_setup),
    MICROG_ACTIVE(R.string.state_microg_active),
    MICROG_ACTIVE_BACKED_UP(R.string.state_microg_active_backed_up),
    RESTORE_PREPARED(R.string.state_restore_prepared),
    ERROR(R.string.state_error);
}

/** Actions the UI can offer depending on the state. */
enum class AppAction(val label: String) {
    DEGOOGLE("DeGoogle"),
    SOFT_REBOOT("Soft Reboot"),
    CANCEL_AND_RESTORE("Cancel and restore"),
    OPEN_MICROG("Open microG"),
    CREATE_BACKUP("Create backup"),
    UPDATE_BACKUP("Update backup"),
    RESTORE_GOOGLE("Restore Google"),
    VIEW_DIAGNOSTICS("Diagnostics"),
    NONE(""),
}
