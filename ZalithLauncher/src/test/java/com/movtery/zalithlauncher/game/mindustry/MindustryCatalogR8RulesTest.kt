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

import junit.framework.TestCase.assertTrue
import org.junit.Test
import java.io.File

/**
 * The catalog is parsed with Gson reflection, so the release build must keep the model untouched.
 *
 * Without the keep rules R8 rewrites the model into an abstract shell and drops the no-arguments
 * constructor, and every device fails with
 * "Abstract classes can't be instantiated! ... Class name: mq5" while loading the catalog.
 */
class MindustryCatalogR8RulesTest {
    private val models = listOf(
        "MindustryCatalogManifest",
        "MindustryArtifact",
        "CatalogMirror",
        "MindustryVariant",
        "MindustryBackend"
    )

    @Test
    fun releaseRulesKeepEveryGsonCatalogModel() {
        val rules = File("proguard-rules.pro")
        assertTrue("proguard-rules.pro is missing", rules.isFile)
        val text = rules.readText()

        models.forEach { model ->
            val kept = text.contains("-keep class com.movtery.zalithlauncher.game.mindustry.$model { *; }") ||
                    text.contains("-keep enum com.movtery.zalithlauncher.game.mindustry.$model { *; }")
            assertTrue("proguard-rules.pro must keep the Gson model $model", kept)
        }
        assertTrue(
            "the generic catalog lists need the Signature attribute",
            text.contains("-keepattributes Signature")
        )
    }
}
