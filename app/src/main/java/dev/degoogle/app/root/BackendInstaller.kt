package dev.degoogle.app.root

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Copia `assets/root/degoogle.sh` para o filesDir do app e garante que o
 * conteúdo não foi adulterado (compara com o hash do asset a cada execução).
 */
class BackendInstaller(private val context: Context) {

    companion object {
        private const val TAG = "DeGoogle.Backend"
        private const val ASSET = "root/degoogle.sh"
        private const val FILE_NAME = "degoogle.sh"
    }

    /** Caminho do backend instalado (ou null se a instalação falhou). */
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
            Log.i(TAG, "backend instalado em ${target.absolutePath} (${assetBytes.size} bytes)")
        }
        target
    }.onFailure { Log.e(TAG, "falha ao instalar backend", it) }.getOrNull()

    fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}
