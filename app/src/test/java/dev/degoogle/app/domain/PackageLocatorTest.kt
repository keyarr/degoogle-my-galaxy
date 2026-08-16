package dev.degoogle.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageLocatorTest {
    @Test
    fun `split APKs e caminho de sistema são preservados`() {
        val info = PackageLocator.parse(
            packageName = "com.google.android.gms",
            pmPathOutput = """
                package:/product/priv-app/GmsCore/base.apk
                package:/product/priv-app/GmsCore/split_config.arm64_v8a.apk
            """.trimIndent(),
            realPaths = mapOf("/product/priv-app/GmsCore/base.apk" to "/product/priv-app/GmsCore/base.apk"),
            filesystemType = "erofs",
            backingPartition = "/dev/block/dm-1",
        )
        assertEquals("/product/priv-app/GmsCore/base.apk", info.activeCodePath)
        assertEquals("/product/priv-app/GmsCore/base.apk", info.originalSystemPath)
        assertEquals("/product/priv-app/GmsCore", info.targetDirectory)
        assertEquals(1, info.splitApks.size)
        assertEquals("erofs", info.filesystemType)
        assertTrue(info.safeTarget)
        assertFalse(info.hasDataUpdate)
    }

    @Test
    fun `update em data app é reportado sem ser tratado como sistema`() {
        val info = PackageLocator.parse(
            packageName = "com.google.android.gms",
            pmPathOutput = "package:/data/app/~~abc/com.google.android.gms/base.apk",
            dumpsysOutput = "codePath=/data/app/~~abc/com.google.android.gms",
        )
        assertTrue(info.hasDataUpdate)
        assertFalse(info.safeTarget)
        assertEquals(null, info.originalSystemPath)
    }

    @Test
    fun `path com traversal é rejeitado`() {
        val info = PackageLocator.parse(
            packageName = "com.android.vending",
            pmPathOutput = "package:/product/priv-app/../data/local/tmp/base.apk",
        )
        assertFalse(info.safeTarget)
        assertFalse(PackageLocator.isAllowedSystemPath("/product/priv-app/../foo"))
    }
}
