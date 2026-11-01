package me.shawnsquire.tandem.ui.alarms.linked

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.shawnsquire.tandem.R
import me.shawnsquire.tandem.ui.theme.TandemTheme

/**
 * "Just me or both of us?" as dropdown menu content, for a joined row's switch. An answer that is not
 * available is shown disabled rather than hidden.
 */
@Composable
fun LinkedSwitchQuestion(
    options: LinkedSwitchOptions,
    onJustMe: () -> Unit,
    onBoth: () -> Unit,
) {
    Text(
        stringResource(R.string.link_just_me_or_both),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.link_just_me)) },
        onClick = onJustMe,
        enabled = options.justMe,
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.link_both_of_us)) },
        onClick = onBoth,
        enabled = options.both,
    )
}

@Preview(showBackground = true)
@Composable
private fun SwitchQuestionPreview() {
    TandemTheme {
        Card { Column { LinkedSwitchQuestion(LinkedSwitchOptions(justMe = true, both = true), {}, {}) } }
    }
}

/** Turning on while the owner's side is off: only "both of us" can do it. */
@Preview(showBackground = true)
@Composable
private fun SwitchQuestionJustMeUnavailablePreview() {
    TandemTheme {
        Card { Column { LinkedSwitchQuestion(LinkedSwitchOptions(justMe = false, both = true), {}, {}) } }
    }
}
