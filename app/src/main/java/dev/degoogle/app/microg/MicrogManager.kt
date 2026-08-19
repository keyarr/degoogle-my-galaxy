package dev.degoogle.app.microg

import android.content.Context
import android.text.format.Formatter
import android.util.Log
import dev.degoogle.app.R
import dev.degoogle.app.root.BackendRunner
import dev.degoogle.app.root.RootExecutor
import java.io.File

/**
 * Orquestra o ciclo do microG usando o backend shell. Cada passo é reportado
 * via [onStep] e o fluxo aborta (sem tocar no sistema) se qualquer validação
 * falhar.
 */
class MicrogManager(
    private val context: Context,
    private val executor: RootExecutor,
    private val backend: BackendRunner,
    private val validator: ApkValidator,
    private val releases: ReleaseRepository,
    private val onStep: (String) -> Unit = {},
) {

    companion object {
        private const val TAG = "DeGoogle.MicroG"
    }

    /** Rótulo legível: versionName quando confiável (F-Droid), senão versionCode. */
    private fun label(r: Release): String =
        if (r.sha256.isNotEmpty() && r.versionName.isNotEmpty()) "v${r.versionName}" else "v${r.versionCode}"

    data class PrepareResult(
        val succeeded: Boolean,
        val gmsRelease: Release?,
        val companionRelease: Release?,
        val message: String,
    )

    suspend fun prepareNewSession(): PrepareResult {
        Log.i(TAG, "iniciando prepareNewSession")
        onStep(context.getString(R.string.op_querying_repo))
        val gms = releases.latest("com.google.android.gms")
            ?: return PrepareResult(false, null, null, context.getString(R.string.op_repo_error)).also {
                Log.e(TAG, "falha ao consultar release do GmsCore")
            }
        Log.i(TAG, "GmsCore: v${gms.versionCode} ${gms.apkName} sha256=${gms.sha256.take(16)}…")
        onStep(context.getString(R.string.op_gms_release, label(gms)))

        val companion = releases.latest("com.android.vending")
            ?: return PrepareResult(false, gms, null, context.getString(R.string.op_companion_error)).also {
                Log.e(TAG, "falha ao consultar release do Companion")
            }
        Log.i(TAG, "Companion: v${companion.versionCode} ${companion.apkName}")
        onStep(context.getString(R.string.op_companion_release, label(companion)))

        val downloads = File(context.filesDir, "downloads").apply { mkdirs() }

        val gmsApk = File(downloads, gms.downloadFileName)
        val companionApk = File(downloads, companion.downloadFileName)

        val gmsLabel = context.getString(R.string.op_downloading_gms)
        onStep(gmsLabel)
        if (!downloadWithProgress(gmsLabel, gms, gmsApk)) {
            gmsApk.delete()
            return PrepareResult(false, gms, companion, context.getString(R.string.op_download_gms_failed))
        }

        val companionLabel = context.getString(R.string.op_downloading_companion)
        onStep(companionLabel)
        if (!downloadWithProgress(companionLabel, companion, companionApk)) {
            gmsApk.delete(); companionApk.delete()
            return PrepareResult(false, gms, companion, context.getString(R.string.op_download_companion_failed))
        }

        onStep(context.getString(R.string.op_validating_apks))
        val v1 = validator.validate(
            file = gmsApk,
            expectedPackage = "com.google.android.gms",
            expectedVersionCode = gms.versionCode,
            expectedVersionName = gms.versionName.takeIf { gms.sha256.isNotEmpty() } ?: "",
            expectedSha256 = gms.sha256,
            expectedCertSha256 = null,
        )
        if (v1 !is ApkValidator.ValidationResult.Ok) {
            gmsApk.delete()
            val reason = (v1 as? ApkValidator.ValidationResult.Failed)?.reason.orEmpty()
            return PrepareResult(false, gms, companion, context.getString(R.string.op_invalid_gms, reason))
        }
        val v2 = validator.validate(
            file = companionApk,
            expectedPackage = "com.android.vending",
            expectedVersionCode = companion.versionCode,
            expectedVersionName = companion.versionName.takeIf { companion.sha256.isNotEmpty() } ?: "",
            expectedSha256 = companion.sha256,
            expectedCertSha256 = null,
        )
        if (v2 !is ApkValidator.ValidationResult.Ok) {
            companionApk.delete()
            val reason = (v2 as? ApkValidator.ValidationResult.Failed)?.reason.orEmpty()
            return PrepareResult(false, gms, companion, context.getString(R.string.op_invalid_companion, reason))
        }

        onStep(context.getString(R.string.op_preflight))
        val preflight = backend.preflight()
        if (!preflight.succeeded) {
            return PrepareResult(
                false,
                gms,
                companion,
                context.getString(
                    R.string.op_preflight_blocked,
                    preflight.exitCode,
                    preflight.stderr.trim().takeLast(400),
                ),
            )
        }

        onStep(context.getString(R.string.op_applying_masks))
        val r = backend.prepare(gmsApk.absolutePath, companionApk.absolutePath)
        if (!r.succeeded) {
            Log.e(TAG, "prepare falhou exit=${r.exitCode}: ${r.stderr.lineSequence().lastOrNull()}")
            return PrepareResult(
                false, gms, companion,
                context.getString(
                    R.string.op_prepare_failed,
                    r.exitCode,
                    r.stderr.trim().takeLast(400),
                ),
            )
        }
        Log.i(TAG, "prepare ok")
        onStep(context.getString(R.string.op_prepared_success))
        return PrepareResult(true, gms, companion, "ok")
    }

    suspend fun finalize(): Boolean {
        onStep(context.getString(R.string.op_validating_post_boot))
        val r = backend.finalize()
        if (!r.succeeded) {
            onStep(
                context.getString(
                    R.string.op_finalize_command_failed,
                    r.exitCode,
                    r.stderr.lineSequence().lastOrNull().orEmpty(),
                ),
            )
            return false
        }
        val postBoot = backend.postBootValidate()
        if (!postBoot.succeeded) {
            onStep(context.getString(R.string.op_post_boot_failed))
            return false
        }
        onStep(context.getString(R.string.op_post_boot_success))
        return true
    }

    suspend fun createBackup(): Boolean {
        onStep(context.getString(R.string.op_creating_backup))
        val r = backend.backup()
        if (!r.succeeded) {
            onStep(
                context.getString(
                    R.string.op_backup_command_failed,
                    r.exitCode,
                    r.stderr.lineSequence().lastOrNull().orEmpty(),
                ),
            )
            return false
        }
        onStep(context.getString(R.string.op_backup_success))
        return true
    }

    suspend fun restoreBackup(): Boolean {
        onStep(context.getString(R.string.op_restoring_backup))
        val r = backend.restoreBackup()
        if (!r.succeeded) {
            onStep(
                context.getString(
                    R.string.op_restore_backup_command_failed,
                    r.exitCode,
                    r.stderr.lineSequence().lastOrNull().orEmpty(),
                ),
            )
            return false
        }
        onStep(context.getString(R.string.op_restore_success))
        return true
    }

    suspend fun restoreStock(wipeData: Boolean = true): Boolean {
        onStep(context.getString(R.string.op_rollback_starting))
        val r = backend.restoreStock(wipeData)
        if (!r.succeeded) {
            onStep(
                context.getString(
                    R.string.op_restore_stock_command_failed,
                    r.exitCode,
                    r.stderr.lineSequence().lastOrNull().orEmpty(),
                ),
            )
            return false
        }
        onStep(context.getString(R.string.op_rollback_prepared))
        return true
    }

    suspend fun softReboot(): Boolean {
        onStep(context.getString(R.string.op_soft_reboot_requesting_log))
        val r = backend.softReboot()
        return r.succeeded
    }

    private fun downloadWithProgress(baseLabel: String, release: Release, targetFile: File): Boolean {
        val startMs = System.currentTimeMillis()
        var lastEmittedPercent = -1
        return releases.download(release, targetFile) { bytesRead, totalBytes ->
            val now = System.currentTimeMillis()
            val elapsedSec = ((now - startMs) / 1000.0).coerceAtLeast(0.1)
            val speedBytesPerSec = (bytesRead / elapsedSec).toLong()

            val percent = if (totalBytes > 0) ((bytesRead * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
            val etaStr = if (totalBytes > 0 && speedBytesPerSec > 0) {
                val remainingBytes = (totalBytes - bytesRead).coerceAtLeast(0)
                val etaSec = remainingBytes / speedBytesPerSec
                formatDuration(etaSec)
            } else {
                "--:--"
            }

            val downloadedStr = Formatter.formatShortFileSize(context, bytesRead)
            val totalStr = if (totalBytes > 0) Formatter.formatShortFileSize(context, totalBytes) else "?"
            val speedStr = "${Formatter.formatShortFileSize(context, speedBytesPerSec)}/s"

            if (percent != lastEmittedPercent || bytesRead == totalBytes) {
                lastEmittedPercent = percent
                val progressText = context.getString(
                    R.string.op_download_progress,
                    baseLabel.removeSuffix("…").removeSuffix("..."),
                    percent,
                    downloadedStr,
                    totalStr,
                    speedStr,
                    etaStr,
                )
                onStep(progressText)
            }
        }
    }

    private fun formatDuration(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return "%02d:%02d".format(m, s)
    }
}
