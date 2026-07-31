/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.game.mindustry

/**
 * Fixed source-built clone APK slot contract.
 */
data class ApkCloneSlot(
    val variant: MindustryVariant,
    val slot: Int
) {
    init {
        require(isValidSlot(variant, slot)) {
            "Invalid APK slot $slot for ${variant.displayName}"
        }
    }

    val packageName: String =
        "com.xenon.mobile.clone.${variant.catalogId}.slot$slot"

    val taskAffinity: String =
        "$packageName.task"

    companion object {
        fun slotCount(variant: MindustryVariant): Int =
            when (variant) {
                MindustryVariant.VANILLA -> 5
                MindustryVariant.MINDUSTRY_X -> 5
                MindustryVariant.BE -> 1
            }

        fun isValidSlot(variant: MindustryVariant, slot: Int): Boolean =
            slot in 1..slotCount(variant)

        fun all(): List<ApkCloneSlot> =
            MindustryVariant.entries.flatMap { variant ->
                (1..slotCount(variant)).map { slot -> ApkCloneSlot(variant, slot) }
            }
    }
}
