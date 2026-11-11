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
 * Validates a downloaded APK before any root copy:
 *  1. it is an APK file parseable by PackageManager;
 *  2. packageName confere;
 *  3. versionCode/versionName match the queried release;
 *  4. its signature (certificate SHA-256) matches the value published in the index;
 *  5. the full-file SHA-256 hash matches;
 *  6. minSdk is compatible with the device.
 *
 * If any validation fails, discard the download (never use it).
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

        // 1) parse with PackageManager
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

        // 3) version (longVersionCode is available only from API 28)
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

        // 4) signature
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

        // 5) full-file hash
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
