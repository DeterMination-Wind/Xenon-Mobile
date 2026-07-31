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

import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.game.mindustry.ApkCloneSlot
import com.movtery.zalithlauncher.game.mindustry.MindustryCatalog
import com.movtery.zalithlauncher.game.mindustry.MindustryBridgeClient
import com.movtery.zalithlauncher.game.mindustry.MindustryBackend
import com.movtery.zalithlauncher.game.mindustry.MindustryInstanceStore
import com.movtery.zalithlauncher.game.mindustry.MindustryProfileManager
import com.movtery.zalithlauncher.game.mindustry.MindustryPaths
import com.movtery.zalithlauncher.game.mindustry.MindustryRuntimeCoordinator
import com.movtery.zalithlauncher.game.mindustry.MindustryServerListRepository
import com.movtery.zalithlauncher.game.mindustry.MindustryServerRow
import com.movtery.zalithlauncher.game.mindustry.MindustryVariant
import com.movtery.zalithlauncher.game.mindustry.UuidProfile
import com.movtery.zalithlauncher.path.PathManager
import com.movtery.zalithlauncher.ui.base.BaseScreen
import com.movtery.zalithlauncher.ui.screens.NormalNavKey
import com.movtery.zalithlauncher.utils.animation.swapAnimateDpAsState
import com.movtery.zalithlauncher.viewmodel.EventViewModel
import com.movtery.zalithlauncher.viewmodel.ScreenBackStackViewModel
import kotlinx.coroutines.launch

private data class HubTab(
    @StringRes val title: Int,
    @DrawableRes val icon: Int
)

private val hubTabs = listOf(
    HubTab(R.string.mindustry_tab_instances, R.drawable.ic_dashboard_filled),
    HubTab(R.string.mindustry_tab_servers, R.drawable.ic_dns_outlined),
    HubTab(R.string.mindustry_tab_identity, R.drawable.ic_person_outlined),
    HubTab(R.string.mindustry_tab_data, R.drawable.ic_folder_outlined),
    HubTab(R.string.mindustry_tab_download, R.drawable.ic_download_2_filled)
)

private sealed interface ServerLoadState {
    data object Loading : ServerLoadState
    data class Loaded(val rows: List<MindustryServerRow>, val stale: Boolean) : ServerLoadState
    data class Failed(val message: String) : ServerLoadState
}

@Composable
fun MindustryHubScreen(
    backStackViewModel: ScreenBackStackViewModel,
    eventViewModel: EventViewModel
) {
    BaseScreen(
        screenKey = NormalNavKey.MindustryHub,
        currentKey = backStackViewModel.mainScreen.currentKey
    ) { isVisible ->
        val yOffset by swapAnimateDpAsState(
            targetValue = (-40).dp,
            swapIn = isVisible
        )

        var selectedTab by rememberSaveable { mutableIntStateOf(0) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
                .offset { IntOffset(x = 0, y = yOffset.roundToPx()) }
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                hubTabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = {
                            Icon(
                                painter = painterResource(tab.icon),
                                contentDescription = stringResource(tab.title)
                            )
                        },
                        text = { Text(text = stringResource(tab.title), maxLines = 1) }
                    )
                }
            }

            when (selectedTab) {
                0 -> InstancesTab()
                1 -> ServersTab()
                2 -> IdentityTab()
                3 -> DataTab()
                else -> DownloadTab(eventViewModel)
            }
        }
    }
}

@Composable
private fun InstancesTab() {
    val context = LocalContext.current
    var refreshKey by rememberSaveable { mutableIntStateOf(0) }
    val targets = remember(refreshKey) { MindustryBridgeClient.installedTargets(context) }
    val instances = remember(refreshKey) {
        MindustryInstanceStore.list(MindustryPaths(PathManager.DIR_MINDUSTRY))
    }
    HubList {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(onClick = { refreshKey++ }) {
                    Text(text = stringResource(R.string.generic_refresh))
                }
            }
        }
        if (targets.isEmpty() && instances.isEmpty()) item {
            HubRow(
                title = stringResource(R.string.mindustry_home_no_instance),
                subtitle = stringResource(R.string.mindustry_home_open_hub),
                chip = stringResource(R.string.mindustry_status_not_installed)
            )
        }
        items(targets, key = { "${it.packageName}:${it.instanceId ?: it.slot}" }) { target ->
            HubRow(
                title = target.label,
                subtitle = target.packageName,
                chip = stringResource(R.string.mindustry_status_ready)
            ) {
                Button(
                    onClick = {
                        MindustryBridgeClient.sendLaunch(context, target)
                    },
                    enabled = target.backend == MindustryBackend.JAR || !MindustryRuntimeCoordinator.isBlocked()
                ) {
                    Text(text = stringResource(R.string.mindustry_action_launch))
                }
            }
        }
        // Keep metadata rows distinct from the bridge-discovered APK targets.
        items(instances.filter { instance -> targets.none { it.instanceId == instance.id } }, key = { it.id }) { instance ->
            HubRow(
                title = instance.name,
                subtitle = "${instance.variant.displayName} / ${instance.buildType} / build ${instance.build}",
                chip = stringResource(R.string.mindustry_status_ready)
            ) {
                Button(onClick = {
                    MindustryBridgeClient.sendLaunch(
                        context,
                        com.movtery.zalithlauncher.game.mindustry.MindustryBridgeTarget(
                            packageName = context.packageName,
                            variant = instance.variant,
                            backend = MindustryBackend.JAR,
                            instanceId = instance.id
                        )
                    )
                }) { Text(text = stringResource(R.string.mindustry_action_launch)) }
            }
        }
    }
}

@Composable
private fun ServersTab() {
    val context = LocalContext.current
    var selectedVariantIndex by rememberSaveable { mutableIntStateOf(0) }
    var refreshKey by rememberSaveable { mutableIntStateOf(0) }
    var joinEntry by remember { mutableStateOf<MindustryServerRow?>(null) }
    val variant = MindustryVariant.entries[selectedVariantIndex]
    val loadState by produceState<ServerLoadState>(
        initialValue = ServerLoadState.Loading,
        key1 = variant,
        key2 = refreshKey
    ) {
        value = ServerLoadState.Loading
        value = runCatching {
            val loaded = MindustryServerListRepository().load(variant, forceRefresh = refreshKey > 0)
            ServerLoadState.Loaded(
                rows = com.movtery.zalithlauncher.game.mindustry.MindustryServerProbe.probe(loaded.entries),
                stale = loaded.stale
            )
        }.getOrElse { ServerLoadState.Failed(it.message ?: it::class.java.simpleName) }
    }

    HubList {
        item {
            TabRow(selectedTabIndex = selectedVariantIndex) {
                MindustryVariant.entries.forEachIndexed { index, item ->
                    Tab(
                        selected = selectedVariantIndex == index,
                        onClick = { selectedVariantIndex = index },
                        text = { Text(text = item.displayName, maxLines = 1) }
                    )
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(onClick = { refreshKey++ }) {
                    Text(text = stringResource(R.string.generic_refresh))
                }
            }
        }
        when (val state = loadState) {
            ServerLoadState.Loading -> item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) { CircularProgressIndicator() }
            }
            is ServerLoadState.Failed -> item {
                HubRow(
                    title = stringResource(R.string.mindustry_catalog_failed),
                    subtitle = stringResource(R.string.mindustry_server_refresh_failed, state.message),
                    chip = stringResource(R.string.mindustry_server_offline)
                )
            }
            is ServerLoadState.Loaded -> {
                items(
                    items = state.rows,
                    key = { "${it.entry.sourceIndex}:${it.entry.address}" }
                ) { row ->
                    val status = row.status
                    val subtitle = if (status.online) {
                        stringResource(
                            R.string.mindustry_server_online,
                            status.pingMs ?: 0L,
                            status.players ?: 0,
                            status.playerLimit ?: 0
                        ) + listOfNotNull(status.map, status.version?.toString()).joinToString(" · ", prefix = " · ")
                    } else {
                        stringResource(R.string.mindustry_server_offline)
                    }
                    HubRow(
                        title = row.entry.name,
                        subtitle = "${row.entry.address}\n$subtitle",
                        chip = if (row.entry.prioritized) "★" else variant.catalogId
                    ) {
                        Button(onClick = { joinEntry = row }) {
                            Text(text = stringResource(R.string.mindustry_action_join))
                        }
                    }
                }
                if (state.stale) item {
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = stringResource(R.string.mindustry_server_offline),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }

    joinEntry?.let { row ->
        val targets = remember(row, variant) {
            MindustryBridgeClient.installedTargets(context).filter { it.variant == variant }
        }
        AlertDialog(
            onDismissRequest = { joinEntry = null },
            title = { Text(text = stringResource(R.string.mindustry_server_target_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (targets.isEmpty()) {
                        Text(text = stringResource(R.string.mindustry_server_no_targets))
                    } else {
                        targets.forEach { target ->
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    MindustryBridgeClient.sendJoin(
                                        context = context,
                                        target = target,
                                        host = row.entry.endpoint.host,
                                        port = row.entry.endpoint.port
                                    )
                                    joinEntry = null
                                }
                            ) { Text(text = target.label) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { joinEntry = null }) {
                    Text(text = stringResource(R.string.generic_cancel))
                }
            }
        )
    }
}

@Composable
private fun IdentityTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profiles by MindustryProfileManager.profiles.collectAsState()
    var currentId by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<UuidProfile?>(null) }
    var creating by remember { mutableStateOf(false) }
    var editorName by remember { mutableStateOf("") }
    var bindingTarget by remember { mutableStateOf<com.movtery.zalithlauncher.game.mindustry.MindustryBridgeTarget?>(null) }
    var bindings by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val targets = remember { MindustryBridgeClient.installedTargets(context) }

    LaunchedEffect(Unit) {
        runCatching { MindustryProfileManager.reload() }
        currentId = runCatching { MindustryProfileManager.resolve(MindustryProfileManager.GLOBAL_SCOPE)?.id }.getOrNull()
    }

    LaunchedEffect(targets, profiles) {
        bindings = targets.associate { target ->
            val scopeKey = if (target.slot != null) {
                MindustryProfileManager.slotScope(target.variant, target.slot)
            } else {
                MindustryProfileManager.variantScope(target.variant)
            }
            targetKey(target) to (MindustryProfileManager.resolve(scopeKey)?.id.orEmpty())
        }
    }

    HubList {
        item {
            HubRow(
                title = stringResource(R.string.mindustry_profile_default),
                subtitle = stringResource(R.string.mindustry_profile_bridge),
                chip = profiles.size.toString()
            ) {
                Button(onClick = {
                    creating = true
                    editing = null
                    editorName = ""
                }) {
                    Text(text = stringResource(R.string.mindustry_profile_create))
                }
            }
        }
        if (profiles.isEmpty()) item {
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(R.string.mindustry_profile_empty),
                style = MaterialTheme.typography.bodySmall
            )
        }
        items(profiles, key = { it.id }) { profile ->
            HubRow(
                title = profile.name,
                subtitle = stringResource(R.string.mindustry_profile_uuid, profile.uuid),
                chip = if (profile.id == currentId) stringResource(R.string.mindustry_profile_current)
                else stringResource(R.string.mindustry_profile_set_current)
            ) {
                TextButton(onClick = {
                    scope.launch {
                        MindustryProfileManager.bind(MindustryProfileManager.GLOBAL_SCOPE, profile.id)
                        currentId = profile.id
                    }
                }) { Text(text = stringResource(R.string.mindustry_profile_set_current)) }
                TextButton(onClick = {
                    editing = UuidProfile(profile.id, profile.uuid, profile.name)
                    creating = false
                    editorName = profile.name
                }) { Text(text = stringResource(R.string.generic_edit)) }
                TextButton(onClick = { scope.launch { MindustryProfileManager.delete(profile.id) } }) {
                    Text(text = stringResource(R.string.generic_delete))
                }
            }
        }
        if (profiles.isNotEmpty() && targets.isNotEmpty()) item {
            HubRow(
                title = stringResource(R.string.mindustry_tab_instances),
                subtitle = targets.joinToString { it.label },
                chip = stringResource(R.string.mindustry_profile_bridge)
            ) {
                TextButton(
                    enabled = currentId != null,
                    onClick = {
                        currentId?.let { profileId ->
                            scope.launch {
                                targets.forEach { target ->
                                    val scopeKey = if (target.slot != null) {
                                        MindustryProfileManager.slotScope(target.variant, target.slot)
                                    } else MindustryProfileManager.variantScope(target.variant)
                                    MindustryProfileManager.bind(scopeKey, profileId)
                                }
                            }
                        }
                    }
                ) { Text(text = stringResource(R.string.mindustry_profile_set_current)) }
            }
        }
        items(targets, key = { "profile:${targetKey(it)}" }) { target ->
            val profile = profiles.firstOrNull { it.id == bindings[targetKey(target)] }
            HubRow(
                title = target.label,
                subtitle = profile?.name
                    ?: stringResource(R.string.mindustry_profile_default),
                chip = stringResource(R.string.mindustry_profile_bridge)
            ) {
                OutlinedButton(onClick = { bindingTarget = target }) {
                    Text(text = stringResource(R.string.mindustry_profile_choose))
                }
            }
        }
    }

    if (creating || editing != null) {
        AlertDialog(
            onDismissRequest = { creating = false; editing = null },
            title = { Text(text = stringResource(if (creating) R.string.mindustry_profile_create else R.string.generic_edit)) },
            text = {
                TextField(
                    value = editorName,
                    onValueChange = { editorName = it },
                    label = { Text(text = stringResource(R.string.mindustry_profile_name_hint)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = editorName.isNotBlank(),
                    onClick = {
                        val old = editing
                        scope.launch {
                            if (old == null) {
                                val profile = MindustryProfileManager.create(editorName.trim())
                                MindustryProfileManager.bind(MindustryProfileManager.GLOBAL_SCOPE, profile.id)
                                currentId = profile.id
                            } else {
                                MindustryProfileManager.edit(old.id, editorName.trim())
                            }
                            creating = false
                            editing = null
                        }
                    }
                ) { Text(text = stringResource(R.string.generic_save)) }
            },
            dismissButton = {
                TextButton(onClick = { creating = false; editing = null }) {
                    Text(text = stringResource(R.string.generic_cancel))
                }
            }
        )
    }

    bindingTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { bindingTarget = null },
            title = { Text(text = target.label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    profiles.forEach { profile ->
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                val scopeKey = if (target.slot != null) {
                                    MindustryProfileManager.slotScope(target.variant, target.slot)
                                } else {
                                    MindustryProfileManager.variantScope(target.variant)
                                }
                                scope.launch {
                                    MindustryProfileManager.bind(scopeKey, profile.id)
                                    bindings = bindings + (targetKey(target) to profile.id)
                                    bindingTarget = null
                                }
                            }
                        ) { Text(text = profile.name) }
                    }
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val scopeKey = if (target.slot != null) {
                                MindustryProfileManager.slotScope(target.variant, target.slot)
                            } else {
                                MindustryProfileManager.variantScope(target.variant)
                            }
                            scope.launch {
                                MindustryProfileManager.clearBinding(scopeKey)
                                bindings = bindings - targetKey(target)
                                bindingTarget = null
                            }
                        }
                    ) { Text(text = stringResource(R.string.mindustry_profile_use_global)) }
                }
            },
            confirmButton = {
                TextButton(onClick = { bindingTarget = null }) {
                    Text(text = stringResource(R.string.generic_cancel))
                }
            }
        )
    }
}

@Composable
private fun DataTab() {
    val context = LocalContext.current
    val targets = remember { MindustryBridgeClient.installedTargets(context) }
    var selectedTargetKey by rememberSaveable {
        mutableStateOf(targets.firstOrNull()?.let { targetKey(it) })
    }
    val selectedTarget = targets.firstOrNull { targetKey(it) == selectedTargetKey }
        ?: targets.firstOrNull()
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedTarget?.let { target ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                MindustryBridgeClient.sendImportBackup(context, target, uri)
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) selectedTarget?.let { target ->
            MindustryBridgeClient.sendExportBackup(context, target, uri)
        }
    }
    val root = runCatching { PathManager.DIR_MINDUSTRY.absolutePath }
        .getOrDefault("Android/data/com.xenon.mobile/files/mindustry")

    HubList {
        item {
            HubRow(
                title = stringResource(R.string.mindustry_data_root),
                subtitle = selectedTarget?.label ?: root,
                chip = stringResource(R.string.mindustry_data_isolated)
            ) {
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                    enabled = selectedTarget != null
                ) {
                    Text(text = stringResource(R.string.generic_import))
                }
                OutlinedButton(
                    onClick = { exportLauncher.launch("mindustry-backup.zip") },
                    enabled = selectedTarget != null
                ) {
                    Text(text = stringResource(R.string.generic_share))
                }
            }
        }
        if (targets.isNotEmpty()) item {
            HubRow(
                title = stringResource(R.string.mindustry_data_target),
                subtitle = targets.joinToString { it.label },
                chip = selectedTarget?.label ?: stringResource(R.string.mindustry_server_no_targets)
            ) {
                targets.forEach { target ->
                    TextButton(onClick = { selectedTargetKey = targetKey(target) }) {
                        Text(text = target.label)
                    }
                }
            }
        }
        item {
            HubRow(
                title = stringResource(R.string.mindustry_data_sync_set),
                subtitle = "mods, saves, schematics, maps",
                chip = stringResource(R.string.mindustry_data_global)
            )
        }
    }
}

private fun targetKey(target: com.movtery.zalithlauncher.game.mindustry.MindustryBridgeTarget): String =
    "${target.packageName}:${target.variant.catalogId}:${target.backend}:${target.slot}:${target.instanceId}"

@Composable
private fun DownloadTab(eventViewModel: EventViewModel) {
    var refreshKey by rememberSaveable { mutableIntStateOf(0) }
    val catalogState by rememberMindustryCatalogState(refreshKey)

    HubList {
        mindustryCatalogItems(
            state = catalogState,
            eventViewModel = eventViewModel,
            onRefresh = { refreshKey++ },
            showCloneSlots = true
        )
    }
}

@Composable
private fun HubList(
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

@Composable
private fun HubRow(
    title: String,
    subtitle: String,
    chip: String,
    actions: @Composable () -> Unit = {}
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            AssistChip(
                onClick = {},
                label = { Text(text = chip, maxLines = 1) }
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                actions()
            }
        }
    }
}
