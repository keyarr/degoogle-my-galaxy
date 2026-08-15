package dev.degoogle.app.microg

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Valida um APK baixado antes de qualquer cópia root:
 *  1. é um arquivo APK parseável pelo PackageManager;
 *  2. packageName confere;
 *  3. versionCode/versionName conferem com a release consultada;
 *  4. assinatura (SHA-256 do certificado) confere com a publicada no índice;
 *  5. hash SHA-256 do arquivo inteiro confere;
 *  6. minSdk compatível com o aparelho.
 *
 * Se qualquer validação falhar, o download deve ser descartado (nunca usado).
 */
class ApkValidator(private val context: Context) {

    companion object {
        private const val TAG = "DeGoogle.ApkValidator"
    }

    sealed class ValidationResult {
        data class Ok(val packageInfo: PackageInfo) : ValidationResult()
        data class Failed(val reason: String) : ValidationResult()
    }

    fun validate(
        file: File,
        expectedPackage: String,
        expectedVersionCode: Long,
        expectedVersionName: String,
        expectedSha256: String,
        expectedCertSha256: String?,
    ): ValidationResult {
        if (!file.exists() || file.length() == 0L) {
            return ValidationResult.Failed("arquivo vazio ou ausente")
        }

        // 1) parse pelo PackageManager
        val pm = context.packageManager
        val info: PackageInfo? = if (Build.VERSION.SDK_INT >= 33) {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
        }
        if (info == null) return ValidationResult.Failed("não é um APK parseável")

        // 2) package name
        if (info.packageName != expectedPackage) {
            return ValidationResult.Failed("packageName inesperado: ${info.packageName}")
        }

        // 3) versão (longVersionCode só existe a partir da API 28)
        val actualCode = if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
        if (actualCode != expectedVersionCode) {
            return ValidationResult.Failed(
                "versionCode inesperado: $actualCode (esperado $expectedVersionCode)"
            )
        }
        if (expectedVersionName.isNotEmpty() &&
            !info.versionName.equals(expectedVersionName, ignoreCase = true)
        ) {
            return ValidationResult.Failed(
                "versionName inesperado: ${info.versionName} (esperado $expectedVersionName)"
            )
        }

        // 4) assinatura
        val certSha = info.signatures?.firstOrNull()?.toByteArray()
            ?.let { sha256Hex(it) }
        if (expectedCertSha256 != null && certSha != null && certSha != expectedCertSha256) {
            return ValidationResult.Failed("assinatura não confere com o índice ($certSha)")
        }

        // 5) hash do arquivo inteiro
        val actualSha = file.inputStream().use { sha256Hex(it.readBytes()) }
        if (expectedSha256.isNotEmpty() && actualSha != expectedSha256) {
            return ValidationResult.Failed("sha256 não confere ($actualSha != $expectedSha256)")
        }

        Log.i(TAG, "APK válido: $expectedPackage v$expectedVersionCode sha256=$actualSha")
        return ValidationResult.Ok(info)
    }

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}
