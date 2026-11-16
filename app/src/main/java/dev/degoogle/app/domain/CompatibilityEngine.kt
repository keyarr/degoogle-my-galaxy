package dev.degoogle.app.domain

import kotlinx.serialization.Serializable

@Serializable
enum class DeviceCompatibility {
    SUPPORTED,
    PROBABLY_SUPPORTED,
    REQUIRES_PROFILE,
    UNSUPPORTED,
    UNSAFE,
}

@Serializable
data class CompatibilityReport(
    val schemaVersion: Int = 1,
    val device: DeviceFacts,
    val rootBackend: RootBackendType,
    val packages: Map<String, SystemPackageInfo> = emptyMap(),
    val capabilities: Map<String, CapabilityResult> = emptyMap(),
    val rebootStrategy: RebootStrategy? = null,
    val compatibility: DeviceCompatibility,
    val knownGoodMatch: KnownGoodMatch,
    val canExecuteNormally: Boolean,
    val reasons: List<String> = emptyList(),
)

data class CompatibilityDecision(
    val compatibility: DeviceCompatibility,
    val knownGood: KnownGoodMatchResult,
    val matrix: CapabilityMatrix,
    val device: DeviceFacts,
    val rebootStrategy: RebootStrategy?,
    val canExecuteNormally: Boolean,
    val reasons: List<String>,
) {
    fun report(facts: SystemFacts): CompatibilityReport = CompatibilityReport(
        device = device,
        rootBackend = rootBackendTypeFrom(facts.rootManager),
        packages = buildMap {
            facts.gmsPackage?.let { put("com.google.android.gms", it) }
            facts.gsfPackage?.let { put("com.google.android.gsf", it) }
            facts.storePackage?.let { put("com.android.vending", it) }
        },
        capabilities = matrix.asReportMap(),
        rebootStrategy = rebootStrategy,
        compatibility = compatibility,
        knownGoodMatch = knownGood.level,
        canExecuteNormally = canExecuteNormally,
        reasons = reasons,
    )

    companion object {
        val EMPTY = CompatibilityDecision(
            compatibility = DeviceCompatibility.REQUIRES_PROFILE,
            knownGood = KnownGoodMatchResult(KnownGoodMatch.NO_MATCH),
            matrix = CapabilityMatrix(emptyMap()),
            device = DeviceFacts(),
            rebootStrategy = null,
            canExecuteNormally = false,
            reasons = listOf("probe not run yet"),
        )
    }
}

object CompatibilityEngine {
    fun evaluate(
        facts: SystemFacts,
        database: KnownGoodDatabase = KnownGoodDatabase.fallback(),
        allowFallbackReboot: Boolean = false,
    ): CompatibilityDecision {
        val device = DeviceFacts.fromSystemFacts(facts)
        val backend = rootBackendTypeFrom(facts.rootManager)
        val rawMatrix = CapabilityEngine.evaluate(facts)
        val knownGood = database.match(device, backend)
        val matrix = rawMatrix
        val reasons = buildList {
            addAll(knownGood.evidence)
            CapabilityMatrix.CRITICAL_CAPABILITIES.forEach { capability ->
                val result = matrix[capability]
                when (result.status) {
                    CapabilityStatus.FAIL -> add("${capability.name}: ${result.reason.ifBlank { "failed" }}")
                    CapabilityStatus.UNKNOWN -> add("${capability.name}: ${result.reason.ifBlank { "unknown" }}")
                    CapabilityStatus.WARN -> add("${capability.name}: ${result.reason.ifBlank { "warning" }}")
                    CapabilityStatus.PASS -> Unit
                }
            }
        }

        val rootFailed = matrix[Capability.ROOT].status == CapabilityStatus.FAIL
        val recoveryUnsafe = CapabilityMatrix.RECOVERY_CRITICAL_CAPABILITIES.any {
            matrix[it].status == CapabilityStatus.FAIL || matrix[it].status == CapabilityStatus.UNKNOWN
        }
        val hasFail = matrix.any(CapabilityMatrix.CRITICAL_CAPABILITIES, CapabilityStatus.FAIL)
        val hasUnknown = matrix.any(CapabilityMatrix.CRITICAL_CAPABILITIES, CapabilityStatus.UNKNOWN)
        val hasWarn = matrix.any(CapabilityMatrix.CRITICAL_CAPABILITIES, CapabilityStatus.WARN)

        val compatibility = when {
            rootFailed -> DeviceCompatibility.UNSUPPORTED
            recoveryUnsafe -> DeviceCompatibility.UNSAFE
            hasFail -> DeviceCompatibility.UNSUPPORTED
            hasUnknown -> DeviceCompatibility.REQUIRES_PROFILE
            hasWarn -> DeviceCompatibility.PROBABLY_SUPPORTED
            else -> DeviceCompatibility.SUPPORTED
        }

        val fallbackRebootWarning = matrix[Capability.SAFE_SOFT_REBOOT].status == CapabilityStatus.WARN &&
            facts.rebootStrategy?.method in setOf("KSUD_SOFT_REBOOT", "ZYGOTE_RESTART")
        val onlyFallbackWarning = CapabilityMatrix.CRITICAL_CAPABILITIES.all { capability ->
            val status = matrix[capability].status
            status == CapabilityStatus.PASS ||
                (capability == Capability.SAFE_SOFT_REBOOT && status == CapabilityStatus.WARN)
        }
        return CompatibilityDecision(
            compatibility = compatibility,
            knownGood = knownGood,
            matrix = matrix,
            device = device,
            rebootStrategy = facts.rebootStrategy,
            canExecuteNormally = compatibility == DeviceCompatibility.SUPPORTED ||
                allowFallbackReboot && fallbackRebootWarning && onlyFallbackWarning,
            reasons = reasons.distinct(),
        )
    }

}

private fun rootBackendTypeFrom(value: String): RootBackendType = when (value.trim().lowercase()) {
    "kernelsu", "kernel su", "ksu" -> RootBackendType.KERNELSU
    "magisk" -> RootBackendType.MAGISK
    "apatch" -> RootBackendType.APATCH
    else -> RootBackendType.UNKNOWN
}
