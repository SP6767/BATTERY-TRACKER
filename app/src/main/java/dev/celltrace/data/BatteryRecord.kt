package dev.celltrace.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "battery_records")
data class BatteryRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val level: Int,
    val observedAt: Long,
    val charging: Boolean,
    val inferred: Boolean,
    val temperatureC: Float?,
    val voltageMv: Int?
)
