package dev.degoogle.app.security

import android.content.pm.PackageManager
import android.os.Build
import dev.degoogle.app.domain.CapabilityResult
import dev.degoogle.app.domain.CapabilityStatus
import java.security.MessageDigest

/**
 * Verifica a capacidade funcional usada pelo FakeGApps, a partir do processo
 * do aplicativo. A mera presença do APK ou do módulo LSPosed não prova que o
 * PackageManager está retornando a assinatura spoofada.
 */
object SignatureSpoofingEvaluation {
    private const val GMS = "com.google.android.gms"
    private const val STORE = "com.android.vending"

    /** Certificado Android/Google embutido pelo FakeGApps. */
    const val FAKEGAPPS_CERT_SHA256 =
        "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83"

    /** Certificado oficial de release do microG GmsCore. */
    const val MICROG_CERT_SHA256 =
        "9bd06727e62796c0130eb6dab39b73157451582cbd138e86c468acc395d14165"

    fun evaluate(
        gmsDigests: List<String>?,
        storeDigests: List<String>?,
        backendResult: CapabilityResult? = null,
    ): CapabilityResult {
        val gmsSpoofed = gmsDigests?.any { it.equals(FAKEGAPPS_CERT_SHA256, ignoreCase = true) } == true
        val storeSpoofed = storeDigests?.any { it.equals(FAKEGAPPS_CERT_SHA256, ignoreCase = true) } == true
        val gmsMicroG = gmsDigests?.any { it.equals(MICROG_CERT_SHA256, ignoreCase = true) } == true

        if (gmsSpoofed && storeSpoofed) {
            return CapabilityResult.pass(
                "PackageManager retornou a assinatura FakeGApps para GMS e Play Store; " +
                    "verificação funcional concluída",
            )
        }

        if (gmsMicroG || gmsSpoofed) {
            return CapabilityResult.pass(
                "microG ativo com assinatura oficial ou FakeGApps verificado (gms=${gmsDigests?.joinToString()})",
            )
        }

        if (backendResult != null && backendResult.status != CapabilityStatus.FAIL) {
            return backendResult
        }

        if (gmsDigests.isNullOrEmpty() || storeDigests.isNullOrEmpty()) {
            return CapabilityResult.unknown(
                "assinaturas de GMS/Play Store não puderam ser lidas pelo PackageManager",
            )
        }

        return CapabilityResult.fail(
            "PackageManager ainda não retorna a assinatura spoofada para GMS e Play Store",
            "gms=${gmsDigests.joinToString()}; store=${storeDigests.joinToString()}",
        )
    }

    fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}

class SignatureSpoofingProbe(
    private val packageManager: PackageManager,
) {
    fun check(backendResult: CapabilityResult? = null): CapabilityResult = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        SignatureSpoofingEvaluation.evaluate(
            gmsDigests = readDigests("com.google.android.gms", flags),
            storeDigests = readDigests("com.android.vending", flags),
            backendResult = backendResult,
        )
    }.getOrElse { error ->
        backendResult ?: CapabilityResult.unknown(
            "não foi possível executar a verificação funcional: ${error.javaClass.simpleName}",
        )
    }

    private fun readDigests(packageName: String, flags: Int): List<String>? {
        val info = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, flags)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }

        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return null
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        return signatures?.map { SignatureSpoofingEvaluation.sha256(it.toByteArray()) }.orEmpty()
    }
}
