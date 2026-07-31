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

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.InflaterInputStream

/**
 * Minimal Arc/Mindustry settings.bin reader used for launcher-owned identity updates.
 */
class MindustrySettingsBin private constructor(
    private val values: LinkedHashMap<String, SettingValue>
) {
    val entries: Map<String, SettingValue>
        get() = values.toMap()

    fun getString(key: String): String? =
        (values[key] as? SettingValue.StringValue)?.value

    fun putString(key: String, value: String) {
        values[key] = SettingValue.StringValue(value)
    }

    fun write(file: File) {
        val parent = file.parentFile
        parent?.mkdirs()

        val temp = File.createTempFile("${file.name}.", ".tmp", parent)
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(temp))).use(::writeTo)
            moveReplacing(temp, file)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    fun writeTo(stream: DataOutputStream) {
        stream.writeInt(values.size)
        values.forEach { (key, value) ->
            stream.writeUTF(key)
            when (value) {
                is SettingValue.BoolValue -> {
                    stream.writeByte(TYPE_BOOL)
                    stream.writeBoolean(value.value)
                }
                is SettingValue.IntValue -> {
                    stream.writeByte(TYPE_INT)
                    stream.writeInt(value.value)
                }
                is SettingValue.LongValue -> {
                    stream.writeByte(TYPE_LONG)
                    stream.writeLong(value.value)
                }
                is SettingValue.FloatValue -> {
                    stream.writeByte(TYPE_FLOAT)
                    stream.writeFloat(value.value)
                }
                is SettingValue.StringValue -> {
                    stream.writeByte(TYPE_STRING)
                    stream.writeUTF(value.value)
                }
                is SettingValue.BinaryValue -> {
                    stream.writeByte(TYPE_BINARY)
                    stream.writeInt(value.value.size)
                    stream.write(value.value)
                }
            }
        }
    }

    sealed class SettingValue {
        data class BoolValue(val value: Boolean) : SettingValue()
        data class IntValue(val value: Int) : SettingValue()
        data class LongValue(val value: Long) : SettingValue()
        data class FloatValue(val value: Float) : SettingValue()
        data class StringValue(val value: String) : SettingValue()
        data class BinaryValue(val value: ByteArray) : SettingValue() {
            override fun equals(other: Any?): Boolean =
                this === other || other is BinaryValue && value.contentEquals(other.value)

            override fun hashCode(): Int = value.contentHashCode()
        }
    }

    companion object {
        const val SETTINGS_FILE_NAME = "settings.bin"

        private const val TYPE_BOOL = 0
        private const val TYPE_INT = 1
        private const val TYPE_LONG = 2
        private const val TYPE_FLOAT = 3
        private const val TYPE_STRING = 4
        private const val TYPE_BINARY = 5

        fun empty(): MindustrySettingsBin =
            MindustrySettingsBin(LinkedHashMap())

        fun of(entries: Iterable<Pair<String, SettingValue>>): MindustrySettingsBin =
            MindustrySettingsBin(LinkedHashMap<String, SettingValue>().apply {
                entries.forEach { (key, value) -> put(key, value) }
            })

        fun read(file: File): MindustrySettingsBin {
            if (!file.exists() || file.length() == 0L) return empty()

            val input = BufferedInputStream(FileInputStream(file))
            input.mark(2)
            val first = input.read()
            val second = input.read()
            input.reset()

            val stream = if (isCompressed(first, second)) InflaterInputStream(input) else input
            return DataInputStream(stream).use(::readFrom)
        }

        fun readProfile(settingsFile: File, id: String = profileIdFrom(settingsFile)): UuidProfile? {
            val settings = read(settingsFile)
            val uuid = settings.getString("uuid") ?: return null
            val name = settings.getString("name") ?: return null
            return runCatching { UuidProfile(id = id, uuid = uuid, name = name) }.getOrNull()
        }

        fun writeProfile(dataDir: File, profile: UuidProfile) {
            dataDir.mkdirs()
            val settingsFile = File(dataDir, SETTINGS_FILE_NAME)
            val settings = read(settingsFile)
            settings.putString("uuid", profile.uuid)
            settings.putString("name", profile.name)
            settings.write(settingsFile)
        }

        private fun readFrom(stream: DataInputStream): MindustrySettingsBin {
            val amount = stream.readInt()
            if (amount <= 0) throw IOException("0 values are not allowed.")

            val values = LinkedHashMap<String, SettingValue>(amount)
            repeat(amount) {
                val key = stream.readUTF()
                val value = when (val type = stream.readByte().toInt()) {
                    TYPE_BOOL -> SettingValue.BoolValue(stream.readBoolean())
                    TYPE_INT -> SettingValue.IntValue(stream.readInt())
                    TYPE_LONG -> SettingValue.LongValue(stream.readLong())
                    TYPE_FLOAT -> SettingValue.FloatValue(stream.readFloat())
                    TYPE_STRING -> SettingValue.StringValue(stream.readUTF())
                    TYPE_BINARY -> {
                        val length = stream.readInt()
                        if (length < 0) throw IOException("Negative binary length for $key: $length")
                        val bytes = ByteArray(length)
                        stream.readFully(bytes)
                        SettingValue.BinaryValue(bytes)
                    }
                    else -> throw IOException("Unknown key type: $type")
                }
                values[key] = value
            }
            return MindustrySettingsBin(values)
        }

        private fun isCompressed(first: Int, second: Int): Boolean =
            first == 0x78 && (second == 0x01 || second == 0x5E || second == 0x9c || second == 0xda)

        private fun moveReplacing(source: File, target: File) {
            try {
                Files.move(
                    source.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }

        private fun profileIdFrom(settingsFile: File): String =
            settingsFile.parentFile?.name?.takeIf { it.isNotBlank() } ?: "settings"
    }
}
