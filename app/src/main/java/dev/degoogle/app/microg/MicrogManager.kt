package dev.degoogle.app.microg

import android.content.Context
import android.util.Log
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
        onStep("Consultando o repositório oficial do microG…")
        val gms = releases.latest("com.google.android.gms")
            ?: return PrepareResult(false, null, null, "Não foi possível consultar o repositório do microG.").also {
                Log.e(TAG, "falha ao consultar release do GmsCore")
            }
        Log.i(TAG, "GmsCore: v${gms.versionCode} ${gms.apkName} sha256=${gms.sha256.take(16)}…")
        onStep("microG Services: ${label(gms)}")

        val companion = releases.latest("com.android.vending")
            ?: return PrepareResult(false, gms, null, "Não foi possível consultar a release do Companion.").also {
                Log.e(TAG, "falha ao consultar release do Companion")
            }
        Log.i(TAG, "Companion: v${companion.versionCode} ${companion.apkName}")
        onStep("microG Companion: ${label(companion)}")

        val downloads = File(context.filesDir, "downloads").apply { mkdirs() }

        val gmsApk = File(downloads, gms.downloadFileName)
        val companionApk = File(downloads, companion.downloadFileName)

        onStep("Baixando microG Services…")
        if (!releases.download(gms, gmsApk)) {
            gmsApk.delete()
            return PrepareResult(false, gms, companion, "Falha no download do microG Services.")
        }
        onStep("Baixando microG Companion…")
        if (!releases.download(companion, companionApk)) {
            gmsApk.delete(); companionApk.delete()
            return PrepareResult(false, gms, companion, "Falha no download do Companion.")
        }

        onStep("Validando APKs (pacote, versão, assinatura, hash)…")
        val v1 = validator.validate(
            file = gmsApk,
            expectedPackage = "com.google.android.gms",
            expectedVersionCode = gms.versionCode,
            // GitHub não publica versionName por pacote (tag vale para o GmsCore;
            // o Companion tem o seu). Só exigimos versionName quando a fonte
            // (F-Droid v2) informa um por pacote.
            expectedVersionName = gms.versionName.takeIf { gms.sha256.isNotEmpty() } ?: "",
            expectedSha256 = gms.sha256,
            expectedCertSha256 = null, // índice v1 não publica cert; v2 sim
        )
        if (v1 !is ApkValidator.ValidationResult.Ok) {
            gmsApk.delete()
            return PrepareResult(false, gms, companion, "microG Services inválido: ${(v1 as? ApkValidator.ValidationResult.Failed)?.reason}")
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
            return PrepareResult(false, gms, companion, "Companion inválido: ${(v2 as? ApkValidator.ValidationResult.Failed)?.reason}")
        }

        onStep("Aplicando máscaras e bind mounts (root)…")
        val r = backend.prepare(gmsApk.absolutePath, companionApk.absolutePath)
        if (!r.succeeded) {
            Log.e(TAG, "prepare falhou exit=${r.exitCode}: ${r.stderr.lineSequence().lastOrNull()}")
            return PrepareResult(
                false, gms, companion,
                "prepare falhou (exit ${r.exitCode}). Nenhuma alteração foi mantida.\n" +
                    r.stderr.trim().takeLast(400),
            )
        }
        Log.i(TAG, "prepare ok")
        onStep("Preparação concluída — soft reboot necessário.")
        return PrepareResult(true, gms, companion, "ok")
    }

    suspend fun finalize(): Boolean {
        onStep("Verificando priv-app e aplicando configuração técnica…")
        val r = backend.finalize()
        if (!r.succeeded) {
            onStep("finalize falhou (exit ${r.exitCode}): ${r.stderr.lineSequence().lastOrNull()}")
            return false
        }
        return true
    }

    suspend fun createBackup(): Boolean {
        onStep("Criando backup do microG…")
        val r = backend.backup()
        if (!r.succeeded) {
            onStep("backup falhou (exit ${r.exitCode}): ${r.stderr.lineSequence().lastOrNull()}")
            return false
        }
        onStep("Backup criado.")
        return true
    }

    suspend fun restoreBackup(): Boolean {
        onStep("Restaurando backup do MicroG Session…")
        val r = backend.restoreBackup()
        if (!r.succeeded) {
            onStep("restore-backup falhou (exit ${r.exitCode}): ${r.stderr.lineSequence().lastOrNull()}")
            return false
        }
        onStep("Backup restaurado.")
        return true
    }

    suspend fun restoreStock(wipeData: Boolean = true): Boolean {
        onStep("Removendo máscaras e revelando o stock…")
        val r = backend.restoreStock(wipeData)
        if (!r.succeeded) {
            onStep("restore-stock falhou (exit ${r.exitCode}): ${r.stderr.lineSequence().lastOrNull()}")
            return false
        }
        onStep("Rollback preparado — soft reboot necessário.")
        return true
    }

    suspend fun softReboot(): Boolean {
        onStep("Solicitando soft reboot…")
        val r = backend.softReboot()
        return r.succeeded
    }
}
