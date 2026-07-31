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

import java.security.SecureRandom
import java.util.Base64

/**
 * Mindustry identity written into the selected instance before launch.
 */
data class UuidProfile(
    val id: String,
    val uuid: String,
    val name: String
) {
    init {
        require(id.isNotBlank()) { "Profile id must not be blank" }
        require(name.isNotBlank()) { "Profile name must not be blank" }
        require(isValidUuid(uuid)) { "Invalid Mindustry UUID: $uuid" }
    }

    companion object {
        private val uuidPattern = Regex("[A-Za-z0-9+/]{11}=")

        fun create(id: String, name: String, random: SecureRandom = SecureRandom()): UuidProfile {
            val bytes = ByteArray(MINDUSTRY_UUID_BYTES)
            random.nextBytes(bytes)
            return fromBytes(id = id, name = name, uuidBytes = bytes)
        }

        fun fromBytes(id: String, name: String, uuidBytes: ByteArray): UuidProfile {
            require(uuidBytes.size == MINDUSTRY_UUID_BYTES) {
                "Mindustry UUID must be $MINDUSTRY_UUID_BYTES bytes"
            }
            return UuidProfile(
                id = id,
                uuid = Base64.getEncoder().encodeToString(uuidBytes),
                name = name
            )
        }

        fun isValidUuid(value: String): Boolean =
            uuidPattern.matches(value) &&
                runCatching { Base64.getDecoder().decode(value).size == MINDUSTRY_UUID_BYTES }
                    .getOrDefault(false)

        private const val MINDUSTRY_UUID_BYTES = 8
    }
}
