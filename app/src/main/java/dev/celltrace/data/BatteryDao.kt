package dev.celltrace.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BatteryDao {
    @Insert suspend fun insert(record: BatteryRecord)
    @Query("SELECT * FROM battery_records ORDER BY observedAt DESC LIMIT 300")
    fun observeRecent(): Flow<List<BatteryRecord>>
    @Query("SELECT * FROM battery_records ORDER BY observedAt DESC LIMIT 1")
    suspend fun latest(): BatteryRecord?
    @Query("SELECT COUNT(*) FROM battery_records")
    suspend fun count(): Int
}
