package dev.degoogle.app.microg

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.jar.JarFile

/**
 * microG package release published in the official F-Droid repository.
 */
data class Release(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val apkName: String,
    val sha256: String,
    val certSha256: String,
    val minSdk: Int?,
    val apkUrl: String,
) {
    val downloadFileName: String get() = apkName
}

/**
 * Fetches releases from the signed v2 index of the official microG F-Droid repo.
 *
 * The repository certificate is pinned so index hashes can be used as
 * a trusted source for APK validation.
 */
class ReleaseRepository(
    private val baseUrl: String = "https://microg.org/fdroid/repo/",
) {

    companion object {
        /** Some CDNs reject requests without a User-Agent. */
        private const val USER_AGENT = "DeGoogle/0.1.0 (Android; microg)"
        private const val REPOSITORY_CERT_SHA256 =
            "9bd06727e62796c0130eb6dab39b73157451582cbd138e86c468acc395d14165"
        private val json = Json { ignoreUnknownKeys = true }
    }

    private fun http(base: String): HttpURLConnection = run {
        val c = URL(base).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", USER_AGENT)
        c
    }

    suspend fun latest(packageName: String): Release? =
        fetchVerifiedIndexV2()?.let { parseIndexV2(it, packageName) }

    /**
     * GmsCore + Companion (FakeStore) pair from the same signed index.
     */
    data class MicrogReleases(val gms: Release, val companion: Release)

    suspend fun latestPair(): MicrogReleases? {
        val index = fetchVerifiedIndexV2() ?: return null
        val gms = parseIndexV2(index, MicrogPackages.GMS) ?: return null
        val companion = parseIndexV2(index, MicrogPackages.VENDING) ?: return null
        return MicrogReleases(gms, companion)
    }

    /** Downloads a release APK to [targetFile], periodically reporting progress in bytes and total bytes. */
    fun download(
        release: Release,
        targetFile: java.io.File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Boolean = runCatching {
        val c = http(release.apkUrl)
        c.readTimeout = 120_000
        if (c.responseCode !in 200..299) return false
        val totalBytes = c.contentLengthLong.takeIf { it > 0 } ?: (c.contentLength.toLong().takeIf { it > 0 } ?: -1L)
        var bytesRead = 0L
        var lastEmitMs = 0L
        val buffer = ByteArray(8 * 1024)

        c.inputStream.use { input ->
            targetFile.outputStream().use { output ->
                var read = input.read(buffer)
                while (read >= 0) {
                    if (read > 0) {
                        output.write(buffer, 0, read)
                        bytesRead += read
                        val now = System.currentTimeMillis()
                        if (now - lastEmitMs >= 200) {
                            lastEmitMs = now
                            onProgress(bytesRead, totalBytes)
                        }
                    }
                    read = input.read(buffer)
                }
                output.flush()
            }
        }
        onProgress(bytesRead, if (totalBytes > 0) totalBytes else bytesRead)
        true
    }.getOrDefault(false)

    // ------------------------------------------------------------------ index-v2

    @Serializable
    private data class EntryV2(@SerialName("index") val index: IndexFile = IndexFile())

    @Serializable
    private data class IndexFile(
        @SerialName("name") val name: String = "",
        @SerialName("sha256") val sha256: String = "",
        @SerialName("size") val size: Long = 0,
    )

    @Serializable
    private data class IndexV2(@SerialName("packages") val packages: Map<String, PackageV2> = emptyMap())

    @Serializable
    private data class PackageV2(@SerialName("versions") val versions: Map<String, PackageVersionV2> = emptyMap())

    @Serializable
    private data class PackageVersionV2(
        @SerialName("file") val file: FileV2 = FileV2(),
        @SerialName("manifest") val manifest: ManifestV2 = ManifestV2(),
    )

    @Serializable
    private data class FileV2(
        @SerialName("name") val name: String = "",
        @SerialName("sha256") val sha256: String = "",
        @SerialName("size") val size: Long = 0,
    )

    @Serializable
    private data class ManifestV2(
        @SerialName("versionCode") val versionCode: Long = 0,
        @SerialName("versionName") val versionName: String = "",
        @SerialName("usesSdk") val usesSdk: UsesSdkV2? = null,
        @SerialName("signer") val signer: SignerV2 = SignerV2(),
    )

    @Serializable
    private data class UsesSdkV2(@SerialName("minSdkVersion") val minSdk: Int? = null)

    @Serializable
    private data class SignerV2(@SerialName("sha256") val sha256: List<String> = emptyList())

    private fun fetchEntry(): ByteArray? = runCatching {
        val c = http(baseUrl + "entry.jar")
        if (c.responseCode !in 200..299) return null
        c.inputStream.use { readBytesAtMost(it, 1_000_000) }
    }.getOrNull()

    private fun fetchVerifiedIndexV2(): ByteArray? = runCatching {
        val entryBytes = fetchEntry() ?: return null
        val entryJson = verifiedEntryJson(entryBytes) ?: return null
        val entry = json.decodeFromString(EntryV2.serializer(), entryJson.decodeToString())
        if (entry.index.name != "/index-v2.json" || !entry.index.sha256.isSha256() ||
            entry.index.size !in 1..20_000_000
        ) {
            return null
        }
        val c = http(baseUrl + entry.index.name.removePrefix("/"))
        if (c.responseCode !in 200..299) return null
        val index = c.inputStream.use { readBytesAtMost(it, entry.index.size.toInt()) } ?: return null
        if (index.size.toLong() != entry.index.size || sha256(index) != entry.index.sha256.lowercase()) return null
        index
    }.getOrNull()

    internal fun verifiedEntryJson(jarBytes: ByteArray): ByteArray? = runCatching {
        val tempFile = File.createTempFile("microg-entry-", ".jar")
        try {
            tempFile.writeBytes(jarBytes)
            JarFile(tempFile, true).use { jar ->
                val entry = jar.getJarEntry("entry.json") ?: return null
                val contents = jar.getInputStream(entry).use { it.readBytes() }
                val certs = entry.certificates ?: return null
                val trusted = certs.any { cert -> sha256(cert.encoded) == REPOSITORY_CERT_SHA256 }
                contents.takeIf { trusted }
            }
        } finally {
            tempFile.delete()
        }
    }.getOrNull()

    internal fun parseIndexV2(indexBytes: ByteArray, packageName: String): Release? = runCatching {
        val index = json.decodeFromString(IndexV2.serializer(), indexBytes.decodeToString())
        val best = index.packages[packageName]?.versions?.values
            ?.filter { it.file.name.startsWith("/") && !it.file.name.contains("..") }
            ?.filter { it.file.sha256.isSha256() && it.manifest.signer.sha256.firstOrNull()?.isSha256() == true }
            ?.filter { it.manifest.versionCode > 0 && it.manifest.versionName.isNotBlank() }
            ?.filter { !it.file.name.contains("-hw") && !it.file.name.contains("-user") }
            ?.maxByOrNull { it.manifest.versionCode }
            ?: return null
        val apkName = best.file.name.removePrefix("/")
        if (apkName.isBlank() || apkName.contains('/')) return null
        Release(
            packageName = packageName,
            versionCode = best.manifest.versionCode,
            versionName = best.manifest.versionName,
            apkName = apkName,
            sha256 = best.file.sha256.lowercase(),
            certSha256 = best.manifest.signer.sha256.first().lowercase(),
            minSdk = best.manifest.usesSdk?.minSdk,
            apkUrl = baseUrl + apkName,
        )
    }.getOrNull()

    private fun String.isSha256(): Boolean =
        length == 64 && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun readBytesAtMost(input: InputStream, maxBytes: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
