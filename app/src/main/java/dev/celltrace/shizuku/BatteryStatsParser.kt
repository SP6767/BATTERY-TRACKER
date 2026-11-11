package dev.celltrace.shizuku

data class BatteryAppStats(
    val uid: Int,
    val packageNames: List<String>,
    val estimatedMah: Double?,
    val foregroundMs: Long?,
    val backgroundRunningMs: Long?,
    val estimatedPercentPerHour: Double?
) {
    val displayName: String
        get() = packageNames.joinToString(", ").ifBlank { "UID $uid" }
}

data class BatteryStatsSnapshot(
    val batteryCapacityMah: Double?,
    val batteryRealtimeMs: Long?,
    val apps: List<BatteryAppStats>,
    val warnings: List<String>,
    val rawLineCount: Int
)

/**
 * Parser for Android's documented `dumpsys batterystats --charged --checkin` CSV.
 * It intentionally does not fabricate a foreground/background power split:
 * BatteryStats reports per-UID estimated mAh, while foreground/background
 * state durations are separate observations.
 */
object BatteryStatsParser {
    fun parse(raw: String): BatteryStatsSnapshot {
        val uidPackages = mutableMapOf<Int, MutableSet<String>>()
        val powerMah = mutableMapOf<Int, Double>()
        val stateTimes = mutableMapOf<Int, Triple<Long, Long, Long>>()
        val processTimes = mutableMapOf<Int, Pair<Long, Long>>()
        var capacityMah: Double? = null
        var batteryRealtimeMs: Long? = null
        var rows = 0

        // Checkin output may place multiple CSV records on one physical line.
        val recordStart = Regex("""(?<!\S)(?=\d+,\d+,[ilu],)""")
        raw.lineSequence().forEach { physicalLine ->
            recordStart.split(physicalLine.trim()).forEach { record ->
                val f = record.trim().split(',')
                if (f.size < 5 || f[0].toIntOrNull() == null) return@forEach
                rows++
                val uid = f.getOrNull(1)?.toIntOrNull() ?: return@forEach
                val mode = f.getOrNull(2)
                val section = f.getOrNull(3)
                if (mode != "l" && mode != "i") return@forEach

                when (section) {
                    "uid" -> {
                        val mappedUid = f.getOrNull(4)?.toIntOrNull()
                        val packageName = f.getOrNull(5)?.trim()
                        if (mappedUid != null && !packageName.isNullOrBlank()) {
                            uidPackages.getOrPut(mappedUid) { linkedSetOf() }.add(packageName)
                        }
                    }
                    "pwi" -> {
                        if (f.getOrNull(4) == "uid") {
                            val mah = f.getOrNull(5)?.toDoubleOrNull()
                            if (mah != null && mah >= 0) powerMah[uid] = mah
                        }
                    }
                    "st" -> {
                        val foreground = f.getOrNull(4)?.toLongOrNull()
                        val active = f.getOrNull(5)?.toLongOrNull()
                        val running = f.getOrNull(6)?.toLongOrNull()
                        if (foreground != null && active != null && running != null) {
                            stateTimes[uid] = Triple(foreground, active, running)
                        }
                    }
                    "pr" -> {
                        // pr: process, user CPU ms, system CPU ms, foreground CPU ms, starts
                        val user = f.getOrNull(5)?.toLongOrNull() ?: 0L
                        val system = f.getOrNull(6)?.toLongOrNull() ?: 0L
                        val foregroundCpu = f.getOrNull(7)?.toLongOrNull() ?: 0L
                        val previous = processTimes[uid] ?: (0L to 0L)
                        processTimes[uid] = (previous.first + user + system) to
                            (previous.second + foregroundCpu)
                    }
                    "pws" -> {
                        capacityMah = f.getOrNull(4)?.toDoubleOrNull()?.takeIf { it > 0 }
                    }
                    "bt" -> {
                        // bt fields: starts, battery realtime, battery uptime, ...
                        batteryRealtimeMs = f.getOrNull(4)?.toLongOrNull()?.takeIf { it > 0 }
                    }
                }
            }
        }

        val warnings = mutableListOf<String>()
        if (powerMah.isEmpty()) warnings += "This firmware did not expose per-UID pwi power estimates."
        if (uidPackages.isEmpty()) warnings += "No UID-to-package mapping was present in the checkin export."
        if (capacityMah == null) warnings += "Battery capacity was not included; %/hour cannot be calculated."
        if (batteryRealtimeMs == null) warnings += "BatteryStats did not expose a usable battery-time window."

        val allUids = (powerMah.keys + stateTimes.keys + processTimes.keys).toSet()
        val apps = allUids.mapNotNull { appUid ->
            val packages = uidPackages[appUid]?.toList().orEmpty()
            if (packages.isEmpty() || appUid < 10_000) return@mapNotNull null
            val state = stateTimes[appUid]
            val process = processTimes[appUid]
            val foregroundMs = state?.first
            val backgroundMs = state?.third?.minus(state.first)?.coerceAtLeast(0)
            val mah = powerMah[appUid]
            val hours = batteryRealtimeMs?.toDouble()?.div(3_600_000.0)
            val percentPerHour = if (mah != null && capacityMah != null && hours != null && hours > 0) {
                (mah / capacityMah) * 100.0 / hours
            } else null
            BatteryAppStats(
                uid = appUid,
                packageNames = packages,
                estimatedMah = mah,
                foregroundMs = foregroundMs,
                backgroundRunningMs = backgroundMs,
                estimatedPercentPerHour = percentPerHour
            )
        }.sortedByDescending { it.estimatedMah ?: 0.0 }

        return BatteryStatsSnapshot(
            batteryCapacityMah = capacityMah,
            batteryRealtimeMs = batteryRealtimeMs,
            apps = apps,
            warnings = warnings,
            rawLineCount = rows
        )
    }
}
