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

import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * Reproducible source pins shared by CI and regression fixtures.
 */
data class GameSourceLock(
    val schemaVersion: Int = 1,
    val defaults: Map<String, SourcePin> = emptyMap(),
    val slots: Map<String, SourcePin> = emptyMap(),
    val fixtures: Map<String, SourcePin> = emptyMap()
) {
    fun validate(): GameSourceLock {
        require(schemaVersion == 1) { "Unsupported source lock schema: $schemaVersion" }
        val pins = defaults.values + slots.values + fixtures.values
        require(pins.isNotEmpty()) { "Source lock must contain at least one pin" }
        pins.forEach(SourcePin::validate)
        defaults.keys.forEach(MindustryVariant::fromCatalogId)
        slots.keys.forEach { key ->
            val parts = key.split(':')
            require(parts.size == 2) { "Invalid source lock slot key: $key" }
            val variant = MindustryVariant.fromCatalogId(parts[0])
            require(parts[1].toIntOrNull()?.let { ApkCloneSlot.isValidSlot(variant, it) } == true) {
                "Invalid source lock slot key: $key"
            }
        }
        return this
    }

    fun pinFor(variant: MindustryVariant, slot: Int? = null): SourcePin =
        slot?.let { slots["${variant.catalogId}:$it"] }
            ?: defaults[variant.catalogId]
            ?: error("No source pin for ${variant.catalogId}${slot?.let { ":$it" }.orEmpty()}")

    companion object {
        private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

        fun parse(json: String): GameSourceLock = gson.fromJson(json, GameSourceLock::class.java).validate()
    }
}

/**
 * One immutable source repository pin and its ordered patch overlay.
 */
data class SourcePin(
    val sourceRepo: String,
    val sourceCommit: String,
    val patchSeries: List<String> = emptyList()
) {
    fun validate() {
        require(sourceRepo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) {
            "Invalid source repository: $sourceRepo"
        }
        require(sourceCommit.matches(Regex("[0-9a-fA-F]{40}"))) {
            "Invalid source commit for $sourceRepo: $sourceCommit"
        }
        patchSeries.forEach { patch ->
            require(patch.isNotBlank()) { "Patch series entries must not be blank" }
        }
    }
}
