package com.lunk333.whrrah.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Crop
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import com.lunk333.whrrah.overlay.OverlayLayout
import com.lunk333.whrrah.overlay.OverlayRenderer
import com.lunk333.whrrah.overlay.RunData
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Renders a run's clip to a new video in the gallery (Movies/WHRRAH) with Media3 Transformer:
 * optionally trimmed to the padded window around the lap, and optionally with a desktop WHRRAH layout
 * burned in and cropped to that layout's export frame (as desktop's export).
 *
 * The source can be the phone's copy or the camera's file over its WiFi — the player range-reads it,
 * so only the trimmed part comes over.
 */
@OptIn(UnstableApi::class)
class ClipExporter(context: Context) {
    private val app = context.applicationContext

    var running by mutableStateOf(false); private set
    /** 0..1, or null before the first progress report. */
    var progress by mutableStateOf<Float?>(null); private set
    var status by mutableStateOf(""); private set
    /** The finished export (null while running / after a failure). */
    var result by mutableStateOf<String?>(null); private set

    private var transformer: Transformer? = null
    private val progressHolder = ProgressHolder()

    /**
     * Overlay inputs: [layout] drawn with [data] at data time (videoStartMs + video position);
     * null [layout] = no overlay and no crop.
     */
    class Overlay(val layout: OverlayLayout, val data: RunData?, val videoStartMs: Long)

    /** Start an export. [startMs]/[endMs]: trim window in the clip (null = from the start / to the end). Main thread. */
    fun start(source: Uri, outName: String, startMs: Long?, endMs: Long?, overlay: Overlay?) {
        if (running) return
        running = true; progress = null; result = null
        status = "Rendering…"
        val out = File(app.cacheDir, "export_${System.currentTimeMillis()}.mp4")

        val clip = MediaItem.ClippingConfiguration.Builder().apply {
            if (startMs != null && startMs > 0) setStartPositionMs(startMs)
            if (endMs != null) setEndPositionMs(endMs)
        }.build()
        val item = MediaItem.Builder().setUri(source).setClippingConfiguration(clip).build()
        val effects = ArrayList<Effect>()
        if (overlay != null) {
            effects += OverlayEffect(ImmutableList.of(LayoutOverlay(overlay, startMs ?: 0L)))
            effects += LayoutCrop(overlay.layout)
        }
        val edited = EditedMediaItem.Builder(item).setEffects(Effects(listOf(), effects)).build()

        val t = Transformer.Builder(app).addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                transformer = null
                status = "Saving to the gallery…"
                Thread {
                    val saved = runCatching { saveToGallery(out, outName) }
                    out.delete()
                    android.os.Handler(app.mainLooper).post {
                        running = false
                        saved.onSuccess { result = it; status = "Saved to $it"; progress = 1f }
                            .onFailure { status = "Rendered, but couldn't save to the gallery: ${it.message}" }
                    }
                }.start()
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                transformer = null
                running = false
                out.delete()
                status = "Export failed: ${exportException.errorCodeName}" + (exportException.cause?.message?.let { " — $it" } ?: "")
            }
        }).build()
        transformer = t
        t.start(edited, out.path)
    }

    /** Poll from the UI (main thread) while running. */
    fun pollProgress() {
        val t = transformer ?: return
        if (t.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) progress = progressHolder.progress / 100f
    }

    fun cancel() {
        transformer?.cancel(); transformer = null
        if (running) { running = false; status = "Cancelled" }
    }

    /** Copies [file] into Movies/WHRRAH/ via MediaStore; returns the gallery path shown to the user. */
    private fun saveToGallery(file: File, name: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val dir = File(app.getExternalFilesDir(null), "exports").apply { mkdirs() }
            file.copyTo(File(dir, name), overwrite = true)
            return "${dir.path}/$name"
        }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/WHRRAH")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = app.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore refused the file")
        try {
            resolver.openOutputStream(uri)!!.use { os -> file.inputStream().use { it.copyTo(os) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null); throw e
        }
        return "Movies/WHRRAH/$name"
    }

    /**
     * The layout drawn into a bitmap the size of the frame (capped at ~1920 px wide for speed, then
     * scaled up), redrawn every frame — Media3 re-uploads it whenever its contents change.
     * The video is letterboxed into the layout's frame, as desktop's export.
     */
    private class LayoutOverlay(private val o: Overlay, private val clipStartMs: Long) : BitmapOverlay() {
        private val renderer = OverlayRenderer()
        private var bitmap: Bitmap? = null
        private var canvas: Canvas? = null
        private var k = 1f                  // bitmap px per frame px
        private var frameW = 1; private var frameH = 1
        /** Whether frame times count from the clip start (the trimmed output) rather than the file's. */
        private var relative: Boolean? = null

        override fun configure(videoSize: Size) {
            frameW = videoSize.width; frameH = videoSize.height
            k = min(1f, 1920f / frameW)
            bitmap = Bitmap.createBitmap(max(1, (frameW * k).toInt()), max(1, (frameH * k).toInt()), Bitmap.Config.ARGB_8888)
            canvas = Canvas(bitmap!!)
        }

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
            OverlaySettings.Builder().setScale(1f / k, 1f / k).build()

        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            val bmp = bitmap ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).also { bitmap = it; canvas = Canvas(it) }
            val c = canvas!!
            if (relative == null) relative = clipStartMs > 1000 && presentationTimeUs < clipStartMs * 1000 / 2
            val videoMs = presentationTimeUs / 1000 + if (relative == true) clipStartMs else 0
            bmp.eraseColor(Color.TRANSPARENT)
            val l = o.layout
            val fit = min(l.width.toFloat() / frameW, l.height.toFloat() / frameH)
            val offX = (l.width - frameW * fit) / 2; val offY = (l.height - frameH * fit) / 2
            c.save()
            c.scale(k / fit, k / fit)
            c.translate(-offX, -offY)
            renderer.draw(c, l, o.data, (o.videoStartMs + videoMs) / 1000.0)
            c.restore()
            return bmp
        }
    }

    /** Crops the frame to the layout's export crop (desktop `crop_rect`); a no-op for "Original". */
    private class LayoutCrop(layout: OverlayLayout) : androidx.media3.effect.MatrixTransformation {
        private val l = layout
        private var crop: Crop? = null

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            val r = l.cropRect()
            val fit = min(l.width.toFloat() / inputWidth, l.height.toFloat() / inputHeight)
            val offX = (l.width - inputWidth * fit) / 2; val offY = (l.height - inputHeight * fit) / 2
            // Crop rect in frame px → NDC (y up).
            val x0 = ((r[0] - offX) / fit).coerceIn(0f, inputWidth.toFloat()); val x1 = ((r[0] + r[2] - offX) / fit).coerceIn(0f, inputWidth.toFloat())
            val y0 = ((r[1] - offY) / fit).coerceIn(0f, inputHeight.toFloat()); val y1 = ((r[1] + r[3] - offY) / fit).coerceIn(0f, inputHeight.toFloat())
            val c = Crop(-1 + 2 * x0 / inputWidth, -1 + 2 * x1 / inputWidth, 1 - 2 * y1 / inputHeight, 1 - 2 * y0 / inputHeight)
            crop = c
            return c.configure(inputWidth, inputHeight)
        }

        override fun getMatrix(presentationTimeUs: Long): android.graphics.Matrix = crop!!.getMatrix(presentationTimeUs)
    }

    companion object {
        @Volatile private var instance: ClipExporter? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: ClipExporter(context).also { instance = it } }
    }
}
