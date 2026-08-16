package dev.degoogle.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceFactsTest {
    @Test
    fun `propriedades ausentes permanecem desconhecidas`() {
        val facts = DeviceFacts.fromProperties(
            mapOf(
                "ro.product.manufacturer" to "samsung",
                "ro.product.model" to "SM-TEST",
                "ro.build.version.sdk" to "36",
            ),
        )

        assertEquals("samsung", facts.manufacturer)
        assertEquals("SM-TEST", facts.model)
        assertEquals(36, facts.sdk)
        assertNull(facts.device)
        assertNull(facts.buildFingerprint)
        assertEquals(emptyList<String>(), facts.abiList)
    }

    @Test
    fun `one ui e abi list usam propriedades reais`() {
        val facts = DeviceFacts.fromProperties(
            mapOf(
                "ro.build.version.sem" to "8.5",
                "ro.product.cpu.abilist" to "arm64-v8a, armeabi-v7a",
            ),
            kernelVersion = "Linux test",
        )

        assertEquals("8.5", facts.oneUiVersion)
        assertEquals(listOf("arm64-v8a", "armeabi-v7a"), facts.abiList)
        assertEquals("Linux test", facts.kernelVersion)
    }
}
