package dev.degoogle.app.domain

import dev.degoogle.app.root.RootExecutor
import kotlinx.serialization.Serializable

/** Complete package location without assuming a firmware layout. */
@Serializable
data class SystemPackageInfo(
    val packageName: String,
    val activeCodePath: String? = null,
    val originalSystemPath: String? = null,
    val targetDirectory: String? = null,
    val baseApk: String? = null,
    val splitApks: List<String> = emptyList(),
    val hasDataUpdate: Boolean = false,
    val backingPartition: String? = null,
    val filesystemType: String? = null,
    val resolvedRealPath: String? = null,
) {
    /** A target is safe only under approved roots and without path traversal. */
    val safeTarget: Boolean
        get() {
            val target = targetDirectory ?: return false
            if (target.contains("..") || target.contains('\\')) return false
            if (ALLOWED_SYSTEM_ROOTS.none { target.startsWith("$it/") }) return false
            val parent = target.substringBeforeLast('/', "")
            return parent.endsWith("/app") || parent.endsWith("/priv-app")
        }

    companion object {
        val ALLOWED_SYSTEM_ROOTS = listOf("/system", "/system_ext", "/product")
    }
}

/**
 * Parser and locator small enough for fixture-based tests. The locator
 * uses argv only; packageName is never passed through a concatenated shell command.
 */
class PackageLocator(
    private val executor: RootExecutor,
    private val allowedSystemRoots: List<String> = SystemPackageInfo.ALLOWED_SYSTEM_ROOTS,
) {
    suspend fun locate(packageName: String): SystemPackageInfo {
        val pmResult = executor.execute(listOf("pm", "path", packageName))
        val paths = parsePmPaths(pmResult.stdout)
        val active = paths.firstOrNull()
        val dumpsys = executor.execute(listOf("dumpsys", "package", packageName)).stdout
        val realPaths = paths.associateWith { path ->
            executor.execute(listOf("readlink", "-f", path)).stdout.trim().takeIf(String::isNotBlank) ?: path
        }
        val fsType = active?.let {
            executor.execute(listOf("stat", "-f", "-c", "%T", it)).stdout.trim().takeIf(String::isNotBlank)
        }
        val partition = active?.let {
            executor.execute(listOf("df", "-P", it)).stdout.lineSequence().lastOrNull()
                ?.trim()?.split(Regex("\\s+"))?.firstOrNull()
        }
        return parse(
            packageName = packageName,
            pmPathOutput = pmResult.stdout,
            dumpsysOutput = dumpsys,
            realPaths = realPaths,
            backingPartition = partition,
            filesystemType = fsType,
            allowedSystemRoots = allowedSystemRoots,
        )
    }

    companion object {
        fun parse(
            packageName: String,
            pmPathOutput: String,
            dumpsysOutput: String = "",
            realPaths: Map<String, String> = emptyMap(),
            backingPartition: String? = null,
            filesystemType: String? = null,
            allowedSystemRoots: List<String> = SystemPackageInfo.ALLOWED_SYSTEM_ROOTS,
        ): SystemPackageInfo {
            val paths = parsePmPaths(pmPathOutput)
            val active = paths.firstOrNull()
            val updatedSystemApp = dumpsysOutput.contains("UPDATED_SYSTEM_APP")
            val systemCodePath = Regex("systemCodePath=([^\\s]+)")
                .find(dumpsysOutput)?.groupValues?.get(1)
                ?.takeIf { updatedSystemApp && isAllowedSystemPath(it, allowedSystemRoots) }
            val original = paths.firstOrNull { isAllowedSystemPath(it, allowedSystemRoots) } ?: systemCodePath
            val base = paths.firstOrNull { it.substringAfterLast('/') == "base.apk" } ?: original ?: active
            val target = (original ?: active)?.substringBeforeLast('/', missingDelimiterValue = "")
                ?.takeIf(String::isNotBlank)
            val splits = paths.filterNot { it == base }
            val hasDataUpdate = paths.any(::isDataAppPath) && updatedSystemApp && systemCodePath != null &&
                dumpsysOutput.contains("codePath=/data/app/")
            return SystemPackageInfo(
                packageName = packageName,
                activeCodePath = active,
                originalSystemPath = original,
                targetDirectory = target,
                baseApk = base,
                splitApks = splits,
                hasDataUpdate = hasDataUpdate,
                backingPartition = backingPartition,
                filesystemType = filesystemType,
                resolvedRealPath = active?.let { realPaths[it] ?: it },
            )
        }

        fun parsePmPaths(output: String): List<String> = output.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }
            .filter { it.isNotBlank() && !it.contains('\u0000') }
            .toList()

        fun isDataAppPath(path: String): Boolean = path.startsWith("/data/app/")

        fun isAllowedSystemPath(path: String, roots: List<String> = SystemPackageInfo.ALLOWED_SYSTEM_ROOTS): Boolean =
            !path.contains("..") && !path.contains('\\') && roots.any { path.startsWith("$it/") }
    }
}
