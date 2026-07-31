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

import java.io.File
import java.util.Locale
import java.util.regex.Pattern

/**
 * JVM process contract for the Mindustry jar backend.
 */
data class MindustryLaunchOptions(
    val javaExecutable: File,
    val jar: File,
    val workingDirectory: File = jar.parentFile ?: File("."),
    val dataDir: File,
    val jvmArgs: List<String> = emptyList(),
    val gameArgs: List<String> = emptyList(),
    val minHeapMb: Int = 0,
    val maxHeapMb: Int = 1024,
    val rendererEnv: Map<String, String> = emptyMap()
) {
    fun buildCommandLine(): List<String> =
        listOf(javaExecutable.absolutePath) + buildJvmArgs()

    fun buildJvmArgs(): List<String> {
        val result = mutableListOf<String>()

        if (minHeapMb > 0) result += "-Xms${minHeapMb}m"
        if (maxHeapMb > 0) result += "-Xmx${maxHeapMb}m"

        result += "-Dfile.encoding=UTF-8"
        result += "-Dmindustry.data.dir=${dataDir.absolutePath}"
        result += sanitizeJvmArgs(jvmArgs)
        result += "-jar"
        result += jar.absolutePath
        result += gameArgs

        return result
    }

    fun buildEnvironment(base: Map<String, String> = emptyMap()): Map<String, String> =
        LinkedHashMap<String, String>().apply {
            putAll(base)
            put("MINDUSTRY_DATA_DIR", dataDir.absolutePath)
            putAll(rendererEnv)
        }

    companion object {
        private val token = Pattern.compile("\\S+")
        private val classPathOptions = setOf("-cp", "-classpath", "--class-path")

        fun tokenize(raw: String?): List<String> {
            if (raw.isNullOrBlank()) return emptyList()
            val matcher = token.matcher(raw)
            val out = mutableListOf<String>()
            while (matcher.find()) out += matcher.group()
            return out
        }

        fun sanitizeJvmArgs(args: List<String>): List<String> {
            val sanitized = mutableListOf<String>()
            var skipNext = false

            args.forEach { arg ->
                if (skipNext) {
                    skipNext = false
                    return@forEach
                }

                val lower = arg.lowercase(Locale.ROOT)
                if (lower in classPathOptions) {
                    skipNext = true
                    return@forEach
                }
                if (!isInheritedMinecraftArg(lower)) sanitized += arg
            }

            return sanitized
        }

        private fun isInheritedMinecraftArg(lowercaseArg: String): Boolean =
            lowercaseArg.startsWith("-dminecraft.") ||
                lowercaseArg.startsWith("-dauthlibinjector.") ||
                lowercaseArg.startsWith("-dnide8auth.") ||
                lowercaseArg.startsWith("-djava.awt.") ||
                lowercaseArg.startsWith("-dsun.awt.") ||
                lowercaseArg.startsWith("-dawt.") ||
                lowercaseArg.startsWith("--quickplay") ||
                lowercaseArg.contains("authlib-injector") ||
                lowercaseArg.contains("nide8auth") ||
                lowercaseArg.contains("cacio") ||
                lowercaseArg.contains("forge") ||
                lowercaseArg.contains("fabric")
    }
}
