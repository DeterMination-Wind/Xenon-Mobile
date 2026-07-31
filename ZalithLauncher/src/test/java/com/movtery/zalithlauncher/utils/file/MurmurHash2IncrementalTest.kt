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

package com.movtery.zalithlauncher.utils.file

import org.apache.commons.codec.digest.MurmurHash2
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempFile

class MurmurHash2IncrementalTest {

    @Test
    fun testTwoWay() {
        val file = createTempFile("zalith-murmur-", ".bin").toFile()
        try {
            file.writeBytes(
                byteArrayOf(
                    0x09, 0x10, 0x20, 0x33, 0x0a, 0x44, 0x55, 0x0d,
                    0x66, 0x77, 0x7f, 0x00, 0x01, 0x02, 0x03
                )
            )
            val hash1 = way1(file)
            val hash2 = way2(file)
            assertEquals(hash1, hash2)
        } finally {
            file.delete()
        }
    }

    //Old
    private fun way1(file: File): Long {
        val baos = ByteArrayOutputStream()
        Files.newInputStream(file.toPath()).use { stream ->
            val buf = ByteArray(1024)
            var bytesRead: Int
            while (stream.read(buf).also { bytesRead = it } != -1) {
                for (i in 0 until bytesRead) {
                    val b = buf[i]
                    if (b.toInt() !in listOf(0x9, 0xa, 0xd, 0x20)) {
                        baos.write(b.toInt())
                    }
                }
            }
        }
        return Integer.toUnsignedLong(MurmurHash2.hash32(baos.toByteArray(), baos.size(), 1))
    }

    private fun way2(file: File): Long {
        return MurmurHash2Incremental.computeHash(file, byteToSkip = listOf(0x9, 0xa, 0xd, 0x20))
    }
}
