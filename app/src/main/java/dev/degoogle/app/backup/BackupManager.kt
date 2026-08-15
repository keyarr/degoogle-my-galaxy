package dev.degoogle.app.backup

import dev.degoogle.app.root.RootExecutor

/**
 * Operações auxiliares sobre o backup no formato do MicroG Session.
 *
 * Criação e restauração são executadas pelo backend shell; esta classe mantém
 * apenas a exclusão explícita solicitada pela UI.
 */
class BackupManager(
    private val executor: RootExecutor,
) {

    /** Exclui o backup. O app pede confirmação antes de chamar isto. */
    suspend fun delete(): Boolean {
        val r = executor.execute(
            listOf("rm", "-rf", "/data/local/tmp/microg-backup")
        )
        return r.succeeded
    }
}
