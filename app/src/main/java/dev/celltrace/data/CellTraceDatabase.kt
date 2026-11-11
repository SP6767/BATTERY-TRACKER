package dev.celltrace.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [BatteryRecord::class], version = 1, exportSchema = false)
abstract class CellTraceDatabase : RoomDatabase() {
    abstract fun batteryDao(): BatteryDao

    companion object {
        @Volatile private var INSTANCE: CellTraceDatabase? = null
        fun get(context: Context): CellTraceDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    CellTraceDatabase::class.java,
                    "celltrace.db"
                ).build().also { INSTANCE = it }
            }
    }
}
