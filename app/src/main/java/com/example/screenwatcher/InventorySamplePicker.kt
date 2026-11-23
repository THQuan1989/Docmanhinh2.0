package com.example.screenwatcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Overlay cho người dùng chạm chọn trực tiếp các ô vật phẩm làm mẫu GIỮ. */
class InventorySamplePicker(
    private val ctx: Context,
    private val frame: Bitmap,
    private val ruleId: String,
    private val regions: List<Region>,
    private val onDone: (Int) -> Unit,
    private val onCancel: () -> Unit,
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var size = InventoryVision.cellSizePx(ctx, ruleId)
    private var ox = InventoryVision.offsetX(ctx, ruleId)
    private var oy = InventoryVision.offsetY(ctx, ruleId)
    private var cells: List<Rect> = InventoryVision.buildCells(frame.width, frame.height, regions, size, ox, oy)
    private fun rebuild() {
        cells = InventoryVision.buildCells(frame.width, frame.height, regions, size, ox, oy)
        InventoryVision.setOffset(ctx, ruleId, ox, oy)
        selected.clear(); refresh()
    }
    private fun applySaved() {
        size = InventoryVision.cellSizePx(ctx, ruleId)
        ox = InventoryVision.offsetX(ctx, ruleId); oy = InventoryVision.offsetY(ctx, ruleId)
        cells = InventoryVision.buildCells(frame.width, frame.height, regions, size, ox, oy)
        selected.clear(); refresh()
    }
    private var note = ""
    private fun autoAlign() {
        if (InventoryVision.autoAlign(ctx, ruleId, frame, regions)) { note = "Đã tự căn lưới (ô ${InventoryVision.cellPx(ctx, ruleId)}px). Chỉnh thêm nếu cần. "; applySaved() }
        else { note = "Không dò được lưới tự động (vùng quá nhỏ hoặc không thấy viền ô), hãy căn tay. "; refresh() }
    }
    private fun resize(deltaPx: Int) {
        InventoryVision.setCellPx(ctx, ruleId, InventoryVision.cellPx(ctx, ruleId) + deltaPx)
        size = InventoryVision.cellSizePx(ctx, ruleId)
        rebuild()
    }
    private fun nudge(dx: Int, dy: Int) { ox += dx; oy += dy; rebuild() }
    private val selected = LinkedHashSet<Int>()
    private val view = GridView()
    private val bar = LinearLayout(ctx)
    private val status = TextView(ctx)
    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    private fun params(h: Int, gravity: Int, flags: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        flags, PixelFormat.TRANSLUCENT
    ).apply {
        this.gravity = gravity
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
    private fun button(label: String, weight: Float, action: () -> Unit) = Button(ctx).apply {
        text = label; isAllCaps = false; textSize = 12f; minWidth = 0; minimumWidth = 0
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        setOnClickListener { action() }
    }

    fun show() {
        bar.orientation = LinearLayout.VERTICAL; bar.setPadding(dp(8), dp(6), dp(8), dp(6))
        bar.background = GradientDrawable().apply { setColor(Color.parseColor("#E6202020")) }
        status.setTextColor(Color.WHITE); status.textSize = 13f
        bar.addView(status)
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("💾 Lưu mẫu", 1.2f) {
            var n = 0
            selected.forEach { if (InventoryVision.addSample(ctx, ruleId, frame, cells[it])) n++ }
            hide(); onDone(n)
        })
        row.addView(button("Bỏ chọn", 0.9f) { selected.clear(); refresh() })
        row.addView(button("−", 0.4f) { resize(-2) })
        row.addView(button("+", 0.4f) { resize(2) })
        row.addView(button("Huỷ", 0.8f) { hide(); onCancel() })
        bar.addView(row)
        val move = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        move.addView(button("◀", 1f) { nudge(-3, 0) })
        move.addView(button("▲", 1f) { nudge(0, -3) })
        move.addView(button("▼", 1f) { nudge(0, 3) })
        move.addView(button("▶", 1f) { nudge(3, 0) })
        move.addView(button("🎯 Tự căn", 1.4f) { autoAlign() })
        move.addView(button("Đặt lại", 1.2f) { ox = 0; oy = 0; rebuild() })
        bar.addView(move)
        if (!InventoryVision.hasGrid(ctx, ruleId)) autoAlign() else refresh()
        wm.addView(view, params( WindowManager.LayoutParams.MATCH_PARENT, Gravity.TOP, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN))
        wm.addView(bar, params(WindowManager.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL).apply { y = dp(40) })
    }
    fun hide() { runCatching { wm.removeView(view) }; runCatching { wm.removeView(bar) } }
    private fun refresh() { status.text = note + "Chạm ô vật phẩm muốn GIỮ (xanh). Đã chọn: ${selected.size}. Cỡ ô: ${size}px (−/+ đổi cỡ). KÉO ngón tay trên màn hình hoặc bấm ◀▲▼▶ để dịch lưới cho khớp ô; dịch lưới sẽ bỏ chọn. Ô còn lại vào JOB."; view.invalidate() }

    private inner class GridView : View(ctx) {
        private val stroke = Paint().apply { style = Paint.Style.STROKE; strokeWidth = dp(3).toFloat() }
        private val fill = Paint().apply { style = Paint.Style.FILL }
        private var downX = 0f; private var downY = 0f
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(0x33000000)
            val loc = IntArray(2); getLocationOnScreen(loc); canvas.save(); canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
            cells.forEachIndexed { index, rect ->
                val keep = index in selected
                val color = if (keep) Color.rgb(46, 204, 113) else Color.rgb(255, 152, 0)
                fill.color = color; fill.alpha = if (keep) 70 else 28
                canvas.drawRect(RectF(rect), fill)
                stroke.color = color; canvas.drawRect(RectF(rect), stroke)
            }
            canvas.restore()
        }
        private var lastX = 0f; private var lastY = 0f; private var dragging = false
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; lastX = downX; lastY = downY; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && kotlin.math.hypot((event.rawX - downX).toDouble(), (event.rawY - downY).toDouble()) >= dp(16)) dragging = true
                    if (dragging) {
                        val dx = (event.rawX - lastX).toInt(); val dy = (event.rawY - lastY).toInt()
                        if (dx != 0 || dy != 0) {
                            ox += dx; oy += dy
                            lastX += dx; lastY += dy
                            cells = InventoryVision.buildCells(frame.width, frame.height, regions, size, ox, oy)
                            selected.clear(); invalidate()
                        }
                    }
                }
                MotionEvent.ACTION_UP -> if (dragging) { rebuild() } else {
                    val hit = cells.indices.firstOrNull { cells[it].contains(event.rawX.toInt(), event.rawY.toInt()) }
                    if (hit != null) { if (!selected.add(hit)) selected.remove(hit); refresh() }
                }
            }
            return true
        }
    }
}
