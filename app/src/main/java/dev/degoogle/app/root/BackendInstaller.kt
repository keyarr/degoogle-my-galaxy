package dev.degoogle.app.root

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Copies `assets/root/degoogle.sh` to the app filesDir and ensures the
 * content has not been tampered with (compared against the asset hash on each run).
 */
class BackendInstaller(private val context: Context) {

    companion object {
        private const val TAG = "DeGoogle.Backend"
        private const val ASSET = "root/degoogle.sh"
        private const val FILE_NAME = "degoogle.sh"
    }

    /** Path to the installed backend (or null if installation failed). */
    fun ensureInstalled(): File? = runCatching {
        val target = File(context.filesDir, FILE_NAME)
        val assetBytes = context.assets.open(ASSET).use { it.readBytes() }
        val needWrite = !target.exists() ||
            !target.readBytes().contentEquals(assetBytes)
        if (needWrite) {
            target.writeBytes(assetBytes)
            target.setExecutable(true, false)
            target.setReadable(true, false)
            target.setWritable(true, true)
            Log.i(TAG, "backend installed at ${target.absolutePath} (${assetBytes.size} bytes)")
        }
        target
    }.onFailure { Log.e(TAG, "failed to install backend", it) }.getOrNull()

    fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}
