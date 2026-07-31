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

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
import org.junit.Test
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.DeflaterOutputStream

class MindustrySettingsBinTest {
    @Test
    fun writeProfileCreatesSettingsBinWithNameAndUuid() {
        val dataDir = freshDir("profile-new")
        val profile = UuidProfile.fromBytes(
            id = "default",
            name = "Xenon",
            uuidBytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        )

        MindustrySettingsBin.writeProfile(dataDir, profile)

        val read = MindustrySettingsBin.readProfile(settingsFile(dataDir), id = "default")
        assertEquals(profile, read)
    }

    @Test
    fun writeProfilePreservesExistingSettingsAndReplacesIdentity() {
        val dataDir = freshDir("profile-preserve")
        MindustrySettingsBin.of(
            listOf(
                "fullscreen" to MindustrySettingsBin.SettingValue.BoolValue(true),
                "launches" to MindustrySettingsBin.SettingValue.IntValue(42),
                "lastBuild" to MindustrySettingsBin.SettingValue.LongValue(146L),
                "uiscale" to MindustrySettingsBin.SettingValue.FloatValue(1.25f),
                "locale" to MindustrySettingsBin.SettingValue.StringValue("zh_CN"),
                "payload" to MindustrySettingsBin.SettingValue.BinaryValue(byteArrayOf(9, 8, 7)),
                "uuid" to MindustrySettingsBin.SettingValue.StringValue("AAAAAAAAAAA="),
                "name" to MindustrySettingsBin.SettingValue.StringValue("OldName")
            )
        ).write(settingsFile(dataDir))

        val profile = UuidProfile.fromBytes(
            id = "default",
            name = "NewName",
            uuidBytes = byteArrayOf(8, 7, 6, 5, 4, 3, 2, 1)
        )
        MindustrySettingsBin.writeProfile(dataDir, profile)

        val settings = MindustrySettingsBin.read(settingsFile(dataDir))
        assertEquals(8, settings.entries.size)
        assertEquals(MindustrySettingsBin.SettingValue.BoolValue(true), settings.entries["fullscreen"])
        assertEquals(MindustrySettingsBin.SettingValue.IntValue(42), settings.entries["launches"])
        assertEquals(MindustrySettingsBin.SettingValue.LongValue(146L), settings.entries["lastBuild"])
        assertEquals(MindustrySettingsBin.SettingValue.FloatValue(1.25f), settings.entries["uiscale"])
        assertEquals(MindustrySettingsBin.SettingValue.StringValue("zh_CN"), settings.entries["locale"])
        assertEquals(
            MindustrySettingsBin.SettingValue.BinaryValue(byteArrayOf(9, 8, 7)),
            settings.entries["payload"]
        )
        assertEquals(profile.uuid, settings.getString("uuid"))
        assertEquals(profile.name, settings.getString("name"))
    }

    @Test
    fun compressedSettingsFileCanBeReadThenWrittenBack() {
        val dataDir = freshDir("profile-compressed")
        val file = settingsFile(dataDir)
        val original = MindustrySettingsBin.of(
            listOf(
                "name" to MindustrySettingsBin.SettingValue.StringValue("CompressedName"),
                "uuid" to MindustrySettingsBin.SettingValue.StringValue("AAAAAAAAAAA="),
                "mods-enabled" to MindustrySettingsBin.SettingValue.BoolValue(false)
            )
        )
        writeCompressed(file, original)

        assertEquals("CompressedName", MindustrySettingsBin.read(file).getString("name"))

        val profile = UuidProfile.fromBytes(
            id = "default",
            name = "UncompressedName",
            uuidBytes = byteArrayOf(1, 1, 2, 3, 5, 8, 13, 21)
        )
        MindustrySettingsBin.writeProfile(dataDir, profile)

        val updatedProfile = MindustrySettingsBin.readProfile(file, id = "default")
        assertEquals(profile, updatedProfile)
        assertEquals(
            MindustrySettingsBin.SettingValue.BoolValue(false),
            MindustrySettingsBin.read(file).entries["mods-enabled"]
        )
    }

    @Test
    fun readProfileReturnsNullWhenIdentityIsMissing() {
        val dataDir = freshDir("profile-missing")
        MindustrySettingsBin.of(
            listOf("locale" to MindustrySettingsBin.SettingValue.StringValue("en"))
        ).write(settingsFile(dataDir))

        assertEquals(null, MindustrySettingsBin.readProfile(settingsFile(dataDir)))
    }

    @Test
    fun readProfileReturnsNullWhenUuidIsInvalid() {
        val dataDir = freshDir("profile-invalid")
        MindustrySettingsBin.of(
            listOf(
                "name" to MindustrySettingsBin.SettingValue.StringValue("JavaUuidUser"),
                "uuid" to MindustrySettingsBin.SettingValue.StringValue("550e8400-e29b-41d4-a716-446655440000")
            )
        ).write(settingsFile(dataDir))

        assertEquals(null, MindustrySettingsBin.readProfile(settingsFile(dataDir)))
    }

    private fun freshDir(name: String): File =
        File("build/test-mindustry/settings-bin/$name").apply {
            deleteRecursively()
            mkdirs()
        }

    private fun settingsFile(dataDir: File): File =
        File(dataDir, MindustrySettingsBin.SETTINGS_FILE_NAME)

    private fun writeCompressed(file: File, settings: MindustrySettingsBin) {
        file.parentFile?.mkdirs()
        DataOutputStream(
            DeflaterOutputStream(
                BufferedOutputStream(FileOutputStream(file))
            )
        ).use(settings::writeTo)
        assertNotNull(file.takeIf { it.exists() })
    }
}
