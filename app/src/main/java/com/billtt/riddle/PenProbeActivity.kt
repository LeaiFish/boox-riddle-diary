package com.billtt.riddle

import android.app.Activity
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList

/**
 * Latency probe replicating the official OnyxPenDemo combination exactly:
 * SurfaceView host + default TouchHelper.create() (hardware-rendered ink).
 * Not registered in the launcher; start with:
 *   adb shell am start -n com.billtt.riddle/.PenProbeActivity
 * Watch: does ink appear instantly while writing? Do callbacks log?
 *   adb logcat -s PenProbe
 */
class PenProbeActivity : Activity() {

    private lateinit var surface: SurfaceView
    private var touchHelper: TouchHelper? = null

    private val callback = object : RawInputCallback() {
        override fun onBeginRawDrawing(b: Boolean, p: TouchPoint) { Log.i(TAG, "begin") }
        override fun onEndRawDrawing(b: Boolean, p: TouchPoint) { Log.i(TAG, "end") }
        override fun onRawDrawingTouchPointMoveReceived(p: TouchPoint) { Log.i(TAG, "move ${p.x},${p.y}") }
        override fun onRawDrawingTouchPointListReceived(l: TouchPointList) { Log.i(TAG, "list n=${l.points.size}") }
        override fun onBeginRawErasing(b: Boolean, p: TouchPoint) {}
        override fun onEndRawErasing(b: Boolean, p: TouchPoint) {}
        override fun onRawErasingTouchPointMoveReceived(p: TouchPoint) {}
        override fun onRawErasingTouchPointListReceived(l: TouchPointList) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surface = SurfaceView(this)
        setContentView(surface)
        touchHelper = TouchHelper.create(surface, callback)
        surface.addOnLayoutChangeListener(object : android.view.View.OnLayoutChangeListener {
            override fun onLayoutChange(v: android.view.View, l: Int, t: Int, r: Int, b: Int,
                                        ol: Int, ot: Int, orr: Int, ob: Int) {
                if (surface.width == 0) return
                surface.removeOnLayoutChangeListener(this)
                val limit = Rect()
                surface.getLocalVisibleRect(limit)
                touchHelper!!.setStrokeWidth(4.0f)
                    .setLimitRect(limit, arrayListOf())
                    .openRawDrawing()
                touchHelper!!.setStrokeStyle(TouchHelper.STROKE_STYLE_FOUNTAIN)
                touchHelper!!.setRawDrawingEnabled(true)
                Log.i(TAG, "probe ready (demo-timing) limit=$limit")
            }
        })
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                holder.lockCanvas()?.let { c -> c.drawColor(android.graphics.Color.WHITE); holder.unlockCanvasAndPost(c) }
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {}
        })
    }

    override fun onResume() {
        super.onResume()
        touchHelper?.setRawDrawingEnabled(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        touchHelper?.closeRawDrawing()
    }

    companion object { const val TAG = "PenProbe" }
}
