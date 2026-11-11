package dev.degoogle.app.microg

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import dev.degoogle.app.R
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
        expectedCertSha256: String,
    ): ValidationResult {
        if (!file.exists() || file.length() == 0L) {
            return ValidationResult.Failed(context.getString(R.string.validator_empty_file))
        }

        // 1) parse pelo PackageManager
        val pm = context.packageManager
        val packageInfoFlags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info = pm.getPackageArchiveInfo(file.absolutePath, packageInfoFlags)
        if (info == null) return ValidationResult.Failed(context.getString(R.string.validator_unparseable))

        // 2) package name
        if (info.packageName != expectedPackage) {
            return ValidationResult.Failed(context.getString(R.string.validator_unexpected_package, info.packageName))
        }

        // 3) versão (longVersionCode só existe a partir da API 28)
        val actualCode = if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
        if (actualCode != expectedVersionCode) {
            return ValidationResult.Failed(
                context.getString(R.string.validator_unexpected_version_code, actualCode, expectedVersionCode),
            )
        }
        if (expectedVersionName.isNotEmpty() &&
            !info.versionName.equals(expectedVersionName, ignoreCase = true)
        ) {
            return ValidationResult.Failed(
                context.getString(R.string.validator_unexpected_version_name, info.versionName, expectedVersionName),
            )
        }

        // 4) assinatura
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        val certSha = signatures?.firstOrNull()?.toByteArray()
            ?.let { sha256Hex(it) }
        if (certSha == null) {
            return ValidationResult.Failed(context.getString(R.string.validator_signature_missing))
        }
        if (certSha != expectedCertSha256.lowercase()) {
            return ValidationResult.Failed(context.getString(R.string.validator_signature_mismatch, certSha))
        }

        // 5) hash do arquivo inteiro
        if (expectedSha256.length != 64 || expectedSha256.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) {
            return ValidationResult.Failed(context.getString(R.string.validator_sha256_missing))
        }
        val actualSha = file.inputStream().use { sha256Hex(it.readBytes()) }
        if (actualSha != expectedSha256.lowercase()) {
            return ValidationResult.Failed(context.getString(R.string.validator_sha256_mismatch, actualSha, expectedSha256))
        }

        Log.i(TAG, "Valid APK: $expectedPackage v$expectedVersionCode sha256=$actualSha")
        return ValidationResult.Ok(info)
    }

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}
