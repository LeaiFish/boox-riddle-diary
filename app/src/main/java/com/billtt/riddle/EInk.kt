package com.billtt.riddle

import android.view.View
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode

/**
 * Thin wrapper around e-ink refresh control.
 * Every call is wrapped in runCatching so it degrades to a no-op on non-BOOX
 * devices (e.g. an emulator).
 *
 * Key point: multi-frame fade animations must not use the high-quality GU mode
 * (~300ms per refresh) or they stutter badly. We use DU4 (4-level grayscale fast
 * refresh, ~150ms) plus ink-level quantization to get a crisp "stepped" fade in/
 * out, and do a single GC full refresh at the end of each cycle to clear ghosting.
 */
object EInk {

    /** Writing phase: A2/animation binary fast mode — lowest latency for live ink. */
    fun beginWriting(view: View) {
        runCatching { EpdController.setViewDefaultUpdateMode(view, UpdateMode.ANIMATION) }
    }

    /** Enter the animation phase: set this view's default refresh mode to DU4 fast refresh. */
    fun beginAnimation(view: View) {
        runCatching { EpdController.setViewDefaultUpdateMode(view, UpdateMode.DU4) }
    }

    /**
     * Render one animation frame: a plain invalidate() triggers View.onDraw (so the
     * new ink levels are actually drawn); the e-ink refresh itself uses the DU4 fast
     * mode set by beginAnimation().
     * Note: do NOT use EpdController.postInvalidate here — it only refreshes the ink
     * layer and does not trigger onDraw.
     */
    fun animateFrame(view: View) {
        view.invalidate()
    }

    /** A single full-screen high-quality refresh (GC), used to clear ghosting at the end of a cycle. */
    fun fullRefresh(view: View) {
        runCatching { EpdController.invalidate(view, UpdateMode.GC) }
            .onFailure { view.invalidate() }
    }
}
