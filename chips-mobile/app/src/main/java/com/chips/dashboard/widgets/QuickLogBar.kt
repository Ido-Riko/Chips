package com.chips.dashboard.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun QuickLogBar(onLogTarget: (String) -> Unit) {
    BottomAppBar(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = { onLogTarget("Workout") }) {
                Text("Workout ✓")
            }
            Button(onClick = { onLogTarget("Beat Saber") }) {
                Text("Beat Saber ✓")
            }
            Button(onClick = { onLogTarget("Drawing") }) {
                Text("Drawing ✓")
            }
            Button(onClick = { onLogTarget("Japanese") }) {
                Text("Japanese ✓")
            }
        }
    }
}
