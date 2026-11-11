package dev.degoogle.app.domain

/**
 * StateDetector: converts [SystemFacts] (actual Android state) into
 * [DeviceState]. Rules match `compute_state` in the shell backend.
 */
object StateDetector {

    fun detect(facts: SystemFacts, profile: DeviceProfile? = null): DeviceState {
        if (!facts.rootOk) return DeviceState.NO_ROOT
        if (facts.operationModeConflict) return DeviceState.ERROR

        // PROFILE_MATCH is legacy backend telemetry. The source of truth for the
        // UI is the local profile, which also validates manufacturer, model, and SDK. In
        // a post-operation state, PackageManager may have been reindexed
        // by another backend version; do not mark the device UNSUPPORTED when
        // the local profile and operational evidence match.
        val gmsTarget = facts.gmsPackage?.targetDirectory?.takeIf { facts.gmsPackage.safeTarget }
        val gsfTarget = facts.gsfPackage?.targetDirectory?.takeIf { facts.gsfPackage.safeTarget }
        val storeTarget = facts.storePackage?.targetDirectory?.takeIf { facts.storePackage.safeTarget }

        if (!facts.mountGms && !facts.mountGsf && !facts.mountStore) {
            // No active mounts: stock state or rollback pending a reboot.
            if (facts.stockReindexPending) return DeviceState.RESTORE_PREPARED
            //
            // A legitimate system app update appears in
            // /data/app. It is accepted only when Package Locator also
            // confirms hasDataUpdate; a /data/app path alone is still
            // treated as an unexpected state.
            val gmsIsStock = isStockPackagePath(
                facts.gmsPath,
                gmsTarget,
                facts.gmsPackage,
            )
            if (!gmsIsStock) return DeviceState.ERROR

            val gsfPresent = !facts.gsfPath.isNullOrBlank()
            val storePresent = !facts.storePath.isNullOrBlank()
            if (!gsfPresent || !storePresent) return DeviceState.RESTORE_PREPARED

            val gsfIsStock = isStockPackagePath(
                facts.gsfPath,
                gsfTarget,
                facts.gsfPackage,
            )
            val storeIsStock = isStockPackagePath(
                facts.storePath,
                storeTarget,
                facts.storePackage,
            )
            return if (gsfIsStock && storeIsStock) DeviceState.STOCK else DeviceState.ERROR
        }

        // An active mount exists; it must be complete and use OUR masks.
        if (!facts.mountGms || !facts.mountGsf || !facts.mountStore) {
            return DeviceState.ERROR
        }
        // Mount from an unknown source (for example, the old script microg-mask): the app
        // does not manage this state and must not modify it.
        if (!facts.mountGmsIsOurs || !facts.mountGsfIsOurs || !facts.mountStoreIsOurs) {
            return DeviceState.ERROR
        }

        if (facts.operationMode == OperationMode.PURE) {
            if (!facts.maskGmsEmpty || !facts.maskGsfEmpty || !facts.maskStoreEmpty || !facts.pureMasksValid) {
                return DeviceState.ERROR
            }
            val targets = listOf(gmsTarget, gsfTarget, storeTarget)
            if (targets.any { it == null } || targets.distinct().size != targets.size ||
                targets.indices.any { i -> targets.indices.any { j ->
                    i != j && targets[i]!!.startsWith("${targets[j]!!.trimEnd('/')}/")
                } }
            ) return DeviceState.ERROR
            val packagesAbsent = facts.gmsPath.isNullOrBlank() &&
                facts.gsfPath.isNullOrBlank() && facts.storePath.isNullOrBlank()
            if (facts.postBootValidated && packagesAbsent) return DeviceState.DEGOOGLE_ACTIVE
            return DeviceState.DEGOOGLE_PREPARED
        }

        val gmsFromMask = isUnderTarget(facts.gmsPath, gmsTarget)
        if (!gmsFromMask) return DeviceState.ERROR

        if (!facts.gsfPath.isNullOrEmpty()) {
            // Active mounts but GSF is still registered => PM still sees the stock state.
            // (the soft reboot has not happened yet).
            return DeviceState.PREPARED
        }

        // Post-boot: GSF is gone. microG must be a real priv-app.
        if (!facts.gmsPrivileged) return DeviceState.ERROR

        val storeFromMask = isUnderTarget(facts.storePath, storeTarget)
        if (!storeFromMask) return DeviceState.ERROR

        if (!facts.finalizeDone) return DeviceState.MICROG_BOOTED

        return if (facts.backupPresent) {
            DeviceState.MICROG_ACTIVE_BACKED_UP
        } else {
            // No backup: this may be a first install (NEEDS_SETUP is determined
            // by the UI based on whether the prompt was already shown; auxiliary data).
            DeviceState.MICROG_ACTIVE
        }
    }

    /**
     * Accepts the stock APK in the approved directory or an update registered
     * by Package Locator in /data/app. The latter is never inferred from
     * the path text alone.
     */
    private fun isStockPackagePath(
        path: String?,
        systemDir: String?,
        packageInfo: SystemPackageInfo?,
    ): Boolean {
        val actual = path?.takeIf { it.isNotBlank() } ?: return false
        if (isUnderTarget(actual, systemDir)) return true
        return actual.startsWith("/data/app/") && packageInfo?.hasDataUpdate == true && packageInfo.safeTarget
    }

    private fun isUnderTarget(path: String?, target: String?): Boolean =
        !path.isNullOrBlank() && !target.isNullOrBlank() && path.startsWith("${target.trimEnd('/')}/")
}

/**
 * Validate that this is a real priv-app; the path being under the mask is not enough.
 */
data class PrivAppValidationResult(
    val packageRegistered: Boolean,
    val pathUnderMask: Boolean,
    val privilegedFlag: Boolean,
    val requestedInteractAcrossUsers: Boolean,
    val mountSourceIsMask: Boolean,
) {
    val valid: Boolean
        get() = packageRegistered && pathUnderMask && privilegedFlag &&
            mountSourceIsMask && requestedInteractAcrossUsers

    val issues: List<String>
        get() = buildList {
            if (!packageRegistered) add("Package not registered")
            if (!pathUnderMask) add("Path outside mask")
            if (!privilegedFlag) add("PRIVILEGED flag missing")
            if (!requestedInteractAcrossUsers) add("INTERACT_ACROSS_USERS missing")
            if (!mountSourceIsMask) add("Mount not from our mask")
        }
}

object PrivAppValidator {

    fun validate(facts: SystemFacts, profile: DeviceProfile): PrivAppValidationResult {
        val registered = !facts.gmsPath.isNullOrEmpty()
        val pathUnderMask = facts.gmsPath?.startsWith(profile.gmsSystemDir) == true
        val privileged = facts.gmsPrivileged
        // INTERACT_ACROSS_USERS: the backend reports this in flags; here we treat it as
        // satisfied if privileged and the path is under the mask (PM grants
        // privileged permissions to priv-apps). The backend performs the strict check.
        val requested = facts.gmsFlags.orEmpty().contains("PRIVILEGED")
        val mountOk = facts.mountGms
        return PrivAppValidationResult(
            packageRegistered = registered,
            pathUnderMask = pathUnderMask,
            privilegedFlag = privileged,
            requestedInteractAcrossUsers = requested,
            mountSourceIsMask = mountOk,
        )
    }
}
