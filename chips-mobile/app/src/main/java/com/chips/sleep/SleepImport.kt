package com.chips.sleep

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit

object SleepImport {
    val permission = HealthPermission.getReadPermission(SleepSessionRecord::class)
    const val historyPermission = "android.permission.health.READ_HEALTH_DATA_HISTORY"
    suspend fun run(context: Context): Int {
        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        check(permission in granted) { "Allow sleep access in Health Connect, then import again." }
        val end = Instant.now()
        // Stay inside the default 30-day allowance on every foreground import.
        val start = if(historyPermission in granted) Instant.EPOCH else end.minus(30, ChronoUnit.DAYS)
        val rows = mutableListOf<SleepEntry>()
        var token: String? = null
        do {
            val page = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end), pageSize = 500, pageToken = token))
            page.records.forEach { r ->
                val stages = JSONArray()
                r.stages.forEach { s -> stages.put(JSONObject().put("stage",s.stage).put("start",s.startTime.toEpochMilli()).put("end",s.endTime.toEpochMilli())) }
                rows += SleepEntry(r.metadata.id,r.metadata.dataOrigin.packageName,r.startTime.toEpochMilli(),r.endTime.toEpochMilli(),(r.endZoneOffset ?: java.time.ZoneId.systemDefault().rules.getOffset(r.endTime)).totalSeconds,stages.toString(),
                    (r.startZoneOffset ?: java.time.ZoneId.systemDefault().rules.getOffset(r.startTime)).totalSeconds)
            }
            token = page.pageToken?.takeIf { it.isNotBlank() }
        } while(token != null)
        // Permission revocation or a failed page never replaces the saved history.
        check(permission in client.permissionController.getGrantedPermissions()) { "Sleep permission was removed. Saved history is unchanged." }
        SleepStore(context).use { it.replaceWindow(start.toEpochMilli(),end.toEpochMilli(),rows) }
        return rows.size
    }
}
