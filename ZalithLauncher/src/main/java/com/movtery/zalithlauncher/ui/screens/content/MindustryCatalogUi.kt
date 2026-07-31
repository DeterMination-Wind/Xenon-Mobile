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

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.movtery.zalithlauncher.BuildKeys
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.game.mindustry.ApkCloneSlot
import com.movtery.zalithlauncher.game.mindustry.MindustryArtifact
import com.movtery.zalithlauncher.game.mindustry.MindustryArtifactStore
import com.movtery.zalithlauncher.game.mindustry.MindustryApkInstaller
import com.movtery.zalithlauncher.game.mindustry.MindustryInstallFailure
import com.movtery.zalithlauncher.game.mindustry.MindustryInstallResult
import com.movtery.zalithlauncher.game.mindustry.MindustryInstanceStore
import com.movtery.zalithlauncher.game.mindustry.MindustryPaths
import com.movtery.zalithlauncher.game.mindustry.MindustryRuntimeCoordinator
import com.movtery.zalithlauncher.game.mindustry.MindustryBackend
import com.movtery.zalithlauncher.game.mindustry.MindustryCatalog
import com.movtery.zalithlauncher.game.mindustry.MindustryCatalogLoadResult
import com.movtery.zalithlauncher.game.mindustry.MindustryCatalogManifest
import com.movtery.zalithlauncher.game.mindustry.MindustryCatalogRepository
import com.movtery.zalithlauncher.game.mindustry.MindustryVariant
import com.movtery.zalithlauncher.path.PathManager
import com.movtery.zalithlauncher.ui.activities.createMindustryJarIntent
import com.movtery.zalithlauncher.utils.file.formatFileSize
import com.movtery.zalithlauncher.viewmodel.EventViewModel
import kotlinx.coroutines.launch

@Composable
internal fun rememberMindustryCatalogState(refreshKey: Int): State<MindustryCatalogLoadResult> =
    produceState<MindustryCatalogLoadResult>(
        initialValue = MindustryCatalogLoadResult.Loading,
        key1 = refreshKey
    ) {
        value = MindustryCatalogLoadResult.Loading
        value = MindustryCatalogRepository.fetchDefaultManifest()
    }

internal fun LazyListScope.mindustryCatalogItems(
    state: MindustryCatalogLoadResult,
    eventViewModel: EventViewModel,
    onRefresh: () -> Unit,
    showCloneSlots: Boolean = false
) {
    when (state) {
        MindustryCatalogLoadResult.Loading -> item {
            CatalogStatusCard(
                title = stringResource(R.string.mindustry_download_catalog),
                subtitle = stringResource(R.string.mindustry_catalog_loading),
                icon = R.drawable.ic_download_2_filled
            ) {
                CircularProgressIndicator()
            }
        }

        is MindustryCatalogLoadResult.Empty -> {
            item {
                CatalogStatusCard(
                    title = stringResource(R.string.mindustry_catalog_empty),
                    subtitle = stringResource(
                        R.string.mindustry_catalog_empty_detail,
                        state.sourceUrl
                    ),
                    icon = R.drawable.ic_info_outlined
                ) {
                    CatalogRefreshActions(eventViewModel, onRefresh)
                }
            }
            if (showCloneSlots) item { CloneSlotSummaryCard() }
        }

        is MindustryCatalogLoadResult.Error -> item {
            CatalogStatusCard(
                title = stringResource(R.string.mindustry_catalog_failed),
                subtitle = stringResource(
                    R.string.mindustry_catalog_failed_detail,
                    state.message,
                    state.attemptedUrls.size
                ),
                icon = R.drawable.ic_info_outlined
            ) {
                CatalogRefreshActions(eventViewModel, onRefresh)
            }
        }

        is MindustryCatalogLoadResult.Success -> {
            item {
                CatalogLoadedCard(
                    state = state,
                    eventViewModel = eventViewModel,
                    onRefresh = onRefresh
                )
            }
            items(
                items = state.manifest.artifacts.sortedWith(
                    compareBy<MindustryArtifact> { it.variant.ordinal }
                        .thenBy { it.backend.ordinal }
                        .thenBy { it.slot ?: 0 }
                        .thenByDescending { it.build }
                        .thenBy { it.id }
                ),
                key = { it.id }
            ) { artifact ->
                MindustryArtifactCard(
                    artifact = artifact,
                    manifest = state.manifest,
                    eventViewModel = eventViewModel
                )
            }
            if (showCloneSlots) item { CloneSlotSummaryCard() }
        }
    }
}

@Composable
private fun CatalogStatusCard(
    title: String,
    subtitle: String,
    @DrawableRes icon: Int,
    actions: @Composable () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                actions()
            }
        }
    }
}

@Composable
private fun CatalogLoadedCard(
    state: MindustryCatalogLoadResult.Success,
    eventViewModel: EventViewModel,
    onRefresh: () -> Unit
) {
    CatalogStatusCard(
        title = stringResource(R.string.mindustry_download_catalog),
        subtitle = stringResource(
            R.string.mindustry_catalog_loaded_detail,
            state.sourceUrl,
            state.manifest.artifacts.size
        ),
        icon = R.drawable.ic_download_2_filled
    ) {
        AssistChip(
            onClick = {},
            label = {
                Text(
                    text = stringResource(R.string.mindustry_runtime_mg),
                    maxLines = 1
                )
            }
        )
        CatalogRefreshActions(eventViewModel, onRefresh)
    }
}

@Composable
private fun CatalogRefreshActions(
    eventViewModel: EventViewModel,
    onRefresh: () -> Unit
) {
    OutlinedButton(onClick = onRefresh) {
        Text(text = stringResource(R.string.generic_refresh))
    }
    Button(onClick = {
        eventViewModel.sendEvent(EventViewModel.Event.OpenLink(BuildKeys.URL_HOME))
    }) {
        Text(text = stringResource(R.string.generic_view))
    }
}

@Composable
private fun MindustryArtifactCard(
    artifact: MindustryArtifact,
    manifest: MindustryCatalogManifest,
    eventViewModel: EventViewModel
) {
    val backend = stringResource(artifact.backend.titleRes())
    val variant = stringResource(artifact.variant.titleRes())
    val artifactUrls = MindustryCatalog.artifactDownloadUrls(artifact, manifest)
    val firstUrl = artifactUrls.firstOrNull()
    val runtimeLabel = artifact.mgVersion ?: stringResource(R.string.mindustry_runtime_mg)
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var cachedFile by remember(artifact.id) {
        mutableStateOf(MindustryArtifactStore.cachedArtifactFile(artifact))
    }
    var isDownloading by remember(artifact.id) { mutableStateOf(false) }
    var downloadError by remember(artifact.id) { mutableStateOf<String?>(null) }
    val restoredSession = remember(artifact.id) {
        MindustryApkInstaller.restore(context)?.takeIf { it.artifactId == artifact.id }
    }
    var installStatus by remember(artifact.id) {
        mutableStateOf(
            restoredSession?.status?.let { status ->
                when (status) {
                    "pending_user_action" -> context.getString(R.string.mindustry_install_pending)
                    "success" -> context.getString(R.string.mindustry_install_success, restoredSession.packageName)
                    else -> context.getString(R.string.mindustry_install_failed, status, context.getString(R.string.generic_error))
                }
            }
        )
    }
    var isInstalling by remember(artifact.id) { mutableStateOf(false) }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(artifact.backend.iconRes()),
                contentDescription = null
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$variant ${artifact.versionName}",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.mindustry_artifact_build_detail,
                        backend,
                        artifact.channel,
                        artifact.build,
                        artifact.buildType
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = stringResource(
                        R.string.mindustry_artifact_runtime_detail,
                        artifact.javaVersion,
                        artifact.nativeProfile,
                        runtimeLabel,
                        formatFileSize(artifact.size)
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                artifact.slot?.let { slot ->
                    Text(
                        text = stringResource(R.string.mindustry_artifact_slot_detail, slot),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    text = stringResource(
                        R.string.mindustry_artifact_min_launcher,
                        artifact.minLauncherVersion
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                when {
                    isDownloading -> Text(
                        text = stringResource(R.string.mindustry_status_downloading),
                        style = MaterialTheme.typography.bodySmall
                    )
                    cachedFile != null -> Text(
                        text = stringResource(
                            R.string.mindustry_artifact_cached_detail,
                            cachedFile?.name.orEmpty()
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                    downloadError != null -> Text(
                        text = stringResource(
                            R.string.mindustry_status_download_failed,
                            downloadError.orEmpty()
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                    installStatus != null -> Text(
                        text = installStatus.orEmpty(),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            text = cachedFile?.let {
                                stringResource(R.string.mindustry_status_cached)
                            } ?: artifact.id,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
                Button(
                    onClick = {
                        coroutineScope.launch {
                            isDownloading = true
                            downloadError = null
                            runCatching {
                                MindustryArtifactStore.downloadToCache(artifact, manifest)
                            }.onSuccess { file ->
                                cachedFile = file
                                if (artifact.backend == MindustryBackend.JAR) {
                                    runCatching {
                                        MindustryInstanceStore.ensureFromArtifact(
                                            artifact,
                                            file,
                                            MindustryPaths(PathManager.DIR_MINDUSTRY)
                                        )
                                    }.onFailure { error ->
                                        downloadError = error.message ?: "Could not create JAR instance"
                                    }
                                }
                            }.onFailure { throwable ->
                                downloadError = throwable.message ?: throwable::class.java.simpleName
                            }
                            isDownloading = false
                        }
                    },
                    enabled = !isDownloading
                ) {
                    Text(
                        text = if (cachedFile == null) {
                            stringResource(R.string.generic_download)
                        } else {
                            stringResource(R.string.mindustry_action_redownload)
                        }
                    )
                }
                if (artifact.backend == MindustryBackend.APK) {
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                isInstalling = true
                                installStatus = context.getString(R.string.mindustry_status_downloading)
                                val result = MindustryApkInstaller.install(context, artifact, manifest)
                                installStatus = when (result) {
                                    is MindustryInstallResult.PendingUserAction ->
                                        context.getString(R.string.mindustry_install_pending)
                                    is MindustryInstallResult.Success ->
                                        context.getString(R.string.mindustry_install_success, result.packageName)
                                    is MindustryInstallResult.Failure ->
                                        context.getString(
                                            R.string.mindustry_install_failed,
                                            context.getString(result.failure.labelRes()),
                                            result.message.ifBlank { context.getString(R.string.generic_error) }
                                        )
                                }
                                isInstalling = false
                            }
                        },
                        enabled = !isDownloading && !isInstalling
                    ) {
                        Text(text = stringResource(R.string.mindustry_action_install))
                    }
                } else if (cachedFile != null) {
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                runCatching {
                                    val instance = MindustryInstanceStore.ensureFromArtifact(
                                        artifact,
                                        requireNotNull(cachedFile),
                                        MindustryPaths(PathManager.DIR_MINDUSTRY)
                                    )
                                    check(MindustryRuntimeCoordinator.tryAcquire(instance.id, MindustryBackend.JAR)) {
                                        "Another Mindustry instance is running"
                                    }
                                    try {
                                        context.startActivity(
                                            createMindustryJarIntent(
                                                context,
                                                instance,
                                                MindustryPaths(PathManager.DIR_MINDUSTRY)
                                            )
                                        )
                                    } catch (error: Throwable) {
                                        MindustryRuntimeCoordinator.release(instance.id)
                                        throw error
                                    }
                                }.onFailure { error ->
                                    downloadError = error.message ?: context.getString(R.string.generic_error)
                                }
                            }
                        },
                        enabled = !isDownloading && !isInstalling
                    ) {
                        Text(text = stringResource(R.string.mindustry_action_launch))
                    }
                }
                OutlinedButton(
                    onClick = {
                        firstUrl?.let {
                            eventViewModel.sendEvent(EventViewModel.Event.OpenLink(it))
                        }
                    },
                    enabled = firstUrl != null
                ) {
                    Text(text = stringResource(R.string.mindustry_action_open_artifact))
                }
            }
        }
    }
}

private fun MindustryInstallFailure.labelRes(): Int = when (this) {
    MindustryInstallFailure.INVALID_PACKAGE -> R.string.mindustry_install_failure_invalid_package
    MindustryInstallFailure.VERSION_DOWNGRADE -> R.string.mindustry_install_failure_version_downgrade
    MindustryInstallFailure.SIGNATURE_MISMATCH -> R.string.mindustry_install_failure_signature_mismatch
    MindustryInstallFailure.ABI_UNSUPPORTED -> R.string.mindustry_install_failure_abi_unsupported
    MindustryInstallFailure.UNKNOWN_SOURCES -> R.string.mindustry_install_failure_unknown_sources
    MindustryInstallFailure.INSUFFICIENT_STORAGE -> R.string.mindustry_install_failure_insufficient_storage
    MindustryInstallFailure.USER_CANCELED -> R.string.mindustry_install_failure_user_canceled
    MindustryInstallFailure.SYSTEM_FAILURE -> R.string.mindustry_install_failure_system_failure
}

@Composable
private fun CloneSlotSummaryCard() {
    CatalogStatusCard(
        title = stringResource(R.string.mindustry_clone_slots),
        subtitle = stringResource(R.string.mindustry_clone_slot_total, ApkCloneSlot.all().size),
        icon = R.drawable.ic_package_2_outlined
    ) {
        AssistChip(
            onClick = {},
            label = {
                Text(
                    text = stringResource(R.string.mindustry_backend_apk),
                    maxLines = 1
                )
            }
        )
    }
}

@StringRes
private fun MindustryVariant.titleRes(): Int = when (this) {
    MindustryVariant.VANILLA -> R.string.mindustry_variant_vanilla
    MindustryVariant.BE -> R.string.mindustry_variant_be
    MindustryVariant.MINDUSTRY_X -> R.string.mindustry_variant_mindustryx
}

@StringRes
private fun MindustryBackend.titleRes(): Int = when (this) {
    MindustryBackend.JAR -> R.string.mindustry_backend_jar
    MindustryBackend.APK -> R.string.mindustry_backend_apk
}

@DrawableRes
private fun MindustryBackend.iconRes(): Int = when (this) {
    MindustryBackend.JAR -> R.drawable.ic_sports_esports_outlined
    MindustryBackend.APK -> R.drawable.ic_package_2_outlined
}
