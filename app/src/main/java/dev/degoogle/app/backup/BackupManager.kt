package dev.degoogle.app.backup

import dev.degoogle.app.root.RootExecutor

/**
 * Utility operations for backups in the MicroG Session format.
 *
 * Creation and restoration are handled by the shell backend; this class only handles
 * explicit deletion requested by the UI.
 */
class BackupManager(
    private val executor: RootExecutor,
) {

    /** Deletes the backup. The app asks for confirmation before calling this. */
    suspend fun delete(): Boolean {
        val r = executor.execute(
            listOf("rm", "-rf", "/data/local/tmp/microg-backup")
        )
        return r.succeeded
    }
}
