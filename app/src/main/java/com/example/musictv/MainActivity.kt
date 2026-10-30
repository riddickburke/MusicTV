package com.example.musictv

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlin.math.min

private const val START_URL = "https://music.apple.com"
private const val DESKTOP_UA =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

/** Draws the on-screen pointer. Never takes touch input itself. */
class CursorView(ctx: Context) : View(ctx) {
    var px = 0f
    var py = 0f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f
    }

    override fun onDraw(c: Canvas) {
        c.drawCircle(px, py, 14f, fill)
        c.drawCircle(px, py, 14f, ring)
    }
}

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var cursor: CursorView
    private lateinit var target: WebView // main view, or the sign-in popup while open
    private var cx = 0f
    private var cy = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this)
        web = newWebView()
        target = web
        cursor = CursorView(this)
        root.addView(web, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(cursor, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setContentView(root)

        root.post {
            cx = root.width / 2f
            cy = root.height / 2f
            syncCursor()
        }
        web.loadUrl(START_URL)
    }

    private fun newWebView(): WebView {
        val w = WebView(this)
        w.isFocusable = false
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true) // Apple's sign-in opens a popup
            mediaPlaybackRequiresUserGesture = false
            userAgentString = DESKTOP_UA
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.webViewClient = WebViewClient()
        w.webChromeClient = object : WebChromeClient() {
            // Needed for Widevine (protected media) playback
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }

            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message
            ): Boolean {
                val popup = newWebView()
                popup.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView) {
                        root.removeView(popup)
                        popup.destroy()
                        target = web
                    }

                    override fun onPermissionRequest(request: PermissionRequest) {
                        runOnUiThread { request.grant(request.resources) }
                    }
                }
                root.addView(popup, 1, ViewGroup.LayoutParams(-1, -1))
                target = popup
                (resultMsg.obj as WebView.WebViewTransport).webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }
        return w
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down) moveCursor(e.keyCode, e.repeatCount)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (!down) tap()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (down) {
                    if (target !== web) target.evaluateJavascript("window.close()", null)
                    else if (web.canGoBack()) web.goBack()
                    else finish()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (down) musicJs("m.isPlaying ? m.pause() : m.play()")
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { if (down) musicJs("m.skipToNextItem()"); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (down) musicJs("m.skipToPreviousItem()"); return true }
        }
        return super.dispatchKeyEvent(e)
    }

    private fun musicJs(body: String) {
        web.evaluateJavascript(
            "(function(){var m=window.MusicKit&&MusicKit.getInstance&&MusicKit.getInstance();if(!m)return;$body})()",
            null
        )
    }

    private fun moveCursor(key: Int, repeat: Int) {
        val s = 24f * (1 + min(repeat, 20) / 4f)
        val w = root.width.toFloat()
        val h = root.height.toFloat()
        when (key) {
            KeyEvent.KEYCODE_DPAD_LEFT -> cx = (cx - s).coerceAtLeast(0f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> cx = (cx + s).coerceAtMost(w - 1)
            KeyEvent.KEYCODE_DPAD_UP -> if (cy <= 4f) wheel(+1f) else cy = (cy - s).coerceAtLeast(0f)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (cy >= h - 5f) wheel(-1f) else cy = (cy + s).coerceAtMost(h - 1)
        }
        syncCursor()
    }

    private fun syncCursor() {
        cursor.px = cx
        cursor.py = cy
        cursor.invalidate()
    }

    /** Mouse-wheel scroll at the pointer; works for inner scroll containers too. */
    private fun wheel(amount: Float) {
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            x = cx; y = cy; setAxisValue(MotionEvent.AXIS_VSCROLL, amount)
        })
        val t = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(
            t, t, MotionEvent.ACTION_SCROLL, 1, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        target.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    private fun tap() {
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, cx, cy, 0)
        val up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, cx, cy, 0)
        target.dispatchTouchEvent(down)
        target.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        super.onDestroy()
    }
}
