package me.shawnsquire.tandem

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import me.shawnsquire.tandem.ui.MainScreen
import me.shawnsquire.tandem.ui.theme.TandemTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TandemTheme { MainScreen() } }
    }
}
