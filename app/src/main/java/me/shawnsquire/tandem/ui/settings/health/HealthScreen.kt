package me.shawnsquire.tandem.ui.settings.health

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import me.shawnsquire.tandem.R
import me.shawnsquire.tandem.ui.theme.TandemTheme

/** Placeholder; T5 replaces the body. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthRoute(onBack: () -> Unit = {}, modifier: Modifier = Modifier) {
    // The outer Scaffold already applies system-bar insets to the NavHost.
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_health)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
                windowInsets = WindowInsets(0),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding))
    }
}

@Preview(showBackground = true)
@Composable
private fun HealthRoutePreview() {
    TandemTheme { HealthRoute() }
}
