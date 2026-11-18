package com.example.tvlauncher

import android.app.ActivityManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.io.File
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private lateinit var playerView: PlayerView
    private lateinit var overlay: View
    private lateinit var stateIcon: ImageView
    private lateinit var epTitle: TextView
    private lateinit var epIndex: TextView
    private lateinit var bar: ProgressBar
    private lateinit var timeText: TextView
    private lateinit var techText: TextView
    private lateinit var toastView: TextView
    private lateinit var spinner: View

    private val handler = Handler(Looper.getMainLooper())
    private var dir = ""
    private var eps: List<Episode> = emptyList()
    private var idx = 0
    private var softAudio = true
    private var fallbackDone = false
    private var longHandled = false
    private var lastSave = 0L
    private var lastSeek = 0L
    private var aspect = 0
    private var videoDec: String? = null
    private var audioDec: String? = null
    private var ready = false
    private lateinit var drawer: View
    private lateinit var epList: RecyclerView
    private lateinit var epAdapter: EpAdapter
    private var drawerOpen = false
    private var drawerSel = 0
    private var swallowCenter = false
    private val closeDrawerRun = Runnable { closeDrawer() }

    private val hideOverlay = Runnable { fadeOverlay(false) }
    private val hideToast = Runnable { toastView.visibility = View.GONE }
    private val ticker = object : Runnable {
        override fun run() {
            updateBar()
            saveProgress(false)
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // follow the TV's own brightness; never push it up or down
        val lp = window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        volumeControlStream = AudioManager.STREAM_MUSIC   // volume keys always control the system volume

        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.playerView)
        playerView.useController = false
        overlay = findViewById(R.id.overlay)
        stateIcon = findViewById(R.id.stateIcon)
        epTitle = findViewById(R.id.epTitle)
        epIndex = findViewById(R.id.epIndex)
        bar = findViewById(R.id.bar)
        timeText = findViewById(R.id.timeText)
        techText = findViewById(R.id.techText)
        toastView = findViewById(R.id.toast)
        spinner = findViewById(R.id.spinner)

        dir = intent.getStringExtra("dir") ?: ""
        val path = intent.getStringExtra("path") ?: ""
        eps = Library.episodes(dir)
        idx = eps.indexOfFirst { it.path == path }.coerceAtLeast(0)
        softAudio = Store.softAudio
        if (eps.isEmpty()) {
            finish()
            return
        }
        drawer = findViewById(R.id.drawer)
        epList = findViewById(R.id.epList)
        epAdapter = EpAdapter({ }, { })
        epAdapter.selectMode = true
        epAdapter.items = eps
        epList.layoutManager = LinearLayoutManager(this)
        epList.itemAnimator = null
        epList.adapter = epAdapter
    }

    private fun refreshIcon() {
        val p = player ?: return
        stateIcon.setImageResource(if (p.playWhenReady) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun openDrawer() {
        drawerSel = idx
        epAdapter.current = idx
        epAdapter.sel = idx
        epAdapter.notifyDataSetChanged()
        (epList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(idx, 140)
        fadeOverlay(false)
        if (!drawerOpen) {
            drawerOpen = true
            drawer.alpha = 0f
            drawer.translationX = -60f
            drawer.visibility = View.VISIBLE
            drawer.animate().alpha(1f).translationX(0f).setDuration(240).start()
        }
        handler.removeCallbacks(closeDrawerRun)
        handler.postDelayed(closeDrawerRun, 12000)
    }

    private fun closeDrawer() {
        handler.removeCallbacks(closeDrawerRun)
        if (!drawerOpen) return
        drawerOpen = false
        drawer.animate().alpha(0f).translationX(-60f).setDuration(200).withEndAction { drawer.visibility = View.GONE }.start()
    }

    private fun moveDrawer(delta: Int) {
        val n = (drawerSel + delta).coerceIn(0, eps.size - 1)
        if (n == drawerSel) return
        val old = drawerSel
        drawerSel = n
        epAdapter.sel = n
        epAdapter.notifyItemChanged(old)
        epAdapter.notifyItemChanged(n)
        epList.scrollToPosition(n)
        handler.removeCallbacks(closeDrawerRun)
        handler.postDelayed(closeDrawerRun, 12000)
    }

    private fun drawerKey(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> moveDrawer(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveDrawer(1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event.repeatCount == 0) {
                    swallowCenter = true
                    closeDrawer()
                    if (drawerSel != idx) {
                        saveProgress(true)
                        loadEpisode(drawerSel, -1L)
                    }
                }
            }
            else -> closeDrawer()
        }
        return true
    }

    override fun onStart() {
        super.onStart()
        if (eps.isEmpty()) return
        buildPlayer()
        loadEpisode(idx, -1L)
        handler.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        saveProgress(true)
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(hideOverlay)
        releasePlayer()
    }

    private fun releasePlayer() {
        playerView.player = null
        player?.release()
        player = null
        trackSelector = null
    }

    // ------------------------------------------------------------ player

    private fun buildPlayer() {
        releasePlayer()
        // Memory cap by SIZE as well as time: a 4K stream must never be able to fill the TV's RAM.
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val bytes = (am.memoryClass * 1024L * 1024L * 3L / 10L).toInt().coerceIn(24 * 1024 * 1024, 80 * 1024 * 1024)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(5000, 30000, 1500, 3000)
            .setTargetBufferBytes(bytes)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(0, false)
            .build()

        // Video: always the TV's MediaCodec (hardware 4K / HEVC / HDR). There is no software video decoder in this app.
        // Audio: MediaCodec / HDMI passthrough first; the FFmpeg decoder is only added as a LAST fallback behind it.
        val factory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(
                if (softAudio) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
            )
            .setEnableDecoderFallback(true)
        val selector = DefaultTrackSelector(this)
        trackSelector = selector

        val p = ExoPlayer.Builder(this, factory)
            .setTrackSelector(selector)
            .setLoadControl(loadControl)
            .build()
        p.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true
        )
        p.setVideoChangeFrameRateStrategy(
            if (Store.matchFps) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
            else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
        )
        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                videoDec = DecoderInfo.describe(decoderName)
            }

            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                audioDec = DecoderInfo.describe(decoderName)
            }
        })
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                spinner.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (state == Player.STATE_READY) ready = true
                if (state == Player.STATE_ENDED) onEnded()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                refreshIcon()
                if (!isPlaying && overlay.visibility == View.VISIBLE) handler.removeCallbacks(hideOverlay)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) { updateBar() }
            override fun onTracksChanged(tracks: Tracks) { updateBar() }
            override fun onPlayerError(error: PlaybackException) { handleError(error) }
        })
        player = p
        playerView.player = p
        playerView.resizeMode = when (aspect) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    private fun mediaItemFor(e: Episode): MediaItem {
        val b = MediaItem.Builder().setUri(Uri.fromFile(File(e.path)))
        // sidecar subtitles with the same name (movie.mkv + movie.srt)
        try {
            val f = File(e.path)
            val base = f.nameWithoutExtension
            val subs = ArrayList<MediaItem.SubtitleConfiguration>()
            f.parentFile?.listFiles()?.forEach { s ->
                val n = s.name
                if (n.startsWith(base) && !n.startsWith(".")) {
                    val ext = s.extension.lowercase()
                    val mime = when (ext) {
                        "srt" -> MimeTypes.APPLICATION_SUBRIP
                        "vtt" -> MimeTypes.TEXT_VTT
                        "ssa", "ass" -> MimeTypes.TEXT_SSA
                        else -> null
                    }
                    if (mime != null) subs.add(MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(s)).setMimeType(mime).setLanguage("und").build())
                }
            }
            if (subs.isNotEmpty()) b.setSubtitleConfigurations(subs)
        } catch (e: Exception) {
        }
        return b.build()
    }

    /** startOverride: -1 = resume from saved progress, otherwise a position in ms. */
    private fun loadEpisode(i: Int, startOverride: Long) {
        val p = player ?: return
        idx = i.coerceIn(0, eps.size - 1)
        val e = eps[idx]
        val prog = Store.prog(e.path)
        val start = if (startOverride >= 0) startOverride else if (prog != null && !prog.done && prog.pos > 3000) prog.pos else 0L
        ready = false
        videoDec = null
        audioDec = null
        p.setMediaItem(mediaItemFor(e), start)
        p.prepare()
        p.playWhenReady = true
        Store.save(dir, e.path, start, prog?.dur ?: 0L, false)
        epTitle.text = e.name
        epIndex.text = (idx + 1).toString() + " / " + eps.size
        showOverlay(true)
    }

    private fun onEnded() {
        val e = eps.getOrNull(idx) ?: return
        val dur = player?.duration ?: 0L
        Store.save(dir, e.path, dur.coerceAtLeast(0L), dur.coerceAtLeast(0L), true)
        if (idx + 1 < eps.size) {
            toast("Next: " + eps[idx + 1].name)
            loadEpisode(idx + 1, 0L)
        } else {
            finish()
        }
    }

    private fun handleError(error: PlaybackException) {
        spinner.visibility = View.GONE
        // If the software audio decoder was involved and failed, continue with hardware-only audio at the same spot.
        if (softAudio && !fallbackDone) {
            fallbackDone = true
            softAudio = false
            val pos = player?.currentPosition ?: 0L
            handler.post {
                buildPlayer()
                loadEpisode(idx, pos)
                toast("Software audio stopped - continuing with hardware audio")
            }
            return
        }
        toast("Cannot play this file (" + error.errorCodeName + ")")
    }

    private fun saveProgress(force: Boolean) {
        val p = player ?: return
        val e = eps.getOrNull(idx) ?: return
        if (!ready) return
        val now = System.currentTimeMillis()
        if (!force && now - lastSave < 5000) return
        lastSave = now
        val dur = p.duration
        if (dur <= 0 || dur == C.TIME_UNSET) return
        val pos = p.currentPosition.coerceIn(0L, dur)
        val done = pos >= dur * 95 / 100
        Store.save(dir, e.path, if (done) dur else pos, dur, done)
    }

    // ------------------------------------------------------------ overlay

    private fun fadeOverlay(show: Boolean) {
        overlay.animate().cancel()
        if (show) {
            if (overlay.visibility != View.VISIBLE) {
                overlay.alpha = 0f
                overlay.translationY = 20f
                overlay.visibility = View.VISIBLE
            }
            overlay.animate().alpha(1f).translationY(0f).setDuration(220).start()
        } else {
            overlay.animate().alpha(0f).translationY(20f).setDuration(220).withEndAction { overlay.visibility = View.GONE }.start()
        }
    }

    private fun showOverlay(autoHide: Boolean) {
        updateBar()
        fadeOverlay(true)
        handler.removeCallbacks(hideOverlay)
        if (autoHide && player?.playWhenReady != false) handler.postDelayed(hideOverlay, 4500)
    }

    private fun toast(msg: String) {
        toastView.text = msg
        toastView.visibility = View.VISIBLE
        toastView.alpha = 0f
        toastView.animate().alpha(1f).setDuration(160).start()
        handler.removeCallbacks(hideToast)
        handler.postDelayed(hideToast, 2400)
    }

    private fun updateBar() {
        val p = player ?: return
        if (overlay.visibility != View.VISIBLE) return
        val dur = p.duration
        val pos = p.currentPosition
        if (dur > 0 && dur != C.TIME_UNSET) {
            bar.progress = (pos * 1000 / dur).toInt().coerceIn(0, 1000)
            timeText.text = U.fmt(pos) + " / " + U.fmt(dur)
        } else {
            timeText.text = U.fmt(pos)
        }
        refreshIcon()
        techText.text = techLine(p)
    }

    private fun techLine(p: ExoPlayer): String {
        val parts = ArrayList<String>()
        val v = p.videoFormat
        val vs = p.videoSize
        if (vs.width > 0) parts.add(vs.width.toString() + "x" + vs.height)
        DecoderInfo.shortName(v?.sampleMimeType)?.let { parts.add(it) }
        val hdr = when {
            v?.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
            v?.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10"
            v?.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
            else -> null
        }
        if (hdr != null) parts.add(hdr)
        videoDec?.let { parts.add(it) }
        val a = p.audioFormat
        if (a != null) {
            val an = DecoderInfo.shortName(a.sampleMimeType) ?: ""
            val ch = if (a.channelCount > 0) " " + a.channelCount + "ch" else ""
            parts.add("Audio: " + an + ch + (audioDec?.let { " (" + it + ")" } ?: " (passthrough)"))
        }
        return parts.joinToString("   |   ")
    }

    // ------------------------------------------------------------ actions

    private fun togglePlay() {
        val p = player ?: return
        p.playWhenReady = !p.playWhenReady
        showOverlay(true)
    }

    private fun step(delta: Int) {
        val n = idx + delta
        if (n < 0) {
            toast("This is the first episode")
            return
        }
        if (n >= eps.size) {
            toast("This is the last episode")
            return
        }
        saveProgress(true)
        toast((if (delta > 0) "Next: " else "Previous: ") + eps[n].name)
        loadEpisode(n, -1L)
    }

    private fun seek(sign: Int, repeat: Int) {
        val p = player ?: return
        val now = System.currentTimeMillis()
        if (repeat > 0 && now - lastSeek < 220) return   // do not flood the decoder while the key is held
        lastSeek = now
        val stepMs = if (repeat > 12) 60_000L else if (repeat > 4) 30_000L else 10_000L
        val dur = p.duration
        var t = p.currentPosition + sign * stepMs
        t = t.coerceAtLeast(0L)
        if (dur > 0 && dur != C.TIME_UNSET) t = t.coerceAtMost(dur - 1000L)
        p.seekTo(t)
        showOverlay(true)
    }

    private fun showOptions() {
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        labels.add("Audio track"); acts.add { showTracks(C.TRACK_TYPE_AUDIO, "Audio") }
        labels.add("Subtitles"); acts.add { showTracks(C.TRACK_TYPE_TEXT, "Subtitles") }
        labels.add("Aspect: " + (if (aspect == 0) "Fit" else "Zoom")); acts.add {
            aspect = (aspect + 1) % 2
            playerView.resizeMode = if (aspect == 0) AspectRatioFrameLayout.RESIZE_MODE_FIT else AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            toast("Aspect: " + (if (aspect == 0) "Fit" else "Zoom"))
        }
        labels.add("Software audio fallback: " + (if (softAudio) "On" else "Off")); acts.add {
            softAudio = !softAudio
            Store.softAudio = softAudio
            val pos = player?.currentPosition ?: 0L
            buildPlayer()
            loadEpisode(idx, pos)
            toast("Software audio fallback " + (if (softAudio) "on" else "off"))
        }
        labels.add("Mark as watched and play next"); acts.add { onEnded() }
        AlertDialog.Builder(this)
            .setTitle(eps[idx].name)
            .setItems(labels.toTypedArray()) { _, w -> acts[w]() }
            .show()
    }

    private fun showTracks(type: Int, title: String) {
        val p = player ?: return
        val ts = trackSelector ?: return
        val groups = p.currentTracks.groups.filter { it.type == type && it.isSupported }
        if (groups.isEmpty()) {
            toast("No $title options in this file")
            return
        }
        val isText = type == C.TRACK_TYPE_TEXT
        val labels = ArrayList<String>()
        if (isText) labels.add("Off")
        for ((i, g) in groups.withIndex()) {
            val f = g.getTrackFormat(0)
            val lang = f.language
            val raw: String? = f.label
            val name: String = when {
                !raw.isNullOrEmpty() -> raw
                !lang.isNullOrEmpty() && lang != "und" -> Locale(lang).displayLanguage
                else -> "$title ${i + 1}"
            }
            val extra = if (!isText) {
                val c = DecoderInfo.shortName(f.sampleMimeType) ?: ""
                val ch = if (f.channelCount > 0) " " + f.channelCount + "ch" else ""
                "   (" + c + ch + ")"
            } else ""
            labels.add(name + extra + (if (g.isSelected) "   *" else ""))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which ->
                val b = ts.buildUponParameters()
                b.clearOverridesOfType(type)
                if (isText) {
                    if (which == 0) {
                        b.setTrackTypeDisabled(type, true)
                    } else {
                        b.setTrackTypeDisabled(type, false)
                        b.setOverrideForType(TrackSelectionOverride(groups[which - 1].mediaTrackGroup, 0))
                    }
                } else {
                    b.setOverrideForType(TrackSelectionOverride(groups[which].mediaTrackGroup, 0))
                }
                ts.setParameters(b.build())
            }
            .show()
    }

    // ------------------------------------------------------------ keys

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (drawerOpen) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) return super.onKeyDown(keyCode, event)
            return drawerKey(keyCode, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (event.repeatCount == 0) openDrawer()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (event.repeatCount == 0) step(-1)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (event.repeatCount == 0) step(1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seek(-1, event.repeatCount)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                seek(1, event.repeatCount)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                event.startTracking()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (event.repeatCount == 0) togglePlay()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                player?.playWhenReady = true
                showOverlay(true)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                player?.playWhenReady = false
                showOverlay(false)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                finish()
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_PROG_YELLOW -> {
                if (event.repeatCount == 0) showOptions()
                return true
            }
            KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_PROG_BLUE -> {
                if (event.repeatCount == 0) showTracks(C.TRACK_TYPE_TEXT, "Subtitles")
                return true
            }
            KeyEvent.KEYCODE_LANGUAGE_SWITCH, KeyEvent.KEYCODE_PROG_GREEN -> {
                if (event.repeatCount == 0) showTracks(C.TRACK_TYPE_AUDIO, "Audio")
                return true
            }
        }
        // volume keys and everything else fall through to the system
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            longHandled = true
            showOptions()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (swallowCenter && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            swallowCenter = false
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (!longHandled) togglePlay()
            longHandled = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, R.anim.exit)
    }
}
