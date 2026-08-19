package dev.degoogle.app.microg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class ReleaseRepositoryDownloadTest {

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
