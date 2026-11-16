package com.capsulepal.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TaskEntity::class,
        TaskStepEntity::class,
        TaskGroupEntity::class,
        PlanEntity::class,
        TemplateEntity::class,
        TemplateBlockEntity::class,
        BatterySessionEntity::class,
        BatteryHealthRecordEntity::class,
        DischargeSessionEntity::class,
        ChargeSessionEntity::class,
        BatterySampleEntity::class,
        AppPowerRecordEntity::class
    ],
    version = 15,
    exportSchema = true
)
abstract class CapsuleDatabase : RoomDatabase() {

    abstract fun taskDao(): TaskDao
    abstract fun groupDao(): GroupDao
    abstract fun planDao(): PlanDao
    abstract fun templateDao(): TemplateDao
    abstract fun batteryDao(): BatteryDao

    companion object {
        @Volatile
        private var instance: CapsuleDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN repeatRule TEXT NOT NULL DEFAULT 'ONCE'")
                db.execSQL("ALTER TABLE tasks ADD COLUMN repeatDays TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE tasks ADD COLUMN skipHolidays INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN seriesId INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN shortTitle TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN awardedXp INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN customCategory TEXT NOT NULL DEFAULT ''")
                // 旧分类并入新分类体系。
                val renames = listOf(
                    "COOKING" to "FOOD",
                    "LAUNDRY" to "CHORES",
                    "BEDTIME" to "ROUTINE"
                )
                renames.forEach { (old, new) ->
                    db.execSQL("UPDATE tasks SET category = '$new' WHERE category = '$old'")
                    db.execSQL("UPDATE template_blocks SET category = '$new' WHERE category = '$old'")
                }
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN fromTemplate INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `battery_sessions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`startTs` INTEGER NOT NULL, " +
                        "`endTs` INTEGER NOT NULL, " +
                        "`lastTs` INTEGER NOT NULL, " +
                        "`startPct` INTEGER NOT NULL, " +
                        "`endPct` INTEGER NOT NULL, " +
                        "`startMah` INTEGER NOT NULL, " +
                        "`endMah` INTEGER NOT NULL, " +
                        "`peakPowerW` REAL NOT NULL, " +
                        "`fullQuiet` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `battery_health_records` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`ts` INTEGER NOT NULL, " +
                        "`sessionId` INTEGER NOT NULL, " +
                        "`healthPct` REAL NOT NULL, " +
                        "`weight` INTEGER NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`amplitudePct` INTEGER NOT NULL)"
                )
            }
        }

        /**
         * v8：学习样本改为存原始测量值（start/end 的 mAh 与百分比），健康度在读取时
         * 按当前额定容量重算。旧样本无法回溯原始值，标记为 LEGACY 且不参与加权。
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `battery_health_records` RENAME TO `battery_health_records_old`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `battery_health_records` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`ts` INTEGER NOT NULL, " +
                        "`sessionId` INTEGER NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`weight` INTEGER NOT NULL, " +
                        "`startMah` INTEGER NOT NULL, " +
                        "`startPct` INTEGER NOT NULL, " +
                        "`endMah` INTEGER NOT NULL, " +
                        "`endPct` INTEGER NOT NULL, " +
                        "`quiet` INTEGER NOT NULL, " +
                        "`estimator` TEXT NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO `battery_health_records` (`id`, `ts`, `sessionId`, `kind`, " +
                        "`weight`, `startMah`, `startPct`, `endMah`, `endPct`, `quiet`, `estimator`) " +
                        "SELECT `id`, `ts`, `sessionId`, `kind`, `weight`, 0, 0, 0, 0, 0, 'LEGACY' " +
                        "FROM `battery_health_records_old`"
                )
                db.execSQL("DROP TABLE `battery_health_records_old`")
            }
        }

        /** v13：极客版专属的放电会话、24 小时采样与应用功耗历史。 */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `battery_discharge_sessions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`startTs` INTEGER NOT NULL, " +
                        "`endTs` INTEGER NOT NULL, " +
                        "`lastTs` INTEGER NOT NULL, " +
                        "`startPct` INTEGER NOT NULL, " +
                        "`endPct` INTEGER NOT NULL, " +
                        "`startMah` INTEGER NOT NULL, " +
                        "`endMah` INTEGER NOT NULL, " +
                        "`avgPowerW` REAL NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `battery_samples` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`ts` INTEGER NOT NULL, " +
                        "`levelPct` INTEGER NOT NULL, " +
                        "`tempC` REAL NOT NULL, " +
                        "`currentMa` INTEGER NOT NULL, " +
                        "`powerW` REAL NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `app_power_records` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`epochDay` INTEGER NOT NULL, " +
                        "`packageName` TEXT NOT NULL, " +
                        "`label` TEXT NOT NULL, " +
                        "`foregroundMinutes` INTEGER NOT NULL, " +
                        "`trafficBytes` INTEGER NOT NULL, " +
                        "`powerMah` REAL NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_battery_samples_ts` ON `battery_samples` (`ts`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_app_power_records_epochDay` " +
                        "ON `app_power_records` (`epochDay`)"
                )
            }
        }

        /** v14：应用功耗改为 Scene 风格，去掉流量，新增使用期间的平均/最高温度。 */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `app_power_records_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`epochDay` INTEGER NOT NULL, " +
                        "`packageName` TEXT NOT NULL, " +
                        "`label` TEXT NOT NULL, " +
                        "`foregroundMinutes` INTEGER NOT NULL, " +
                        "`avgTempC` REAL NOT NULL, " +
                        "`maxTempC` REAL NOT NULL, " +
                        "`powerMah` REAL NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO `app_power_records_new` " +
                        "(`id`, `epochDay`, `packageName`, `label`, `foregroundMinutes`, " +
                        "`avgTempC`, `maxTempC`, `powerMah`) " +
                        "SELECT `id`, `epochDay`, `packageName`, `label`, `foregroundMinutes`, " +
                        "-1, -1, `powerMah` FROM `app_power_records`"
                )
                db.execSQL("DROP TABLE `app_power_records`")
                db.execSQL("ALTER TABLE `app_power_records_new` RENAME TO `app_power_records`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_app_power_records_epochDay` " +
                        "ON `app_power_records` (`epochDay`)"
                )
            }
        }

        /** v15：极客版新增充电会话记录，与放电会话并列。 */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `battery_charge_sessions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`startTs` INTEGER NOT NULL, " +
                        "`endTs` INTEGER NOT NULL, " +
                        "`lastTs` INTEGER NOT NULL, " +
                        "`startPct` INTEGER NOT NULL, " +
                        "`endPct` INTEGER NOT NULL, " +
                        "`startMah` INTEGER NOT NULL, " +
                        "`endMah` INTEGER NOT NULL, " +
                        "`avgPowerW` REAL NOT NULL, " +
                        "`peakPowerW` REAL NOT NULL)"
                )
            }
        }

        /** v9：新增用户自定义分组，待办通过 groupId 归属，0 表示未分组。 */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `task_groups` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`sortOrder` INTEGER NOT NULL)"
                )
                db.execSQL("ALTER TABLE tasks ADD COLUMN groupId INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v10：重复规则增加「连续 n 天」，非该规则的待办 repeatCount 为 0。 */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN repeatCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v11：新增计划表，待办通过 planId 归属计划，0 表示不属于任何计划。 */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `plans` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`note` TEXT NOT NULL, " +
                        "`color` TEXT NOT NULL, " +
                        "`targetEpochDay` INTEGER NOT NULL, " +
                        "`createdAtEpochDay` INTEGER NOT NULL, " +
                        "`archived` INTEGER NOT NULL)"
                )
                db.execSQL("ALTER TABLE tasks ADD COLUMN planId INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v12：计划分「重复 / 周期」两类；待办区分普通与日程来源。 */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN kind TEXT NOT NULL DEFAULT 'NORMAL'")
                db.execSQL("ALTER TABLE plans ADD COLUMN type TEXT NOT NULL DEFAULT 'REPEAT'")
                db.execSQL("ALTER TABLE plans ADD COLUMN startEpochDay INTEGER NOT NULL DEFAULT 0")
                // 有目标日期的旧计划按周期计划处理，开始日取创建日；其余归为重复计划。
                db.execSQL(
                    "UPDATE plans SET type = CASE WHEN targetEpochDay > 0 THEN 'PERIOD' ELSE 'REPEAT' END"
                )
                db.execSQL(
                    "UPDATE plans SET startEpochDay = createdAtEpochDay " +
                        "WHERE type = 'PERIOD' AND startEpochDay = 0"
                )
            }
        }

        fun get(context: Context): CapsuleDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CapsuleDatabase::class.java,
                    "capsule_pal.db"
                ).addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15
                )
                    .build()
                    .also { instance = it }
            }
    }
}
