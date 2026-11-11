package dev.degoogle.app.domain

/**
 * Device profile: expected system paths and constraints.
 *
 * v1 supports a single profile (Samsung Galaxy S24 Ultra — SM-S928x), the one
 * used to develop and test the original script. No paths are assumed
 * without first confirming them with `pm path`.
 */
data class DeviceProfile(
    val id: String,
    val manufacturer: String,
    val models: Set<String>,
    /** Path to the stock Google Play Services priv-app directory. */
    val gmsSystemDir: String,
    /** Path to the stock Google Services Framework priv-app directory. */
    val gsfSystemDir: String,
    /** Path to the stock Play Store (Phonesky) priv-app directory. */
    val storeSystemDir: String,
    /** Minimum required SDK (null = any). */
    val minSdk: Int? = null,
    /** APK name pattern within the GMS directory. */
    val gmsApkName: String = "GmsCore.apk",
    /** APK name pattern within the store directory. */
    val storeApkName: String = "Companion.apk",
)

object DeviceProfiles {

    val SUPPORTED: List<DeviceProfile> = listOf(
        DeviceProfile(
            id = "samsung_sm-s928b",
            manufacturer = "samsung",
            models = setOf("SM-S928B", "SM-S928U", "SM-S928W", "SM-S928N", "SM-S9280"),
            gmsSystemDir = "/product/priv-app/GmsCore",
            gsfSystemDir = "/system_ext/priv-app/GoogleServicesFramework",
            storeSystemDir = "/product/priv-app/Phonesky",
        ),
    )

    /** Finds the first profile compatible with the collected facts. */
    fun matching(
        manufacturer: String,
        model: String,
        sdk: String,
    ): DeviceProfile? {
        val man = manufacturer.lowercase()
        return SUPPORTED.firstOrNull { profile ->
            profile.manufacturer == man &&
                profile.models.any { it.equals(model, ignoreCase = true) } &&
                (profile.minSdk == null || sdk.toIntOrNull()?.let { it >= profile.minSdk } == true)
        }
    }

    /**
     * Ephemeral profile for Samsung devices outside the approved list: reuses
     * directories discovered by Package Locator instead of requiring S24 paths.
     * Returns null for non-Samsung devices or when no target can be derived.
     */
    fun dynamic(facts: SystemFacts): DeviceProfile? {
        if (!facts.manufacturer.equals("samsung", ignoreCase = true)) return null
        return DeviceProfile(
            id = "dynamic-${facts.model.ifBlank { "unknown" }}",
            manufacturer = "samsung",
            models = setOf(facts.model),
            gmsSystemDir = stockDir(facts.gmsPackage, facts.gmsPath),
            gsfSystemDir = stockDir(facts.gsfPackage, facts.gsfPath),
            storeSystemDir = stockDir(facts.storePackage, facts.storePath),
        )
    }

    private fun stockDir(info: SystemPackageInfo?, activePath: String?): String {
        info?.targetDirectory?.takeIf { info.safeTarget }?.let { return it }
        info?.originalSystemPath?.takeIf { underAllowedRoots(it) }
            ?.let { return it.substringBeforeLast('/') }
        activePath?.takeIf { underAllowedRoots(it) }
            ?.let { return it.substringBeforeLast('/') }
        // Placeholder that never matches a real path: any state requiring this
        // directory results in ERROR (fail closed), never STOCK.
        return "__unknown__"
    }

    private fun underAllowedRoots(path: String): Boolean =
        !path.contains("..") && SystemPackageInfo.ALLOWED_SYSTEM_ROOTS.any {
            path == it || path.startsWith("$it/")
        }
}
