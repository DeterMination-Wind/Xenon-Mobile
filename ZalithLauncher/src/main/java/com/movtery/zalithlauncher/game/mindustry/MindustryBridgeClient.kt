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

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ResultReceiver
import java.io.File

data class MindustryBridgeTarget(
    val packageName: String,
    val variant: MindustryVariant,
    val backend: MindustryBackend,
    val slot: Int? = null,
    val instanceId: String? = null,
    val installed: Boolean = true
) {
    val label: String
        get() = buildString {
            append(variant.displayName)
            append(" /")
            append(' ')
            append(backend.name)
            slot?.let { append(" / slot ").append(it) }
            instanceId?.let { append(" / ").append(it) }
        }
}

/** Hub-side discovery and request builder for clone Bridge services. */
object MindustryBridgeClient {
    private const val HUB_SERVICE_CLASS = "com.movtery.zalithlauncher.game.mindustry.MindustryBridgeService"
    /** Stable class name injected into every source-built clone by the tag workflow. */
    private const val CLONE_SERVICE_CLASS = "xenon.mobile.bridge.MindustryBridgeService"

    fun installedTargets(context: Context): List<MindustryBridgeTarget> {
        val packageManager = context.packageManager
        val clones = ApkCloneSlot.all().mapNotNull { clone ->
            val packageName = clone.packageName
            runCatching {
                packageManager.getPackageInfo(packageName, 0)
            }.getOrNull() ?: return@mapNotNull null
            MindustryBridgeTarget(
                packageName = packageName,
                variant = clone.variant,
                backend = MindustryBackend.APK,
                slot = clone.slot
            )
        }

        val jars = runCatching {
            val instances = MindustryPaths(mindustryRoot(context)).instances
            instances.listFiles().orEmpty().filter { directory ->
                directory.isDirectory && directory.listFiles()?.any { it.extension.equals("jar", true) } == true
            }.mapNotNull { directory ->
                val instance = MindustryInstanceStore.read(directory) ?: return@mapNotNull null
                MindustryBridgeTarget(
                    packageName = context.packageName,
                    variant = instance.variant,
                    backend = MindustryBackend.JAR,
                    slot = null,
                    instanceId = instance.id
                )
            }
        }.getOrDefault(emptyList())
        return (clones + jars).distinctBy {
            "${it.packageName}:${it.variant.catalogId}:${it.backend}:${it.slot}:${it.instanceId}"
        }
    }

    fun sendJoin(
        context: Context,
        target: MindustryBridgeTarget,
        host: String,
        port: Int,
        receiver: ResultReceiver? = null
    ): Boolean {
        require(port in 1..65535) { "Invalid server port" }
        val intent = requestIntent(context, target, MindustryBridgeContract.ACTION_JOIN, receiver).apply {
            putExtra(MindustryBridgeContract.EXTRA_HOST, host)
            putExtra(MindustryBridgeContract.EXTRA_PORT, port)
        }
        return runCatching {
            context.startService(intent)
            true
        }.getOrDefault(false)
    }

    fun sendStatus(
        context: Context,
        target: MindustryBridgeTarget,
        receiver: ResultReceiver? = null
    ): Boolean = send(context, target, MindustryBridgeContract.ACTION_STATUS, receiver)

    fun sendLaunch(
        context: Context,
        target: MindustryBridgeTarget,
        receiver: ResultReceiver? = null
    ): Boolean = send(context, target, MindustryBridgeContract.ACTION_LAUNCH, receiver)

    fun sendImportBackup(
        context: Context,
        target: MindustryBridgeTarget,
        uri: Uri,
        receiver: ResultReceiver? = null
    ): Boolean = send(context, target, MindustryBridgeContract.ACTION_IMPORT_ZIP, receiver) {
        withZipUri(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun sendExportBackup(
        context: Context,
        target: MindustryBridgeTarget,
        uri: Uri,
        receiver: ResultReceiver? = null
    ): Boolean = send(context, target, MindustryBridgeContract.ACTION_EXPORT_ZIP, receiver) {
        withZipUri(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    fun sendDiagnostics(
        context: Context,
        target: MindustryBridgeTarget,
        uri: Uri,
        receiver: ResultReceiver? = null
    ): Boolean = send(context, target, MindustryBridgeContract.ACTION_EXPORT_DIAGNOSTICS, receiver) {
        withZipUri(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    fun sendGracefulExit(
        context: Context,
        target: MindustryBridgeTarget,
        receiver: ResultReceiver? = null
    ): Boolean = send(context, target, MindustryBridgeContract.ACTION_REQUEST_GRACEFUL_EXIT, receiver)

    fun sendReset(
        context: Context,
        target: MindustryBridgeTarget,
        receiver: ResultReceiver? = null
    ): Boolean = send(context, target, MindustryBridgeContract.ACTION_RESET_WHITELISTED_DATA, receiver)

    fun sendProfile(
        context: Context,
        target: MindustryBridgeTarget,
        profile: UuidProfile,
        receiver: ResultReceiver? = null
    ): Boolean {
        return send(context, target, MindustryBridgeContract.ACTION_SET_PROFILE, receiver) {
            putExtra(MindustryBridgeContract.EXTRA_PROFILE_ID, profile.id)
            putExtra(MindustryBridgeContract.EXTRA_UUID, profile.uuid)
            putExtra(MindustryBridgeContract.EXTRA_NAME, profile.name)
        }
    }

    private fun send(
        context: Context,
        target: MindustryBridgeTarget,
        action: String,
        receiver: ResultReceiver?,
        customize: Intent.() -> Unit = {}
    ): Boolean {
        val intent = requestIntent(context, target, action, receiver).apply(customize)
        return runCatching {
            intent.data?.let { uri ->
                val grantFlags = intent.flags and
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                if (grantFlags != 0) {
                    runCatching { context.grantUriPermission(target.packageName, uri, grantFlags) }
                }
            }
            context.startService(intent)
            true
        }.getOrDefault(false)
    }

    private fun requestIntent(
        context: Context,
        target: MindustryBridgeTarget,
        action: String,
        receiver: ResultReceiver?
    ): Intent = Intent(action).apply {
        component = ComponentName(
            target.packageName,
            if (target.backend == MindustryBackend.APK) CLONE_SERVICE_CLASS else HUB_SERVICE_CLASS
        )
        putExtra(MindustryBridgeContract.EXTRA_PROTOCOL_VERSION, MindustryBridgeContract.PROTOCOL_VERSION)
        putExtra(MindustryBridgeContract.EXTRA_CALLER_PACKAGE, context.packageName)
        putExtra(MindustryBridgeContract.EXTRA_VARIANT, target.variant.catalogId)
        putExtra(MindustryBridgeContract.EXTRA_BACKEND, target.backend.name.lowercase())
        target.slot?.let { putExtra(MindustryBridgeContract.EXTRA_SLOT, it) }
        target.instanceId?.let { putExtra(MindustryBridgeContract.EXTRA_INSTANCE_ID, it) }
        receiver?.let { putExtra(MindustryBridgeContract.EXTRA_RESULT_RECEIVER, it) }
    }

    private fun Intent.withZipUri(uri: Uri, grantFlags: Int) {
        putExtra(MindustryBridgeContract.EXTRA_ZIP_URI, uri)
        data = uri
        clipData = ClipData.newRawUri("xenon-mobile-bridge", uri)
        addFlags(grantFlags)
    }

    private fun mindustryRoot(context: Context): File =
        context.getExternalFilesDir(null)?.resolve("mindustry") ?: context.filesDir.resolve("mindustry")
}
