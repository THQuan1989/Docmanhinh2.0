package com.example.screenwatcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Chọn vật phẩm GIỮ NGUYÊN bằng cách chạm trên màn hình (Inventory Protector).
 * Hiện các vật AI vừa thấy: khung xanh = giữ nguyên, khung cam = vào hàng đợi JOB.
 * Chạm một vật là đổi trạng thái của CẢ LOẠI đó (mọi vật cùng nhãn).
 * [onDone] nhận (các nhãn được giữ, các nhãn đang hiện trên màn hình), nhãn đều viết thường.
 */
class ItemPickerOverlay(
    private val ctx: Context,
    private val items: List<AiDetector.Item>,
    initialProtected: Set<String>,
    private val onDone: (Set<String>, Set<String>) -> Unit,
    private val onCancel: () -> Unit,
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prot = HashSet<String>(initialProtected)
    private val visible: Set<String> = items.map { it.label.lowercase() }.toSet()
    private val dv = ItemView()
    private val bar = LinearLayout(ctx)
    private val status = TextView(ctx)

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun params(h: Int, g: Int, flags: Int) = LayoutParams(
        LayoutParams.MATCH_PARENT, h, LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = g
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    private fun btn(t: String, w: Float, f: () -> Unit) = Button(ctx).apply {
        text = t; isAllCaps = false; textSize = 12f; maxLines = 1
        minWidth = 0; minimumWidth = 0; setPadding(dp(2), 0, dp(2), 0)
        setOnClickListener { f() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w)
    }

    fun show() {
        bar.orientation = LinearLayout.VERTICAL
        bar.setPadding(dp(8), dp(6), dp(8), dp(6))
        bar.background = GradientDrawable().apply { setColor(Color.parseColor("#E6202020")) }
        status.setTextColor(Color.WHITE); status.textSize = 13f
        bar.addView(status)
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(btn("✔ Xong", 1.2f) { hide(); onDone(HashSet(prot), visible) })
        row.addView(btn("Giữ tất cả", 1.2f) { prot.addAll(visible); refresh() })
        row.addView(btn("Bỏ giữ hết", 1.2f) { prot.removeAll(visible); refresh() })
        row.addView(btn("✖ Huỷ", 0.9f) { hide(); onCancel() })
        bar.addView(row)
        refresh()
        wm.addView(
            dv,
            params(
                LayoutParams.MATCH_PARENT,
                0,
                LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            ),
        )
        wm.addView(
            bar,
            params(
                LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCH_MODAL,
            ).apply { y = dp(40) },
        )
    }

    fun hide() {
        runCatching { wm.removeView(dv) }
        runCatching { wm.removeView(bar) }
    }

    private fun refresh() {
        val keep = items.filter { it.label.lowercase() in prot }
        val job = items.filter { it.label.lowercase() !in prot }
        fun names(l: List<AiDetector.Item>) =
            if (l.isEmpty()) "—" else l.groupingBy { it.label }.eachCount().entries.joinToString(", ") { "${it.key}×${it.value}" }
        status.text = "Chạm vào vật để đổi: XANH = giữ nguyên, CAM = vào hàng đợi JOB.\n" +
            "Giữ nguyên: ${names(keep)}\nHàng đợi JOB: ${names(job)}"
        dv.invalidate()
    }

    private inner class ItemView : View(ctx) {
        private val stroke = Paint().apply { style = Paint.Style.STROKE; strokeWidth = dp(3).toFloat() }
        private val fill = Paint().apply { style = Paint.Style.FILL }
        private val txt = Paint().apply { textSize = dp(13).toFloat(); isAntiAlias = true; color = Color.WHITE }
        private val tbg = Paint().apply { style = Paint.Style.FILL }
        private var downX = 0f
        private var downY = 0f

        private fun box(i: AiDetector.Item): RectF {
            val w = if (i.w > 0) i.w.toFloat() else dp(56).toFloat()
            val h = if (i.h > 0) i.h.toFloat() else dp(56).toFloat()
            return RectF(i.x - w / 2, i.y - h / 2, i.x + w / 2, i.y + h / 2)
        }

        override fun onDraw(c: Canvas) {
            c.drawColor(0x55000000)
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            c.save()
            c.translate(-loc[0].toFloat(), -loc[1].toFloat())
            for (i in items) {
                val keep = i.label.lowercase() in prot
                val col = if (keep) Color.parseColor("#2ECC71") else Color.parseColor("#FF7043")
                val r = box(i)
                fill.color = col; fill.alpha = 70
                c.drawRect(r, fill)
                stroke.color = col
                c.drawRect(r, stroke)
                val tag = (if (keep) "🛡 " else "JOB ") + i.label
                val tw = txt.measureText(tag) + dp(8)
                tbg.color = col
                c.drawRect(r.left, r.top - dp(20), r.left + tw, r.top, tbg)
                c.drawText(tag, r.left + dp(4), r.top - dp(5), txt)
            }
            c.restore()
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY }
                MotionEvent.ACTION_UP -> {
                    if (Math.hypot((e.rawX - downX).toDouble(), (e.rawY - downY).toDouble()) < dp(16)) toggleAt(e.rawX, e.rawY)
                }
            }
            return true
        }

        private fun toggleAt(x: Float, y: Float) {
            val pad = dp(12).toFloat()
            val hit = items.filter { val r = box(it); x >= r.left - pad && x <= r.right + pad && y >= r.top - pad && y <= r.bottom + pad }
                .minByOrNull { val r = box(it); r.width() * r.height() } ?: return
            val k = hit.label.lowercase()
            if (!prot.remove(k)) prot.add(k)
            refresh()
        }
    }
}
