package dev.celltrace.shizuku

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Runs inside Shizuku's user-service process (normally shell UID 2000).
 * Only executes a read-only BatteryStats diagnostic command.
 */
class BatteryStatsUserService : IBatteryStatsService.Stub() {
    override fun dumpChargedCheckin(): String {
        val process = Runtime.getRuntime().exec(
            arrayOf("dumpsys", "batterystats", "--charged", "--checkin")
        )
        val stdout = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
        val stderr = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IllegalStateException("dumpsys exited with $exitCode: ${stderr.take(1200)}")
        }
        if (stdout.isBlank()) {
            throw IllegalStateException("BatteryStats returned no checkin data. ${stderr.take(500)}")
        }
        return stdout
    }
}
