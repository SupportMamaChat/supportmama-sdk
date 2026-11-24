package dev.supportmama

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.platform.LocalContext

/**
 * The chat as a screen of its own: what [SupportMama.open] starts. Uses the
 * phone's dynamic colours on Android 12+, Material defaults before that.
 * Compose apps that want their own theme can embed [SupportChat] instead.
 */
class SupportChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val draft = intent.getStringExtra(EXTRA_DRAFT)
        onBackPressedDispatcher.addCallback(this) { leave() }
        setContent {
            val dark = isSystemInDarkTheme()
            val context = LocalContext.current
            val colors = when {
                Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) {
                SupportChat(onBack = ::leave, draft = draft)
            }
        }
    }

    /** From a notification the chat may be the whole task: back goes to the app, not home. */
    private fun leave() {
        if (isTaskRoot) packageManager.getLaunchIntentForPackage(packageName)?.let(::startActivity)
        finish()
    }

    companion object {
        const val EXTRA_DRAFT = "dev.supportmama.extra.DRAFT"
    }
}
