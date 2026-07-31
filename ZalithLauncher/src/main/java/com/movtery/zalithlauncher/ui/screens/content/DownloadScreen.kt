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

package com.movtery.zalithlauncher.ui.screens.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.movtery.zalithlauncher.ui.base.BaseScreen
import com.movtery.zalithlauncher.ui.screens.NestedNavKey
import com.movtery.zalithlauncher.ui.screens.NormalNavKey
import com.movtery.zalithlauncher.ui.screens.TitledNavKey
import com.movtery.zalithlauncher.utils.animation.swapAnimateDpAsState
import com.movtery.zalithlauncher.viewmodel.ErrorViewModel
import com.movtery.zalithlauncher.viewmodel.EventViewModel
import com.movtery.zalithlauncher.viewmodel.ModpackImportViewModel
import com.movtery.zalithlauncher.viewmodel.ScreenBackStackViewModel

/**
 * Navigate to the Xenon Mobile Mindustry download surface.
 */
fun ScreenBackStackViewModel.navigateToDownload(targetScreen: TitledNavKey? = null) {
    downloadScreen.clearWith(targetScreen ?: NormalNavKey.MindustryDownloads)
    mainScreen.removeAndNavigateTo(
        removes = clearBeforeNavKeys,
        screenKey = downloadScreen,
        useClassEquality = true
    )
}

@Composable
fun DownloadScreen(
    key: NestedNavKey.Download,
    backScreenViewModel: ScreenBackStackViewModel,
    modpackImportViewModel: ModpackImportViewModel,
    eventViewModel: EventViewModel,
    submitError: (ErrorViewModel.ThrowableMessage) -> Unit
) {
    LaunchedEffect(key) {
        backScreenViewModel.downloadScreen.currentKey = NormalNavKey.MindustryDownloads
    }

    BaseScreen(
        screenKey = key,
        currentKey = backScreenViewModel.mainScreen.currentKey,
        useClassEquality = true
    ) { isVisible: Boolean ->
        val yOffset by swapAnimateDpAsState(
            targetValue = (-40).dp,
            swapIn = isVisible
        )
        var refreshKey by rememberSaveable { mutableIntStateOf(0) }
        val catalogState by rememberMindustryCatalogState(refreshKey)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
                .offset { IntOffset(x = 0, y = yOffset.roundToPx()) },
            contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            mindustryCatalogItems(
                state = catalogState,
                eventViewModel = eventViewModel,
                onRefresh = { refreshKey++ }
            )
        }
    }
}
