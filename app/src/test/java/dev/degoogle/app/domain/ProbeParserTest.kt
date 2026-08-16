package dev.degoogle.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeParserTest {

    private val sample = """
        DEGOOGLE_ROOT_OK=1
        DEGOOGLE_ROOT_MANAGER=KernelSU
        DEGOOGLE_PROFILE_ID=samsung_sm-s928b
        DEGOOGLE_PROFILE_MATCH=1
        DEGOOGLE_MANUFACTURER=samsung
        DEGOOGLE_MODEL=SM-S928B
        DEGOOGLE_DEVICE=e3q
        DEGOOGLE_PRODUCT=e3qxxx
        DEGOOGLE_ANDROID_SDK=36
        DEGOOGLE_ANDROID_RELEASE=16
        DEGOOGLE_FINGERPRINT=samsung/e3qxxx/e3q:16/UP1A.231005.007:user/release-keys
        DEGOOGLE_BOARD=kalama
        DEGOOGLE_HARDWARE=qcom
        DEGOOGLE_BUILD_ID=UP1A.231005.007
        DEGOOGLE_SECURITY_PATCH=2026-08-01
        DEGOOGLE_ONE_UI_VERSION=8.5
        DEGOOGLE_KERNEL_VERSION=Linux test
        DEGOOGLE_ABI_LIST=arm64-v8a,armeabi-v7a
        DEGOOGLE_SELINUX=Enforcing
        DEGOOGLE_ABI=arm64-v8a
        DEGOOGLE_GMS_PATH=/product/priv-app/GmsCore/GmsCore.apk
        DEGOOGLE_GMS_VERSION=0.3.4.240913
        DEGOOGLE_GMS_VERSION_CODE=23484
        DEGOOGLE_GMS_UID=10123
        DEGOOGLE_GMS_FLAGS= PRIVILEGED SYSTEM
        DEGOOGLE_GMS_PRIVILEGED=1
        DEGOOGLE_GSF_PATH=
        DEGOOGLE_STORE_PATH=/product/priv-app/Phonesky/Companion.apk
        DEGOOGLE_PREPARATION_INFO=GMS: update em /data/app será removido durante a preparação
        DEGOOGLE_STORE_VERSION=0.3.0
        DEGOOGLE_GMS_ACTIVE_CODE_PATH=/product/priv-app/GmsCore/base.apk
        DEGOOGLE_GMS_ORIGINAL_SYSTEM_PATH=/product/priv-app/GmsCore/base.apk
        DEGOOGLE_GMS_TARGET_DIRECTORY=/product/priv-app/GmsCore
        DEGOOGLE_GMS_BASE_APK=/product/priv-app/GmsCore/base.apk
        DEGOOGLE_GMS_SPLIT_APKS=/product/priv-app/GmsCore/split_config.arm64.apk
        DEGOOGLE_GMS_HAS_DATA_UPDATE=0
        DEGOOGLE_CAP_GLOBAL_MOUNT_NAMESPACE_STATUS=PASS
        DEGOOGLE_CAP_GLOBAL_MOUNT_NAMESPACE_EVIDENCE=nsenter exit=0
        DEGOOGLE_CAP_BIND_MOUNT_STATUS=PASS
        DEGOOGLE_CAP_SELINUX_CONTEXT_CLONABLE_STATUS=PASS
        DEGOOGLE_CAP_SIGNATURE_SPOOFING_STATUS=PASS
        DEGOOGLE_CAP_PACKAGE_MANAGER_CACHE_ACCESS_STATUS=PASS
        DEGOOGLE_CAP_SAFE_SOFT_REBOOT_STATUS=WARN
        DEGOOGLE_CAP_SAFE_RESTORE_STATUS=PASS
        DEGOOGLE_REBOOT_STRATEGY_BACKEND=KERNELSU
        DEGOOGLE_REBOOT_STRATEGY_METHOD=KSUD_SOFT_REBOOT
        DEGOOGLE_REBOOT_STRATEGY_CONFIDENCE=LOW
        DEGOOGLE_MOUNT_GMS=1
        DEGOOGLE_MOUNT_GMS_IS_LEGACY=0
        DEGOOGLE_MOUNT_GSF=1
        DEGOOGLE_MOUNT_GSF_IS_LEGACY=0
        DEGOOGLE_MOUNT_STORE=1
        DEGOOGLE_MOUNT_STORE_IS_LEGACY=0
        DEGOOGLE_MOUNT_GMS_SOURCE=/data/local/tmp/degoogle-mask/gms
        DEGOOGLE_BACKUP_PRESENT=1
        DEGOOGLE_MASK_RESIDUE_PRESENT=1
        DEGOOGLE_FINALIZE_DONE=1
        DEGOOGLE_STATE=MICROG_ACTIVE_BACKED_UP
        DEGOOGLE_SCRIPT_VERSION=0.1.0
    """.trimIndent()

    @Test
    fun `parse dos fatos principais`() {
        val f = ProbeParser.parse(sample)
        assertTrue(f.rootOk)
        assertEquals("KernelSU", f.rootManager)
        assertTrue(f.profileMatch)
        assertEquals("samsung", f.manufacturer)
        assertEquals("SM-S928B", f.model)
        assertEquals("36", f.androidSdk)
        assertEquals("kalama", f.board)
        assertEquals("UP1A.231005.007", f.buildId)
        assertEquals(listOf("arm64-v8a", "armeabi-v7a"), f.abiList)
        assertEquals("/product/priv-app/GmsCore/GmsCore.apk", f.gmsPath)
        assertEquals("0.3.4.240913", f.gmsVersion)
        assertEquals("10123", f.gmsUid)
        assertTrue(f.gmsPrivileged)
        assertEquals(null, f.gsfPath)
        assertEquals("GMS: update em /data/app será removido durante a preparação", f.preparationInfo)
        assertTrue(f.mountGms)
        assertTrue(!f.mountGmsIsLegacy)
        assertTrue(f.mountGsf)
        assertTrue(f.mountStore)
        assertTrue(f.maskResiduePresent)
        assertEquals("/data/local/tmp/degoogle-mask/gms", f.mountGmsSource)
        assertEquals("/product/priv-app/GmsCore/base.apk", f.gmsPackage?.baseApk)
        assertEquals(listOf("/product/priv-app/GmsCore/split_config.arm64.apk"), f.gmsPackage?.splitApks)
        assertEquals(CapabilityStatus.PASS, f.capabilityResults[Capability.BIND_MOUNT]?.status)
        assertEquals(CapabilityStatus.WARN, f.capabilityResults[Capability.SAFE_SOFT_REBOOT]?.status)
        assertEquals("KSUD_SOFT_REBOOT", f.rebootStrategy?.method)
        assertTrue(f.backupPresent)
        assertTrue(f.finalizeDone)
        assertEquals(DeviceState.MICROG_ACTIVE_BACKED_UP, f.shellState)
    }

    @Test
    fun `parse sem root resulta em rootOk false e state NO_ROOT`() {
        val out = """
            DEGOOGLE_ROOT_OK=0
            DEGOOGLE_STATE=NO_ROOT
        """.trimIndent()
        val f = ProbeParser.parse(out)
        assertFalse(f.rootOk)
        assertEquals(DeviceState.NO_ROOT, f.shellState)
        assertEquals(null, f.gmsPath)
    }

    @Test
    fun `linhas que não são do protocolo são ignoradas`() {
        val out = """
            texto humano
            DEGOOGLE_ROOT_OK=1
            outro texto
            DEGOOGLE_STATE=STOCK
        """.trimIndent()
        val f = ProbeParser.parse(out)
        assertTrue(f.rootOk)
        assertEquals(DeviceState.STOCK, f.shellState)
    }
}
