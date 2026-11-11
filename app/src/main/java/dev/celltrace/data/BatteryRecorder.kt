package dev.celltrace.data

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object BatteryRecorder {
    private const val PREFS = "celltrace_state"
    private const val KEY_LAST_LEVEL = "last_level"
    private const val KEY_LAST_CHARGING = "last_charging"
    private const val KEY_LAST_CHARGE_START = "last_charge_start"
    private const val KEY_LAST_FULL = "last_full"

    suspend fun capture(context: Context, intent: Intent) = withContext(Dispatchers.IO) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (level < 0) return@withContext
        val pct = ((level * 100f) / scale).toInt().coerceIn(0, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val charging = plugged != 0 || status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val tempRaw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        val voltageRaw = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val oldLevel = prefs.getInt(KEY_LAST_LEVEL, -1)
        val oldCharging = prefs.getBoolean(KEY_LAST_CHARGING, charging)

        if (oldCharging != charging && charging) prefs.edit().putLong(KEY_LAST_CHARGE_START, now).apply()
        if (pct == 100 && oldLevel != 100) prefs.edit().putLong(KEY_LAST_FULL, now).apply()

        val dao = CellTraceDatabase.get(context).batteryDao()
        if (oldLevel == -1) {
            dao.insert(BatteryRecord(
                level = pct, observedAt = now, charging = charging, inferred = false,
                temperatureC = if (tempRaw >= 0) tempRaw / 10f else null,
                voltageMv = voltageRaw.takeIf { it > 0 }
            ))
        } else if (pct != oldLevel) {
            val step = if (pct > oldLevel) 1 else -1
            var value = oldLevel + step
            while (if (step > 0) value <= pct else value >= pct) {
                dao.insert(BatteryRecord(
                    level = value,
                    observedAt = now,
                    charging = charging,
                    inferred = kotlin.math.abs(pct - oldLevel) > 1,
                    temperatureC = if (tempRaw >= 0) tempRaw / 10f else null,
                    voltageMv = voltageRaw.takeIf { it > 0 }
                ))
                value += step
            }
        }

        prefs.edit()
            .putInt(KEY_LAST_LEVEL, pct)
            .putBoolean(KEY_LAST_CHARGING, charging)
            .apply()
    }

    fun chargeAnchor(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_CHARGE_START, 0L)

    fun fullAnchor(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_FULL, 0L)

    fun monitoringEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("monitoring_enabled", false)

    fun setMonitoringEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("monitoring_enabled", enabled).apply()
    }
}
