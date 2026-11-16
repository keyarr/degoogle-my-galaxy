package dev.degoogle.app.microg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.concurrent.thread

class ReleaseRepositoryDownloadTest {

    @Test
    fun `verifiedEntryJson rejeita indice sem assinatura confiavel`() {
        val jar = ByteArrayOutputStream().use { bytes ->
            JarOutputStream(bytes).use { output ->
                output.putNextEntry(JarEntry("entry.json"))
                output.write("{}".toByteArray())
                output.closeEntry()
            }
            bytes.toByteArray()
        }

        assertEquals(null, ReleaseRepository().verifiedEntryJson(jar))
    }

    @Test
    fun `parseIndexV2 exige hash e certificado do APK`() {
        val index = """
            {"packages":{"com.google.android.gms":{"versions":{"v1":{
              "file":{"name":"/com.google.android.gms-1.apk","sha256":"${"a".repeat(64)}","size":1},
              "manifest":{"versionCode":1,"versionName":"1.0","usesSdk":{"minSdkVersion":21},
                "signer":{"sha256":["${"b".repeat(64)}"]}}
            }}}}}
        """.trimIndent().toByteArray()

        val release = ReleaseRepository().parseIndexV2(index, MicrogPackages.GMS)

        assertEquals("a".repeat(64), release?.sha256)
        assertEquals("9bd06727e62796c0130eb6dab39b73157451582cbd138e86c468acc395d14165", release?.certSha256)
        assertEquals(21, release?.minSdk)
    }

    @Test
    fun `parseIndexV2 rejeita APK sem signer`() {
        val index = """
            {"packages":{"com.google.android.gms":{"versions":{"v1":{
              "file":{"name":"/com.google.android.gms-1.apk","sha256":"${"a".repeat(64)}","size":1},
              "manifest":{"versionCode":1,"versionName":"1.0"}
            }}}}}
        """.trimIndent().toByteArray()

        assertEquals(null, ReleaseRepository().parseIndexV2(index, MicrogPackages.GMS))
    }

    @Test
    fun `download reporta progresso e salva arquivo corretamente`() {
        val testData = "0123456789".repeat(1024).toByteArray() // 10 KB
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        val serverThread = thread {
            try {
                val client: Socket = serverSocket.accept()
                val reader = client.getInputStream().bufferedReader()
                while (true) {
                    val line = reader.readLine()
                    if (line.isNullOrEmpty()) break
                }

                val out = client.getOutputStream()
                val response = "HTTP/1.1 200 OK\r\n" +
                    "Content-Length: ${testData.size}\r\n" +
                    "Content-Type: application/octet-stream\r\n" +
                    "Connection: close\r\n\r\n"
                out.write(response.toByteArray())
                out.write(testData)
                out.flush()
                client.close()
            } catch (_: Exception) {
            } finally {
                serverSocket.close()
            }
        }

        val tempFile = File.createTempFile("test_download", ".apk")
        val progressUpdates = mutableListOf<Pair<Long, Long>>()

        try {
            val repo = ReleaseRepository()
            val release = Release(
                packageName = "com.google.android.gms",
                versionCode = 1,
                versionName = "1.0",
                apkName = "test.apk",
                sha256 = "",
                certSha256 = "",
                minSdk = null,
                apkUrl = "http://127.0.0.1:$port/test.apk",
            )

            val success = repo.download(release, tempFile) { bytesRead, totalBytes ->
                progressUpdates.add(bytesRead to totalBytes)
            }

            assertTrue(success)
            assertEquals(testData.size.toLong(), tempFile.length())
            assertTrue(progressUpdates.isNotEmpty())
            val lastUpdate = progressUpdates.last()
            assertEquals(testData.size.toLong(), lastUpdate.first)
            assertEquals(testData.size.toLong(), lastUpdate.second)
        } finally {
            serverThread.join(2000)
            tempFile.delete()
        }
    }
}
