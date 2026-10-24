package com.example.converter

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

class Fmt(
    val label: String,
    val ext: String,
    val mime: String,
    val video: Boolean,
    val args: (Int) -> List<String>
)

private fun p(q: Int, a: String, b: String, c: String) = when (q) { 0 -> a; 1 -> b; else -> c }
private const val EVEN = "scale=trunc(iw/2)*2:trunc(ih/2)*2"
private fun aud(vararg x: String) = listOf("-vn") + x.toList()
private fun vid(vararg x: String) = listOf("-map", "0:v:0", "-map", "0:a?") + x.toList()
private fun h264(q: Int) = vid(
    "-vf", EVEN, "-c:v", "libx264", "-preset", "veryfast", "-crf", p(q, "20", "24", "29"),
    "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "160k"
)

val FORMATS = listOf(
    Fmt("MP3", "mp3", "audio/mpeg", false) { q -> aud("-c:a", "libmp3lame", "-q:a", p(q, "0", "2", "5")) },
    Fmt("OGG Vorbis", "ogg", "audio/ogg", false) { q -> aud("-c:a", "libvorbis", "-q:a", p(q, "7", "5", "3")) },
    Fmt("Opus", "opus", "audio/ogg", false) { q -> aud("-c:a", "libopus", "-b:a", p(q, "192k", "128k", "64k")) },
    Fmt("FLAC", "flac", "audio/flac", false) { _ -> aud("-c:a", "flac") },
    Fmt("WAV", "wav", "audio/x-wav", false) { _ -> aud("-c:a", "pcm_s16le") },
    Fmt("AAC", "aac", "audio/aac", false) { q -> aud("-c:a", "aac", "-b:a", p(q, "256k", "160k", "96k")) },
    Fmt("M4A", "m4a", "audio/mp4", false) { q -> aud("-c:a", "aac", "-b:a", p(q, "256k", "160k", "96k")) },
    Fmt("ALAC", "m4a", "audio/mp4", false) { _ -> aud("-c:a", "alac") },
    Fmt("WMA", "wma", "audio/x-ms-wma", false) { q -> aud("-c:a", "wmav2", "-b:a", p(q, "192k", "128k", "64k")) },
    Fmt("AIFF", "aiff", "audio/x-aiff", false) { _ -> aud("-c:a", "pcm_s16be") },
    Fmt("AC3", "ac3", "audio/ac3", false) { q -> aud("-c:a", "ac3", "-b:a", p(q, "448k", "256k", "128k")) },
    Fmt("MP4", "mp4", "video/mp4", true) { q -> h264(q) + listOf("-movflags", "+faststart") },
    Fmt("MKV", "mkv", "video/x-matroska", true) { q -> h264(q) },
    Fmt("WebM", "webm", "video/webm", true) { q ->
        vid("-c:v", "libvpx-vp9", "-b:v", "0", "-crf", p(q, "24", "32", "38"), "-deadline", "good",
            "-cpu-used", "5", "-row-mt", "1", "-pix_fmt", "yuv420p", "-c:a", "libopus", "-b:a", "128k")
    },
    Fmt("MOV", "mov", "video/quicktime", true) { q -> h264(q) },
    Fmt("AVI", "avi", "video/x-msvideo", true) { q ->
        vid("-c:v", "mpeg4", "-vtag", "xvid", "-q:v", p(q, "2", "5", "9"), "-c:a", "libmp3lame", "-q:a", p(q, "2", "4", "6"))
    },
    Fmt("WMV", "wmv", "video/x-ms-wmv", true) { q ->
        vid("-c:v", "wmv2", "-b:v", p(q, "4M", "2M", "1M"), "-c:a", "wmav2", "-b:a", "128k")
    },
    Fmt("FLV", "flv", "video/x-flv", true) { q ->
        vid("-c:v", "flv", "-q:v", p(q, "2", "5", "9"), "-c:a", "aac", "-b:a", "128k")
    },
    Fmt("MPG", "mpg", "video/mpeg", true) { q ->
        vid("-c:v", "mpeg2video", "-q:v", p(q, "2", "5", "9"), "-c:a", "mp2", "-b:a", "192k")
    },
    Fmt("TS", "ts", "video/mp2t", true) { q -> h264(q) },
    Fmt("GIF", "gif", "image/gif", true) { _ ->
        listOf("-map", "0:v:0", "-an", "-vf",
            "fps=12,scale=480:-1:flags=lanczos,split[s0][s1];[s0]palettegen[p];[s1][p]paletteuse",
            "-loop", "0")
    }
)

class MainActivity : ComponentActivity() {

    private var inputUri by mutableStateOf<Uri?>(null)
    private var inputName by mutableStateOf("")
    private var outName by mutableStateOf("")
    private var fmtIndex by mutableIntStateOf(0)
    private var quality by mutableIntStateOf(1)
    private var busy by mutableStateOf(false)
    private var progress by mutableFloatStateOf(0f)
    private var status by mutableStateOf("Pick a file to start.")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { Screen() } }
    }

    @Composable
    private fun AppTheme(content: @Composable () -> Unit) {
        val dark = isSystemInDarkTheme()
        val ctx = LocalContext.current
        val scheme = if (Build.VERSION.SDK_INT >= 31) {
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        } else {
            if (dark) darkColorScheme() else lightColorScheme()
        }
        MaterialTheme(colorScheme = scheme) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable
    private fun Screen() {
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onPicked(uri)
        }
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Converter", style = MaterialTheme.typography.headlineLarge)

            Button(onClick = { picker.launch(arrayOf("audio/*", "video/*")) }, enabled = !busy) {
                Text(if (inputUri == null) "Choose file" else "Change file")
            }
            if (inputUri != null) Text(inputName, style = MaterialTheme.typography.bodyMedium)

            Text("Audio", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FORMATS.forEachIndexed { i, f ->
                    if (!f.video) FilterChip(
                        selected = fmtIndex == i, onClick = { fmtIndex = i },
                        label = { Text(f.label) }, enabled = !busy
                    )
                }
            }
            Text("Video", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FORMATS.forEachIndexed { i, f ->
                    if (f.video) FilterChip(
                        selected = fmtIndex == i, onClick = { fmtIndex = i },
                        label = { Text(f.label) }, enabled = !busy
                    )
                }
            }

            Text("Quality (lossless formats ignore this)", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("High", "Balanced", "Small").forEachIndexed { i, l ->
                    FilterChip(
                        selected = quality == i, onClick = { quality = i },
                        label = { Text(l) }, enabled = !busy
                    )
                }
            }

            OutlinedTextField(
                value = outName, onValueChange = { outName = it },
                label = { Text("Output name") }, singleLine = true,
                enabled = !busy, modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { startConvert() }, enabled = inputUri != null && !busy) {
                    Text("Convert to ${FORMATS[fmtIndex].label}")
                }
                if (busy) OutlinedButton(onClick = { FFmpegKit.cancel() }) { Text("Cancel") }
            }

            if (busy) {
                if (progress > 0f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(status, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Keep the app open while converting.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    private fun onPicked(uri: Uri) {
        var name = "file"
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) name = c.getString(i) ?: name
            }
        }
        inputUri = uri
        inputName = name
        outName = name.substringBeforeLast('.')
        status = "Ready."
    }

    private fun finishUi() {
        busy = false
        runOnUiThread { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    private fun startConvert() {
        val uri = inputUri ?: return
        val fmt = FORMATS[fmtIndex]
        val q = quality
        val base = outName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "converted" }
        val srcExt = inputName.substringAfterLast('.', "").ifEmpty { "bin" }
        busy = true
        progress = 0f
        status = "Preparing..."
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        Thread {
            val inFile = File(cacheDir, "input.$srcExt")
            val outFile = File(cacheDir, "output.${fmt.ext}")
            fun cleanup() { inFile.delete(); outFile.delete() }
            try {
                outFile.delete()
                contentResolver.openInputStream(uri)!!.use { i -> inFile.outputStream().use { o -> i.copyTo(o) } }

                status = "Reading file..."
                val info = FFprobeKit.getMediaInformation(inFile.absolutePath).mediaInformation
                val durMs = (info?.duration?.toDoubleOrNull() ?: 0.0) * 1000.0
                val hasVideo = info?.streams?.any { it.type == "video" } ?: false
                val hasAudio = info?.streams?.any { it.type == "audio" } ?: false
                if (fmt.video && !hasVideo) throw IllegalStateException("This file has no video, so it can't become ${fmt.label}. Pick an audio format.")
                if (!fmt.video && !hasAudio) throw IllegalStateException("This file has no audio track.")

                status = "Converting to ${fmt.label}..."
                val args = (listOf("-y", "-i", inFile.absolutePath) + fmt.args(q) + outFile.absolutePath).toTypedArray()

                FFmpegKit.executeWithArgumentsAsync(args, { session ->
                    try {
                        val rc = session.returnCode
                        if (ReturnCode.isSuccess(rc)) {
                            status = "Saving..."
                            val where = save(outFile, base, fmt)
                            progress = 1f
                            status = "Done. Saved to $where as $base.${fmt.ext}"
                        } else if (ReturnCode.isCancel(rc)) {
                            status = "Cancelled."
                        } else {
                            status = "Failed:\n" + (session.allLogsAsString ?: "").takeLast(500)
                        }
                    } catch (e: Exception) {
                        status = "Error: ${e.message}"
                    } finally {
                        cleanup()
                        finishUi()
                    }
                }, { _ -> }, { st ->
                    if (durMs > 0) progress = (st.time.toDouble() / durMs).toFloat().coerceIn(0f, 1f)
                })
            } catch (e: Exception) {
                status = "Error: ${e.message}"
                cleanup()
                finishUi()
            }
        }.start()
    }

    private fun save(file: File, base: String, fmt: Fmt): String {
        val vol = MediaStore.VOLUME_EXTERNAL_PRIMARY
        val (collection, dir) = when {
            fmt.mime.startsWith("audio/") -> MediaStore.Audio.Media.getContentUri(vol) to "Music/Converted"
            fmt.mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(vol) to "Movies/Converted"
            else -> MediaStore.Images.Media.getContentUri(vol) to "Pictures/Converted"
        }
        fun insert(c: Uri, rel: String): Uri? {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$base.${fmt.ext}")
                put(MediaStore.MediaColumns.MIME_TYPE, fmt.mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            }
            return try { contentResolver.insert(c, v) } catch (e: Exception) { null }
        }
        var where = dir
        var uri = insert(collection, dir)
        if (uri == null) {
            where = "Download/Converted"
            uri = insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, where)
        }
        val target = uri ?: throw IllegalStateException("Could not create the output file.")
        contentResolver.openOutputStream(target)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        return where
    }
}
