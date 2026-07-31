/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.movtery.zalithlauncher.game.mindustry

/**
 * Process-local state shared by the Bridge service and the game activity.
 * The service and VMActivity intentionally run in the same Android process.
 */
object MindustryBridgeRuntime {
    @Volatile
    private var activeInstanceId: String? = null

    @Volatile
    private var gracefulExitRequest: (() -> Unit)? = null

    @Synchronized
    fun register(instanceId: String, gracefulExit: () -> Unit) {
        activeInstanceId = instanceId
        gracefulExitRequest = gracefulExit
    }

    @Synchronized
    fun clear(instanceId: String? = null) {
        if (instanceId == null || activeInstanceId == instanceId) {
            activeInstanceId = null
            gracefulExitRequest = null
        }
    }

    fun isRunning(): Boolean = activeInstanceId != null

    fun requestGracefulExit(): Boolean = synchronized(this) {
        val callback = gracefulExitRequest ?: return false
        callback.invoke()
        true
    }
}
