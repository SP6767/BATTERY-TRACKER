package dev.celltrace

import android.app.Application
import dev.celltrace.data.CellTraceDatabase

class CellTraceApp : Application() {
    val database by lazy { CellTraceDatabase.get(this) }
}
