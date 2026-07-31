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

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.ResultReceiver
import com.movtery.zalithlauncher.path.PathManager
import com.movtery.zalithlauncher.ui.activities.createMindustryJarIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Explicit, signature-protected IPC endpoint used by the Hub to control a clone.
 * Requests are one-shot intents and results are returned through ResultReceiver.
 */
class MindustryBridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        val receiver = intent.resultReceiver()
        val requestId = intent.getStringExtra(MindustryBridgeContract.EXTRA_REQUEST_ID).orEmpty()
        val validation = validate(intent)
        if (validation is Validation.Error) {
            send(receiver, validation.code, requestId, error = validation.message)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        val request = (validation as Validation.Valid).request
        when (intent.action) {
            MindustryBridgeContract.ACTION_STATUS -> sendStatus(receiver, requestId, request)
            MindustryBridgeContract.ACTION_SET_PROFILE -> scope.launchRequest(receiver, requestId) {
                setProfile(intent, request)
            }
            MindustryBridgeContract.ACTION_LAUNCH -> scope.launchRequest(receiver, requestId) {
                launchClone(intent, request)
            }
            MindustryBridgeContract.ACTION_JOIN -> scope.launchRequest(receiver, requestId) {
                joinServer(intent, request)
            }
            MindustryBridgeContract.ACTION_IMPORT_ZIP -> scope.launchRequest(receiver, requestId) {
                importBackup(intent, request)
            }
            MindustryBridgeContract.ACTION_EXPORT_ZIP -> scope.launchRequest(receiver, requestId) {
                exportBackup(intent, request)
            }
            MindustryBridgeContract.ACTION_EXPORT_DIAGNOSTICS -> scope.launchRequest(receiver, requestId) {
                exportDiagnostics(intent, request)
            }
            MindustryBridgeContract.ACTION_REQUEST_GRACEFUL_EXIT -> scope.launchRequest(receiver, requestId) {
                gracefulExit()
            }
            MindustryBridgeContract.ACTION_RESET_WHITELISTED_DATA -> scope.launchRequest(receiver, requestId) {
                resetManagedState()
            }
            else -> send(receiver, MindustryBridgeContract.RESULT_BAD_REQUEST, requestId, error = "Unsupported Bridge action")
        }
        return START_NOT_STICKY
    }

    private fun validate(intent: Intent): Validation {
        if (intent.action !in MindustryBridgeContract.actions) {
            return Validation.Error(MindustryBridgeContract.RESULT_BAD_REQUEST, "Unsupported Bridge action")
        }
        if (intent.getIntExtra(MindustryBridgeContract.EXTRA_PROTOCOL_VERSION, -1) !=
            MindustryBridgeContract.PROTOCOL_VERSION
        ) {
            return Validation.Error(MindustryBridgeContract.RESULT_BAD_REQUEST, "Unsupported Bridge protocol version")
        }

        val callerPackage = intent.getStringExtra(MindustryBridgeContract.EXTRA_CALLER_PACKAGE)
            ?: return Validation.Error(MindustryBridgeContract.RESULT_UNAUTHORIZED, "Caller package is required")
        if (!isTrustedCaller(callerPackage)) {
            return Validation.Error(MindustryBridgeContract.RESULT_UNAUTHORIZED, "Caller is not signed with Xenon Mobile")
        }

        val local = localCloneIdentity()
        val variant = intent.getStringExtra(MindustryBridgeContract.EXTRA_VARIANT)?.let {
            runCatching { MindustryVariant.fromCatalogId(it) }.getOrElse {
                return Validation.Error(MindustryBridgeContract.RESULT_BAD_REQUEST, "Unknown Mindustry variant")
            }
        }
        val backend = intent.getStringExtra(MindustryBridgeContract.EXTRA_BACKEND)?.let { raw ->
            MindustryBackend.entries.firstOrNull { it.name.equals(raw, true) }
                ?: return Validation.Error(MindustryBridgeContract.RESULT_BAD_REQUEST, "Unknown Mindustry backend")
        }
        val slot = if (intent.hasExtra(MindustryBridgeContract.EXTRA_SLOT)) {
            intent.getIntExtra(MindustryBridgeContract.EXTRA_SLOT, -1).takeIf { it > 0 }
                ?: return Validation.Error(MindustryBridgeContract.RESULT_BAD_REQUEST, "Invalid Mindustry slot")
        } else null

        if (local != null) {
            if (variant != null && variant != local.first) {
                return Validation.Error(MindustryBridgeContract.RESULT_BAD_REQUEST, "Variant does not match this clone")
            }
            if (slot != null && slot != local.second) {
                return Validation.Error(MindustryBridgeContract.RESULT_BAD_REQUEST, "Slot does not match this clone")
            }
            if (backend != null && backend != MindustryBackend.APK) {
                return Validation.Error(MindustryBridgeContract.RESULT_BAD_REQUEST, "Clone Bridge only accepts APK backend")
            }
        }

        val resolvedVariant = local?.first ?: variant
        val resolvedSlot = local?.second ?: slot
        if (resolvedSlot != null && resolvedVariant != null &&
            !ApkCloneSlot.isValidSlot(resolvedVariant, resolvedSlot)
        ) {
            return Validation.Error(MindustryBridgeContract.RESULT_BAD_REQUEST, "Invalid Mindustry variant and slot")
        }
        val instanceId = intent.getStringExtra(MindustryBridgeContract.EXTRA_INSTANCE_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (local == null && backend == MindustryBackend.JAR) {
            if (instanceId == null) {
                return Validation.Error(MindustryBridgeContract.RESULT_BAD_REQUEST, "JAR requests require an instance id")
            }
            val instance = MindustryInstanceStore.read(instanceId)
                ?: return Validation.Error(MindustryBridgeContract.RESULT_NOT_FOUND, "Mindustry JAR instance was not found")
            if (resolvedVariant != null && instance.variant != resolvedVariant) {
                return Validation.Error(MindustryBridgeContract.RESULT_BAD_REQUEST, "Instance variant does not match request")
            }
        }
        return Validation.Valid(
            Request(
                variant = resolvedVariant,
                backend = backend ?: if (local != null) MindustryBackend.APK else null,
                slot = resolvedSlot,
                instanceId = instanceId,
                callerPackage = callerPackage
            )
        )
    }

    private fun isTrustedCaller(callerPackage: String): Boolean {
        if (callerPackage == packageName) return true
        val callerUid = runCatching { packageManager.getPackageUid(callerPackage, 0) }.getOrNull()
            ?: return false
        val actualUid = Binder.getCallingUid()
        if (actualUid != Process.myUid() && actualUid != Process.SYSTEM_UID &&
            packageManager.getPackagesForUid(actualUid)?.contains(callerPackage) != true
        ) return false
        return packageManager.checkSignatures(applicationInfo.uid, callerUid) ==
            PackageManager.SIGNATURE_MATCH
    }

    private fun localCloneIdentity(): Pair<MindustryVariant, Int>? {
        val match = Regex("^com\\.xenon\\.mobile\\.clone\\.(vanilla|be|mindustryx)\\.slot(\\d+)$")
            .matchEntire(packageName) ?: return null
        val variant = runCatching { MindustryVariant.fromCatalogId(match.groupValues[1]) }.getOrNull() ?: return null
        val slot = match.groupValues[2].toIntOrNull() ?: return null
        return if (ApkCloneSlot.isValidSlot(variant, slot)) variant to slot else null
    }

    private fun sendStatus(receiver: ResultReceiver?, requestId: String, request: Request) {
        val profile = runCatching {
            MindustrySettingsBin.readProfile(File(settingsRoot(request), MindustrySettingsBin.SETTINGS_FILE_NAME))
        }.getOrNull()
        val result = Bundle().apply {
            putString(MindustryBridgeContract.EXTRA_PACKAGE_NAME, packageName)
            putString(MindustryBridgeContract.EXTRA_VARIANT, request.variant?.catalogId)
            putString(MindustryBridgeContract.EXTRA_BACKEND, request.backend?.name?.lowercase())
            request.slot?.let { putInt(MindustryBridgeContract.EXTRA_SLOT, it) }
            putBoolean(MindustryBridgeContract.EXTRA_BUSY, MindustryRuntimeCoordinator.isBlocked() || MindustryBridgeRuntime.isRunning())
            putBoolean(MindustryBridgeContract.EXTRA_INSTALLED, true)
            profile?.let {
                putString(MindustryBridgeContract.EXTRA_PROFILE_ID, it.id)
                putString(MindustryBridgeContract.EXTRA_PROFILE_UUID, it.uuid)
                putString(MindustryBridgeContract.EXTRA_PROFILE_NAME, it.name)
            }
        }
        send(receiver, MindustryBridgeContract.RESULT_OK, requestId, result)
    }

    private suspend fun setProfile(intent: Intent, request: Request): BridgeResult {
        if (isBusy()) return BridgeResult(MindustryBridgeContract.RESULT_BUSY, "Game is running")
        val profile = requestedProfile(intent, request) ?:
            return BridgeResult(MindustryBridgeContract.RESULT_BAD_REQUEST, "A valid Mindustry Profile is required")
        MindustrySettingsBin.writeProfile(settingsRoot(request), profile)
        return BridgeResult(MindustryBridgeContract.RESULT_OK, extras = Bundle().apply {
            putString(MindustryBridgeContract.EXTRA_PROFILE_ID, profile.id)
            putString(MindustryBridgeContract.EXTRA_PROFILE_UUID, profile.uuid)
            putString(MindustryBridgeContract.EXTRA_PROFILE_NAME, profile.name)
        })
    }

    private suspend fun launchClone(intent: Intent, request: Request): BridgeResult {
        if (isBusy()) return BridgeResult(MindustryBridgeContract.RESULT_BUSY, "Another Mindustry instance is running")
        val instanceId = request.instanceId ?: packageName
        if (!MindustryRuntimeCoordinator.tryAcquire(instanceId, request.backend ?: MindustryBackend.APK)) {
            return BridgeResult(MindustryBridgeContract.RESULT_BUSY, "Another Mindustry instance is running")
        }
        return runCatching {
            val launchIntent = if (request.backend == MindustryBackend.JAR && localCloneIdentity() == null) {
                val instance = MindustryInstanceStore.read(instanceId)
                    ?: throw IllegalStateException("Mindustry JAR instance was not found")
                requestedProfile(intent, request)?.let { profile ->
                    MindustrySettingsBin.writeProfile(settingsRoot(request), profile)
                }
                createMindustryJarIntent(
                    context = this,
                    instance = instance,
                    paths = MindustryPaths(PathManager.DIR_MINDUSTRY),
                    joinHost = intent.getStringExtra(MindustryBridgeContract.EXTRA_HOST),
                    joinPort = intent.getIntExtra(
                        MindustryBridgeContract.EXTRA_PORT,
                        MindustryServerEndpoint.DEFAULT_PORT
                    )
                )
            } else {
                packageManager.getLaunchIntentForPackage(packageName)
                    ?: throw IllegalStateException("Clone launch activity was not found")
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            launchIntent.action = MindustryBridgeContract.ACTION_LAUNCH
            launchIntent.putExtras(intent.extras ?: Bundle())
            startActivity(launchIntent)
            BridgeResult(MindustryBridgeContract.RESULT_OK)
        }.getOrElse { error ->
            MindustryRuntimeCoordinator.release(instanceId)
            BridgeResult(MindustryBridgeContract.RESULT_NOT_FOUND, error.message ?: "Clone could not be launched")
        }
    }

    private suspend fun joinServer(intent: Intent, request: Request): BridgeResult {
        val host = intent.getStringExtra(MindustryBridgeContract.EXTRA_HOST)?.trim()
            ?: return BridgeResult(MindustryBridgeContract.RESULT_BAD_REQUEST, "Server host is required")
        val port = intent.getIntExtra(MindustryBridgeContract.EXTRA_PORT, MindustryServerEndpoint.DEFAULT_PORT)
        val endpoint = runCatching {
            val formatted = if (host.contains(':') && !host.startsWith('[')) "[$host]:$port" else "$host:$port"
            MindustryServerEndpoint.parse(formatted)
        }.getOrElse { return BridgeResult(MindustryBridgeContract.RESULT_BAD_REQUEST, "Invalid server address") }

        if (isBusy()) return BridgeResult(MindustryBridgeContract.RESULT_BUSY, "Another Mindustry instance is running")
        val launch = launchClone(
            Intent(intent).apply {
                action = MindustryBridgeContract.ACTION_JOIN
                putExtra(MindustryBridgeContract.EXTRA_HOST, endpoint.host)
                putExtra(MindustryBridgeContract.EXTRA_PORT, endpoint.port)
            },
            request
        )
        return launch
    }

    private suspend fun importBackup(intent: Intent, request: Request): BridgeResult {
        if (isBusy()) return BridgeResult(MindustryBridgeContract.RESULT_BUSY, "Game is running")
        val uri = intent.zipUri() ?: return BridgeResult(MindustryBridgeContract.RESULT_BAD_REQUEST, "Backup URI is required")
        return runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                MindustryBridgeArchive.importBackup(input, settingsRoot(request), request.variant, request.backend, request.slot)
            } ?: throw IllegalStateException("Could not open backup URI")
            BridgeResult(MindustryBridgeContract.RESULT_OK)
        }.getOrElse { BridgeResult(MindustryBridgeContract.RESULT_IO_ERROR, it.message ?: "Backup import failed") }
    }

    private suspend fun exportBackup(intent: Intent, request: Request): BridgeResult {
        val uri = intent.zipUri() ?: return BridgeResult(MindustryBridgeContract.RESULT_BAD_REQUEST, "Destination URI is required")
        return runCatching {
            contentResolver.openOutputStream(uri, "w")?.use { output ->
                MindustryBridgeArchive.exportBackup(settingsRoot(request), output, request.variant, request.backend, request.slot)
            } ?: throw IllegalStateException("Could not open destination URI")
            BridgeResult(MindustryBridgeContract.RESULT_OK)
        }.getOrElse { BridgeResult(MindustryBridgeContract.RESULT_IO_ERROR, it.message ?: "Backup export failed") }
    }

    private suspend fun exportDiagnostics(intent: Intent, request: Request): BridgeResult {
        val uri = intent.zipUri() ?: return BridgeResult(MindustryBridgeContract.RESULT_BAD_REQUEST, "Destination URI is required")
        return runCatching {
            contentResolver.openOutputStream(uri, "w")?.use { output ->
                MindustryBridgeArchive.exportDiagnostics(output, PathManager.DIR_LAUNCHER_LOGS, request.variant, request.backend, request.slot)
            } ?: throw IllegalStateException("Could not open destination URI")
            BridgeResult(MindustryBridgeContract.RESULT_OK)
        }.getOrElse { BridgeResult(MindustryBridgeContract.RESULT_IO_ERROR, it.message ?: "Diagnostics export failed") }
    }

    private suspend fun gracefulExit(): BridgeResult {
        if (!MindustryBridgeRuntime.isRunning()) return BridgeResult(MindustryBridgeContract.RESULT_NOT_FOUND, "No Mindustry instance is running")
        if (!MindustryBridgeRuntime.requestGracefulExit()) {
            return BridgeResult(MindustryBridgeContract.RESULT_NOT_FOUND, "The running instance does not accept graceful exit")
        }
        val stopped = withTimeoutOrNull(10_000L) {
            while (MindustryBridgeRuntime.isRunning() || MindustryRuntimeCoordinator.isBlocked()) delay(50L)
            true
        } ?: false
        return if (stopped) BridgeResult(MindustryBridgeContract.RESULT_OK)
        else BridgeResult(MindustryBridgeContract.RESULT_TIMEOUT, "Graceful exit timed out")
    }

    private suspend fun resetManagedState(): BridgeResult {
        if (isBusy()) return BridgeResult(MindustryBridgeContract.RESULT_BUSY, "Game is running")
        deleteTree(PathManager.DIR_MINDUSTRY_CATALOG_CACHE)
        deleteTree(PathManager.DIR_MINDUSTRY_CLONES)
        deleteTree(File(PathManager.DIR_MINDUSTRY, "bridge-data"))
        getSharedPreferences("mindustry_bridge_state", MODE_PRIVATE).edit().clear().apply()
        return BridgeResult(MindustryBridgeContract.RESULT_OK)
    }

    private suspend fun requestedProfile(intent: Intent, request: Request): UuidProfile? {
        val id = intent.getStringExtra(MindustryBridgeContract.EXTRA_PROFILE_ID)
        val uuid = intent.getStringExtra(MindustryBridgeContract.EXTRA_PROFILE_UUID)
        val name = intent.getStringExtra(MindustryBridgeContract.EXTRA_PROFILE_NAME)
        if (!uuid.isNullOrBlank() && !name.isNullOrBlank()) {
            return runCatching {
                UuidProfile(id = id?.takeIf { it.isNotBlank() } ?: "bridge-${uuid.hashCode()}", uuid = uuid, name = name)
            }.getOrNull()
        }
        return runCatching {
            id?.takeIf { it.isNotBlank() }?.let { MindustryProfileManager.get(it) }
                ?: MindustryProfileManager.resolve(scopeFor(request))
        }.getOrNull()
    }

    private fun scopeFor(request: Request): String =
        if (request.variant != null && request.slot != null && ApkCloneSlot.isValidSlot(request.variant, request.slot)) {
            MindustryProfileManager.slotScope(request.variant, request.slot)
        } else {
            MindustryProfileManager.variantScope(request.variant ?: MindustryVariant.VANILLA)
        }

    private fun settingsRoot(request: Request): File {
        if (request.backend != MindustryBackend.JAR) return PathManager.DIR_MINDUSTRY
        val instanceId = request.instanceId ?: error("JAR request has no instance id")
        val instance = MindustryInstanceStore.read(instanceId)
            ?: error("Mindustry JAR instance was not found: $instanceId")
        return instance.resolveDataDir(MindustryPaths(PathManager.DIR_MINDUSTRY))
    }

    private fun isBusy(): Boolean = MindustryRuntimeCoordinator.isBlocked() || MindustryBridgeRuntime.isRunning()

    private fun send(
        receiver: ResultReceiver?,
        code: Int,
        requestId: String,
        result: Bundle = Bundle(),
        error: String? = null
    ) {
        result.putString(MindustryBridgeContract.EXTRA_REQUEST_ID, requestId)
        result.putInt(MindustryBridgeContract.EXTRA_PROTOCOL_VERSION, MindustryBridgeContract.PROTOCOL_VERSION)
        result.putString(MindustryBridgeContract.EXTRA_STATUS, if (code == MindustryBridgeContract.RESULT_OK) "ok" else "error")
        error?.let { result.putString(MindustryBridgeContract.EXTRA_ERROR, it) }
        receiver?.send(code, result)
    }

    private fun CoroutineScope.launchRequest(
        receiver: ResultReceiver?,
        requestId: String,
        block: suspend () -> BridgeResult
    ) = launch {
        val result = try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            BridgeResult(MindustryBridgeContract.RESULT_IO_ERROR, error.message ?: error::class.java.simpleName)
        }
        send(receiver, result.code, requestId, result.extras, result.message)
    }

    private fun deleteTree(root: File) {
        if (root.exists()) root.walkBottomUp().forEach { it.delete() }
        root.mkdirs()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private data class Request(
        val variant: MindustryVariant?,
        val backend: MindustryBackend?,
        val slot: Int?,
        val instanceId: String?,
        val callerPackage: String
    )

    private sealed interface Validation {
        data class Valid(val request: Request) : Validation
        data class Error(val code: Int, val message: String) : Validation
    }

    private data class BridgeResult(
        val code: Int,
        val message: String? = null,
        val extras: Bundle = Bundle()
    )

    private fun Intent.resultReceiver(): ResultReceiver? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(MindustryBridgeContract.EXTRA_RESULT_RECEIVER, ResultReceiver::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(MindustryBridgeContract.EXTRA_RESULT_RECEIVER)
    }

    private fun Intent.zipUri(): Uri? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(MindustryBridgeContract.EXTRA_ZIP_URI, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(MindustryBridgeContract.EXTRA_ZIP_URI)
    }
}
