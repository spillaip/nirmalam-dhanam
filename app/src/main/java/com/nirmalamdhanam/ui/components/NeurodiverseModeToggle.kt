package com.nirmalamgroup.nirmalamdhanam.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

@Composable
fun NeurodiverseModeToggle(enabled: Boolean, onEnabledChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    ListItem(
        modifier = modifier.fillMaxWidth().semantics { stateDescription = if (enabled) "Neurodiverse mode on" else "Neurodiverse mode off" },
        headlineContent = { Text("Neurodiverse mode") },
        supportingContent = { Text("A calmer, lower-clutter financial view. Your data and calculations stay unchanged.", style = MaterialTheme.typography.bodySmall) },
        trailingContent = { Switch(checked = enabled, onCheckedChange = onEnabledChange) }
    )
}
