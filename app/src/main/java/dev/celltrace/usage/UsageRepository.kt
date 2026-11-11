package dev.celltrace.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

data class UsageSummary(
    val screenOnMillis: Long,
    val foregroundByPackage: Map<String, Long>,
    val hasPermission: Boolean
)

class UsageRepository(private val context: Context) {
    fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun query(from: Long, to: Long): UsageSummary {
        if (!hasUsageAccess()) return UsageSummary(0L, emptyMap(), false)
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = manager.queryEvents(from, to) ?: return UsageSummary(0L, emptyMap(), true)
        val event = UsageEvents.Event()
        var screenInteractive = false
        var screenStart = 0L
        var screenTotal = 0L
        val foregroundStart = mutableMapOf<String, Long>()
        val foregroundTotals = mutableMapOf<String, Long>()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> {
                    screenInteractive = true
                    screenStart = event.timeStamp
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    if (screenInteractive) screenTotal += (event.timeStamp - screenStart).coerceAtLeast(0)
                    screenInteractive = false
                }
                UsageEvents.Event.ACTIVITY_RESUMED,
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    val pkg = event.packageName ?: continue
                    foregroundStart[pkg] = event.timeStamp
                }
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED,
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val pkg = event.packageName ?: continue
                    val start = foregroundStart.remove(pkg)
                    if (start != null) foregroundTotals[pkg] =
                        (foregroundTotals[pkg] ?: 0L) + (event.timeStamp - start).coerceAtLeast(0)
                }
            }
        }
        if (screenInteractive) screenTotal += (to - screenStart).coerceAtLeast(0)
        foregroundStart.forEach { (pkg, start) ->
            foregroundTotals[pkg] = (foregroundTotals[pkg] ?: 0L) + (to - start).coerceAtLeast(0)
        }
        return UsageSummary(screenTotal, foregroundTotals, true)
    }
}
