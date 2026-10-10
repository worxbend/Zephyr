package com.worxbend.zephyr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Replaces route content during shutdown so no feature can admit more work. */
@Composable
fun AppShutdownScreen(
    failure: String?,
    onRetry: () -> Unit,
    onExitWithoutAcknowledgement: () -> Unit,
) {
    ZephyrTheme(darkTheme = false) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(48.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(if (failure == null) "Closing Zephyr…" else "Shutdown needs attention", style = MaterialTheme.typography.headlineMedium)
                if (failure == null) {
                    ZephyrProgressIndicator()
                    Text("Stopping tasks and confirming saved settings.")
                } else {
                    Text(failure, color = MaterialTheme.colorScheme.error)
                    Text("Task receipts or settings may not be saved. New task admission has stopped.")
                    Button(onClick = onRetry) { Text("Retry shutdown") }
                    Button(onClick = onExitWithoutAcknowledgement) { Text("Exit without confirmation") }
                }
            }
        }
    }
}
