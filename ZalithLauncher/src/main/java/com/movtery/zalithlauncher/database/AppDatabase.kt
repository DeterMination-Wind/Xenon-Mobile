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

package com.movtery.zalithlauncher.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.movtery.zalithlauncher.game.account.Account
import com.movtery.zalithlauncher.game.account.AccountDao
import com.movtery.zalithlauncher.game.account.auth_server.data.AuthServer
import com.movtery.zalithlauncher.game.account.auth_server.data.AuthServerDao
import com.movtery.zalithlauncher.game.path.GamePath
import com.movtery.zalithlauncher.game.path.GamePathDao
import com.movtery.zalithlauncher.game.mindustry.MindustryProfileDao
import com.movtery.zalithlauncher.game.mindustry.MindustryProfileBindingEntity
import com.movtery.zalithlauncher.game.mindustry.MindustryProfileEntity

@Database(
    entities = [Account::class, AuthServer::class, GamePath::class, MindustryProfileEntity::class, MindustryProfileBindingEntity::class],
    version = 3,
    exportSchema = false //默认不支持导出
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    /**
     * 启动器账号
     */
    abstract fun accountDao(): AccountDao

    /**
     * 认证服务器
     */
    abstract fun authServerDao(): AuthServerDao

    /**
     * 游戏目录
     */
    abstract fun gamePathDao(): GamePathDao

    /** Mindustry Profile and scope-binding persistence. */
    abstract fun mindustryProfileDao(): MindustryProfileDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN expiresAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS mindustry_profiles (" +
                        "id TEXT NOT NULL, name TEXT NOT NULL, uuid TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(id))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS mindustry_profile_bindings (" +
                        "scopeKey TEXT NOT NULL, profileId TEXT NOT NULL, updatedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(scopeKey))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_mindustry_profile_bindings_profileId " +
                        "ON mindustry_profile_bindings(profileId)"
                )
            }
        }

        /**
         * 获取全局数据库实例
         */
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "launcher_data.db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
