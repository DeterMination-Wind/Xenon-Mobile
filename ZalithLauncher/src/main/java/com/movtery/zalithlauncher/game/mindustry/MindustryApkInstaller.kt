/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.movtery.zalithlauncher.game.mindustry

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageInstaller
import android.os.Build
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Persisted state for one system PackageInstaller transaction.
 */
data class MindustryInstallSessionState(
    val sessionId: Int,
    val artifactId: String,
    val packageName: String,
    val status: String,
    val expectedVersionCode: Long = 0L,
    val expectedVersionName: String = "",
    val expectedSignerDigests: Set<String> = emptySet()
)

/**
 * Atomic APK installer. It never uninstalls another slot and never launches before verification.
 */
object MindustryApkInstaller {
    const val ACTION_INSTALL_STATUS = "com.xenon.mobile.bridge.INSTALL_STATUS"

    private const val PREFS = "mindustry_install_sessions"
    private const val KEY_SESSION_ID = "session_id"
    private const val KEY_ARTIFACT_ID = "artifact_id"
    private const val KEY_PACKAGE_NAME = "package_name"
    private const val KEY_STATUS = "status"
    private const val KEY_VERSION_CODE = "version_code"
    private const val KEY_VERSION_NAME = "version_name"
    private const val KEY_SIGNER_DIGESTS = "signer_digests"
    private const val STATUS_CREATED = "created"
    private const val STATUS_PENDING_USER_ACTION = "pending_user_action"
    private const val STATUS_SUCCESS = "success"
    private const val STATUS_FAILURE = "failure"

    /** Downloads, verifies and commits one APK session to the Android package manager. */
    suspend fun install(
        context: Context,
        artifact: MindustryArtifact,
        manifest: MindustryCatalogManifest
    ): MindustryInstallResult = withContext(Dispatchers.IO) {
        require(artifact.backend == MindustryBackend.APK) { "Only APK artifacts can be installed" }
        val packageName = requireNotNull(artifact.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            return@withContext MindustryInstallResult.Failure(
                MindustryInstallFailure.UNKNOWN_SOURCES,
                "Android has blocked installs from this source"
            )
        }

        val cached = runCatching {
            MindustryArtifactStore.downloadToCache(artifact, manifest)
            MindustryArtifactStore.verifyCachedArtifact(artifact)
        }.getOrElse { error ->
            return@withContext MindustryInstallResult.Failure(
                MindustryInstallFailure.SYSTEM_FAILURE,
                error.message ?: "Artifact download or verification failed"
            )
        }
        val verification = when (val result = MindustryApkVerifier.verifyArchive(context, artifact, cached)) {
            is MindustryApkVerification.Invalid ->
                return@withContext MindustryInstallResult.Failure(result.failure, result.message)
            is MindustryApkVerification.Valid -> result
        }

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            setSize(cached.length())
        }
        val sessionId = try {
            installer.createSession(params)
        } catch (e: Exception) {
            return@withContext MindustryInstallResult.Failure(
                mapInstallerException(e),
                e.message ?: "Could not create install session"
            )
        }

        persist(
            context,
            MindustryInstallSessionState(
                sessionId = sessionId,
                artifactId = artifact.id,
                packageName = packageName,
                status = STATUS_CREATED,
                expectedVersionCode = artifact.versionCode ?: MindustryApkVerifier.versionCode(verification.packageInfo),
                expectedVersionName = artifact.versionName,
                expectedSignerDigests = MindustryApkVerifier.signerDigests(verification.packageInfo)
            )
        )
        try {
            installer.openSession(sessionId).use { session ->
                cached.inputStream().use { input ->
                    session.openWrite("base.apk", 0L, cached.length()).use { output ->
                        input.copyTo(output)
                        output.flush()
                        session.fsync(output)
                    }
                }
                val intent = Intent(context, MindustryInstallReceiver::class.java).apply {
                    putExtra(KEY_SESSION_ID, sessionId)
                    putExtra(KEY_ARTIFACT_ID, artifact.id)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                persist(
                    context,
                    MindustryInstallSessionState(
                        sessionId = sessionId,
                        artifactId = artifact.id,
                        packageName = packageName,
                        status = STATUS_PENDING_USER_ACTION,
                        expectedVersionCode = artifact.versionCode ?: MindustryApkVerifier.versionCode(verification.packageInfo),
                        expectedVersionName = artifact.versionName,
                        expectedSignerDigests = MindustryApkVerifier.signerDigests(verification.packageInfo)
                    )
                )
                session.commit(pendingIntent.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            persist(context, MindustryInstallSessionState(sessionId, artifact.id, packageName, STATUS_FAILURE))
            return@withContext MindustryInstallResult.Failure(mapInstallerException(e), e.message.orEmpty())
        }
        MindustryInstallResult.PendingUserAction(sessionId)
    }

    /** Returns the persisted session so the Hub can restore progress after process death. */
    fun restore(context: Context): MindustryInstallSessionState? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sessionId = prefs.getInt(KEY_SESSION_ID, -1)
        if (sessionId < 0) return null
        val state = MindustryInstallSessionState(
            sessionId = sessionId,
            artifactId = prefs.getString(KEY_ARTIFACT_ID, "").orEmpty(),
            packageName = prefs.getString(KEY_PACKAGE_NAME, "").orEmpty(),
            status = prefs.getString(KEY_STATUS, STATUS_FAILURE).orEmpty(),
            expectedVersionCode = prefs.getLong(KEY_VERSION_CODE, 0L),
            expectedVersionName = prefs.getString(KEY_VERSION_NAME, "").orEmpty(),
            expectedSignerDigests = prefs.getStringSet(KEY_SIGNER_DIGESTS, emptySet()).orEmpty()
        )
        return state
    }

    /** Handles the system broadcast and only reports success after PackageManager verification. */
    fun handleResult(context: Context, intent: Intent): MindustryInstallResult {
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        val state = restore(context)
        if (state != null && state.sessionId != sessionId) {
            return failure(MindustryInstallFailure.SYSTEM_FAILURE, "Install result does not match the saved session")
        }
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            persist(
                context,
                state?.copy(status = STATUS_PENDING_USER_ACTION)
                    ?: return failure(MindustryInstallFailure.SYSTEM_FAILURE, message)
            )
            return MindustryInstallResult.PendingUserAction(sessionId)
        }

        val result = if (status == PackageInstaller.STATUS_SUCCESS && state != null) {
            if (state.packageName.isNotBlank()) {
                val installed = runCatching {
                    context.packageManager.getPackageInfo(
                        state.packageName,
                        PackageManagerFlags.signingCertificates
                    )
                }.getOrNull()
                if (installed != null &&
                    MindustryApkVerifier.versionCode(installed) == state.expectedVersionCode &&
                    installed.versionName == state.expectedVersionName &&
                    MindustryApkVerifier.signerDigests(installed)
                        .intersect(state.expectedSignerDigests).isNotEmpty()
                ) {
                    persist(context, state.copy(status = STATUS_SUCCESS))
                    MindustryInstallResult.Success(state.sessionId, state.packageName)
                } else if (installed == null) {
                    failure(MindustryInstallFailure.SYSTEM_FAILURE, "Package manager did not report the installed package")
                } else if (MindustryApkVerifier.signerDigests(installed)
                        .intersect(state.expectedSignerDigests).isEmpty()
                ) {
                    failure(MindustryInstallFailure.SIGNATURE_MISMATCH, "Installed APK signer does not match the downloaded APK")
                } else {
                    failure(MindustryInstallFailure.INVALID_PACKAGE, "Installed APK version does not match the downloaded APK")
                }
            } else failure(MindustryInstallFailure.SYSTEM_FAILURE, "Install session had no package name")
        } else {
            failure(mapStatus(status, message), message)
        }
        if (result is MindustryInstallResult.Failure) {
            persist(context, state?.copy(status = STATUS_FAILURE) ?: return result)
        }
        context.sendBroadcast(Intent(ACTION_INSTALL_STATUS).setPackage(context.packageName))
        return result
    }

    /** Starts an installed clone only after re-reading and matching its package metadata. */
    fun launchInstalled(context: Context, artifact: MindustryArtifact): Boolean {
        if (!MindustryApkVerifier.verifyInstalled(context, artifact)) return false
        val launchIntent = context.packageManager.getLaunchIntentForPackage(requireNotNull(artifact.packageName)) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return true
    }

    private fun persist(context: Context, state: MindustryInstallSessionState) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_SESSION_ID, state.sessionId)
            .putString(KEY_ARTIFACT_ID, state.artifactId)
            .putString(KEY_PACKAGE_NAME, state.packageName)
            .putString(KEY_STATUS, state.status)
            .putLong(KEY_VERSION_CODE, state.expectedVersionCode)
            .putString(KEY_VERSION_NAME, state.expectedVersionName)
            .putStringSet(KEY_SIGNER_DIGESTS, state.expectedSignerDigests)
            .apply()
    }

    private fun failure(
        failure: MindustryInstallFailure,
        message: String
    ): MindustryInstallResult.Failure = MindustryInstallResult.Failure(failure, message)

    private fun mapStatus(status: Int, message: String): MindustryInstallFailure = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> MindustryInstallFailure.USER_CANCELED
        PackageInstaller.STATUS_FAILURE_BLOCKED -> MindustryInstallFailure.UNKNOWN_SOURCES
        PackageInstaller.STATUS_FAILURE_STORAGE -> MindustryInstallFailure.INSUFFICIENT_STORAGE
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> MindustryInstallFailure.ABI_UNSUPPORTED
        PackageInstaller.STATUS_FAILURE_CONFLICT ->
            if (message.contains("SIGNATURE", ignoreCase = true)) {
                MindustryInstallFailure.SIGNATURE_MISMATCH
            } else {
                MindustryInstallFailure.INVALID_PACKAGE
            }
        PackageInstaller.STATUS_FAILURE_INVALID -> MindustryInstallFailure.INVALID_PACKAGE
        else -> when {
            message.contains("VERSION_DOWNGRADE", ignoreCase = true) -> MindustryInstallFailure.VERSION_DOWNGRADE
            message.contains("SIGNATURE", ignoreCase = true) -> MindustryInstallFailure.SIGNATURE_MISMATCH
            message.contains("INSUFFICIENT_STORAGE", ignoreCase = true) -> MindustryInstallFailure.INSUFFICIENT_STORAGE
            message.contains("USER_RESTRICTED", ignoreCase = true) -> MindustryInstallFailure.UNKNOWN_SOURCES
            else -> MindustryInstallFailure.SYSTEM_FAILURE
        }
    }

    private fun mapInstallerException(error: Throwable): MindustryInstallFailure {
        val message = error.message.orEmpty()
        return when {
            message.contains("storage", true) -> MindustryInstallFailure.INSUFFICIENT_STORAGE
            message.contains("downgrade", true) -> MindustryInstallFailure.VERSION_DOWNGRADE
            else -> MindustryInstallFailure.SYSTEM_FAILURE
        }
    }

    private object PackageManagerFlags {
        const val signingCertificates: Int = PackageManager.GET_SIGNING_CERTIFICATES
    }
}

/** Result returned immediately by the PackageInstaller transaction setup. */
sealed interface MindustryInstallResult {
    data class PendingUserAction(val sessionId: Int) : MindustryInstallResult
    data class Success(val sessionId: Int, val packageName: String) : MindustryInstallResult
    data class Failure(val failure: MindustryInstallFailure, val message: String) : MindustryInstallResult
}
