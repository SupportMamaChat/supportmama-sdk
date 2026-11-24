package dev.supportmama

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.supportmama.internal.Chat
import dev.supportmama.internal.Images
import dev.supportmama.internal.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The chat screen, for Compose apps that would rather show it in their own
 * navigation than open [SupportChatActivity]. Takes its colours and type from
 * the surrounding [MaterialTheme], so it looks like the rest of your app.
 *
 * Call [SupportMama.init] first.
 *
 * @param onBack shown as the top bar's back arrow; null hides it.
 * @param draft text to start the composer with, e.g. "My order didn't arrive".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportChat(onBack: (() -> Unit)?, modifier: Modifier = Modifier, draft: String? = null) {
    val context = LocalContext.current
    val chat = remember { Chat.get(context) }
    val config = remember { chat.requireConfig() }
    val messages by chat.messages.collectAsState()
    val offline by chat.offline.collectAsState()
    val name by chat.name.collectAsState()
    val staged by chat.staged.collectAsState()
    var input by rememberSaveable { mutableStateOf(draft.orEmpty()) }
    val listState = rememberLazyListState()

    // While on screen: read, and asked for replies every few seconds.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            chat.visible = true
            chat.markSeen()
            try {
                while (true) {
                    chat.refresh()
                    delay(5_000)
                }
            } finally {
                chat.visible = false
            }
        }
    }
    // The list runs bottom-up, so item 0 is the newest and the conversation
    // stays pinned to the composer when the keyboard opens.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }
    NotificationPermission(chat, enabled = config.notifications, hasSent = messages.any { it.sent && !it.fromTeam })

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(config.title ?: stringResource(R.string.supportmama_title)) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.supportmama_ic_back), stringResource(R.string.supportmama_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
        ) {
            if (offline) {
                Text(
                    stringResource(R.string.supportmama_offline),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (messages.lastOrNull()?.let { !it.fromTeam && it.sent } == true) {
                    item(key = "waiting") {
                        Text(
                            stringResource(R.string.supportmama_waiting),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                }
                itemsIndexed(messages.asReversed(), key = { _, m -> m.localId }) { j, message ->
                    val i = messages.lastIndex - j
                    val firstOfRun = i == 0 || messages[i - 1].fromTeam != message.fromTeam
                    if (message.fromTeam) TeamBubble(message, if (firstOfRun) config.teamLabel ?: stringResource(R.string.supportmama_team) else null)
                    else UserBubble(message) { chat.retry(message.localId) }
                }
                item(key = "intro") {
                    val viewport = with(LocalDensity.current) { listState.layoutInfo.viewportSize.height.toDp() }
                    // Alone, the introduction fills the screen and centres; once
                    // there is a conversation it is just its first lines.
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .then(if (messages.isEmpty()) Modifier.heightIn(min = (viewport - 16.dp).coerceAtLeast(0.dp)) else Modifier)
                            .padding(bottom = 12.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(config.headline ?: stringResource(R.string.supportmama_headline), style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            config.intro ?: stringResource(R.string.supportmama_intro),
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (messages.isEmpty()) {
                            if (config.askForName) {
                                Spacer(Modifier.height(20.dp))
                                OutlinedTextField(
                                    value = name,
                                    onValueChange = chat::setName,
                                    singleLine = true,
                                    label = { Text(stringResource(R.string.supportmama_name_hint)) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            config.email?.let { email ->
                                val uri = LocalUriHandler.current
                                TextButton(onClick = { runCatching { uri.openUri("mailto:$email") } }, modifier = Modifier.padding(top = 8.dp)) {
                                    Text(stringResource(R.string.supportmama_email, email))
                                }
                            }
                        }
                    }
                }
            }

            staged?.let { path ->
                Box(Modifier.padding(start = 16.dp, top = 4.dp)) {
                    Thumb(path, 72.dp)
                    Surface(
                        shape = CircleShape, color = MaterialTheme.colorScheme.inverseSurface,
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clickable { chat.removeAttachment() },
                    ) {
                        Icon(
                            painterResource(R.drawable.supportmama_ic_close), stringResource(R.string.supportmama_remove_screenshot),
                            tint = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.padding(4.dp),
                        )
                    }
                }
            }
            Composer(
                input = input,
                onInput = { input = it },
                canSend = input.isNotBlank() || staged != null,
                onSend = { chat.send(input); input = "" },
                onPicked = if (config.screenshots) chat::attach else null,
            )
        }
    }
}

@Composable
private fun Composer(input: String, onInput: (String) -> Unit, canSend: Boolean, onSend: () -> Unit, onPicked: ((android.net.Uri) -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (onPicked != null) {
            val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(onPicked) }
            IconButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Icon(painterResource(R.drawable.supportmama_ic_image), stringResource(R.string.supportmama_attach_screenshot))
            }
        }
        TextField(
            value = input,
            onValueChange = { onInput(it.take(4000)) },
            placeholder = { Text(stringResource(R.string.supportmama_placeholder)) },
            maxLines = 6,
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(8.dp))
        FilledIconButton(
            onClick = onSend,
            enabled = canSend,
            modifier = Modifier.size(48.dp).padding(bottom = 4.dp),
            colors = IconButtonDefaults.filledIconButtonColors(),
        ) {
            Icon(painterResource(R.drawable.supportmama_ic_send), stringResource(R.string.supportmama_send))
        }
    }
}

@Composable
private fun UserBubble(message: Message, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        val image = message.image?.takeIf { File(it).exists() }
        val dim = if (message.sent) 1f else 0.6f
        if (image != null) {
            Box(Modifier.alpha(dim).padding(bottom = if (message.text.isEmpty()) 0.dp else 4.dp)) { Thumb(image, 160.dp) }
        }
        // The picture is gone from this phone: say one was sent rather than show nothing.
        val text = if (message.hasImage && image == null) {
            listOf(stringResource(R.string.supportmama_screenshot_sent), message.text).filter { it.isNotEmpty() }.joinToString("\n")
        } else message.text
        if (text.isNotEmpty()) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .alpha(dim)
                    .clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        when {
            message.failed -> Text(
                stringResource(R.string.supportmama_failed),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.clickable(onClick = onRetry).padding(top = 4.dp, bottom = 2.dp),
            )
            !message.sent -> Text(
                stringResource(R.string.supportmama_sending),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun TeamBubble(message: Message, label: String?) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        if (label != null) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 4.dp),
            )
        }
        Text(
            message.text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun Thumb(path: String, size: Dp) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState<android.graphics.Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { Images.thumbnail(path, px * 2) }
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        bitmap?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

/**
 * Android 13+ needs permission for the reply notification. Asked once, right
 * after the first message goes out, when the reason is obvious.
 */
@Composable
private fun NotificationPermission(chat: Chat, enabled: Boolean, hasSent: Boolean) {
    if (!enabled || Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(hasSent) {
        if (!hasSent || chat.askedNotifications) return@LaunchedEffect
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return@LaunchedEffect
        chat.askedNotifications = true
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
