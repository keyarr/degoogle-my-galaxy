package dev.degoogle.app.security

import dev.degoogle.app.domain.CapabilityStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class SignatureSpoofingProbeTest {

    @Test
    fun `duas assinaturas FakeGApps passam na prova funcional`() {
        val result = SignatureSpoofingEvaluation.evaluate(
            gmsDigests = listOf(SignatureSpoofingEvaluation.FAKEGAPPS_CERT_SHA256),
            storeDigests = listOf(SignatureSpoofingEvaluation.FAKEGAPPS_CERT_SHA256),
        )

        assertEquals(CapabilityStatus.PASS, result.status)
    }

    @Test
    fun `assinatura stock não passa como spoofada`() {
        val result = SignatureSpoofingEvaluation.evaluate(
            gmsDigests = listOf("stock-gms"),
            storeDigests = listOf("stock-store"),
        )

        assertEquals(CapabilityStatus.FAIL, result.status)
    }

    @Test
    fun `assinatura ausente permanece unknown`() {
        val result = SignatureSpoofingEvaluation.evaluate(null, emptyList())

        assertEquals(CapabilityStatus.UNKNOWN, result.status)
    }
}
