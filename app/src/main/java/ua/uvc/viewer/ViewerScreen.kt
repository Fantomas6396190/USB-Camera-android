package ua.uvc.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.widget.ImageView
import java.nio.ByteBuffer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

private val Ink = Color(0xFF0E1110)
private val Panel = Color(0xE61A1F1C)
private val Amber = Color(0xFFE6A15C)
private val Mist = Color(0xFFE7EEE9)
private val Muted = Color(0xFFB7C2BB)

private enum class Sheet { None, Modes, Picture }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    viewModel: UvcViewModel,
    onAllow: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf(Sheet.None) }
    var fullscreen by remember { mutableStateOf(false) }
    var reticle by remember { mutableStateOf(false) }
    val context = LocalContext.current

    DisposableEffect(state.phase) {
        val window = (context as? android.app.Activity)?.window
        if (state.phase == Phase.Preview && window != null) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(state.notice?.id) {
        if (state.notice != null) {
            delay(4200)
            viewModel.dismissNotice()
        }
    }

    LaunchedEffect(state.phase) {
        if (state.phase != Phase.Preview) fullscreen = false
    }

    DisposableEffect(fullscreen) {
        val window = (context as? android.app.Activity)?.window
        val controller = window?.let {
            WindowCompat.getInsetsController(it, it.decorView)
        }
        if (fullscreen) {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink),
    ) {
        val quarterTurn = state.rotation == 90 || state.rotation == 270
        PreviewSurface(
            width = state.previewWidth,
            height = state.previewHeight,
            rotation = state.rotation,
            mirrored = state.mirrored,
            swapAspect = quarterTurn,
            visible = state.phase == Phase.Preview || state.phase == Phase.Opening,
            onFrameSink = { sink -> viewModel.onVideoFrame = sink },
        )

        if (state.phase != Phase.Preview) {
            StatusMessage(
                phase = state.phase,
                deviceFound = state.devices.isNotEmpty() || state.phase == Phase.Permission,
                onAllow = onAllow,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (reticle && state.phase == Phase.Preview) {
            SightMark(modifier = Modifier.fillMaxSize())
        }

        if (state.phase == Phase.Preview) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(fullscreen) {
                        detectTapGestures(onDoubleTap = {
                            fullscreen = !fullscreen
                            if (fullscreen) sheet = Sheet.None
                        })
                    },
            )
        }

        if (!fullscreen) Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Panel)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.deviceLabel ?: stringResource(R.string.app_name),
                        color = Mist,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                    )
                    if (state.modeLabel != null) {
                        Text(text = state.modeLabel.orEmpty(), color = Amber, fontSize = 13.sp)
                    }
                }
                if (state.devices.size > 1) {
                    DeviceMenu(state.devices, viewModel::selectDevice)
                }
                TextButton(onClick = viewModel::rotate, enabled = state.phase == Phase.Preview) {
                    Text(stringResource(R.string.rotate), color = Mist)
                }
                TextButton(onClick = viewModel::toggleMirror, enabled = state.phase == Phase.Preview) {
                    Text(
                        text = stringResource(if (state.mirrored) R.string.mirror_on else R.string.mirror),
                        color = Mist,
                    )
                }
                TextButton(
                    onClick = { reticle = !reticle },
                    enabled = state.phase == Phase.Preview,
                ) {
                    Text(
                        text = stringResource(if (reticle) R.string.reticle_on else R.string.reticle),
                        color = if (reticle) Amber else Mist,
                    )
                }
            }
            state.notice?.let { notice ->
                Spacer(Modifier.height(6.dp))
                Text(text = notice.text, color = Amber, fontSize = 13.sp)
            }
        }

        if (!fullscreen) Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Panel)
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val ready = state.phase == Phase.Preview
            ActionButton(
                label = stringResource(R.string.format),
                enabled = ready && state.modes.isNotEmpty(),
                onClick = { sheet = Sheet.Modes },
                modifier = Modifier.weight(1f),
            )
            ActionButton(
                label = stringResource(R.string.picture),
                enabled = ready,
                onClick = { sheet = Sheet.Picture },
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (sheet != Sheet.None && !fullscreen) {
        ModalBottomSheet(
            onDismissRequest = { sheet = Sheet.None },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color(0xFF171C19),
            contentColor = Mist,
        ) {
            when (sheet) {
                Sheet.Modes -> ModeSheet(state.modes) { type, width, height, fps ->
                    viewModel.selectMode(type, width, height, fps)
                    sheet = Sheet.None
                }
                Sheet.Picture -> PictureSheet(
                    controls = state.controls,
                    onSlider = viewModel::setSlider,
                    onToggle = viewModel::setToggle,
                    onChoice = viewModel::setChoice,
                    onReset = viewModel::resetControls,
                )
                Sheet.None -> Unit
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PreviewSurface(
    width: Int,
    height: Int,
    rotation: Int,
    mirrored: Boolean,
    swapAspect: Boolean,
    visible: Boolean,
    onFrameSink: ((ByteArray, Int, Int) -> Unit) -> Unit,
) {
    if (!visible) return
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val maxW = with(density) { maxWidth.toPx() }
        val maxH = with(density) { maxHeight.toPx() }
        val frameW = if (swapAspect) height else width
        val frameH = if (swapAspect) width else height
        val ratio = if (frameW > 0 && frameH > 0) frameW.toFloat() / frameH else maxW / maxH
        val viewRatio = if (maxH == 0f) ratio else maxW / maxH
        val (fitW, fitH) = if (swapAspect) {
            maxW to maxW / ratio
        } else if (ratio > viewRatio) {
            maxW to maxW / ratio
        } else {
            maxH * ratio to maxH
        }
        AndroidView(
            modifier = Modifier.requiredSize(
                with(density) { fitW.toDp() },
                with(density) { fitH.toDp() },
            ),
            factory = { context ->
                FrameView(context).also { view ->
                    onFrameSink(view::submit)
                }
            },
            update = { view ->
                view.rotation = 0f
                view.setPictureRotation(rotation)
                view.setPictureMirrored(mirrored)
            },
        )
    }
}

private class FrameView(context: android.content.Context) : ImageView(context) {
    private var bitmap: Bitmap? = null
    private var pictureRotation = 0
    private var pictureMirrored = false
    private val src = Rect()
    private val dst = RectF()

    init {
        scaleType = ScaleType.FIT_XY
        setBackgroundColor(android.graphics.Color.BLACK)
    }

    fun setPictureRotation(degrees: Int) {
        val next = ((degrees % 360) + 360) % 360
        if (next == pictureRotation) return
        pictureRotation = next
        invalidate()
    }

    fun setPictureMirrored(mirrored: Boolean) {
        if (mirrored == pictureMirrored) return
        pictureMirrored = mirrored
        invalidate()
    }

    fun submit(rgb565: ByteArray, width: Int, height: Int) {
        if (width <= 0 || height <= 0 || rgb565.size < width * height * 2) return
        runCatching {
            var current = bitmap
            if (current == null || current.width != width || current.height != height) {
                current = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
                bitmap = current
            }
            current.copyPixelsFromBuffer(ByteBuffer.wrap(rgb565))
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val current = bitmap ?: return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return
        val quarter = pictureRotation == 90 || pictureRotation == 270
        val destW = if (quarter) viewH else viewW
        val destH = if (quarter) viewW else viewH
        src.set(0, 0, current.width, current.height)
        dst.set(-destW / 2f, -destH / 2f, destW / 2f, destH / 2f)
        canvas.save()
        canvas.translate(viewW / 2f, viewH / 2f)
        if (pictureMirrored) canvas.scale(-1f, 1f)
        canvas.rotate(pictureRotation.toFloat())
        canvas.drawBitmap(current, src, dst, null)
        canvas.restore()
    }
}

@Composable
private fun SightMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val gap = 10f
        val mark = 18f
        val marks = arrayOf(
            Offset(center.x - gap - mark, center.y) to Offset(center.x - gap, center.y),
            Offset(center.x + gap, center.y) to Offset(center.x + gap + mark, center.y),
            Offset(center.x, center.y - gap - mark) to Offset(center.x, center.y - gap),
            Offset(center.x, center.y + gap) to Offset(center.x, center.y + gap + mark),
        )
        marks.forEach { (start, end) ->
            drawLine(Color.Black, start, end, strokeWidth = 4f, cap = StrokeCap.Butt)
            drawLine(Color.White, start, end, strokeWidth = 2f, cap = StrokeCap.Butt)
        }
    }
}

@Composable
private fun StatusMessage(
    phase: Phase,
    deviceFound: Boolean,
    onAllow: () -> Unit,
    modifier: Modifier,
) {
    val waiting = phase == Phase.Opening
    val title = when {
        waiting -> stringResource(R.string.app_name)
        deviceFound -> stringResource(R.string.found_title)
        phase == Phase.Error -> stringResource(R.string.empty_title)
        else -> stringResource(R.string.empty_title)
    }
    val body = when {
        waiting -> stringResource(R.string.opening_body)
        deviceFound -> stringResource(R.string.found_body)
        else -> stringResource(R.string.empty_body)
    }
    Column(
        modifier = modifier.padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Mist, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(body, color = Muted, fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 21.sp)
        if (deviceFound && !waiting) {
            Spacer(Modifier.height(22.dp))
            Button(
                onClick = onAllow,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink),
            ) {
                Text(
                    stringResource(R.string.allow_access),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = Amber,
    content: Color = Ink,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = Color(0xFF2A312D),
            disabledContentColor = Color(0xFF8B968F),
        ),
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun DeviceMenu(devices: List<DeviceUi>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(stringResource(R.string.devices), color = Mist)
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            devices.forEach { device ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(device.label) },
                    onClick = {
                        open = false
                        onSelect(device.id)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeSheet(modes: List<ModeUi>, onPick: (Int, Int, Int, Int) -> Unit) {
    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(stringResource(R.string.format), color = Mist, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.format_hint), color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        modes.forEach { mode ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(mode.title, color = Mist, fontWeight = FontWeight.Medium)
                Text(mode.format, color = Amber, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    mode.fps.forEach { fps ->
                        FilterChip(
                            selected = mode.activeFps == fps,
                            onClick = { onPick(mode.type, mode.width, mode.height, fps) },
                            label = { Text("$fps") },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PictureSheet(
    controls: List<ControlUi>,
    onSlider: (String, Int) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onChoice: (String, Int) -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier
            .heightIn(max = 560.dp)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.picture),
                color = Mist,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (controls.isNotEmpty()) {
                TextButton(onClick = onReset) {
                    Text(stringResource(R.string.reset), color = Amber)
                }
            }
        }
        if (controls.isEmpty()) {
            Text(stringResource(R.string.no_controls), color = Muted)
            return
        }
        controls.forEach { control ->
            when (control) {
                is ControlUi.Slider -> ControlSlider(control, onSlider)
                is ControlUi.Toggle -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(control.title, color = Mist, modifier = Modifier.weight(1f))
                        Switch(checked = control.value, onCheckedChange = { onToggle(control.id, it) })
                    }
                    Spacer(Modifier.height(8.dp))
                }
                is ControlUi.Choice -> {
                    Text(control.title, color = Mist, fontSize = 14.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        control.options.forEach { (value, label) ->
                            FilterChip(
                                selected = control.value == value,
                                onClick = { onChoice(control.id, value) },
                                label = { Text(label) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun ControlSlider(control: ControlUi.Slider, onSlider: (String, Int) -> Unit) {
    var value by remember(control.id, control.value) { mutableStateOf(control.value.toFloat()) }
    Text(control.title, color = if (control.enabled) Mist else Muted, fontSize = 14.sp)
    Slider(
        value = value,
        onValueChange = { value = it },
        onValueChangeFinished = { onSlider(control.id, value.toInt()) },
        valueRange = 0f..100f,
        enabled = control.enabled,
    )
}
