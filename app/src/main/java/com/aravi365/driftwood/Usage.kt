package com.aravi365.driftwood

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/** Time spent in each app over the last 30 days, for the "Most used first" order. */
object Usage {
    private const val DAYS_30 = 30L * 24 * 60 * 60 * 1000

    fun allowed(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    fun foregroundTime(ctx: Context): Map<String, Long> {
        if (!allowed(ctx)) return emptyMap()
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        return usm.queryAndAggregateUsageStats(now - DAYS_30, now).mapValues { it.value.totalTimeInForeground }
    }
}
