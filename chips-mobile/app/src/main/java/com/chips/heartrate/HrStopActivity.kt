package com.chips.heartrate

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.chips.ui.FeatureRoute
import com.chips.ui.featureIntent

/** Non-exported notification destination: user-initiated Stop may open its save prompt. */
class HrStopActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startService(Intent(this, HrCaptureService::class.java).setAction(HrCaptureService.STOP)
            .putExtra("session", intent.getStringExtra("session")))
        startActivity(featureIntent(this, FeatureRoute.HEART_RATE))
        finish()
    }
}
