package com.example.screenwatcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** So khớp vật phẩm bằng embedding MobileCLIP2-S2; mọi suy luận chạy offline. */
object InventoryVision {
    private const val VERSION = 3
    private const val DEFAULT_CELL_DP = 64
    // Ngưỡng cosine cần kiểm thử thực tế trên game; có thể điều chỉnh sau khi đo dữ liệu.
    private const val KEEP_THRESHOLD = 0.86f
    private const val EMBEDDING_SIZE = 512

    data class Item(val label: String, val score: Float, val x: Int, val y: Int, val w: Int = 0, val h: Int = 0, val aiLabels: List<String> = emptyList())
    private data class Sample(val embedding: FloatArray)

    private fun prefs(c: Context) = c.getSharedPreferences("sw", Context.MODE_PRIVATE)
    fun hasGrid(c: Context, ruleId: String): Boolean = prefs(c).contains("invCellPx_$ruleId")
    fun cellPx(c: Context, ruleId: String): Int {
        val v = prefs(c).getInt("invCellPx_$ruleId", 0)
        return if (v > 0) v.coerceIn(16, 600) else (DEFAULT_CELL_DP * c.resources.displayMetrics.density).toInt().coerceAtLeast(32)
    }
    fun setCellPx(c: Context, ruleId: String, px: Int) { prefs(c).edit().putInt("invCellPx_$ruleId", px.coerceIn(16, 600)).apply() }
    /** Kích thước ô (px). Có ruleId thì dùng cỡ ô riêng của luật đó (tự căn hoặc chỉnh tay), không thì dùng mặc định 64dp. */
    fun cellSizePx(c: Context, ruleId: String? = null): Int =
        if (ruleId == null) (DEFAULT_CELL_DP * c.resources.displayMetrics.density).toInt().coerceAtLeast(32) else cellPx(c, ruleId)

    fun offsetX(c: Context, ruleId: String): Int = prefs(c).getInt("invOx_$ruleId", 0)
    fun offsetY(c: Context, ruleId: String): Int = prefs(c).getInt("invOy_$ruleId", 0)
    fun setOffset(c: Context, ruleId: String, ox: Int, oy: Int) { prefs(c).edit().putInt("invOx_$ruleId", ox).putInt("invOy_$ruleId", oy).apply() }

    /**
     * Chia các vùng thành lưới ô vuông. Lưới neo theo góc trên-trái MÀN HÌNH, dịch (ox, oy) px,
     * nên nhiều vùng dùng chung một lưới. Ô lấy khi tâm nằm trong vùng và ô nằm trọn trong màn hình.
     */
    fun buildCells(frameW: Int, frameH: Int, regions: List<Region>, size: Int, ox: Int = 0, oy: Int = 0): List<Rect> {
        val out = ArrayList<Rect>()
        val sx = Math.floorMod(ox, size)
        val sy = Math.floorMod(oy, size)
        for (rg in regions) {
            val l = rg.l.coerceIn(0, frameW); val t = rg.t.coerceIn(0, frameH)
            val r = rg.r.coerceIn(l, frameW); val b = rg.b.coerceIn(t, frameH)
            var y = sy + Math.floorDiv(t - sy, size) * size
            while (y < b) {
                var x = sx + Math.floorDiv(l - sx, size) * size
                while (x < r) {
                    val cx = x + size / 2; val cy = y + size / 2
                    if (x >= 0 && y >= 0 && x + size <= frameW && y + size <= frameH && cx >= l && cx < r && cy >= t && cy < b) {
                        val cand = Rect(x, y, x + size, y + size)
                        if (out.none { overlapArea(it, cand) > size.toLong() * size / 4 }) out.add(cand)
                    }
                    x += size
                }
                y += size
            }
        }
        return out
    }

    private class Comb(val score: Double, val offset: Int)

    /** Tìm độ lệch o sao cho các vạch biên tại o, o+p, o+2p... mạnh nhất (cho phép lệch ±1px). */
    private fun comb(prof: DoubleArray, p: Int): Comb? {
        val mean = prof.average().coerceAtLeast(1e-6)
        var best = -1.0; var bo = 0
        for (o in 0 until p) {
            var sum = 0.0; var n = 0
            var x = o
            while (x < prof.size) {
                var m = 0.0
                for (k in -1..1) { val i = x + k; if (i in prof.indices && prof[i] > m) m = prof[i] }
                sum += m; n++; x += p
            }
            if (n >= 2) { val sc = sum / n / mean; if (sc > best) { best = sc; bo = o } }
        }
        return if (best < 0) null else Comb(best, bo)
    }

    /**
     * Tự dò bước ô và vị trí lưới từ các đường viền ô trong vùng lớn nhất, rồi lưu cho luật.
     * Trả về true nếu tìm được.
     */
    @Synchronized fun autoAlign(c: Context, ruleId: String, frame: Bitmap, regions: List<Region>): Boolean {
        val rg = regions.maxByOrNull { it.w.toLong() * it.h } ?: return false
        val l = rg.l.coerceIn(0, frame.width - 1); val t = rg.t.coerceIn(0, frame.height - 1)
        val r = rg.r.coerceIn(l + 1, frame.width); val b = rg.b.coerceIn(t + 1, frame.height)
        val w = r - l; val h = b - t
        if (w < 40 || h < 40) return false
        val px = IntArray(w * h)
        frame.getPixels(px, 0, w, l, t, w, h)
        val lum = IntArray(w * h) { val q = px[it]; (((q shr 16) and 255) * 30 + ((q shr 8) and 255) * 59 + (q and 255) * 11) / 100 }
        val colP = DoubleArray(w); val rowP = DoubleArray(h)
        for (y in 0 until h) for (x in 0 until w - 1) colP[x] += Math.abs(lum[y * w + x + 1] - lum[y * w + x]).toDouble()
        for (y in 0 until h - 1) for (x in 0 until w) rowP[y] += Math.abs(lum[(y + 1) * w + x] - lum[y * w + x]).toDouble()
        val maxP = minOf(w, h) / 2
        if (maxP < 16) return false
        val scores = HashMap<Int, Triple<Double, Int, Int>>()
        var best = 0.0
        for (p in 16..maxP) {
            val cx = comb(colP, p) ?: continue
            val cy = comb(rowP, p) ?: continue
            val sc = (cx.score + cy.score) / 2
            scores[p] = Triple(sc, cx.offset, cy.offset)
            if (sc > best) best = sc
        }
        if (best < 1.5) return false
        // Chọn bước nhỏ nhất đạt ≥90% điểm cao nhất (tránh chọn bội số 2p, 3p).
        val p0 = scores.keys.sorted().firstOrNull { scores[it]!!.first >= best * 0.9 } ?: return false
        // Tinh chỉnh ±1px quanh bước vừa chọn (bước thật thường không phải số nguyên).
        val p = (p0 - 1..p0 + 1).filter { scores.containsKey(it) }.maxByOrNull { scores[it]!!.first } ?: p0
        val (_, offX, offY) = scores[p]!!
        setCellPx(c, ruleId, p)
        setOffset(c, ruleId, l + offX + 1, t + offY + 1)
        return true
    }

    private fun overlapArea(a: Rect, b: Rect): Long {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return if (w > 0 && h > 0) w.toLong() * h else 0L
    }
    fun file(c: Context, ruleId: String) = File(c.filesDir, "inv_samples_$ruleId.bin")

    @Synchronized fun count(c: Context, ruleId: String): Int = load(c, ruleId).size
    @Synchronized fun clear(c: Context, ruleId: String) { file(c, ruleId).delete() }

    @Synchronized fun addSample(c: Context, ruleId: String, frame: Bitmap, rect: Rect): Boolean {
        val l = rect.left.coerceIn(0, frame.width - 1)
        val t = rect.top.coerceIn(0, frame.height - 1)
        val r = rect.right.coerceIn(l + 1, frame.width)
        val b = rect.bottom.coerceIn(t + 1, frame.height)
        if (r - l < 12 || b - t < 12) return false
        val crop = Bitmap.createBitmap(frame, l, t, r - l, b - t)
        val embedding = try { MobileClip2.embed(c, crop) } finally { crop.recycle() }
        if (embedding.size != EMBEDDING_SIZE) return false
        val samples = load(c, ruleId).toMutableList()
        if (samples.none { MobileClip2.cosine(it.embedding, embedding) >= 0.995f }) samples.add(Sample(embedding))
        save(c, ruleId, samples)
        return true
    }

    @Synchronized fun scan(c: Context, ruleId: String, frame: Bitmap, regions: List<Region>): List<Item> {
        val samples = load(c, ruleId)
        if (samples.isEmpty() || regions.isEmpty()) return emptyList()
        val size = cellSizePx(c, ruleId)
        val out = ArrayList<Item>()
        for (rect in buildCells(frame.width, frame.height, regions, size, offsetX(c, ruleId), offsetY(c, ruleId))) {
            val crop = Bitmap.createBitmap(frame, rect.left, rect.top, size, size)
            val embedding = try { MobileClip2.embed(c, crop) } finally { crop.recycle() }
            val best = samples.maxOfOrNull { MobileClip2.cosine(it.embedding, embedding) } ?: -1f
            val keep = best >= KEEP_THRESHOLD
            val label = if (keep) "GIU_${(best * 100).toInt()}" else "JOB"
            out.add(Item(label, if (keep) best else 1f - best, rect.left + size / 2, rect.top + size / 2, size, size))
        }
        return out.sortedWith(compareBy({ it.y / size }, { it.x }))
    }

    private fun load(c: Context, id: String): List<Sample> {
        val f = file(c, id)
        if (!f.exists()) return emptyList()
        return runCatching {
            DataInputStream(f.inputStream().buffered()).use { input ->
                if (input.readInt() != VERSION) return@use emptyList()
                val n = input.readInt().coerceIn(0, 300)
                List(n) { Sample(FloatArray(EMBEDDING_SIZE) { input.readFloat() }) }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(c: Context, id: String, samples: List<Sample>) {
        val target = file(c, id)
        val tmp = File(c.filesDir, "inv_samples_$id.tmp")
        runCatching {
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(VERSION); out.writeInt(samples.size)
                samples.forEach { sample -> sample.embedding.forEach(out::writeFloat) }
            }
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        }.onFailure { tmp.delete() }
    }
}
