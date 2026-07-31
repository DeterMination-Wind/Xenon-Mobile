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

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Explicit installation failure categories shown by the Hub UI and persisted for recovery.
 */
enum class MindustryInstallFailure {
    INVALID_PACKAGE,
    VERSION_DOWNGRADE,
    SIGNATURE_MISMATCH,
    ABI_UNSUPPORTED,
    UNKNOWN_SOURCES,
    INSUFFICIENT_STORAGE,
    USER_CANCELED,
    SYSTEM_FAILURE
}

/**
 * Result of checking a downloaded APK before opening a PackageInstaller session.
 */
sealed interface MindustryApkVerification {
    data class Valid(val packageInfo: PackageInfo) : MindustryApkVerification
    data class Invalid(val failure: MindustryInstallFailure, val message: String) : MindustryApkVerification
}

/**
 * Verifies the identity and native profile of a catalog APK without installing it.
 */
object MindustryApkVerifier {
    fun versionCode(packageInfo: PackageInfo): Long = packageInfo.versionCodeCompat()

    fun signerDigests(packageInfo: PackageInfo): Set<String> = packageInfo.readSignerDigests()

    fun verifyArchive(
        context: Context,
        artifact: MindustryArtifact,
        apkFile: File,
        expectedSignerDigests: Set<String> = hubSignerDigests(context)
    ): MindustryApkVerification {
        if (artifact.backend != MindustryBackend.APK || artifact.packageName.isNullOrBlank()) {
            return invalid(MindustryInstallFailure.INVALID_PACKAGE, "Catalog artifact is not an APK clone")
        }
        val packageInfo = runCatching {
            context.packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.GET_META_DATA or PackageManager.GET_SIGNING_CERTIFICATES
            )
        }.getOrNull() ?: return invalid(MindustryInstallFailure.INVALID_PACKAGE, "APK manifest could not be read")

        if (packageInfo.packageName != artifact.packageName) {
            return invalid(
                MindustryInstallFailure.INVALID_PACKAGE,
                "Expected ${artifact.packageName}, got ${packageInfo.packageName}"
            )
        }
        val archiveVersionCode = packageInfo.versionCodeCompat()
        if (artifact.versionCode != null && archiveVersionCode != artifact.versionCode) {
            return invalid(
                MindustryInstallFailure.INVALID_PACKAGE,
                "Expected versionCode ${artifact.versionCode}, got $archiveVersionCode"
            )
        }
        if (packageInfo.versionName != artifact.versionName) {
            return invalid(
                MindustryInstallFailure.INVALID_PACKAGE,
                "Expected versionName ${artifact.versionName}, got ${packageInfo.versionName}"
            )
        }

        val metadata = packageInfo.applicationInfo?.metaData
            ?: return invalid(MindustryInstallFailure.INVALID_PACKAGE, "APK clone metadata is missing")
        if (!metadata.getString("xenon.variant").equals(artifact.variant.catalogId, true)) {
            return invalid(MindustryInstallFailure.INVALID_PACKAGE, "APK variant metadata does not match catalog")
        }
        if (!metadata.getString("xenon.backend").equals("apk", true)) {
            return invalid(MindustryInstallFailure.INVALID_PACKAGE, "APK backend metadata does not match catalog")
        }
        val slot = metadata.getInt("xenon.slot", -1)
        if (slot != artifact.slot) {
            return invalid(MindustryInstallFailure.INVALID_PACKAGE, "APK slot metadata does not match catalog")
        }

        if (!deviceSupportsNativeProfile(artifact.nativeProfile)) {
            return invalid(MindustryInstallFailure.ABI_UNSUPPORTED, "Device does not support ${artifact.nativeProfile}")
        }
        val hasNativeProfile = runCatching {
            ZipFile(apkFile).use { zip ->
                zip.entries().asSequence().any { it.name.startsWith("lib/${artifact.nativeProfile}/") }
            }
        }.getOrDefault(false)
        if (!hasNativeProfile) {
            return invalid(MindustryInstallFailure.ABI_UNSUPPORTED, "APK has no ${artifact.nativeProfile} native library")
        }

        val catalogSigners = artifact.signatureSha256.map(String::lowercase).toSet()
        val allowedSigners = if (catalogSigners.isNotEmpty()) catalogSigners else expectedSignerDigests
        val actualSigners = packageInfo.readSignerDigests()
        if (allowedSigners.isEmpty() || actualSigners.intersect(allowedSigners).isEmpty()) {
            return invalid(MindustryInstallFailure.SIGNATURE_MISMATCH, "APK signer is not trusted")
        }

        val installed = runCatching {
            context.packageManager.getPackageInfo(artifact.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }.getOrNull()
        if (installed != null && installed.versionCodeCompat() > archiveVersionCode) {
            return invalid(MindustryInstallFailure.VERSION_DOWNGRADE, "Installed version is newer than this APK")
        }
        return MindustryApkVerification.Valid(packageInfo)
    }

    fun verifyInstalled(context: Context, artifact: MindustryArtifact): Boolean {
        val packageName = artifact.packageName ?: return false
        val info = runCatching {
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }.getOrNull() ?: return false
        return info.versionCodeCompat() == artifact.versionCode &&
            info.versionName == artifact.versionName &&
            info.readSignerDigests().let { actual ->
                val allowed = artifact.signatureSha256.map(String::lowercase).toSet().ifEmpty {
                    hubSignerDigests(context)
                }
                allowed.isNotEmpty() && actual.intersect(allowed).isNotEmpty()
            }
    }

    fun hubSignerDigests(context: Context): Set<String> = runCatching {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES
        ).readSignerDigests()
    }.getOrDefault(emptySet())

    private fun deviceSupportsNativeProfile(profile: String): Boolean =
        profile == MindustryCatalog.ARM64_NATIVE_PROFILE &&
            Build.SUPPORTED_ABIS.any { it == MindustryCatalog.ARM64_NATIVE_PROFILE }

    private fun PackageInfo.versionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

    private fun PackageInfo.readSignerDigests(): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            signatures.orEmpty()
        }
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
        }.toSet()
    }

    private fun invalid(failure: MindustryInstallFailure, message: String): MindustryApkVerification.Invalid =
        MindustryApkVerification.Invalid(failure, message)
}
