package com.hermes.mobile.ui.connect

import android.annotation.SuppressLint
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.hermes.mobile.core.connection.ConnState
import com.hermes.mobile.core.net.DiscoveredPc
import com.hermes.mobile.core.net.LinkKind
import com.hermes.mobile.ui.components.IndeterminateAccentBar
import com.hermes.mobile.ui.components.NavRow
import com.hermes.mobile.ui.components.PhaseTicker
import com.hermes.mobile.ui.components.RadarSweep
import com.hermes.mobile.ui.components.ThinkingOrb
import com.hermes.mobile.ui.theme.HermesMono
import com.hermes.mobile.ui.theme.revealOnEnter
import java.util.concurrent.Executors

/**
 * Pairing — the first thirty seconds of the app, and the only screen a user
 * meets before they trust it with a credential.
 *
 * The viewfinder is the page, not a widget on it: there is exactly one thing
 * to do here, and the manual form is a fallback behind a link rather than a
 * competing column of fields.
 */
/** Which pairing route the user is on. Exactly one is on screen at a time. */
private enum class PairMode { DISCOVER, SCAN, MANUAL }

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ConnectScreen(vm: ConnectViewModel = hiltViewModel()) {
    val state by vm.connState.collectAsState()
    val profiles by vm.profiles.collectAsState()
    val discovered by vm.discovered.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val link by vm.link.collectAsState()
    var mode: PairMode by remember { mutableStateOf(PairMode.DISCOVER) }
    var prefillHost by remember { mutableStateOf("") }
    var prefillPort by remember { mutableStateOf("9119") }

    // The overwhelmingly common case is "PC is on the same Wi-Fi, dashboard is
    // running". Sweeping on arrival makes that path one tap; the QR is only
    // needed to carry the credential the first time.
    LaunchedEffect(link) {
        if (link == LinkKind.WIFI || link == LinkKind.ETHERNET) vm.scanLan()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .systemBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(36.dp))
        // The hero: the mark settles, the title follows, then the promise.
        // A first screen that assembles itself reads as an app that is ready
        // for you; one that is simply there reads as a static form.
        Box(Modifier.revealOnEnter(0)) { ThinkingOrb(size = 54.dp) }
        Spacer(Modifier.height(4.dp))
        Text(
            "Hermes",
            style = MaterialTheme.typography.displayMedium,
            modifier = Modifier.revealOnEnter(1),
        )
        Text(
            "Your PC, in your pocket.",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.revealOnEnter(2),
        )
        Text(
            when (mode) {
                PairMode.DISCOVER -> "Your PC and phone need to be on the same Wi-Fi."
                PairMode.SCAN -> "Point the camera at the pairing QR on your PC."
                PairMode.MANUAL -> "Enter your PC's address and dashboard credential."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.revealOnEnter(3),
        )

        StateBanner(state, onRetry = vm::retry, onForget = vm::forgetCurrentProfile)

        if (link == LinkKind.CELLULAR || link == LinkKind.NONE) {
            OfflineHint(link)
        }

        // One mode at a time. The camera preview is its own hardware surface
        // and composites above ordinary content, so showing it alongside the
        // discovery card cut that card in half on a real device. Separate
        // modes also match how pairing actually goes: find the PC first, and
        // only reach for the QR to carry the credential.
        when (mode) {
            PairMode.DISCOVER -> {
                DiscoveryPane(
                    found = discovered,
                    scanning = scanning,
                    enabled = link == LinkKind.WIFI || link == LinkKind.ETHERNET,
                    onScan = vm::scanLan,
                    onPick = { pc ->
                        vm.onDiscoveredPicked(pc) { needy ->
                            prefillHost = needy.host
                            prefillPort = needy.port.toString()
                            mode = PairMode.MANUAL
                        }
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { mode = PairMode.SCAN },
                        modifier = Modifier.heightIn(min = 48.dp).semantics {
                            contentDescription = "Scan the pairing QR code"
                        },
                    ) { Text("Scan QR") }
                    TextButton(
                        onClick = { mode = PairMode.MANUAL },
                        modifier = Modifier.heightIn(min = 48.dp).semantics {
                            contentDescription = "Enter connection details manually"
                        },
                    ) { Text("Type details") }
                }
            }

            PairMode.SCAN -> {
                QrScannerPane(onPayload = vm::onQrScanned)
                TextButton(
                    onClick = { mode = PairMode.DISCOVER },
                    modifier = Modifier.heightIn(min = 48.dp).semantics {
                        contentDescription = "Back to finding your PC on Wi-Fi"
                    },
                ) { Text("Back") }
            }

            PairMode.MANUAL -> {
                ManualEntryPane(
                    initialHost = prefillHost,
                    initialPort = prefillPort,
                    onSubmit = vm::onManualSubmit,
                )
                TextButton(
                    onClick = { mode = PairMode.DISCOVER },
                    modifier = Modifier.heightIn(min = 48.dp).semantics {
                        contentDescription = "Back to finding your PC on Wi-Fi"
                    },
                ) { Text("Back") }
            }
        }

        // A previously paired PC that simply isn't reachable right now should be
        // one tap to retry, not a re-pair from scratch.
        if (profiles.isNotEmpty()) {
            Text(
                "Already paired",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            profiles.forEach { profile ->
                NavRow(
                    title = profile.label,
                    subtitle = profile.displayAddress,
                    onClick = { vm.connectToSaved(profile.id) },
                )
                Spacer(Modifier.height(4.dp))
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun StateBanner(state: ConnState, onRetry: () -> Unit, onForget: () -> Unit) {
    AnimatedVisibility(state !is ConnState.NoProfile) {
        when (state) {
            is ConnState.Connecting -> Working(
                phases = listOf(
                    "Reaching ${state.profile.label}…",
                    "Signing in…",
                    "Opening the socket…",
                ),
                detail = state.profile.displayAddress,
            )
            is ConnState.Probing -> Working(
                phases = listOf("Looking for your PC…", "Sweeping your Wi-Fi…"),
            )
            is ConnState.Reconnecting -> Working(
                phases = listOf(
                    "Reconnecting — attempt ${state.attempt}",
                    "Holding the link open…",
                ),
            )
            is ConnState.Failed -> Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Couldn't connect", style = MaterialTheme.typography.titleSmall)
                    Text(state.message, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onRetry,
                            modifier = Modifier.heightIn(min = 48.dp).semantics {
                                contentDescription = "Retry connecting"
                            },
                        ) { Text("Retry") }
                        if (state.profile != null) {
                            TextButton(
                                onClick = onForget,
                                modifier = Modifier.heightIn(min = 48.dp).semantics {
                                    contentDescription = "Forget this PC and pair again"
                                },
                            ) { Text("Pair again") }
                        }
                    }
                }
            }
            else -> Unit
        }
    }
}

/**
 * A wait that looks alive: the orb keeps working, the label names the real
 * phase and advances through them, and an accent line travels underneath.
 * All three are honest — nothing here claims a percentage it cannot know.
 */
@Composable
private fun Working(phases: List<String>, detail: String? = null) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ThinkingOrb(size = 30.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    PhaseTicker(
                        phases = phases,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    detail?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = HermesMono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            IndeterminateAccentBar()
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun QrScannerPane(onPayload: (String) -> Unit) {
    val cameraPermission = rememberPermissionState(android.Manifest.permission.CAMERA)
    if (cameraPermission.status.isGranted) {
        CameraPreviewBox(onPayload)
        return
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Outlined.QrCodeScanner,
                contentDescription = null,
                Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                if (cameraPermission.status.shouldShowRationale)
                    "The camera is only used to read the pairing QR. Nothing is " +
                        "recorded, stored, or uploaded."
                else "Pairing reads a QR code, so the camera is needed once.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { cameraPermission.launchPermissionRequest() },
                modifier = Modifier.heightIn(min = 48.dp).semantics {
                    contentDescription = "Grant camera permission"
                },
            ) { Text("Allow camera") }
        }
    }
}

@SuppressLint("UnsafeOptInUsageError")
@Composable
private fun CameraPreviewBox(onPayload: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { analysisExecutor.shutdown() } }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(Color.Black, RoundedCornerShape(20.dp)),
    ) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                // SurfaceView (the default) is a separate hardware layer that
                // draws ON TOP of Compose content regardless of z-order, so it
                // punched through the discovery card sitting above it.
                // COMPATIBLE renders into a TextureView, which composes normally.
                previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val scanner = BarcodeScanning.getClient()
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        val mediaImage = imageProxy.image
                        if (mediaImage == null) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        val image = InputImage.fromMediaImage(
                            mediaImage, imageProxy.imageInfo.rotationDegrees,
                        )
                        scanner.process(image)
                            .addOnSuccessListener { barcodes ->
                                barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }
                                    ?.rawValue
                                    ?.let(onPayload)
                            }
                            .addOnCompleteListener { imageProxy.close() }
                    }
                    runCatching {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = "Camera viewfinder for QR pairing" },
        )
        Reticle(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.62f)
                .aspectRatio(1f),
        )
    }
}

/**
 * Four corner marks rather than a full rectangle: it says where to hold the
 * code without covering the part of the frame the scanner needs to read.
 *
 * Two things move: the arms breathe in and out so the reticle reads as armed
 * rather than printed, and a scanning line travels top-to-bottom inside the
 * frame so the wait for a lock has a direction. Both are slow — a busy
 * reticle is harder to aim with, not easier.
 */
@Composable
private fun Reticle(modifier: Modifier) {
    val tint = MaterialTheme.colorScheme.primary
    val t = rememberInfiniteTransition(label = "reticle")
    val scan by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "scan-line",
    )
    val settle by t.animateFloat(
        0.75f, 1f,
        infiniteRepeatable(tween(1500, easing = androidx.compose.animation.core.FastOutSlowInEasing), RepeatMode.Reverse),
        label = "settle",
    )
    Canvas(modifier) {
        val arm = size.minDimension * 0.22f
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        val w = size.width
        val h = size.height
        val lit = tint.copy(alpha = 0.45f + 0.55f * settle)
        listOf(
            // (origin, horizontal arm end, vertical arm end) per corner
            Triple(Offset(0f, 0f), Offset(arm, 0f), Offset(0f, arm)),
            Triple(Offset(w, 0f), Offset(w - arm, 0f), Offset(w, arm)),
            Triple(Offset(0f, h), Offset(arm, h), Offset(0f, h - arm)),
            Triple(Offset(w, h), Offset(w - arm, h), Offset(w, h - arm)),
        ).forEach { (corner, horizontal, vertical) ->
            drawLine(lit, corner, horizontal, strokeWidth = stroke.width, cap = stroke.cap)
            drawLine(lit, corner, vertical, strokeWidth = stroke.width, cap = stroke.cap)
        }
        // The scan line: a soft warm band travelling down the frame.
        val y = h * scan
        drawRect(
            brush = Brush.verticalGradient(
                listOf(
                    Color.Transparent,
                    tint.copy(alpha = 0.28f),
                    Color.Transparent,
                ),
                startY = y - h * 0.10f,
                endY = y + h * 0.10f,
            ),
            topLeft = Offset(0f, (y - h * 0.10f).coerceAtLeast(0f)),
            size = androidx.compose.ui.geometry.Size(w, h * 0.20f),
        )
    }
}

@Composable
private fun ManualEntryPane(
    initialHost: String = "",
    initialPort: String = "9119",
    onSubmit: (label: String, host: String, port: Int?, secret: String, secure: Boolean) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var host by remember(initialHost) { mutableStateOf(initialHost) }
    var port by remember(initialPort) { mutableStateOf(initialPort) }
    var secret by remember { mutableStateOf("") }
    var secure by remember { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("PC address") },
            supportingText = { Text("Your PC's Wi-Fi address, e.g. 192.168.1.x") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "PC address" },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(120.dp),
            )
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = secret,
            onValueChange = { secret = it },
            label = { Text("Credential") },
            supportingText = {
                Text(
                    "A dashboard session token, or user:password for a gated " +
                        "dashboard. Which one applies is detected for you.",
                )
            },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Session token or user:password" },
        )
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Use HTTPS", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Leave off for a PC on your own Wi-Fi. Turn it on only if the " +
                        "dashboard sits behind a TLS proxy or a tunnel.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = secure,
                onCheckedChange = { secure = it },
                modifier = Modifier.semantics { contentDescription = "Use HTTPS and WSS" },
            )
        }
        Button(
            onClick = { onSubmit(label, host, port.toIntOrNull(), secret, secure) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .semantics { contentDescription = "Connect to this PC" },
        ) { Text("Connect") }
        Text(
            "ws${if (secure) "s" else ""}://${host.ifBlank { "…" }}:${port.ifBlank { "9119" }}/api/ws",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = HermesMono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Wi-Fi discovery.
 *
 * A home router hands the PC a DHCP lease, and that address changes. Pairing
 * that only accepts a QR turns every lease change into a re-pair; sweeping the
 * phone's own /24 for `/api/status` finds the PC wherever it landed.
 */
@Composable
private fun DiscoveryPane(
    found: List<DiscoveredPc>,
    scanning: Boolean,
    enabled: Boolean,
    onScan: () -> Unit,
    onPick: (DiscoveredPc) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Wifi,
                    contentDescription = null,
                    Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Text("On this Wi-Fi", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.weight(1f))
                if (found.isNotEmpty()) {
                    Text(
                        "${found.size} found",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (found.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // The one wait in the app that is genuinely a *search*, so
                    // it gets the one spatial metaphor: a radar. It freezes
                    // when nothing is sweeping, so it never lies about work.
                    RadarSweep(active = scanning)
                    Text(
                        if (scanning) "Sweeping your Wi-Fi…"
                        else "No Hermes found yet. Start the dashboard on your PC, then scan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            } else {
                found.forEachIndexed { i, pc ->
                    Box(Modifier.revealOnEnter(i)) {
                        NavRow(
                            title = pc.label.ifBlank { "Hermes" },
                            subtitle = pc.host + ":" + pc.port +
                                (if (pc.status.authRequired) " - needs login" else ""),
                            onClick = { onPick(pc) },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }

            OutlinedButton(
                onClick = onScan,
                enabled = enabled && !scanning,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "Scan this Wi-Fi for your PC" },
            ) { Text(if (found.isEmpty()) "Find my PC" else "Scan again") }
        }
    }
}

/** Says the one thing that actually fixes it, rather than "no connection". */
@Composable
private fun OfflineHint(link: LinkKind) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            if (link == LinkKind.CELLULAR)
                "You're on mobile data. Hermes Remote reaches your PC over your " +
                    "home Wi-Fi - connect to the same network as the PC."
            else "No network. Join the Wi-Fi your PC is on.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(14.dp),
        )
    }
}

/**
 * Pair ANOTHER system while one is already connected.
 *
 * The full pairing screen only renders pre-connection, so adding foundry next
 * to the PC used to mean unpairing first and losing the other credential.
 * This dialog hosts the same scanner / manual panes against the same
 * ConnectViewModel; it closes as soon as the new profile is in the vault.
 */
@Composable
fun AddSystemDialog(
    onDismiss: () -> Unit,
    vm: ConnectViewModel = hiltViewModel(),
) {
    val profiles by vm.profiles.collectAsState()
    val state by vm.connState.collectAsState()
    val initialCount = remember { profiles.size }
    var manual by remember { mutableStateOf(false) }

    LaunchedEffect(profiles.size) {
        if (profiles.size > initialCount) onDismiss()
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Pair another system", style = MaterialTheme.typography.titleMedium)
                (state as? ConnState.Failed)?.let { failed ->
                    Text(
                        failed.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (manual) {
                    ManualEntryPane(
                        initialHost = "",
                        initialPort = "9119",
                        onSubmit = vm::onManualSubmit,
                    )
                } else {
                    QrScannerPane(onPayload = vm::onQrScanned)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { manual = !manual }) {
                        Text(if (manual) "Scan QR instead" else "Type details instead")
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}
