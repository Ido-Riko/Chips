package com.chips.sleep

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class HealthPrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Text("CHIPS reads sleep sessions and stages from Health Connect only when you choose to import. Imports stay in CHIPS on this phone and are not uploaded to the CHIPS server. You can remove the local copy in History → Sleep and revoke access in Health Connect settings. Gadgetbridge and Health Connect retain their own copies. Android backup may include app data according to your device's backup settings.", Modifier.padding(24.dp)) } }
    }
}
