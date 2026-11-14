package app.persianbridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    // Bumped on every resume so the live permission status rereads the system state.
    private val tick = mutableIntStateOf(0)

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { tick.intValue++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // My key and settings live here, so no screenshots and no screen recordings of this app.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        Bridge.init(this)
        setContent {
            val d by Store.data.collectAsState()
            PbTheme(d.dynamicColor) {
                Root(tick.intValue) { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        tick.intValue++
    }
}

private enum class Screen { HOME, SETTINGS }

@Composable
private fun Root(tick: Int, askNotifications: () -> Unit) {
    var screen by remember { mutableStateOf(Screen.HOME) }
    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }
    when (screen) {
        Screen.HOME -> Home(tick, askNotifications) { screen = Screen.SETTINGS }
        Screen.SETTINGS -> SettingsScreen { screen = Screen.HOME }
    }
}

@Composable
private fun Home(tick: Int, askNotifications: () -> Unit, openSettings: () -> Unit) {
    val c = Pb.c
    val ctx = LocalContext.current
    val d by Store.data.collectAsState()
    val running by BubbleService.running.collectAsState()
    val connected by ChatReader.connected.collectAsState()
    val models by Bridge.models.collectAsState()

    // These reread the real system state every time I come back to the app.
    val overlay = remember(tick) { Settings.canDrawOverlays(ctx) }
    val a11y = remember(tick, connected) { ChatReader.isEnabled(ctx) }
    val notif = remember(tick) {
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val telegram = remember(tick) { ctx.packageManager.getLaunchIntentForPackage(ChatReader.TELEGRAM) }
    LaunchedEffect(tick) { Bridge.refreshModels() }

    val ready = overlay && a11y

    ScreenBase {
        Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_bridge), null, Modifier.size(64.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Txt("Persian Bridge", style = T.title)
                Txt("You press Send. I only translate.", style = T.small, color = c.muted)
            }
        }

        if (telegram == null) {
            Spacer(Modifier.height(16.dp))
            Notice(
                NoticeKind.Warn,
                title = "Telegram not found",
                text = "I work with the Play Store version of Telegram. If you have it, Android may be hiding it from me.",
            )
        }

        SectionLabel("Set up")
        Column(Modifier.fillMaxWidth().panel(18.dp, c.surface, c.line).padding(horizontal = 16.dp, vertical = 6.dp)) {
            StatusRow(
                "Show over other apps", "Lets the bubble sit on top of Telegram.", overlay,
                actionLabel = "Allow",
                onAction = { ctx.open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))) },
            )
            StatusRow(
                "Read the Telegram chat",
                "An accessibility service that only listens to Telegram. It reads what is on screen and fills the reply box. It never sends.",
                a11y,
                actionLabel = "Turn on",
                onAction = { ctx.open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            )
            StatusRow(
                "Notifications", "Android shows a notice while the bubble runs, with a Stop button.", notif,
                actionLabel = "Allow", onAction = askNotifications,
            )
            TranslationRow()
            StatusRow(
                "Gemini key for Smart mode", "Optional. Without it everything runs in Private mode.", d.apiKey.isNotBlank(),
                actionLabel = "Add key", onAction = openSettings,
            )
        }

        if (!a11y) {
            Spacer(Modifier.height(12.dp))
            Notice(
                NoticeKind.Info,
                title = "Switch greyed out?",
                text = "Android 13 locks accessibility for apps installed from a file. Try the switch once, then open App info, tap the three dots at the top right and choose Allow restricted settings. After that the switch works.",
                actionLabel = "Open App info",
                onAction = { ctx.open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) },
            )
        }

        Spacer(Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (running) {
                PbButton("Stop the bubble", { BubbleService.stop(ctx) }, Modifier.fillMaxWidth(), BtnKind.Tonal)
            } else {
                PbButton("Start the bubble", { BubbleService.start(ctx) }, Modifier.fillMaxWidth(), enabled = ready)
                if (!ready) Txt("Allow the first two steps above to start.", style = T.small, color = c.muted)
            }
            if (running && telegram != null) {
                Notice(NoticeKind.Ok, "The bubble is on. Open a chat in Telegram and look for it on the screen edge.")
                PbButton("Open Telegram", { ctx.open(telegram) }, Modifier.fillMaxWidth(), BtnKind.Primary)
            }
            if (running && models != ModelState.READY && d.apiKey.isBlank()) {
                Notice(NoticeKind.Warn, "Private mode needs the offline Persian models. Download them above first.")
            }
            PbButton("Settings", openSettings, Modifier.fillMaxWidth(), BtnKind.Ghost, Ic.Gear)
        }
    }
}
