package com.sam.airblock.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import com.sam.airblock.R
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Type styles of the Flight strip: Google Sans Flex (the Material 3
 * Expressive face, bundled under the OFL) with its round terminals. Launchers
 * refuse app fonts in widget text, so it is drawn into bitmaps — alpha masks
 * the launcher tints, so the colours still follow light/dark and the
 * wallpaper palette. [ascentEm]/[descentEm] trim the line box.
 */
internal enum class StripType(
    val weight: Int,
    val width: Int,
    val tracking: Float,
    val ascentEm: Float,
    val descentEm: Float,
) {
    /** The bold face: callsign, airport codes, stat values. */
    HERO(weight = 760, width = 108, tracking = -0.005f, ascentEm = 0.84f, descentEm = 0.25f),
    /** The quieter face. Full ascent: city and airline names carry accents. */
    SUPPORT(weight = 540, width = 100, tracking = 0.004f, ascentEm = 0.97f, descentEm = 0.29f),
}

/** The stats pill's footprint inside the photo, in photo pixels. */
internal data class CutOut(val rect: RectF, val radius: Float, val gap: Float)

/** Draws every bitmap the strip shows. Create per render; cheap. */
internal class StripArt(context: Context) {

    private val app = context.applicationContext
    private val dpi = context.resources.displayMetrics.densityDpi
    val density = context.resources.displayMetrics.density

    // ---- type -------------------------------------------------------------

    fun paint(type: StripType, sizePx: Float, numeric: Boolean, weight: Int = type.weight): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = typeface(app)
            textSize = sizePx
            setFontVariationSettings("'wght' $weight, 'wdth' ${type.width}")
            letterSpacing = type.tracking
            // Tabular digits: "8,000" → "8,100" must not nudge the layout
            if (numeric) fontFeatureSettings = "'tnum'"
        }

    /** Width [text] takes as drawn by [text], side padding included. */
    fun measure(text: String, type: StripType, sizePx: Float, numeric: Boolean = false): Float =
        paint(type, sizePx, numeric).measureText(text) + 2 * pad(sizePx)

    fun lineHeight(type: StripType, sizePx: Float): Int =
        ceil(type.ascentEm * sizePx).toInt() + ceil(type.descentEm * sizePx).toInt()

    /**
     * [text] as an alpha mask, cut to [maxWidthPx] with an ellipsis if needed.
     * [box] (ascent, descent in px) overrides the line box, so texts of two
     * sizes on one line can share a baseline.
     */
    fun text(
        text: String,
        type: StripType,
        sizePx: Float,
        numeric: Boolean = false,
        maxWidthPx: Float = Float.MAX_VALUE,
        box: Pair<Float, Float>? = null,
    ): Bitmap {
        val p = paint(type, sizePx, numeric)
        val padPx = pad(sizePx)
        val fitted = if (p.measureText(text) + 2 * padPx <= maxWidthPx) text
        else TextUtils.ellipsize(text, p, max(0f, maxWidthPx - 2 * padPx),
            TextUtils.TruncateAt.END).toString()
        val w = ceil(p.measureText(fitted) + 2 * padPx).toInt().coerceAtLeast(1)
        val top = box?.first?.let { ceil(it).toInt() } ?: ceil(type.ascentEm * sizePx).toInt()
        val h = box?.let { top + ceil(it.second).toInt() } ?: lineHeight(type, sizePx)
        return alpha(w, h) { it.drawText(fitted, padPx, top.toFloat(), p) }
    }

    private fun pad(sizePx: Float) = ceil(sizePx * 0.04f)

    /** Vertical middle of the capitals on a [type] line — where glyphs centre. */
    private fun capMiddle(type: StripType, sizePx: Float) =
        ceil(type.ascentEm * sizePx) - CAP_EM * sizePx / 2f

    // ---- route line -------------------------------------------------------

    /**
     * The M3 Expressive wavy progress indicator as a flight path: wavy where
     * already flown, a faint flat track ahead ending in a stop dot, and the
     * plane at [progress]. Centred on the capitals of [sizePx] hero text.
     */
    fun route(progress: Float?, widthPx: Int, sizePx: Float): Bitmap {
        val type = StripType.HERO
        val h = lineHeight(type, sizePx)
        val cy = capMiddle(type, sizePx)
        val stroke = sizePx * 0.14f
        val amp = sizePx * 0.1f
        val wavelength = sizePx * 0.6f
        val plane = sizePx * 0.9f
        val gap = plane / 2f + stroke * 0.6f
        val first = plane / 2f + stroke
        val last = widthPx - plane / 2f - stroke * 2.5f
        val planeX = first + (last - first) * (progress ?: 0.5f).coerceIn(0f, 1f)
        return alpha(widthPx, h) { c ->
            val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                color = Color.BLACK
            }
            val faint = Paint(solid).apply { alpha = 92 }
            val start = stroke / 2f + 0.5f
            if (progress != null) {
                val wave = Path()
                var x = start
                wave.moveTo(x, cy)
                while (x < planeX - gap) {
                    x = min(x + 1.5f, planeX - gap)
                    wave.lineTo(x, cy + amp * sin((x - start) / wavelength * 2f * PI.toFloat()))
                }
                if (planeX - gap > start) c.drawPath(wave, solid)
            } else if (planeX - gap > start) {
                c.drawLine(start, cy, planeX - gap, cy, faint)
            }
            val end = widthPx - stroke * 1.9f
            if (end > planeX + gap) c.drawLine(planeX + gap, cy, end, cy, faint)
            c.drawCircle(widthPx - stroke * 0.75f, cy, stroke * 0.55f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
            vector(c, R.drawable.ic_flight, planeX, cy, plane, rotation = 90f)
        }
    }

    // ---- stats pages ------------------------------------------------------

    /**
     * One stats page as a single alpha mask: values bold, units light, the
     * climb/descend arrow inline, all on one baseline. [heightPx] is the
     * pill's inner height; the text is centred in it.
     */
    /** Width [statPage] needs for [parts], before any padding to a common width. */
    fun statPageWidth(parts: List<StatPage.Part>, valuePx: Float, unitPx: Float): Int {
        val value = paint(StripType.HERO, valuePx, numeric = true, weight = 720)
        val unit = paint(StripType.SUPPORT, unitPx, numeric = true, weight = 560)
        val space = valuePx * 0.22f
        var w = 0f
        parts.forEachIndexed { i, part ->
            if (i > 0 && part !is StatPage.Gap && parts[i - 1] !is StatPage.Gap) w += space
            w += when (part) {
                is StatPage.Value -> value.measureText(part.text)
                is StatPage.Unit -> unit.measureText(part.text)
                StatPage.Gap -> valuePx * 0.72f
                is StatPage.Arrow -> CAP_EM * valuePx * 0.78f + valuePx * 0.12f
            }
        }
        return ceil(w + 2 * valuePx * 0.12f).toInt()
    }

    /** Height of a stats line: the value's caps, with room for the units' descenders. */
    fun statLineHeight(valuePx: Float): Int = ceil(valuePx * (CAP_EM + 0.36f)).toInt()

    /**
     * One stats page as a single alpha mask: values bold, units light, the
     * climb/descend arrow inline, all on one baseline.
     */
    fun statPage(parts: List<StatPage.Part>, valuePx: Float, unitPx: Float): Bitmap {
        val value = paint(StripType.HERO, valuePx, numeric = true, weight = 720)
        val unit = paint(StripType.SUPPORT, unitPx, numeric = true, weight = 560)
        val space = valuePx * 0.22f
        val gapW = valuePx * 0.72f
        val arrowS = CAP_EM * valuePx * 0.78f
        val arrowStroke = valuePx * 0.12f
        val heightPx = statLineHeight(valuePx)
        val baseline = ceil(valuePx * (CAP_EM + 0.1f))
        return alpha(statPageWidth(parts, valuePx, unitPx), heightPx) { c ->
            var x = arrowStroke
            parts.forEachIndexed { i, part ->
                if (i > 0 && part !is StatPage.Gap && parts[i - 1] !is StatPage.Gap) x += space
                when (part) {
                    is StatPage.Value -> { c.drawText(part.text, x, baseline, value); x += value.measureText(part.text) }
                    is StatPage.Unit -> { c.drawText(part.text, x, baseline, unit); x += unit.measureText(part.text) }
                    StatPage.Gap -> x += gapW
                    is StatPage.Arrow -> {
                        arrow(c, part.climb, x + arrowStroke / 2f, baseline - CAP_EM * valuePx / 2f,
                            arrowS, arrowStroke)
                        x += arrowS + arrowStroke
                    }
                }
            }
        }
    }

    /**
     * Page dots for each of [count] pages, stacked vertically in [heightPx]:
     * the current page's dot long and solid, the rest small and faint — the
     * M3 page indicator turned upright, since the pill pages up and down.
     */
    fun pageDots(count: Int, heightPx: Float): List<Bitmap> {
        val n = count.coerceAtLeast(1)
        val dot = (heightPx / (n * 2.2f + 1.2f)).coerceIn(2.5f * density, 4f * density)
        val gap = dot * 0.9f
        val long = dot * 2.4f
        val h = ceil((n - 1) * (dot + gap) + long).toInt()
        val w = ceil(dot).toInt()
        return (0 until n).map { current ->
            alpha(w, h) { c ->
                var y = 0f
                for (i in 0 until n) {
                    val len = if (i == current) long else dot
                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        alpha = if (i == current) 255 else 90
                    }
                    c.drawRoundRect(RectF(0f, y, dot, y + len), dot / 2f, dot / 2f, p)
                    y += len + gap
                }
            }
        }
    }

    private fun arrow(c: Canvas, climb: Boolean, left: Float, cy: Float, s: Float, stroke: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.BLACK
        }
        val x1 = left + s
        val yTip = if (climb) cy - s / 2f else cy + s / 2f
        val yTail = if (climb) cy + s / 2f else cy - s / 2f
        val head = s * 0.62f
        c.drawLine(left, yTail, x1, yTip, p)
        c.drawPath(Path().apply {
            moveTo(x1 - head, yTip)
            lineTo(x1, yTip)
            lineTo(x1, if (climb) yTip + head else yTip - head)
        }, p)
    }

    // ---- photo, logos -----------------------------------------------------

    /** Width of the photo at [heightPx]: its own aspect, within sane bounds. */
    fun photoWidth(path: String?, heightPx: Int): Int {
        val aspect = path?.let { p ->
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(p, o)
            if (o.outWidth > 0 && o.outHeight > 0) o.outWidth / o.outHeight.toFloat() else null
        } ?: DEFAULT_ASPECT
        return (heightPx * aspect.coerceIn(MIN_ASPECT, MAX_ASPECT)).roundToInt()
    }

    /**
     * The WHOLE photo — never cropped or zoomed — in a rounded rectangle, with
     * [cut] punched out where the stats pill comes off it, so a sliver of
     * wallpaper separates the two. Null if the file is unreadable.
     */
    fun photo(path: String, widthPx: Int, heightPx: Int, radiusPx: Float, cut: CutOut?): Bitmap? {
        val src = decodeBounded(path) ?: return null
        return argb(widthPx, heightPx, PHOTO_MAX_DENSITY) { c ->
            // CENTER, not crop: the box already has the photo's own aspect, so
            // this fills it; an extreme aspect is shown whole, never zoomed
            val m = Matrix().apply {
                setRectToRect(RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()),
                    RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat()), Matrix.ScaleToFit.CENTER)
            }
            val shown = RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()).also { m.mapRect(it) }
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    .apply { setLocalMatrix(m) }
            }
            c.drawRoundRect(shown, radiusPx, radiusPx, p)
            cut?.let { punch(c, it) }
        }
    }

    /** The no-photo tile: a rounded rectangle mask (tinted), cut out like the photo. */
    fun tile(widthPx: Int, heightPx: Int, radiusPx: Float, cut: CutOut?): Bitmap = alpha(widthPx, heightPx) { c ->
        c.drawRoundRect(RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat()), radiusPx, radiusPx,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
        cut?.let { punch(c, it) }
    }

    private fun punch(c: Canvas, cut: CutOut) {
        val r = RectF(cut.rect).apply { inset(-cut.gap, -cut.gap) }
        c.drawRoundRect(r, cut.radius + cut.gap, cut.radius + cut.gap,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
    }

    /**
     * The model's logo as a mask the launcher tints, [heightPx] tall. With
     * [dropMaker] the maker's wordmark is cut off at the logo's widest gap
     * ("AIRBUS A320neo" → "A320neo"). Null if unreadable or empty.
     */
    fun mark(path: String, heightPx: Int, dropMaker: Boolean): Bitmap? {
        val logo = BitmapFactory.decodeFile(path) ?: return null
        val w = logo.width
        val h = logo.height
        val px = IntArray(w * h).also { logo.getPixels(it, 0, w, 0, 0, w, h) }
        val inkCol = BooleanArray(w) { x -> (0 until h).any { y -> (px[y * w + x] ushr 24) > 24 } }
        val inkRow = BooleanArray(h) { y -> (0 until w).any { x -> (px[y * w + x] ushr 24) > 24 } }
        var left = inkCol.indexOfFirst { it }
        val right = inkCol.indexOfLast { it }
        val top = inkRow.indexOfFirst { it }
        val bottom = inkRow.indexOfLast { it }
        if (left < 0 || top < 0) return null
        if (dropMaker) {
            // The widest run of empty columns inside the ink is the word gap
            var bestLen = 0
            var bestEnd = -1
            var run = 0
            for (x in left..right) {
                if (!inkCol[x]) { run++; if (run > bestLen) { bestLen = run; bestEnd = x } } else run = 0
            }
            if (bestEnd > 0 && bestLen >= (bottom - top + 1) * 0.08f) {
                left = bestEnd + 1
                while (left < right && !inkCol[left]) left++
            }
        }
        // Rows of the kept part only: the model number may be shorter than the maker word
        val rows = (top..bottom).filter { y -> (left..right).any { x -> (px[y * w + x] ushr 24) > 24 } }
        val t = rows.first()
        val b = rows.last()
        val srcW = right - left + 1
        val srcH = b - t + 1
        val outW = (srcW * heightPx / srcH.toFloat()).roundToInt().coerceAtLeast(1)
        return alpha(outW, heightPx) { c ->
            c.drawBitmap(logo, android.graphics.Rect(left, t, right + 1, b + 1),
                RectF(0f, 0f, outW.toFloat(), heightPx.toFloat()),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
    }

    /** The airline's logo on a white rounded tile — logos are drawn for white. */
    fun airlineTile(path: String, sizePx: Int, radiusPx: Float): Bitmap? {
        val logo = BitmapFactory.decodeFile(path) ?: return null
        return argb(sizePx, sizePx) { c ->
            val box = RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat())
            c.drawRoundRect(box, radiusPx, radiusPx, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            val inset = sizePx * 0.12f
            val inner = RectF(inset, inset, sizePx - inset, sizePx - inset)
            val clip = Path().apply { addRoundRect(inner, radiusPx * 0.6f, radiusPx * 0.6f, Path.Direction.CW) }
            c.save()
            c.clipPath(clip)
            val scale = min(inner.width() / logo.width, inner.height() / logo.height)
            val lw = logo.width * scale
            val lh = logo.height * scale
            c.drawBitmap(logo, null, RectF(inner.centerX() - lw / 2, inner.centerY() - lh / 2,
                inner.centerX() + lw / 2, inner.centerY() + lh / 2),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            c.restore()
        }
    }

    /** A vector icon as a mask, [rotation] degrees clockwise. */
    fun icon(resId: Int, sizePx: Int, rotation: Float = 0f): Bitmap = alpha(sizePx, sizePx) { c ->
        vector(c, resId, sizePx / 2f, sizePx / 2f, sizePx.toFloat(), rotation)
    }

    // ---- helpers ------------------------------------------------------------

    private fun vector(c: Canvas, resId: Int, cx: Float, cy: Float, size: Float, rotation: Float) {
        val d = app.getDrawable(resId)?.mutate() ?: return
        d.setTint(Color.BLACK)
        val half = size / 2f
        d.setBounds((cx - half).roundToInt(), (cy - half).roundToInt(),
            (cx + half).roundToInt(), (cy + half).roundToInt())
        c.save()
        c.rotate(rotation, cx, cy)
        d.draw(c)
        c.restore()
    }

    private inline fun alpha(w: Int, h: Int, draw: (Canvas) -> Unit): Bitmap =
        Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ALPHA_8)
            .apply { density = dpi }
            .also { draw(Canvas(it)) }

    /**
     * An ARGB bitmap for a [w]×[h] px view. With [maxDensity] it is drawn at
     * no more than that density and tagged so the view still shows it at
     * [w]×[h]: photos past ~2.25× look no sharper at this size, only heavier.
     */
    private inline fun argb(w: Int, h: Int, maxDensity: Float = 0f, draw: (Canvas) -> Unit): Bitmap {
        val k = if (maxDensity > 0f && density > maxDensity) maxDensity / density else 1f
        val bw = (w * k).roundToInt().coerceAtLeast(1)
        val bh = (h * k).roundToInt().coerceAtLeast(1)
        return Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            .apply { density = (dpi * k).roundToInt() }
            .also { bmp -> Canvas(bmp).let { c -> c.scale(k, k); draw(c) } }
    }

    /** Thumbnails are ~420×280 already; just guard against surprises. */
    private fun decodeBounded(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = (bounds.outWidth / 900).coerceAtLeast(1)
        })
    }

    companion object {
        /** Cap height of Google Sans Flex, in em (OS/2 sCapHeight 1432 / 2000). */
        const val CAP_EM = 0.716f
        /** Planespotters thumbnails are 3:2 to 16:9; the box follows each photo. */
        const val DEFAULT_ASPECT = 1.5f
        const val MIN_ASPECT = 1.0f
        const val MAX_ASPECT = 2.0f
        /** Photos are drawn at no more than this density (see argb). */
        const val PHOTO_MAX_DENSITY = 2.25f

        @Volatile private var face: Typeface? = null

        fun typeface(context: Context): Typeface = face ?: synchronized(this) {
            face ?: context.resources.getFont(R.font.google_sans_flex).also { face = it }
        }
    }
}
