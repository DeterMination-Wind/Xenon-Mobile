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
import com.movtery.zalithlauncher.database.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.util.UUID

/**
 * Room-backed Mindustry Profile manager shared by the Hub and clone bridge.
 */
object MindustryProfileManager {
    const val GLOBAL_SCOPE = "global"
    const val HUB_SCOPE = "hub"

    private lateinit var dao: MindustryProfileDao
    private val _profiles = MutableStateFlow<List<UuidProfile>>(emptyList())
    val profiles = _profiles.asStateFlow()

    /** Initializes the manager once during application startup. */
    fun initialize(context: Context) {
        dao = AppDatabase.getInstance(context).mindustryProfileDao()
    }

    /** Reloads the in-memory snapshot after application startup or a database change. */
    suspend fun reload() {
        _profiles.value = dao.getProfiles().map { it.toProfile() }
    }

    /** Resolves one stable Profile id without applying a scope fallback. */
    suspend fun get(profileId: String): UuidProfile? =
        dao.getProfile(profileId)?.toProfile()

    /** Returns the database-backed profile stream for Compose or other observers. */
    fun observeProfiles(): Flow<List<MindustryProfileEntity>> = dao.observeProfiles()

    /** Creates and persists a new profile with a random eight-byte Mindustry UUID. */
    suspend fun create(name: String, id: String = UUID.randomUUID().toString()): UuidProfile =
        save(UuidProfile.create(id = id, name = name))

    /** Edits a profile name while keeping its stable UUID and id. */
    suspend fun edit(profileId: String, name: String): UuidProfile {
        val current = requireNotNull(dao.getProfile(profileId)) { "Profile does not exist: $profileId" }
        return save(UuidProfile(id = current.id, uuid = current.uuid, name = name))
    }

    /** Deletes a profile and every binding pointing at it. */
    suspend fun delete(profileId: String) {
        dao.clearBindingsForProfile(profileId)
        dao.deleteProfileById(profileId)
        reload()
    }

    /** Binds a profile to a global, Hub, variant or clone-slot scope. */
    suspend fun bind(scopeKey: String, profileId: String) {
        require(scopeKey.isNotBlank()) { "Profile scope must not be blank" }
        requireNotNull(dao.getProfile(profileId)) { "Profile does not exist: $profileId" }
        dao.saveBinding(
            MindustryProfileBindingEntity(
                scopeKey = scopeKey,
                profileId = profileId,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /** Clears one binding without deleting the Profile. */
    suspend fun clearBinding(scopeKey: String) = dao.clearBinding(scopeKey)

    /** Resolves a scope, then the global default, without silently creating a profile. */
    suspend fun resolve(scopeKey: String): UuidProfile? =
        (dao.getBinding(scopeKey) ?: dao.getBinding(GLOBAL_SCOPE))
            ?.let { dao.getProfile(it.profileId)?.toProfile() }

    /** Returns a deterministic scope for one APK clone slot. */
    fun slotScope(variant: MindustryVariant, slot: Int): String {
        require(ApkCloneSlot.isValidSlot(variant, slot)) { "Invalid clone slot" }
        return "slot:${variant.catalogId}:$slot"
    }

    /** Returns a deterministic scope for a Jar variant. */
    fun variantScope(variant: MindustryVariant): String = "variant:${variant.catalogId}"

    /**
     * Reads a clone's existing identity without writing anything. The caller must display
     * this value and call [importConfirmed] only after explicit user confirmation.
     */
    suspend fun previewCloneIdentity(dataDir: File): UuidProfile? = withContext(Dispatchers.IO) {
        MindustrySettingsBin.readProfile(
            settingsFile = File(dataDir, MindustrySettingsBin.SETTINGS_FILE_NAME),
            id = "imported-${UUID.randomUUID()}"
        )
    }

    /** Imports a previously previewed identity only after an explicit confirmation. */
    suspend fun importConfirmed(preview: UuidProfile, confirmed: Boolean): UuidProfile {
        require(confirmed) { "Mindustry Profile import requires explicit confirmation" }
        return save(preview)
    }

    private suspend fun save(profile: UuidProfile): UuidProfile {
        val existing = dao.getProfile(profile.id)
        val now = System.currentTimeMillis()
        dao.saveProfile(
            MindustryProfileEntity(
                id = profile.id,
                name = profile.name,
                uuid = profile.uuid,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now
            )
        )
        reload()
        return profile
    }
}
