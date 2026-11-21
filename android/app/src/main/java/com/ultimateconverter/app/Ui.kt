@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.ultimateconverter.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val APP_VERSION = "1.0"

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7C8CFF),
    onPrimary = Color(0xFF0E1030),
    primaryContainer = Color(0xFF2D3370),
    onPrimaryContainer = Color(0xFFDDE0FF),
    secondaryContainer = Color(0xFF343A66),
    onSecondaryContainer = Color(0xFFE0E3FF),
    background = Color(0xFF14151C),
    onBackground = Color(0xFFE8E9F0),
    surface = Color(0xFF1C1E28),
    onSurface = Color(0xFFE8E9F0),
    surfaceVariant = Color(0xFF262938),
    onSurfaceVariant = Color(0xFFA3A8C0),
    outline = Color(0xFF4A4E66),
    error = Color(0xFFE5636B),
)

private val GoodGreen = Color(0xFF54C98A)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}

@Composable
fun ConverterApp(vm: ConverterViewModel) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val cs = MaterialTheme.colorScheme

    // keep the screen awake while a batch runs (conversion stops if the app is closed)
    DisposableEffect(vm.converting) {
        view.keepScreenOn = vm.converting
        onDispose { view.keepScreenOn = false }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        vm.addUris(uris)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.addUris(uris)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: Exception) {
                // still usable for this session
            }
            vm.setOutputTree(uri)
        }
    }
    var preview by remember { mutableStateOf<FileJob?>(null) }

    val addPhotos = {
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    }
    val addFiles = { filePicker.launch(arrayOf("image/*", "video/*", "application/pdf")) }

    Scaffold(
        containerColor = cs.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("Ultimate Converter", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Text("v$APP_VERSION", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
            )
        },
        bottomBar = { BottomBar(vm) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                if (vm.jobs.isEmpty()) {
                    HeroCard(onPhotos = { addPhotos() }, onFiles = { addFiles() })
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(
                            onClick = { addPhotos() },
                            enabled = !vm.converting,
                            modifier = Modifier.weight(1f).height(52.dp),
                        ) { Text("＋ Photos & videos") }
                        FilledTonalButton(
                            onClick = { addFiles() },
                            enabled = !vm.converting,
                            modifier = Modifier.weight(1f).height(52.dp),
                        ) { Text("＋ Files / PDFs") }
                    }
                }
            }
            items(vm.jobs, key = { it.id }) { job ->
                JobCard(vm, job, onOpenPreview = { preview = job })
            }
            if (vm.jobs.isNotEmpty()) {
                item {
                    TextButton(onClick = { vm.clearAll() }, enabled = !vm.converting) { Text("Clear all") }
                }
            }
            item { OptionsCard(vm, onChooseFolder = { folderPicker.launch(null) }) }
        }
    }

    preview?.let { job -> PreviewDialog(vm, job, onDismiss = { preview = null }) }
}

// ---------------------------------------------------------------------------------------------
@Composable
private fun HeroCard(onPhotos: () -> Unit, onFiles: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = cs.surface), shape = RoundedCornerShape(24.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("🔁", fontSize = 48.sp)
            Spacer(Modifier.height(8.dp))
            Text("Convert photos, videos & PDFs", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "Pick your files, choose a format, tap Convert.",
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onPhotos, modifier = Modifier.fillMaxWidth().height(60.dp)) {
                Text("Photos & videos", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(onClick = onFiles, modifier = Modifier.fillMaxWidth().height(60.dp)) {
                Text("Files (PDFs and others)", fontSize = 17.sp)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Tip: in your Gallery, tap Share and choose Ultimate Converter.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
@Composable
private fun JobCard(vm: ConverterViewModel, job: FileJob, onOpenPreview: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val thumb = remember(job.thumbBase, job.rotation) {
        job.thumbBase?.let { MediaIO.rotate(it, job.rotation).asImageBitmap() }
    }

    Card(
        onClick = onOpenPreview,
        colors = CardDefaults.cardColors(containerColor = cs.surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(cs.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (thumb != null) {
                        Image(
                            bitmap = thumb,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(
                            when (job.kind) {
                                Kind.IMAGE -> "🖼"
                                Kind.VIDEO -> "🎞"
                                Kind.PDF -> "📄"
                            },
                            fontSize = 28.sp,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        job.name,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Medium,
                    )
                    if (job.info.isNotEmpty()) {
                        Text(job.info, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    val statusText: String
                    val statusColor: Color
                    when (job.state) {
                        JobState.QUEUED -> {
                            statusText = if (job.rotation != 0) "Rotated ${job.rotation}°" else ""
                            statusColor = cs.onSurfaceVariant
                        }
                        JobState.CONVERTING -> { statusText = "Converting…"; statusColor = cs.primary }
                        JobState.DONE -> { statusText = "✓ Done"; statusColor = GoodGreen }
                        JobState.ERROR -> { statusText = job.message; statusColor = cs.error }
                        JobState.SKIPPED -> { statusText = job.message; statusColor = cs.onSurfaceVariant }
                        JobState.CANCELLED -> { statusText = "Cancelled"; statusColor = cs.onSurfaceVariant }
                    }
                    if (statusText.isNotEmpty()) {
                        Text(statusText, style = MaterialTheme.typography.bodySmall, color = statusColor)
                    }
                    if (job.state == JobState.DONE && job.outputs.isNotEmpty()) {
                        TextButton(
                            onClick = { openFile(ctx, job.outputs.first()) },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.heightIn(min = 40.dp),
                        ) { Text("Open result") }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (job.kind == Kind.IMAGE) {
                    SmallButton("↺", enabled = !vm.converting) { vm.rotate(job, 270) }
                    SmallButton("↻", enabled = !vm.converting) { vm.rotate(job, 90) }
                }
                if (vm.reorderable) {
                    SmallButton("▲", enabled = !vm.converting) { vm.move(job, -1) }
                    SmallButton("▼", enabled = !vm.converting) { vm.move(job, 1) }
                }
                Spacer(Modifier.weight(1f))
                SmallButton("✕", enabled = !vm.converting) { vm.remove(job) }
            }
        }
    }
}

@Composable
private fun SmallButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(width = 56.dp, height = 48.dp),
        contentPadding = PaddingValues(0.dp),
    ) { Text(label, fontSize = 20.sp) }
}

// ---------------------------------------------------------------------------------------------
@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun OptionsCard(vm: ConverterViewModel, onChooseFolder: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val target = vm.target

    Card(colors = CardDefaults.cardColors(containerColor = cs.surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionTitle("Convert to")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in vm.availableTargets) {
                    FilterChip(
                        selected = target == t,
                        onClick = { vm.target = t },
                        label = { Text(t.label, fontSize = 15.sp) },
                        modifier = Modifier.height(44.dp),
                    )
                }
            }

            // --- quality ---
            when (target) {
                Target.PNG, Target.BMP -> Note("${target.label} is always lossless - nothing to set.")
                Target.M4A -> Note("The sound is saved as AAC (M4A).")
                else -> {
                    SwitchRow(
                        if (target == Target.MP4) "Highest quality (bigger file)" else "Lossless (bigger file)",
                        vm.lossless,
                    ) { vm.lossless = it }
                    Column {
                        Text("Quality: ${vm.quality}", color = if (vm.lossless) cs.onSurfaceVariant else cs.onSurface)
                        Slider(
                            value = vm.quality.toFloat(),
                            onValueChange = { vm.quality = it.toInt().coerceIn(1, 100) },
                            valueRange = 1f..100f,
                            enabled = !vm.lossless,
                        )
                    }
                }
            }

            // --- PDF output ---
            if (target == Target.PDF) {
                HorizontalDivider(color = cs.outline)
                SectionTitle("PDF options")
                SwitchRow("Combine all photos into one PDF", vm.combinePdf) { vm.combinePdf = it }
                if (vm.combinePdf) {
                    OutlinedTextField(
                        value = vm.pdfName,
                        onValueChange = { vm.pdfName = it },
                        singleLine = true,
                        label = { Text("File name") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Note("Pages follow the list order - use ▲ ▼ on the cards to rearrange.")
                }
                Text("Page size")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in PageSize.values()) {
                        FilterChip(
                            selected = vm.pageSize == p,
                            onClick = { vm.pageSize = p },
                            label = { Text(p.label, fontSize = 15.sp) },
                            modifier = Modifier.height(44.dp),
                        )
                    }
                }
            }

            // --- PDF input ---
            if (vm.hasPdfInput && target.isPicture) {
                HorizontalDivider(color = cs.outline)
                SectionTitle("PDF page resolution")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (d in DPI_CHOICES) {
                        FilterChip(
                            selected = vm.dpi == d,
                            onClick = { vm.dpi = d },
                            label = {
                                Text(
                                    when (d) {
                                        150 -> "Standard · 150"
                                        200 -> "High · 200"
                                        else -> "Maximum · 300"
                                    },
                                    fontSize = 15.sp,
                                )
                            },
                            modifier = Modifier.height(44.dp),
                        )
                    }
                }
                Note("Each PDF page becomes one picture. Multi-page PDFs get their own folder.")
            }

            // --- where to save ---
            HorizontalDivider(color = cs.outline)
            SectionTitle("Save to")
            Text(vm.outputLabel, color = cs.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onChooseFolder,
                    enabled = !vm.converting,
                    modifier = Modifier.height(48.dp),
                ) { Text("Choose a folder…") }
                if (vm.outputTree != null) {
                    TextButton(onClick = { vm.setOutputTree(null) }, enabled = !vm.converting) { Text("Use default") }
                }
            }
            Note("Originals are never changed or deleted.")
        }
    }
}

// ---------------------------------------------------------------------------------------------
@Composable
private fun BottomBar(vm: ConverterViewModel) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surface, tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (vm.statusText.isNotEmpty()) {
                Text(vm.statusText, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
            }
            if (vm.converting) {
                LinearProgressIndicator(
                    progress = { vm.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { vm.cancel() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Cancel")
                }
            } else {
                Button(
                    onClick = { vm.start() },
                    enabled = vm.jobs.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    val n = vm.jobs.size
                    Text(
                        if (n == 0) "Add files to start" else "Convert $n file${if (n == 1) "" else "s"}",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
@Composable
private fun PreviewDialog(vm: ConverterViewModel, job: FileJob, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val loaded by produceState(initialValue = Pair(false, null as Bitmap?), job.uri) {
        val bmp = withContext(Dispatchers.IO) { vm.loadBigPreview(job) }
        value = Pair(true, bmp)
    }
    val shown = remember(loaded, job.rotation) {
        loaded.second?.let { MediaIO.rotate(it, job.rotation).asImageBitmap() }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = cs.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(job.name, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        shown != null -> Image(
                            bitmap = shown,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                        !loaded.first -> CircularProgressIndicator()
                        else -> Text("No preview available", color = Color.White)
                    }
                }
                if (job.info.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(job.info, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (job.kind == Kind.IMAGE) {
                        FilledTonalButton(
                            onClick = { vm.rotate(job, 270) },
                            enabled = !vm.converting,
                            modifier = Modifier.height(52.dp),
                        ) { Text("↺ Left", fontSize = 16.sp) }
                        FilledTonalButton(
                            onClick = { vm.rotate(job, 90) },
                            enabled = !vm.converting,
                            modifier = Modifier.height(52.dp),
                        ) { Text("↻ Right", fontSize = 16.sp) }
                    }
                    Button(onClick = onDismiss, modifier = Modifier.height(52.dp)) { Text("Close", fontSize = 16.sp) }
                }
            }
        }
    }
}

private fun openFile(ctx: Context, uri: Uri) {
    try {
        val type = ctx.contentResolver.getType(uri)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(ctx, "No app found to open this file", Toast.LENGTH_SHORT).show()
    }
}
