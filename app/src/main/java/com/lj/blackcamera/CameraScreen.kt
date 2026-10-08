package com.lj.blackcamera

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private val Dim = Color(0xFF7A7A7A)
private val Faint = Color(0xFF2A2A2A)
private val Panel = Color(0xFF111111)

@Composable
fun CameraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color.White, onPrimary = Color.Black,
            background = Color.Black, onBackground = Color.White,
            surface = Color.Black, onSurface = Color.White,
            surfaceVariant = Panel, onSurfaceVariant = Dim,
            surfaceContainer = Panel, surfaceContainerHigh = Panel, surfaceContainerHighest = Panel,
            outline = Dim,
        ),
    ) {
        // Text without an explicit colour defaults to this; otherwise it'd be black on black.
        CompositionLocalProvider(LocalContentColor provides Color.White, content = content)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CameraScreen(onStart: () -> Unit) {
    val ctx = LocalContext.current
    val running by Stream.running.collectAsState()
    val clients by Stream.clients.collectAsState()
    val error by Stream.error.collectAsState()
    val torch by Stream.torch.collectAsState()
    val hasTorch by Stream.hasTorch.collectAsState()
    val size by Stream.size.collectAsState()

    var lens by remember { mutableStateOf(Prefs.lens(ctx)) }
    var quality by remember { mutableStateOf(Prefs.quality(ctx)) }
    var turn by remember { mutableStateOf(Prefs.turn(ctx)) }
    var portText by remember { mutableStateOf(Prefs.port(ctx).toString()) }
    var preview by remember { mutableStateOf(true) }

    // The address can change when Wi-Fi reconnects, so keep checking.
    var ip by remember { mutableStateOf(Stream.localIp()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            ip = withContext(Dispatchers.IO) { Stream.localIp() }
            delay(3_000)
        }
    }
    val url = "http://${ip ?: "?"}:${Prefs.port(ctx)}"

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Black Camera", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp)
                .border(1.dp, Faint),
            contentAlignment = Alignment.Center
        ) {
            when {
                running && preview -> Preview()
                running -> Text("Preview off", color = Dim, modifier = Modifier.padding(40.dp))
                else -> Text("Stopped", color = Dim, modifier = Modifier.padding(40.dp))
            }
        }
        Spacer(Modifier.height(16.dp))

        if (running) {
            Text(
                url,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { copy(ctx, url) }
            )
            Text(
                buildString {
                    append(if (clients == 1) "1 viewer" else "$clients viewers")
                    if (size.isNotEmpty()) append(" · $size")
                    append(" · tap the address to copy")
                },
                color = Dim, fontSize = 13.sp
            )
            if (ip == null) Text("Not connected to Wi-Fi", color = Color(0xFFFF6B6B), fontSize = 13.sp)
        } else {
            Text("Streams the camera to your local network.", color = Dim)
        }
        error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = Color(0xFFFF6B6B), fontSize = 13.sp)
        }
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                if (running) {
                    CameraService.stop(ctx)
                } else {
                    val port = portText.toIntOrNull()
                    if (port == null || port !in 1024..65535) {
                        Stream.error.value = "Port must be 1024–65535"
                    } else {
                        Prefs.setPort(ctx, port)
                        Stream.error.value = null
                        onStart()
                    }
                }
            }) { Text(if (running) "Stop" else "Start") }
            if (running) {
                OutlinedButton(onClick = { preview = !preview }) { Text(if (preview) "Hide preview" else "Preview") }
                if (hasTorch) {
                    OutlinedButton(onClick = { CameraService.setTorch(ctx, !torch) }) {
                        Text(if (torch) "Light off" else "Light")
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))

        Setting("Camera") {
            Lens.entries.forEach {
                Choice(it.label, it == lens) {
                    lens = it
                    Prefs.setLens(ctx, it)
                    CameraService.reconfigure(ctx)
                }
            }
        }
        Setting("Quality") {
            Quality.entries.forEach {
                Choice(it.label, it == quality) {
                    quality = it
                    Prefs.setQuality(ctx, it)
                    CameraService.reconfigure(ctx)
                }
            }
        }
        Setting("Picture") {
            Turn.entries.forEach {
                Choice(it.label, it == turn) {
                    turn = it
                    Prefs.setTurn(ctx, it)
                    CameraService.reconfigure(ctx)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = portText,
            onValueChange = { portText = it.filter(Char::isDigit).take(5) },
            label = { Text("Port") },
            enabled = !running,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(140.dp)
        )
        Spacer(Modifier.height(20.dp))

        Text("On your PC (same Wi-Fi)", fontWeight = FontWeight.Bold)
        Text(
            "Browser: $url\n" +
                "VLC / OBS / Home Assistant: $url/stream\n" +
                "Single picture: $url/snapshot\n\n" +
                "Streaming keeps going with the screen locked. Stop it here or from the notification.",
            color = Dim, fontSize = 13.sp
        )
    }
}

/** The live picture, decoded from the same JPEGs the PC receives. */
@Composable
private fun Preview() {
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    DisposableEffect(Unit) {
        Stream.demand.incrementAndGet()
        onDispose { Stream.demand.decrementAndGet() }
    }
    LaunchedEffect(Unit) {
        var seq = 0L
        while (isActive) {
            val next = withContext(Dispatchers.IO) { Stream.next(seq, 500) } ?: continue
            seq = next.first
            frame = withContext(Dispatchers.Default) {
                BitmapFactory.decodeByteArray(next.second, 0, next.second.size, BitmapFactory.Options().apply { inSampleSize = 2 })
            }
            delay(66) // ~15 fps is plenty for aiming
        }
    }
    val bmp = frame
    if (bmp == null) {
        Text("Starting camera…", color = Dim, modifier = Modifier.padding(40.dp))
    } else {
        Image(
            bmp.asImageBitmap(),
            contentDescription = "Camera preview",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(bmp.width.toFloat() / bmp.height)
                .heightIn(max = 420.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Setting(title: String, choices: @Composable () -> Unit) {
    Text(title, color = Dim, fontSize = 13.sp)
    FlowRow { choices() }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            label,
            color = if (selected) Color.White else Dim,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

private fun copy(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Black Camera", text))
    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}
