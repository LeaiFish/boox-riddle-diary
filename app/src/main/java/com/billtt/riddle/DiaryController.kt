package com.billtt.riddle

import android.app.Activity
import android.graphics.Rect
import android.util.Log
import android.widget.Toast
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.pen.EpdPenManager
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.data.TouchPointList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * State machine: writing -> ink absorption -> awaiting reply -> reply reveal ->
 * linger -> reply fade -> writing.
 *
 * During writing, pen points arrive via TouchHelper (app-render mode; see attach())
 * and are drawn live in software by DiaryView. When idle is detected the raw pen is
 * disabled and the fade animations run via DiaryView + e-ink DU4 fast refresh.
 */
class DiaryController(
    private val activity: Activity,
    private val view: DiaryView,
    private val prefs: Prefs,
) {

    enum class State { WRITING, ABSORBING, AWAITING_REPLY, REVEALING, LINGERING, FADING_REPLY }

    @Volatile var state = State.WRITING
        private set
    @Volatile private var penResumeRequested = false
    @Volatile private var lastPenMs = 0L

    /** Settings gesture only on a blank, quiet page — a palm resting mid-writing never qualifies. */
    fun settingsGestureAllowed(): Boolean =
        state == State.WRITING && view.strokes.isEmpty() &&
            System.currentTimeMillis() - lastPenMs > 3000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var touchHelper: TouchHelper? = null
    private var cycleJob: Job? = null
    @Volatile private var skipLingerRequested = false

    // The stroke currently being written (accumulated from move callbacks; used as a
    // fallback on pen-up if the full point list wasn't delivered).
    private val pendingPoints = ArrayList<StrokePoint>()

    private val idleRunnable = Runnable { onIdle() }

    // ------------------------------------------------------------- lifecycle

    /** Call after the view is laid out; if the size is still 0, defer until layout completes. */
    fun attach() {
        if (view.width == 0 || view.height == 0) {
            Log.i(TAG, "attach: view not laid out yet (${view.width}x${view.height}), deferring")
            view.addOnLayoutChangeListener(object : android.view.View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: android.view.View, l: Int, t: Int, r: Int, b: Int,
                    ol: Int, ot: Int, or_: Int, ob: Int,
                ) {
                    if (v.width > 0 && v.height > 0) {
                        v.removeOnLayoutChangeListener(this)
                        attach()
                    }
                }
            })
            return
        }
        val limit = Rect(0, 0, view.width, view.height)
        val ok = runCatching {
            // On this device only the app-render mode (FEATURE_APP_TOUCH_RENDER) delivers
            // pen-point callbacks; the default SurfaceFlinger hardware-draw mode does not
            // fire callbacks under this firmware. Live ink during writing is therefore
            // drawn in software by DiaryView (see the move callback / addLivePoint).
            touchHelper = if (HW_PEN_RENDER) {
                // T10C: default SurfaceFlinger hardware-draw mode — EPD renders live ink
                // itself with near-zero latency, callbacks still record the stroke.
                TouchHelper.create(view, rawCallback)
            } else {
                TouchHelper.create(view, TouchHelper.FEATURE_APP_TOUCH_RENDER, rawCallback)
            }
                .setStrokeWidth(view.baseStrokeWidth)
                .setStrokeStyle(TouchHelper.STROKE_STYLE_PENCIL)
                .setLimitRect(limit, ArrayList())
                .openRawDrawing()
            touchHelper?.setRawInputReaderEnable(true)
            touchHelper?.setRawDrawingRenderEnabled(true)
            touchHelper?.setRawDrawingEnabled(true)
            if (EPD_STROKE_RENDER) {
                EpdController.setStrokeStyle(EpdController.STROKE_STYLE_PENCIL)
                EpdController.setStrokeWidth(view.baseStrokeWidth)
                EpdController.setScreenHandWritingPenState(view, EpdPenManager.PEN_START)
            }
        }.isSuccess
        Log.i(TAG, "attach: limit=$limit touchHelper=${if (touchHelper != null) "ok" else "null"} ok=$ok")
        if (!ok) {
            touchHelper = null
            Toast.makeText(activity, R.string.toast_not_boox, Toast.LENGTH_LONG).show()
        }
        EInk.beginWriting(view)
    }

    /** Resume writing: re-enable raw pen input, only in the writing state. Called when the
     *  window regains focus / a dialog is dismissed. */
    fun onResume() {
        Log.i(TAG, "onResume: state=$state touchHelper=${touchHelper != null}")
        if (state == State.WRITING) {
            touchHelper?.setRawDrawingRenderEnabled(true)
            touchHelper?.setRawDrawingEnabled(true)
        }
    }

    fun onPause() {
        touchHelper?.setRawDrawingEnabled(false)
        view.removeCallbacks(idleRunnable)
    }

    fun onDestroy() {
        runCatching { touchHelper?.closeRawDrawing() }
        scope.cancel()
    }

    // ------------------------------------------- debug touch input (non-BOOX)

    /** True when the raw pen driver is unavailable (emulator); fall back to touch events. */
    val debugTouchFallback: Boolean get() = touchHelper == null

    fun debugAddPoint(x: Float, y: Float, pressure: Float, up: Boolean) {
        if (state != State.WRITING) return
        pendingPoints.add(StrokePoint(x, y, pressure))
        if (up) {
            view.addStroke(Stroke(ArrayList(pendingPoints)))
            pendingPoints.clear()
            EInk.animateFrame(view)
            scheduleIdleCheck()
        }
    }

    /** Any touch during the linger phase makes the reply fade early. */
    fun requestSkipLinger() {
        if (state == State.LINGERING) skipLingerRequested = true
    }

    // --------------------------------------------------------- raw pen callbacks
    // Note: these fire on Onyx SDK background threads and are all posted to the main thread.


    private val penPauseRunnable = Runnable {
        runCatching { EpdController.setScreenHandWritingPenState(view, EpdPenManager.PEN_PAUSE) }
    }

    private val rawCallback = object : RawInputCallback() {

        override fun onBeginRawDrawing(shortcut: Boolean, point: TouchPoint) {
            lastPenMs = System.currentTimeMillis()
            if (state == State.LINGERING || state == State.FADING_REPLY) penResumeRequested = true
            if (EPD_STROKE_RENDER) runCatching {
                view.removeCallbacks(penPauseRunnable)
                EpdController.setScreenHandWritingPenState(view, EpdPenManager.PEN_DRAWING)
                EpdController.startStroke(view.baseStrokeWidth, point.x, point.y, point.pressure, point.size, point.timestamp.toFloat())
            }
            val p = StrokePoint(point.x, point.y, normalizePressure(point.pressure))
            view.post {
                view.removeCallbacks(idleRunnable)
                cancelSpeculation()
                pendingPoints.clear()
                view.beginLiveStroke()
                pendingPoints.add(p)
                if (!HW_PEN_RENDER && !EPD_STROKE_RENDER) view.addLivePoint(p)
            }
        }

        override fun onRawDrawingTouchPointMoveReceived(point: TouchPoint) {
            lastPenMs = System.currentTimeMillis()
            if (EPD_STROKE_RENDER) runCatching {
                EpdController.addStrokePoint(view.baseStrokeWidth, point.x, point.y, point.pressure, point.size, point.timestamp.toFloat())
            }
            val p = StrokePoint(point.x, point.y, normalizePressure(point.pressure))
            view.post {
                pendingPoints.add(p)
                if (!HW_PEN_RENDER && !EPD_STROKE_RENDER) view.addLivePoint(p)   // live partial draw + local fast refresh
            }
        }

        override fun onRawDrawingTouchPointListReceived(pointList: TouchPointList) {
            val pts = pointList.points.map {
                StrokePoint(it.x, it.y, normalizePressure(it.pressure))
            }
            view.post {
                view.addStroke(Stroke(pts))
                pendingPoints.clear()
            }
        }

        override fun onEndRawDrawing(outLimitRegion: Boolean, point: TouchPoint) {
            if (EPD_STROKE_RENDER) runCatching {
                EpdController.finishStroke(view.baseStrokeWidth, point.x, point.y, point.pressure, point.size, point.timestamp.toFloat())
                EpdController.penUp()
                view.postDelayed(penPauseRunnable, 800L)
            }
            Log.i(TAG, "onEndRawDrawing strokes=${view.strokes.size} pending=${pendingPoints.size}")
            view.post {
                if (view.strokes.isEmpty() && pendingPoints.size >= 2) {
                    view.addStroke(Stroke(ArrayList(pendingPoints)))
                }
                pendingPoints.clear()
                view.endLiveStroke()   // clear live stroke and redraw (final stroke is in strokes)
                scheduleIdleCheck()
            }
        }

        override fun onBeginRawErasing(shortcut: Boolean, point: TouchPoint) {
            view.post {
                view.removeCallbacks(idleRunnable)
                cancelSpeculation()
            }
        }

        override fun onRawErasingTouchPointMoveReceived(point: TouchPoint) {}

        override fun onRawErasingTouchPointListReceived(pointList: TouchPointList) {
            val pts = pointList.points.map { StrokePoint(it.x, it.y, 1f) }
            view.post {
                if (view.eraseAt(pts, ERASER_RADIUS)) refreshAfterErase()
                scheduleIdleCheck()
            }
        }

        override fun onEndRawErasing(outLimitRegion: Boolean, point: TouchPoint) {
            view.post { scheduleIdleCheck() }
        }
    }

    private fun normalizePressure(raw: Float): Float =
        (raw / MAX_PRESSURE).coerceIn(0.05f, 1f)

    /** After erasing, briefly leave raw pen mode to redraw the remaining strokes and full-refresh. */
    private fun refreshAfterErase() {
        touchHelper?.setRawDrawingEnabled(false)
        view.invalidate()          // redraw remaining strokes (erased ones are gone)
        EInk.fullRefresh(view)     // GC full refresh to clear ghosting of erased ink
        view.postDelayed({
            if (state == State.WRITING) touchHelper?.setRawDrawingEnabled(true)
        }, 300)
    }

    // ------------------------------------------------------------- idle detection

    private fun scheduleIdleCheck() {
        view.removeCallbacks(idleRunnable)
        view.removeCallbacks(speculateRunnable)
        if (state == State.WRITING && view.strokes.isNotEmpty()) {
            view.postDelayed(idleRunnable, IDLE_MS)
            view.postDelayed(speculateRunnable, SPECULATE_MS)
        }
    }

    // Early speculative request: capture the page after a short rest and start the AI
    // call before the absorb threshold. If the pen comes back down, the in-flight
    // request is cancelled and a fresh one fires at the next rest. Captures are
    // in-memory byte arrays — nothing accumulates on disk.
    private var specDeferred: Deferred<Result<String>>? = null

    private val speculateRunnable = Runnable {
        if (state != State.WRITING || view.strokes.isEmpty()) return@Runnable
        val oracle = OracleFactory.create(prefs) ?: return@Runnable
        specDeferred?.cancel()
        specDeferred = scope.async {
            val png = withContext(Dispatchers.Default) { view.capturePagePng() }
            withContext(Dispatchers.IO) { runCatching { oracle.ask(png) } }
        }
    }

    private fun cancelSpeculation() {
        view.removeCallbacks(speculateRunnable)
        specDeferred?.cancel()
        specDeferred = null
    }

    private fun onIdle() {
        if (state != State.WRITING || view.strokes.isEmpty()) return
        startCycle()
    }

    // --------------------------------------------------------- main cycle (one round)

    private fun startCycle() {
        state = State.ABSORBING
        if (EPD_STROKE_RENDER) runCatching {
            view.removeCallbacks(penPauseRunnable)
            EpdController.setScreenHandWritingPenState(view, EpdPenManager.PEN_PAUSE)
        }
        skipLingerRequested = false

        cycleJob = scope.launch {
            // Seamless overlay handoff: paint the recorded strokes into the view's own
            // buffer FIRST (invisible while raw drawing holds the screen), so the refresh
            // triggered by disabling raw drawing swaps to identical content — no flash.
            EInk.animateFrame(view)
            delay(FRAME_MS * 2)
            touchHelper?.setRawDrawingEnabled(false)
            delay(FRAME_MS)

            // Prefer the speculative request fired after SPECULATE_MS of pen rest;
            // fall back to capture-and-ask now (still parallel with the absorb animation).
            val replyDeferred = specDeferred ?: OracleFactory.create(prefs)?.let { oracle ->
                async {
                    val png = withContext(Dispatchers.Default) { view.capturePagePng() }
                    withContext(Dispatchers.IO) { runCatching { oracle.ask(png) } }
                }
            }
            specDeferred = null
            view.removeCallbacks(speculateRunnable)

            animateAbsorb()
            view.clearStrokes()
            EInk.fullRefresh(view)

            state = State.AWAITING_REPLY
            val reply: String = when {
                replyDeferred == null -> activity.getString(R.string.toast_need_key)
                else -> {
                    val result = runCatching {
                        withTimeout(REPLY_TIMEOUT_MS) { replyDeferred.await() }
                    }.getOrNull()
                    result?.getOrNull() ?: silentReply(result?.exceptionOrNull())
                }
            }

            state = State.REVEALING
            view.setReply(reply)
            animateReveal()

            state = State.LINGERING
            // Pen is live from here on: starting to write interrupts the reply instantly.
            touchHelper?.setRawDrawingEnabled(true)
            lingerInterruptibly(lingerMillisFor(view.replyWords.size))

            if (penResumeRequested) {
                // Writer already has the pen down — wipe the reply fast, no ceremony.
                view.clearReply()
                EInk.animateFrame(view)
            } else {
                state = State.FADING_REPLY
                animateReplyFade()
                view.clearReply()
                // GC deghost only when idle; never while ink is being written above.
                if (penResumeRequested) EInk.animateFrame(view) else EInk.fullRefresh(view)
            }

            state = State.WRITING
            EInk.beginWriting(view)
            touchHelper?.setRawDrawingEnabled(true)
            penResumeRequested = false
            scheduleIdleCheck()
        }
    }

    private fun silentReply(cause: Throwable?): String {
        cause?.let {
            Toast.makeText(activity, "API error: ${it.message}", Toast.LENGTH_LONG).show()
        }
        return "……"
    }

    // ------------------------------------------------------------- animations
    //
    // E-ink refresh is slow, so a continuous per-frame gradient stutters. Ink level is
    // quantized to 5 steps; we scan the timeline with a fine sample step and only issue
    // a real DU4 fast refresh when some element crosses a step — every refresh is a
    // visible grayscale jump, dead frames are skipped, and the result is a crisp,
    // stepped absorb / reveal.

    /** Quantize to steps 0..4, consistent with DiaryView's quantization. */
    private fun level(a: Float): Int = Math.round(a.coerceIn(0f, 1f) * 4f)

    /**
     * Generic stepped-fade driver.
     * @param count   number of elements
     * @param totalMs total curve duration
     * @param setA    write element i's ink level in place
     * @param curve   given element i and time t, return its target level in [0,1]
     */
    private suspend fun runStagedFade(
        count: Int, totalMs: Long, setA: (Int, Float) -> Unit,
        abort: () -> Boolean = { false }, curve: (Int, Long) -> Float,
    ) {
        if (count == 0) return
        val prev = IntArray(count) { Int.MIN_VALUE }
        var t = 0L
        while (t <= totalMs) {
            var changed = false
            for (i in 0 until count) {
                val a = curve(i, t)
                setA(i, a)
                val lv = level(a)
                if (lv != prev[i]) { prev[i] = lv; changed = true }
            }
            if (changed) {
                EInk.animateFrame(view)
                delay(FRAME_MS)
            }
            if (abort()) return
            t += SAMPLE_MS
        }
    }

    /**
     * Ink absorption: split the strokes into bands in write order (offscreen bitmaps)
     * and fade the bands out with a stagger. Keeps the "absorbed head to tail" ordering
     * while drawing only a few bitmaps per frame — continuous and smooth.
     */
    private suspend fun animateAbsorb() {
        if (view.strokes.isEmpty()) return
        view.prepareAbsorb()
        val k = view.absorbBandAlphas.size
        if (k == 0) return
        val totalMs = ABSORB_BAND_STAGGER_MS * (k - 1) + ABSORB_BAND_FADE_MS
        runStagedFade(k, totalMs, { i, a -> view.absorbBandAlphas[i] = a }) { i, t ->
            val local = (t - i * ABSORB_BAND_STAGGER_MS).coerceAtLeast(0L)
            (1f - local.toFloat() / ABSORB_BAND_FADE_MS).coerceIn(0f, 1f)
        }
        view.finishAbsorb()
    }

    /** Reply reveal: a diagonal sweep — ink rises from the top-left to the bottom-right. */
    private suspend fun animateReveal() {
        val n = view.replyWords.size
        if (n == 0) return
        val starts = sweepStarts()
        runStagedFade(n, REVEAL_SWEEP_MS + FADE_MS, { i, a -> view.wordAlphas[i] = a }) { i, t ->
            ((t - starts[i]).coerceAtLeast(0L).toFloat() / FADE_MS).coerceIn(0f, 1f)
        }
        for (i in 0 until n) view.wordAlphas[i] = 1f
        EInk.animateFrame(view)
    }

    /** Reply fade: the same diagonal sweep, sinking back into the page. */
    private suspend fun animateReplyFade() {
        val n = view.replyWords.size
        if (n == 0) return
        val starts = sweepStarts()
        runStagedFade(n, REVEAL_SWEEP_MS + FADE_MS, { i, a -> view.wordAlphas[i] = a },
            abort = { penResumeRequested }) { i, t ->
            (1f - (t - starts[i]).coerceAtLeast(0L).toFloat() / FADE_MS).coerceIn(0f, 1f)
        }
    }

    /** Per-word start delays proportional to x+y (top-left first, bottom-right last). */
    private fun sweepStarts(): LongArray {
        val keys = FloatArray(view.replyWords.size) { view.replyWords[it].x + view.replyWords[it].y }
        val minK = keys.minOrNull() ?: 0f
        val span = ((keys.maxOrNull() ?: 0f) - minK).coerceAtLeast(1f)
        return LongArray(keys.size) { (REVEAL_SWEEP_MS * (keys[it] - minK) / span).toLong() }
    }

    private fun lingerMillisFor(wordCount: Int): Long =
        (1000L + wordCount * 110L).coerceIn(1800L, 9000L)

    private suspend fun lingerInterruptibly(millis: Long) {
        var waited = 0L
        while (waited < millis && !skipLingerRequested && !penResumeRequested) {
            delay(120)
            waited += 120
        }
    }

    companion object {
        /** Try the hardware pen-render path (works on some firmwares, e.g. T10C; Note X2 needs false). */
        const val HW_PEN_RENDER = true
        /** EXPERIMENT: draw live ink via EpdController.startStroke/addStrokePoint —
         *  the EPD accelerated hand-writing overlay (untested by upstream on this path). */
        const val EPD_STROKE_RENDER = false

        const val TAG = "RiddleDiary"

        /** How long the pen must rest to count as "a passage finished" and trigger absorption
         *  (the original riddle project uses 2.8s). */
        const val IDLE_MS = 2000L
        const val SPECULATE_MS = 800L   // early page capture + AI request while waiting for IDLE_MS
        const val REVEAL_SWEEP_MS = 900L

        /** Minimum interval after each real refresh — DU4 fast refresh is ~150ms. */
        const val FRAME_MS = 130L

        /** Timeline sampling step (does not refresh; only used to detect step crossings). */
        const val SAMPLE_MS = 30L

        const val FADE_MS = 420L                  // reply: one word from full to 0 (or reverse)
        const val ABSORB_BAND_FADE_MS = 300L      // absorb: one band from full down to 0
        const val ABSORB_BAND_STAGGER_MS = 75L    // absorb: gap between adjacent bands starting to fade (ordering)
        const val ABSORB_TOTAL_STAGGER_MS = 1100L // reply fade: total stagger budget
        const val ABSORB_STAGGER_MAX_MS = 90L

        const val REVEAL_WORD_MS = 55L            // gap between adjacent words starting to reveal

        const val REPLY_TIMEOUT_MS = 150_000L
        const val ERASER_RADIUS = 24f
        const val MAX_PRESSURE = 4096f
    }
}
