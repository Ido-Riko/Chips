package com.chips.heartrate

import com.chips.data.ActivityRecord
import com.chips.data.ActivityStore

/** A stable activity ID/link makes the two-database handoff recoverable at every boundary. */
internal class HrSessionLinker(private val hr: HrStore, private val activities: ActivityStore) {
    fun save(id: String): ActivityRecord {
        val session = hr.sessions(id).singleOrNull() ?: error("HR capture no longer exists")
        check(session.ended != null) { "Finish HR capture before saving Beat Saber" }
        check(hr.seal(id)) { "Capture recovered while saving. Stop it first." }
        val record = activities.logHeartRateSession(session.id, session.started, session.zoneId)
        hr.decide(id, save = true)
        return record
    }

    fun pending(): HrSession? {
        val linked = activities.load().mapNotNull { it.hrSessionId }.toSet()
        for(session in hr.sessions(pendingPrompt = true)) {
            // A crash after the activity commit but before acknowledgement never asks for a duplicate.
            if(session.id in linked) hr.decide(session.id, save = true)
            else return session
        }
        return null
    }
}
