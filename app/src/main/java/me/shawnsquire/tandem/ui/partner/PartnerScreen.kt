package me.shawnsquire.tandem.ui.partner

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import me.shawnsquire.tandem.ui.theme.TandemTheme

@Composable
fun PartnerScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Partner")
    }
}

@Preview(showBackground = true)
@Composable
private fun PartnerScreenPreview() {
    TandemTheme { PartnerScreen() }
}
