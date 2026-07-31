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

/**
 * Same-signature IPC surface between the Hub app and clone APK slots.
 */
object MindustryBridgeContract {
    const val PROTOCOL_VERSION = 1
    const val HUB_PACKAGE = "com.xenon.mobile"
    const val PERMISSION = "$HUB_PACKAGE.permission.MINDUSTRY_BRIDGE"

    const val ACTION_LAUNCH = "$HUB_PACKAGE.bridge.LAUNCH"
    const val ACTION_STATUS = "$HUB_PACKAGE.bridge.STATUS"
    const val ACTION_SET_PROFILE = "$HUB_PACKAGE.bridge.SET_PROFILE"
    const val ACTION_JOIN = "$HUB_PACKAGE.bridge.JOIN"
    const val ACTION_IMPORT_ZIP = "$HUB_PACKAGE.bridge.IMPORT_ZIP"
    const val ACTION_EXPORT_ZIP = "$HUB_PACKAGE.bridge.EXPORT_ZIP"
    const val ACTION_EXPORT_DIAGNOSTICS = "$HUB_PACKAGE.bridge.EXPORT_DIAGNOSTICS"
    const val ACTION_REQUEST_GRACEFUL_EXIT = "$HUB_PACKAGE.bridge.REQUEST_GRACEFUL_EXIT"
    const val ACTION_RESET_WHITELISTED_DATA = "$HUB_PACKAGE.bridge.RESET_WHITELISTED_DATA"

    const val EXTRA_INSTANCE_ID = "instance_id"
    const val EXTRA_UUID = "uuid"
    const val EXTRA_NAME = "name"
    const val EXTRA_HOST = "host"
    const val EXTRA_PORT = "port"
    const val EXTRA_VARIANT_HINT = "variant_hint"
    const val EXTRA_BUILD_HINT = "build_hint"
    const val EXTRA_ZIP_URI = "zip_uri"
    const val EXTRA_PROTOCOL_VERSION = "protocol_version"
    const val EXTRA_CALLER_PACKAGE = "caller_package"
    const val EXTRA_RESULT_RECEIVER = "result_receiver"
    const val EXTRA_REQUEST_ID = "request_id"
    const val EXTRA_VARIANT = "variant"
    const val EXTRA_BACKEND = "backend"
    const val EXTRA_SLOT = "slot"
    const val EXTRA_PROFILE_ID = "profile_id"
    /** Profile values use the same wire keys as the clone Bridge implementation. */
    const val EXTRA_PROFILE_UUID = EXTRA_UUID
    const val EXTRA_PROFILE_NAME = EXTRA_NAME
    const val EXTRA_INSTALLED = "installed"
    const val EXTRA_BUSY = "busy"
    const val EXTRA_PACKAGE_NAME = "package_name"
    const val EXTRA_ERROR = "error"
    const val EXTRA_STATUS = "status"
    const val EXTRA_RESULT_URI = "result_uri"

    const val RESULT_OK = 0
    const val RESULT_BAD_REQUEST = 1
    const val RESULT_UNAUTHORIZED = 2
    const val RESULT_BUSY = 3
    const val RESULT_NOT_FOUND = 4
    const val RESULT_IO_ERROR = 5
    const val RESULT_TIMEOUT = 6
    const val RESULT_UNSUPPORTED = 7

    val actions: Set<String> = setOf(
        ACTION_LAUNCH,
        ACTION_STATUS,
        ACTION_SET_PROFILE,
        ACTION_JOIN,
        ACTION_IMPORT_ZIP,
        ACTION_EXPORT_ZIP,
        ACTION_EXPORT_DIAGNOSTICS,
        ACTION_REQUEST_GRACEFUL_EXIT,
        ACTION_RESET_WHITELISTED_DATA
    )
}
