package com.chips.heartrate

import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.chips.MainActivity
import com.chips.data.ActivityStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HrStopPromptTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext

    @Test fun notificationStopSavesHrThenOffersDurableLinkedBeatSaberChoice() {
        assumeEmulator()
        for(permission in HrCaptureService.permissions().toList()+android.Manifest.permission.POST_NOTIFICATIONS)
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName,permission)
        var emit:((Int,Long,Long)->Unit)?=null
        HrCaptureService.transportFactory={_,_,_,received ->
            emit=received
            object:HrTransport { override fun start(){};override fun reconnect(){};override fun close(){} }
        }
        var id:String?=null
        var activityId:String?=null
        try {
            compose.activityRule.scenario.onActivity { activity ->
                ContextCompat.startForegroundService(activity,Intent(activity,HrCaptureService::class.java)
                    .setAction(HrCaptureService.START).putExtra("device","00:11:22:33:44:55"))
            }
            compose.waitUntil(5000) { HrCaptureService.state.value.session!=null && emit!=null }
            id=HrCaptureService.state.value.session!!
            compose.runOnIdle { repeat(3) { emit!!(70,System.currentTimeMillis(),SystemClock.elapsedRealtime()) } }
            val manager=context.getSystemService(NotificationManager::class.java)
            val notification=manager.activeNotifications.first { it.id==HrCaptureService.NOTIFICATION_ID }.notification
            notification.actions.first { it.title=="Stop" }.actionIntent.send()
            compose.waitUntil(5000) { !HrCaptureService.state.value.stopping && !HrCaptureService.state.value.active }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("hr-beat-saber-prompt").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(3,HrStore(context).use { it.sessions(id).single().samples })
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("hr-beat-saber-prompt").assertIsDisplayed()
            compose.onNodeWithTag("hr-save-beat-saber").performClick()
            compose.waitUntil(5000) { ActivityStore.get(context).load().any { it.hrSessionId==id } }
            val record=ActivityStore.get(context).load().single { it.hrSessionId==id }
            activityId=record.id
            assertEquals("PENDING",record.syncStatus)
            assertEquals("saved",HrStore(context).use { it.sessions(id).single().prompt })
            compose.onNodeWithTag("hr-open-activity-$id").performScrollTo().performClick()
            compose.onNodeWithTag("activity-open-hr-${record.id}").performScrollTo().performClick()
            compose.onNodeWithTag("hr-session-$id").performScrollTo().assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("hr-beat-saber-prompt").assertDoesNotExist()
            assertEquals(1,ActivityStore.get(context).load().count { it.hrSessionId==id })
        } finally {
            compose.runOnIdle { HrCaptureService.command(context,HrCaptureService.STOP) }
            compose.waitUntil(5000) { !HrCaptureService.state.value.stopping }
            HrCaptureService.transportFactory=null
            id?.let { value -> HrStore(context).use { it.end(value);it.delete(value) } }
            activityId?.let { ActivityStore.get(context).writableDatabase.delete("activities","id=?",arrayOf(it)); ActivityStore.get(context).load() }
        }
    }

    @Test fun hrOnlyChoiceSurvivesRecreationAndNeverCreatesAnActivity() {
        val store=ActivityStore.get(context)
        val id=HrStore(context).use { hr ->
            val id=hr.begin("test-band")
            hr.sample(id,70,System.currentTimeMillis(),SystemClock.elapsedRealtime())
            hr.end(id,offerBeatSaber=true)
            id
        }
        try {
            compose.waitUntil(5000) { compose.onAllNodesWithTag("hr-beat-saber-prompt").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("hr-only").performClick()
            compose.waitUntil(5000) { HrStore(context).use { it.sessions(id).single().prompt=="declined" } }
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("hr-beat-saber-prompt").assertDoesNotExist()
            assertFalse(store.load().any { it.hrSessionId==id })
            assertEquals(1,HrStore(context).use { it.sessions(id).single().samples })
        } finally { HrStore(context).use { it.delete(id) } }
    }
}
