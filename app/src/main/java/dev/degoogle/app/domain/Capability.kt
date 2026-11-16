package dev.degoogle.app.domain

import kotlinx.serialization.Serializable

/** Observable capabilities used by the compatibility engine. */
@Serializable
enum class Capability {
    ROOT,
    SAMSUNG_DEVICE,
    GLOBAL_MOUNT_NAMESPACE,
    BIND_MOUNT,
    SYSTEM_GMS_FOUND,
    SYSTEM_GSF_FOUND,
    SYSTEM_STORE_FOUND,
    GMS_MASKABLE,
    GSF_MASKABLE,
    STORE_MASKABLE,
    SELINUX_ENFORCING,
    SELINUX_CONTEXT_CLONABLE,
    SIGNATURE_SPOOFING,
    PACKAGE_MANAGER_CACHE_ACCESS,
    PRIV_APP_COMPATIBLE,
    SAFE_SOFT_REBOOT,
    SAFE_RESTORE,
}

@Serializable
enum class CapabilityStatus {
    PASS,
    WARN,
    FAIL,
    UNKNOWN,
}

/** Result of a check, always with evidence or a reason. */
@Serializable
data class CapabilityResult(
    val status: CapabilityStatus,
    val evidence: String = "",
    val reason: String = "",
) {
    val passed: Boolean get() = status == CapabilityStatus.PASS

    companion object {
        fun pass(evidence: String) = CapabilityResult(CapabilityStatus.PASS, evidence = evidence)
        fun warn(reason: String, evidence: String = "") =
            CapabilityResult(CapabilityStatus.WARN, evidence = evidence, reason = reason)
        fun fail(reason: String, evidence: String = "") =
            CapabilityResult(CapabilityStatus.FAIL, evidence = evidence, reason = reason)
        fun unknown(reason: String) = CapabilityResult(CapabilityStatus.UNKNOWN, reason = reason)
    }
}

/** Immutable capability matrix; missing values are not treated as PASS. */
data class CapabilityMatrix(
    val results: Map<Capability, CapabilityResult>,
) {
    operator fun get(capability: Capability): CapabilityResult =
        results[capability] ?: CapabilityResult.unknown("capability not observed")

    fun allPass(capabilities: Collection<Capability> = CRITICAL_CAPABILITIES): Boolean =
        capabilities.all { this[it].status == CapabilityStatus.PASS }

    fun any(capabilities: Collection<Capability>, status: CapabilityStatus): Boolean =
        capabilities.any { this[it].status == status }

    fun asReportMap(): Map<String, CapabilityResult> =
        results.mapKeys { it.key.name }

    companion object {
        /** Minimum requirements for any critical change. */
        val CRITICAL_CAPABILITIES: Set<Capability> = linkedSetOf(
            Capability.ROOT,
            Capability.GLOBAL_MOUNT_NAMESPACE,
            Capability.BIND_MOUNT,
            Capability.SYSTEM_GMS_FOUND,
            Capability.SYSTEM_GSF_FOUND,
            Capability.SYSTEM_STORE_FOUND,
            Capability.GMS_MASKABLE,
            Capability.GSF_MASKABLE,
            Capability.STORE_MASKABLE,
            Capability.SELINUX_ENFORCING,
            Capability.SELINUX_CONTEXT_CLONABLE,
            Capability.SAFE_SOFT_REBOOT,
            Capability.SAFE_RESTORE,
        )

        val RECOVERY_CRITICAL_CAPABILITIES: Set<Capability> = linkedSetOf(
            Capability.GLOBAL_MOUNT_NAMESPACE,
            Capability.BIND_MOUNT,
            Capability.SELINUX_CONTEXT_CLONABLE,
            Capability.SAFE_SOFT_REBOOT,
            Capability.SAFE_RESTORE,
        )
    }
}

/** Detected root backend; detection does not imply operational validation. */
@Serializable
enum class RootBackendType {
    KERNELSU,
    MAGISK,
    APATCH,
    UNKNOWN,
}

@Serializable
data class RebootStrategy(
    val backend: RootBackendType = RootBackendType.UNKNOWN,
    val method: String = "",
    val confidence: Confidence = Confidence.UNKNOWN,
    val firmwareValidated: Boolean = false,
)

@Serializable
enum class Confidence {
    VERIFIED,
    HIGH,
    MEDIUM,
    LOW,
    UNKNOWN,
}

/** Derives the matrix from facts emitted by the backend. */
object CapabilityEngine {

    fun evaluate(facts: SystemFacts): CapabilityMatrix {
        val inferred = linkedMapOf<Capability, CapabilityResult>()

        inferred[Capability.ROOT] = if (facts.rootOk) {
            CapabilityResult.pass("id -u = 0; backend=${facts.rootManager.ifBlank { "unknown" }}")
        } else {
            CapabilityResult.fail("root not available", "id -u != 0")
        }
        inferred[Capability.SAMSUNG_DEVICE] = when {
            facts.manufacturer.isBlank() -> CapabilityResult.unknown("manufacturer missing")
            facts.manufacturer.equals("samsung", ignoreCase = true) ->
                CapabilityResult.pass("ro.product.manufacturer=${facts.manufacturer}")
            else -> CapabilityResult.fail("not a Samsung device", facts.manufacturer)
        }

        inferred[Capability.SYSTEM_GMS_FOUND] = if (facts.mountGms) {
            CapabilityResult.pass(facts.gmsPath ?: "GMS masked via microG")
        } else {
            packageFound(facts.gmsPackage, facts.gmsPath, "GMS")
        }
        val optionalGsfAbsent = facts.gsfPackage == null && facts.gsfPath.isNullOrBlank() && !facts.mountGsf &&
            !facts.stockReindexPending && (!facts.gsfTargetPresent || facts.operationMode == null)
        val optionalStoreAbsent = facts.storePackage == null && facts.storePath.isNullOrBlank() && !facts.mountStore &&
            !facts.stockReindexPending && (!facts.storeTargetPresent || facts.operationMode == null)
        inferred[Capability.SYSTEM_GSF_FOUND] = if (facts.mountGsf) {
            CapabilityResult.pass("GSF masked (empty)")
        } else if (optionalGsfAbsent) {
            CapabilityResult.pass("GSF is absent from stock")
        } else {
            packageFound(facts.gsfPackage, facts.gsfPath, "GSF")
        }
        inferred[Capability.SYSTEM_STORE_FOUND] = if (facts.mountStore) {
            CapabilityResult.pass(facts.storePath ?: "Store masked via Companion")
        } else if (optionalStoreAbsent) {
            CapabilityResult.pass("Store is absent from stock")
        } else {
            packageFound(facts.storePackage, facts.storePath, "Store")
        }
        inferred[Capability.GMS_MASKABLE] = if (facts.mountGms) {
            CapabilityResult.pass("GMS target already masked successfully")
        } else {
            maskable(facts.gmsPackage, facts.gmsPath, "GMS")
        }
        inferred[Capability.GSF_MASKABLE] = if (facts.mountGsf) {
            CapabilityResult.pass("GSF target already masked successfully")
        } else if (optionalGsfAbsent) {
            CapabilityResult.pass("GSF target is absent from stock")
        } else {
            maskable(facts.gsfPackage, facts.gsfPath, "GSF")
        }
        inferred[Capability.STORE_MASKABLE] = if (facts.mountStore) {
            CapabilityResult.pass("Store target already masked successfully")
        } else if (optionalStoreAbsent) {
            CapabilityResult.pass("Store target is absent from stock")
        } else {
            maskable(facts.storePackage, facts.storePath, "Store")
        }

        inferred[Capability.SELINUX_ENFORCING] = when (facts.selinux.trim().lowercase()) {
            "enforcing" -> CapabilityResult.pass("getenforce=Enforcing")
            "permissive" -> CapabilityResult.warn("SELinux is Permissive; policy was not changed", "getenforce=Permissive")
            "disabled" -> CapabilityResult.warn("SELinux is Disabled; context checks are not applicable", "getenforce=Disabled")
            else -> CapabilityResult.unknown("SELinux state missing")
        }
        inferred[Capability.PRIV_APP_COMPATIBLE] = when {
            facts.gmsPrivileged -> CapabilityResult.pass("GMS reported as PRIVILEGED")
            facts.gmsPath == null -> CapabilityResult.unknown("GMS not found")
            else -> CapabilityResult.fail("GMS lacks PRIVILEGED flag", facts.gmsFlags.orEmpty())
        }

        // These capabilities must not be inferred from model similarity.
        inferred[Capability.GLOBAL_MOUNT_NAMESPACE] = CapabilityResult.unknown(
            "global namespace not tested by probe",
        )
        inferred[Capability.BIND_MOUNT] = CapabilityResult.unknown(
            "reversible bind mount not tested by probe",
        )
        inferred[Capability.SELINUX_CONTEXT_CLONABLE] = CapabilityResult.unknown(
            "target SELinux context not validated",
        )
        inferred[Capability.SIGNATURE_SPOOFING] = CapabilityResult.unknown(
            "no functional signature-spoofing evidence",
        )
        inferred[Capability.PACKAGE_MANAGER_CACHE_ACCESS] = CapabilityResult.unknown(
            "PackageManager cache access not tested",
        )
        inferred[Capability.SAFE_SOFT_REBOOT] = CapabilityResult.unknown(
            "reboot strategy not validated on this firmware",
        )
        inferred[Capability.SAFE_RESTORE] = CapabilityResult.unknown(
            "rollback not yet validated by preflight",
        )

        // The shell may provide more specific evidence. It always takes precedence over
        // local inference, including when the status is WARN/UNKNOWN.
        facts.capabilityResults.forEach { (capability, result) -> inferred[capability] = result }
        // An update without a validated stock target remains blocked; a path under
        // /data/app never replaces evidence for the original package.
        requalifyDataUpdate(inferred, Capability.GMS_MASKABLE, facts.gmsPackage, "GMS")
        requalifyDataUpdate(inferred, Capability.GSF_MASKABLE, facts.gsfPackage, "GSF")
        requalifyDataUpdate(inferred, Capability.STORE_MASKABLE, facts.storePackage, "Store")
        return CapabilityMatrix(inferred)
    }

    private fun requalifyDataUpdate(
        inferred: MutableMap<Capability, CapabilityResult>,
        capability: Capability,
        info: SystemPackageInfo?,
        label: String,
    ) {
        if (inferred[capability]?.status != CapabilityStatus.UNKNOWN) return
        if (info?.hasDataUpdate != true) return
        if (info.activeCodePath?.startsWith("/data/app/") != true) return
        inferred[capability] = CapabilityResult.warn(
            reason = "$label: active update in /data/app; stock target will be revalidated during cleanup",
            evidence = "active=${info.activeCodePath}; original=${info.originalSystemPath ?: "not exposed by PM"}",
        )
    }

    private fun packageFound(info: SystemPackageInfo?, legacyPath: String?, label: String): CapabilityResult =
        when {
            info?.activeCodePath?.isNotBlank() == true ->
                CapabilityResult.pass("$label: ${info.activeCodePath}")
            !legacyPath.isNullOrBlank() -> CapabilityResult.pass("$label: $legacyPath")
            else -> CapabilityResult.fail("$label not found")
        }

    private fun maskable(info: SystemPackageInfo?, legacyPath: String?, label: String): CapabilityResult {
        if (info != null) {
            if (info.hasDataUpdate && info.activeCodePath?.startsWith("/data/app/") == true) {
                return CapabilityResult.warn(
                    reason = "$label: active update in /data/app; stock target will be revalidated during cleanup",
                    evidence = "active=${info.activeCodePath}; original=${info.originalSystemPath ?: "not exposed by PM"}",
                )
            }
            if (info.originalSystemPath.isNullOrBlank()) {
                if (info.targetDirectory != null && !info.safeTarget) {
                    return CapabilityResult.fail(
                        "$label: target outside allowed roots",
                        info.targetDirectory,
                    )
                }
                return CapabilityResult.unknown("$label: ambiguous original system path")
            }
            if (!info.safeTarget) {
                return CapabilityResult.fail("$label: target outside allowed roots", info.targetDirectory.orEmpty())
            }
            if (info.hasDataUpdate) {
                return CapabilityResult.warn(
                    reason = "$label: update in /data/app; explicit cleanup required",
                    evidence = info.activeCodePath.orEmpty(),
                )
            }
            return CapabilityResult.pass("$label: target=${info.targetDirectory}")
        }
        return when {
            legacyPath.isNullOrBlank() -> CapabilityResult.unknown("$label: package not found")
            legacyPath.startsWith("/system/") ||
            legacyPath.startsWith("/system_ext/") ||
                legacyPath.startsWith("/product/") ->
                CapabilityResult.pass("$label: known system path=$legacyPath")
            legacyPath.startsWith("/data/app/") ->
                CapabilityResult.warn(
                    "$label: only an update in /data/app; stock target needs revalidation",
                    legacyPath,
                )
            else -> CapabilityResult.fail("$label: path outside allowed roots", legacyPath)
        }
    }
}
