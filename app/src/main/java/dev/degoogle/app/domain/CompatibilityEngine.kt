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
    val canExecuteExperimental: Boolean,
    val reasons: List<String> = emptyList(),
)

data class CompatibilityDecision(
    val compatibility: DeviceCompatibility,
    val knownGood: KnownGoodMatchResult,
    val matrix: CapabilityMatrix,
    val device: DeviceFacts,
    val rebootStrategy: RebootStrategy?,
    val canExecuteNormally: Boolean,
    val canExecuteExperimental: Boolean,
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
        canExecuteExperimental = canExecuteExperimental,
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
            canExecuteExperimental = false,
            reasons = listOf("probe ainda não executado"),
        )
    }
}

object CompatibilityEngine {
    fun evaluate(
        facts: SystemFacts,
        database: KnownGoodDatabase = KnownGoodDatabase.fallback(),
    ): CompatibilityDecision {
        val device = DeviceFacts.fromSystemFacts(facts)
        val backend = rootBackendTypeFrom(facts.rootManager)
        val rawMatrix = CapabilityEngine.evaluate(facts)
        val knownGood = database.match(device, backend)
        val matrix = promoteFirmwareValidatedStrategy(rawMatrix, knownGood, facts)
        val expectedPathMismatch = expectedPathMismatch(knownGood.profile, facts)
        val reasons = buildList {
            addAll(knownGood.evidence)
            if (expectedPathMismatch.isNotEmpty()) {
                addAll(expectedPathMismatch)
            }
            CapabilityMatrix.CRITICAL_CAPABILITIES.forEach { capability ->
                val result = matrix[capability]
                when (result.status) {
                    CapabilityStatus.FAIL -> add("${capability.name}: ${result.reason.ifBlank { "falhou" }}")
                    CapabilityStatus.UNKNOWN -> add("${capability.name}: ${result.reason.ifBlank { "desconhecida" }}")
                    CapabilityStatus.WARN -> add("${capability.name}: ${result.reason.ifBlank { "aviso" }}")
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
            expectedPathMismatch.isNotEmpty() -> DeviceCompatibility.UNSUPPORTED
            recoveryUnsafe -> DeviceCompatibility.UNSAFE
            hasFail -> DeviceCompatibility.UNSUPPORTED
            hasUnknown -> DeviceCompatibility.REQUIRES_PROFILE
            knownGood.level == KnownGoodMatch.EXACT_MATCH -> DeviceCompatibility.SUPPORTED
            else -> DeviceCompatibility.PROBABLY_SUPPORTED
        }

        val allCriticalPass = matrix.allPass()
        val canExperimental = compatibility == DeviceCompatibility.PROBABLY_SUPPORTED &&
            allCriticalPass &&
            matrix[Capability.SAFE_SOFT_REBOOT].status == CapabilityStatus.PASS &&
            matrix[Capability.SAFE_RESTORE].status == CapabilityStatus.PASS

        return CompatibilityDecision(
            compatibility = compatibility,
            knownGood = knownGood,
            matrix = matrix,
            device = device,
            rebootStrategy = facts.rebootStrategy,
            canExecuteNormally = compatibility == DeviceCompatibility.SUPPORTED,
            canExecuteExperimental = canExperimental,
            reasons = reasons.distinct(),
        )
    }

    private fun promoteFirmwareValidatedStrategy(
        matrix: CapabilityMatrix,
        knownGood: KnownGoodMatchResult,
        facts: SystemFacts,
    ): CapabilityMatrix {
        val profile = knownGood.profile ?: return matrix
        val strategy = facts.rebootStrategy ?: return matrix
        if (knownGood.level != KnownGoodMatch.EXACT_MATCH ||
            !profile.firmwareValidated ||
            profile.rebootStrategy != strategy.method ||
            matrix[Capability.SAFE_SOFT_REBOOT].status != CapabilityStatus.WARN
        ) return matrix
        return CapabilityMatrix(
            matrix.results + (
                Capability.SAFE_SOFT_REBOOT to CapabilityResult.pass(
                    "estratégia ${strategy.method} homologada no Known-Good DB para este firmware",
                )
            ),
        )
    }

    private fun expectedPathMismatch(profile: KnownGoodProfile?, facts: SystemFacts): List<String> {
        if (profile == null || profile.expectedPaths.isEmpty()) return emptyList()
        val observed = mapOf(
            "gms" to (facts.gmsPackage?.targetDirectory ?: facts.gmsPath?.substringBeforeLast('/')),
            "gsf" to (facts.gsfPackage?.targetDirectory ?: facts.gsfPath?.substringBeforeLast('/')),
            "store" to (facts.storePackage?.targetDirectory ?: facts.storePath?.substringBeforeLast('/')),
        )
        val packages = mapOf(
            "gms" to facts.gmsPackage,
            "gsf" to facts.gsfPackage,
            "store" to facts.storePackage,
        )
        return profile.expectedPaths.mapNotNull { (name, expected) ->
            val actual = observed[name]
            val packageInfo = packages[name]
            val activeDataUpdate = packageInfo?.hasDataUpdate == true &&
                packageInfo.activeCodePath?.startsWith("/data/app/") == true
            // Enquanto um update está ativo, o Package Manager expõe apenas
            // o path em /data/app. Isso não invalida o target stock conhecido;
            // o cleanup explícito ainda será revalidado pelo backend.
            if (actual == expected || activeDataUpdate) {
                null
            } else {
                "$name: expected=$expected actual=${actual ?: "UNKNOWN"}"
            }
        }
    }

}

private fun rootBackendTypeFrom(value: String): RootBackendType = when (value.trim().lowercase()) {
    "kernelsu", "kernel su", "ksu" -> RootBackendType.KERNELSU
    "magisk" -> RootBackendType.MAGISK
    "apatch" -> RootBackendType.APATCH
    else -> RootBackendType.UNKNOWN
}
