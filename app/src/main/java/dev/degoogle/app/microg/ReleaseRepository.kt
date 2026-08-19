package dev.degoogle.app.microg

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Release de um pacote microG publicada no repositório F-Droid oficial.
 */
data class Release(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val apkName: String,
    val sha256: String,
    val minSdk: Int?,
    val apkUrl: String,
) {
    val downloadFileName: String get() = apkName
}

/**
 * Consulta o repo F-Droid oficial do microG (https://microg.org/fdroid/repo/):
 *  - index-v2.json (JSON) — preferido;
 *  - fallback: index-v1.jar (zip com index.xml), parseado com XmlPullParser
 *    (sem dependência extra).
 *
 * Nenhuma versão é hardcoded: sempre a última versionCode disponível.
 */
class ReleaseRepository(
    private val baseUrl: String = "https://microg.org/fdroid/repo/",
    private val githubApiBase: String = "https://api.github.com/repos/microG/GmsCore",
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    companion object {
        /** GitHub exige User-Agent (rejeita sem); alguns CDNs também. */
        private const val USER_AGENT = "DeGoogle/0.1.0 (Android; microg)"
    }

    private fun http(base: String): HttpURLConnection = run {
        val c = URL(base).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", USER_AGENT)
        c
    }

    /**
     * Consulta a release estável mais recente do pacote:
     *  1. GitHub Releases (releases/latest — já filtra prereleases);
     *  2. fallback: F-Droid repo oficial (index-v2, depois index-v1).
     */
    suspend fun latest(packageName: String): Release? {
        return latestFromGithub(packageName)
            ?: runCatching {
                val v2 = fetchIndexV2()
                if (v2 != null) {
                    parseIndexV2(v2, packageName) ?: parseIndexV1(fetchIndexV1(), packageName)
                } else {
                    parseIndexV1(fetchIndexV1(), packageName)
                }
            }.getOrNull()
    }

    // --------------------------------------------------------------- GitHub

    @Serializable
    private data class GhRelease(
        @SerialName("tag_name") val tagName: String = "",
        @SerialName("assets") val assets: List<GhAsset> = emptyList(),
    )

    @Serializable
    private data class GhAsset(
        @SerialName("name") val name: String = "",
        @SerialName("size") val size: Long = 0,
        @SerialName("browser_download_url") val url: String = "",
    )

    /** Release (não-prerelease) mais recente do GitHub, com o asset do pacote. */
    internal fun latestFromGithub(packageName: String): Release? = runCatching {
        val c = http("$githubApiBase/releases/latest")
        if (c.responseCode !in 200..299) return null
        val body = c.inputStream.bufferedReader().use { it.readText() }
        val rel = json.decodeFromString(GhRelease.serializer(), body)
        val asset = rel.assets.firstOrNull { a ->
            a.name.startsWith("$packageName-") &&
                a.name.endsWith(".apk") &&
                !a.name.contains("-hw") &&
                !a.name.contains("-user")
        } ?: return null
        val versionCode = asset.name
            .removePrefix("$packageName-")
            .removeSuffix(".apk")
            .toLongOrNull() ?: return null
        Release(
            packageName = packageName,
            versionCode = versionCode,
            versionName = rel.tagName.removePrefix("v"),
            apkName = asset.name,
            sha256 = "", // GitHub não publica hash; validamos package/versão/parse
            minSdk = null,
            apkUrl = asset.url,
        )
    }.getOrNull()

    // ------------------------------------------------------------------ v2

    /** Baixa o APK de uma release para [targetFile], emitindo callbacks periódicos com o progresso em bytes e bytes totais. */
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

    // ------------------------------------------------------------------ v2

    @Serializable
    private data class IndexV2(
        @SerialName("packages") val packages: Map<String, PackageV2> = emptyMap(),
    )

    /** Cada pacote do índice v2 tem `metadata` e `versions` (chave = sha256). */
    @Serializable
    private data class PackageV2(
        @SerialName("versions") val versions: Map<String, PackageVersionV2> = emptyMap(),
    )

    @Serializable
    private data class PackageVersionV2(
        @SerialName("file") val file: FileV2 = FileV2(),
        @SerialName("manifest") val manifest: ManifestV2 = ManifestV2(),
    ) {
        /** sha256 = chave do map de versões (igual a file.sha256). */
        val sha256: String get() = file.sha256
        val apkName: String get() = file.name.removePrefix("/")
    }

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
    )

    @Serializable
    private data class UsesSdkV2(@SerialName("minSdkVersion") val minSdkVersion: Int? = null)

    private fun fetchIndexV2(): String? = runCatching {
        val c = http(baseUrl + "index-v2.json")
        c.requestMethod = "GET"
        if (c.responseCode !in 200..299) return null
        val s = c.inputStream.bufferedReader().use { it.readText() }
        if (s.length > 200_000_000) null else s
    }.getOrNull()

    private fun parseIndexV2(body: String, packageName: String): Release? = runCatching {
        val idx = json.decodeFromString(IndexV2.serializer(), body)
        val versions = idx.packages[packageName]?.versions ?: return null
        val best = versions.values.maxByOrNull { it.manifest.versionCode } ?: return null
        if (best.apkName.isEmpty() || best.sha256.isEmpty()) return null
        Release(
            packageName = packageName,
            versionCode = best.manifest.versionCode,
            versionName = best.manifest.versionName,
            apkName = best.apkName,
            sha256 = best.sha256,
            minSdk = best.manifest.usesSdk?.minSdkVersion,
            apkUrl = baseUrl + best.apkName,
        )
    }.getOrNull()

    // ------------------------------------------------------------------ v1

    private fun fetchIndexV1(): ByteArray? = runCatching {
        val c = http(baseUrl + "index-v1.jar")
        if (c.responseCode !in 200..299) return null
        c.inputStream.use { it.readBytes() }
    }.getOrNull()

    private fun parseIndexV1(jar: ByteArray?, packageName: String): Release? {
        if (jar == null) return null
        return runCatching {
            val xmlBytes = ZipInputStream(ByteArrayInputStream(jar)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "index.xml") {
                        return@use zip.readBytes()
                    }
                    entry = zip.nextEntry
                }
                null
            } ?: return null

            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(ByteArrayInputStream(xmlBytes), "UTF-8")

            var currentApp: String? = null
            var currentVersionCode = 0L
            var currentVersionName = ""
            var currentApkName = ""
            var currentSha256 = ""
            var best: Release? = null

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "application" -> currentApp = parser.getAttributeValue(null, "id")
                        "versioncode" -> currentVersionCode = parser.nextText().toLongOrNull() ?: 0
                        "versionname" -> currentVersionName = parser.nextText()
                        "apkname" -> currentApkName = parser.nextText()
                        "hash" -> {
                            if (parser.getAttributeValue(null, "type") == "sha256") {
                                currentSha256 = parser.nextText()
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "version" && currentApp == packageName) {
                        if (currentVersionCode > (best?.versionCode ?: 0L) &&
                            currentApkName.isNotEmpty() && currentSha256.isNotEmpty()
                        ) {
                            best = Release(
                                packageName = packageName,
                                versionCode = currentVersionCode,
                                versionName = currentVersionName,
                                apkName = currentApkName,
                                sha256 = currentSha256,
                                minSdk = null,
                                apkUrl = baseUrl + currentApkName,
                            )
                        }
                        currentVersionCode = 0
                        currentVersionName = ""
                        currentApkName = ""
                        currentSha256 = ""
                    }
                }
                event = parser.next()
            }
            best
        }.getOrNull()
    }
}
