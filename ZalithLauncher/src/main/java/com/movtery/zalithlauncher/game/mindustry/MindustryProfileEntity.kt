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

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room row for a user-visible Mindustry Profile.
 */
@Entity(tableName = "mindustry_profiles")
data class MindustryProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val uuid: String,
    val createdAt: Long,
    val updatedAt: Long
) {
    fun toProfile(): UuidProfile = UuidProfile(id = id, uuid = uuid, name = name)

    companion object {
        fun fromProfile(profile: UuidProfile, now: Long = System.currentTimeMillis()): MindustryProfileEntity =
            MindustryProfileEntity(
                id = profile.id,
                name = profile.name,
                uuid = profile.uuid,
                createdAt = now,
                updatedAt = now
            )
    }
}

/**
 * Room row binding a launcher scope to a Profile id.
 */
@Entity(tableName = "mindustry_profile_bindings")
data class MindustryProfileBindingEntity(
    @PrimaryKey val scopeKey: String,
    val profileId: String,
    val updatedAt: Long
)

/**
 * Persistence API for Mindustry Profiles and scope bindings.
 */
@Dao
interface MindustryProfileDao {
    @Query("SELECT * FROM mindustry_profiles ORDER BY name COLLATE NOCASE, id")
    fun observeProfiles(): Flow<List<MindustryProfileEntity>>

    @Query("SELECT * FROM mindustry_profiles ORDER BY name COLLATE NOCASE, id")
    suspend fun getProfiles(): List<MindustryProfileEntity>

    @Query("SELECT * FROM mindustry_profiles WHERE id = :id LIMIT 1")
    suspend fun getProfile(id: String): MindustryProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProfile(profile: MindustryProfileEntity)

    @Delete
    suspend fun deleteProfile(profile: MindustryProfileEntity)

    @Query("DELETE FROM mindustry_profiles WHERE id = :id")
    suspend fun deleteProfileById(id: String)

    @Query("SELECT * FROM mindustry_profile_bindings WHERE scopeKey = :scopeKey LIMIT 1")
    suspend fun getBinding(scopeKey: String): MindustryProfileBindingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveBinding(binding: MindustryProfileBindingEntity)

    @Query("DELETE FROM mindustry_profile_bindings WHERE scopeKey = :scopeKey")
    suspend fun clearBinding(scopeKey: String)

    @Query("DELETE FROM mindustry_profile_bindings WHERE profileId = :profileId")
    suspend fun clearBindingsForProfile(profileId: String)
}
