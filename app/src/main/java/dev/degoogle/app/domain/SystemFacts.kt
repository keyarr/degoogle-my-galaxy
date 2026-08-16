package dev.degoogle.app.domain

/**
 * Fatos do sistema derivados do `degoogle.sh probe`.
 *
 * O backend emite `DEGOOGLE_KEY=value` em stdout; o parser extrai os fatos.
 * Campo ausente = null / false conforme o tipo.
 */
data class SystemFacts(
    val rootOk: Boolean,
    val rootManager: String,
    val profileId: String,
    val profileMatch: Boolean,
    val manufacturer: String,
    val model: String,
    val device: String,
    val product: String,
    val androidSdk: String,
    val androidRelease: String,
    val fingerprint: String,
    val selinux: String,
    val abi: String,
    val gmsPath: String?,
    val gmsVersion: String?,
    val gmsVersionCode: String?,
    val gmsUid: String?,
    val gmsFlags: String?,
    val gmsPrivileged: Boolean,
    val gsfPath: String?,
    val storePath: String?,
    val storeVersion: String?,
    val mountGms: Boolean,
    val mountGmsIsOurs: Boolean,
    val mountGsf: Boolean,
    val mountGsfIsOurs: Boolean,
    val mountStore: Boolean,
    val mountStoreIsOurs: Boolean,
    val mountGmsSource: String?,
    val backupPresent: Boolean,
    val finalizeDone: Boolean,
    /** Estado derivado pelo próprio backend (informativo; o app recalcula). */
    val shellState: DeviceState?,
    // Fatos adicionais de discovery. Defaults preservam compatibilidade com
    // fixtures e callers da primeira versão.
    val board: String = "",
    val hardware: String = "",
    val buildId: String = "",
    val securityPatch: String = "",
    val oneUiVersion: String = "",
    val kernelVersion: String = "",
    val abiList: List<String> = emptyList(),
    val gmsPackage: SystemPackageInfo? = null,
    val gsfPackage: SystemPackageInfo? = null,
    val storePackage: SystemPackageInfo? = null,
    val capabilityResults: Map<Capability, CapabilityResult> = emptyMap(),
    val rebootStrategy: RebootStrategy? = null,
    /** Aviso operacional quando GMS/Store têm update removível no fluxo. */
    val preparationInfo: String = "",
) {
    companion object {
        val EMPTY = SystemFacts(
            rootOk = false, rootManager = "", profileId = "", profileMatch = false,
            manufacturer = "", model = "", device = "", product = "",
            androidSdk = "", androidRelease = "", fingerprint = "", selinux = "", abi = "",
            gmsPath = null, gmsVersion = null, gmsVersionCode = null, gmsUid = null,
            gmsFlags = null, gmsPrivileged = false,
            gsfPath = null, storePath = null, storeVersion = null,
            mountGms = false, mountGmsIsOurs = false, mountGsf = false, mountGsfIsOurs = false,
            mountStore = false, mountStoreIsOurs = false, mountGmsSource = null,
            backupPresent = false, finalizeDone = false,
            shellState = null,
        )
    }
}

object ProbeParser {

    private val KEY = Regex("^DEGOOGLE_([A-Z0-9_]+)=(.*)$")

    /** Faz o parse da saída stdout do `degoogle.sh probe`. */
    fun parse(output: String): SystemFacts {
        val map = mutableMapOf<String, String>()
        output.lineSequence().forEach { line ->
            val m = KEY.matchEntire(line.trimEnd('\r'))
            if (m != null) map[m.groupValues[1]] = m.groupValues[2]
        }

        fun str(key: String): String? = map[key]?.takeIf { it.isNotEmpty() }
        fun bool(key: String): Boolean = map[key] == "1"
        fun list(key: String): List<String> = str(key).orEmpty().split(',')
            .map(String::trim).filter(String::isNotBlank)

        fun packageInfo(prefix: String, packageName: String): SystemPackageInfo? {
            val info = SystemPackageInfo(
                packageName = packageName,
                activeCodePath = str("${prefix}_ACTIVE_CODE_PATH"),
                originalSystemPath = str("${prefix}_ORIGINAL_SYSTEM_PATH"),
                targetDirectory = str("${prefix}_TARGET_DIRECTORY"),
                baseApk = str("${prefix}_BASE_APK"),
                splitApks = list("${prefix}_SPLIT_APKS"),
                hasDataUpdate = bool("${prefix}_HAS_DATA_UPDATE"),
                backingPartition = str("${prefix}_BACKING_PARTITION"),
                filesystemType = str("${prefix}_FILESYSTEM_TYPE"),
                resolvedRealPath = str("${prefix}_RESOLVED_REAL_PATH"),
            )
            return info.takeIf {
                it.activeCodePath != null || it.originalSystemPath != null ||
                    it.targetDirectory != null || it.hasDataUpdate
            }
        }

        val capabilityResults = buildMap {
            Capability.entries.forEach { capability ->
                val status = str("CAP_${capability.name}_STATUS")?.let {
                    runCatching { CapabilityStatus.valueOf(it.uppercase()) }.getOrNull()
                } ?: return@forEach
                put(
                    capability,
                    CapabilityResult(
                        status = status,
                        evidence = str("CAP_${capability.name}_EVIDENCE").orEmpty(),
                        reason = str("CAP_${capability.name}_REASON").orEmpty(),
                    ),
                )
            }
        }

        val rebootStrategy = str("REBOOT_STRATEGY_METHOD")?.let {
            RebootStrategy(
                backend = str("REBOOT_STRATEGY_BACKEND")?.let { backend ->
                    runCatching { RootBackendType.valueOf(backend.uppercase()) }.getOrDefault(RootBackendType.UNKNOWN)
                } ?: RootBackendType.UNKNOWN,
                method = it,
                confidence = str("REBOOT_STRATEGY_CONFIDENCE")?.let { confidence ->
                    runCatching { Confidence.valueOf(confidence.uppercase()) }.getOrDefault(Confidence.UNKNOWN)
                } ?: Confidence.UNKNOWN,
                firmwareValidated = bool("REBOOT_STRATEGY_FIRMWARE_VALIDATED"),
            )
        }

        return SystemFacts(
            rootOk = bool("ROOT_OK"),
            rootManager = str("ROOT_MANAGER").orEmpty(),
            profileId = str("PROFILE_ID").orEmpty(),
            profileMatch = bool("PROFILE_MATCH"),
            manufacturer = str("MANUFACTURER").orEmpty(),
            model = str("MODEL").orEmpty(),
            device = str("DEVICE").orEmpty(),
            product = str("PRODUCT").orEmpty(),
            androidSdk = str("ANDROID_SDK").orEmpty(),
            androidRelease = str("ANDROID_RELEASE").orEmpty(),
            fingerprint = str("FINGERPRINT").orEmpty(),
            selinux = str("SELINUX").orEmpty(),
            abi = str("ABI").orEmpty(),
            gmsPath = str("GMS_PATH"),
            gmsVersion = str("GMS_VERSION"),
            gmsVersionCode = str("GMS_VERSION_CODE"),
            gmsUid = str("GMS_UID"),
            gmsFlags = str("GMS_FLAGS"),
            gmsPrivileged = bool("GMS_PRIVILEGED"),
            gsfPath = str("GSF_PATH"),
            storePath = str("STORE_PATH"),
            storeVersion = str("STORE_VERSION"),
            mountGms = bool("MOUNT_GMS"),
            mountGmsIsOurs = bool("MOUNT_GMS_IS_OURS"),
            mountGsf = bool("MOUNT_GSF"),
            mountGsfIsOurs = bool("MOUNT_GSF_IS_OURS"),
            mountStore = bool("MOUNT_STORE"),
            mountStoreIsOurs = bool("MOUNT_STORE_IS_OURS"),
            mountGmsSource = str("MOUNT_GMS_SOURCE"),
            backupPresent = bool("BACKUP_PRESENT"),
            finalizeDone = bool("FINALIZE_DONE"),
            shellState = str("STATE")?.let { runCatching { DeviceState.valueOf(it) }.getOrNull() },
            board = str("BOARD").orEmpty(),
            hardware = str("HARDWARE").orEmpty(),
            buildId = str("BUILD_ID").orEmpty(),
            securityPatch = str("SECURITY_PATCH").orEmpty(),
            oneUiVersion = str("ONE_UI_VERSION").orEmpty(),
            kernelVersion = str("KERNEL_VERSION").orEmpty(),
            abiList = list("ABI_LIST"),
            gmsPackage = packageInfo("GMS", "com.google.android.gms"),
            gsfPackage = packageInfo("GSF", "com.google.android.gsf"),
            storePackage = packageInfo("STORE", "com.android.vending"),
            capabilityResults = capabilityResults,
            rebootStrategy = rebootStrategy,
            preparationInfo = str("PREPARATION_INFO").orEmpty(),
        )
    }
}
