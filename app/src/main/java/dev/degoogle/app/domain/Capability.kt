package dev.degoogle.app.domain

import kotlinx.serialization.Serializable

/** Capacidades observáveis usadas pelo motor de compatibilidade. */
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

/** Resultado de uma verificação, sempre com evidência ou motivo. */
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

/** Matriz imutável de capabilities; ausência não é interpretada como PASS. */
data class CapabilityMatrix(
    val results: Map<Capability, CapabilityResult>,
) {
    operator fun get(capability: Capability): CapabilityResult =
        results[capability] ?: CapabilityResult.unknown("capability não observada")

    fun allPass(capabilities: Collection<Capability> = CRITICAL_CAPABILITIES): Boolean =
        capabilities.all { this[it].status == CapabilityStatus.PASS }

    fun any(capabilities: Collection<Capability>, status: CapabilityStatus): Boolean =
        capabilities.any { this[it].status == status }

    fun asReportMap(): Map<String, CapabilityResult> =
        results.mapKeys { it.key.name }

    companion object {
        /** Requisitos mínimos para qualquer alteração crítica. */
        val CRITICAL_CAPABILITIES: Set<Capability> = linkedSetOf(
            Capability.ROOT,
            Capability.SAMSUNG_DEVICE,
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
            Capability.SIGNATURE_SPOOFING,
            Capability.PACKAGE_MANAGER_CACHE_ACCESS,
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

/** Backend de root detectado; detecção não implica validação operacional. */
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

/** Deriva a matriz a partir dos fatos emitidos pelo backend. */
object CapabilityEngine {

    fun evaluate(facts: SystemFacts): CapabilityMatrix {
        val inferred = linkedMapOf<Capability, CapabilityResult>()

        inferred[Capability.ROOT] = if (facts.rootOk) {
            CapabilityResult.pass("id -u = 0; backend=${facts.rootManager.ifBlank { "unknown" }}")
        } else {
            CapabilityResult.fail("root não disponível", "id -u != 0")
        }
        inferred[Capability.SAMSUNG_DEVICE] = when {
            facts.manufacturer.isBlank() -> CapabilityResult.unknown("manufacturer ausente")
            facts.manufacturer.equals("samsung", ignoreCase = true) ->
                CapabilityResult.pass("ro.product.manufacturer=${facts.manufacturer}")
            else -> CapabilityResult.fail("fabricante não é Samsung", facts.manufacturer)
        }

        inferred[Capability.SYSTEM_GMS_FOUND] = if (facts.mountGms) {
            CapabilityResult.pass(facts.gmsPath ?: "GMS mascarado via microG")
        } else {
            packageFound(facts.gmsPackage, facts.gmsPath, "GMS")
        }
        inferred[Capability.SYSTEM_GSF_FOUND] = if (facts.mountGsf) {
            CapabilityResult.pass("GSF mascarado (vazio)")
        } else {
            packageFound(facts.gsfPackage, facts.gsfPath, "GSF")
        }
        inferred[Capability.SYSTEM_STORE_FOUND] = if (facts.mountStore) {
            CapabilityResult.pass(facts.storePath ?: "Store mascarada via Companion")
        } else {
            packageFound(facts.storePackage, facts.storePath, "Store")
        }
        inferred[Capability.GMS_MASKABLE] = if (facts.mountGms) {
            CapabilityResult.pass("alvo GMS já mascarado com sucesso")
        } else {
            maskable(facts.gmsPackage, facts.gmsPath, "GMS")
        }
        inferred[Capability.GSF_MASKABLE] = if (facts.mountGsf) {
            CapabilityResult.pass("alvo GSF já mascarado com sucesso")
        } else {
            maskable(facts.gsfPackage, facts.gsfPath, "GSF")
        }
        inferred[Capability.STORE_MASKABLE] = if (facts.mountStore) {
            CapabilityResult.pass("alvo Store já mascarado com sucesso")
        } else {
            maskable(facts.storePackage, facts.storePath, "Store")
        }

        inferred[Capability.SELINUX_ENFORCING] = when (facts.selinux.trim().lowercase()) {
            "enforcing" -> CapabilityResult.pass("getenforce=Enforcing")
            "permissive", "disabled" -> CapabilityResult.fail("SELinux não está Enforcing", facts.selinux)
            else -> CapabilityResult.unknown("estado SELinux ausente")
        }
        inferred[Capability.PRIV_APP_COMPATIBLE] = when {
            facts.gmsPrivileged -> CapabilityResult.pass("GMS reportado como PRIVILEGED")
            facts.gmsPath == null -> CapabilityResult.unknown("GMS não localizado")
            else -> CapabilityResult.fail("GMS não possui flag PRIVILEGED", facts.gmsFlags.orEmpty())
        }

        // Estas capacidades não devem ser deduzidas por semelhança de modelo.
        inferred[Capability.GLOBAL_MOUNT_NAMESPACE] = CapabilityResult.unknown(
            "namespace global não foi testado pelo probe",
        )
        inferred[Capability.BIND_MOUNT] = CapabilityResult.unknown(
            "bind mount reversível não foi testado pelo probe",
        )
        inferred[Capability.SELINUX_CONTEXT_CLONABLE] = CapabilityResult.unknown(
            "contexto SELinux do alvo não foi validado",
        )
        inferred[Capability.SIGNATURE_SPOOFING] = CapabilityResult.unknown(
            "não há evidência funcional de signature spoofing",
        )
        inferred[Capability.PACKAGE_MANAGER_CACHE_ACCESS] = CapabilityResult.unknown(
            "acesso ao cache do PackageManager não foi testado",
        )
        inferred[Capability.SAFE_SOFT_REBOOT] = CapabilityResult.unknown(
            "estratégia de reboot não foi validada neste firmware",
        )
        inferred[Capability.SAFE_RESTORE] = CapabilityResult.unknown(
            "rollback ainda não foi validado pelo preflight",
        )

        // O shell pode fornecer provas mais específicas. Elas sempre vencem a
        // inferência local, inclusive quando o status é WARN/UNKNOWN.
        facts.capabilityResults.forEach { (capability, result) -> inferred[capability] = result }
        // O backend marca update em /data/app como UNKNOWN fora do perfil S24
        // (preparable exige profile_check). Com hasDataUpdate + active em
        // /data/app não é ambíguo: é update conhecido que o cleanup revela.
        // Mantém WARN (bloqueia experimental, sem virar SUPPORTED).
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
            reason = "$label: atualização ativa em /data/app; target stock será revalidado no cleanup",
            evidence = "active=${info.activeCodePath}; original=${info.originalSystemPath ?: "não exposto pelo PM"}",
        )
    }

    private fun packageFound(info: SystemPackageInfo?, legacyPath: String?, label: String): CapabilityResult =
        when {
            info?.activeCodePath?.isNotBlank() == true ->
                CapabilityResult.pass("$label: ${info.activeCodePath}")
            !legacyPath.isNullOrBlank() -> CapabilityResult.pass("$label: $legacyPath")
            else -> CapabilityResult.fail("$label não encontrado")
        }

    private fun maskable(info: SystemPackageInfo?, legacyPath: String?, label: String): CapabilityResult {
        if (info != null) {
            if (info.hasDataUpdate && info.activeCodePath?.startsWith("/data/app/") == true) {
                return CapabilityResult.warn(
                    reason = "$label: atualização ativa em /data/app; target stock será revalidado no cleanup",
                    evidence = "active=${info.activeCodePath}; original=${info.originalSystemPath ?: "não exposto pelo PM"}",
                )
            }
            if (info.originalSystemPath.isNullOrBlank()) {
                if (info.targetDirectory != null && !info.safeTarget) {
                    return CapabilityResult.fail(
                        "$label: alvo fora das raízes permitidas",
                        info.targetDirectory,
                    )
                }
                return CapabilityResult.unknown("$label: caminho original do sistema é ambíguo")
            }
            if (!info.safeTarget) {
                return CapabilityResult.fail("$label: alvo fora das raízes permitidas", info.targetDirectory.orEmpty())
            }
            if (info.hasDataUpdate) {
                return CapabilityResult.warn(
                    reason = "$label: existe atualização em /data/app; requer cleanup explícito",
                    evidence = info.activeCodePath.orEmpty(),
                )
            }
            return CapabilityResult.pass("$label: alvo=${info.targetDirectory}")
        }
        return when {
            legacyPath.isNullOrBlank() -> CapabilityResult.unknown("$label: pacote não localizado")
            legacyPath.startsWith("/system/") ||
            legacyPath.startsWith("/system_ext/") ||
                legacyPath.startsWith("/product/") ->
                CapabilityResult.pass("$label: caminho de sistema conhecido=$legacyPath")
            legacyPath.startsWith("/data/app/") ->
                CapabilityResult.warn(
                    "$label: só há atualização em /data/app; target stock precisa ser revalidado",
                    legacyPath,
                )
            else -> CapabilityResult.fail("$label: caminho fora das raízes permitidas", legacyPath)
        }
    }
}
