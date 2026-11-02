package me.shawnsquire.tandem.ui.alarms

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import me.shawnsquire.tandem.R
import me.shawnsquire.tandem.schedule.VibrationPattern

@Composable
fun vibrationPatternName(pattern: VibrationPattern): String = stringResource(
    when (pattern) {
        VibrationPattern.PULSE -> R.string.vibration_pulse
        VibrationPattern.HEARTBEAT -> R.string.vibration_heartbeat
        VibrationPattern.RAPID -> R.string.vibration_rapid
        VibrationPattern.SLOW_WAVE -> R.string.vibration_slow_wave
        VibrationPattern.ESCALATING -> R.string.vibration_escalating
    },
)

/** The two output switches' hints: why the last one on is locked, and what vibrate-only depends on. */
@Composable
fun OutputHints(soundOn: Boolean, vibrationOn: Boolean, modifier: Modifier = Modifier) {
    if (soundOn && vibrationOn) return
    Column(modifier.padding(horizontal = 16.dp)) {
        Text(
            stringResource(R.string.output_keep_one_on),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Apps can't read the Alarm vibration setting, so this always shows while sound is off.
        if (!soundOn) {
            Text(
                stringResource(R.string.output_vibrate_only_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The preset list. Tapping a preset selects it ([onSelect] also plays one cycle); the list stays open
 * so presets can be compared. [onStopPreview] runs whenever the dialog leaves composition: closing it,
 * or leaving the screen. [onUsePartners] is non-null when an override is set and can be cleared.
 */
@Composable
fun VibrationPatternDialog(
    selected: VibrationPattern,
    onSelect: (VibrationPattern) -> Unit,
    onStopPreview: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    usePartnersLabel: String? = null,
    onUsePartners: () -> Unit = {},
) {
    val latestStop by rememberUpdatedState(onStopPreview)
    // Also on ON_STOP: Home or screen-off must not leave a long pass (Escalating) running.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) latestStop() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            latestStop()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(stringResource(R.string.vibration_title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                VibrationPattern.entries.forEach { pattern ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = pattern == selected, role = Role.RadioButton) { onSelect(pattern) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = pattern == selected, onClick = null)
                        Text(vibrationPatternName(pattern), Modifier.padding(start = 16.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.vibration_done)) } },
        dismissButton = usePartnersLabel?.let { label ->
            { TextButton(onClick = { onUsePartners(); onDismiss() }) { Text(label) } }
        },
    )
}
