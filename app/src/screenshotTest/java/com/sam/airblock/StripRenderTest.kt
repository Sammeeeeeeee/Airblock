package com.sam.airblock

import android.app.Activity
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Adapter
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.StackView
import com.sam.airblock.data.WidgetState
import com.sam.airblock.widget.FlightStrip
import com.sam.airblock.widget.FlightStripReceiver
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/**
 * Renders the Flight strip through Android's own AppWidgetHostView (real
 * framework code under Robolectric) and checks how the launcher will treat
 * it on updates. Run: gradle testDebugUnitTest -Pscreenshots
 * PNGs land in STRIP_RENDER_DIR (default app/build/strip-renders). Optional
 * STRIP_SAMPLE_PHOTO / STRIP_SAMPLE_MODEL / STRIP_SAMPLE_AIRLINE point at a
 * cached photo, model logo and airline logo to draw with.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-420dpi")
class StripRenderTest {

    private lateinit var activity: Activity
    private val ctx: Context get() = activity
    private val out = File(System.getProperty("strip.render.dir") ?: "build/strip-renders")
        .apply { mkdirs() }
    private val now = 1_762_425_540_000L // Fri 6 Nov 2026 10:39 UTC

    private fun sample(name: String) = System.getProperty(name)?.takeIf { File(it).exists() }

    private fun ba() = FlightStrip.sample(now).copy(
        hex = "406b2a", airlineName = "British Airways", flightNumber = "BA8",
        photoPath = sample("strip.sample.photo"), modelLogoPath = sample("strip.sample.model"),
        airlineLogoPath = sample("strip.sample.airline"), elevationDeg = 34.4,
    )

    private fun military() = WidgetState(
        status = WidgetState.Status.OK, callsign = "RRR4950", typeName = "Airbus A400M Atlas",
        typeCode = "A400", registration = "ZM414", hex = "43c6f1", altitudeFt = 23000,
        speedMph = 402, mach = 0.6, distanceKm = 8.4, bearingDeg = 95.0, elevationDeg = 39.6,
        verticalRateFpm = -64, category = "A5", specialType = "Military",
        alertCategory = "Royal Air Force", updatedAt = now, staleAfterMs = now + 120_000,
    )

    private fun police() = WidgetState(
        status = WidgetState.Status.OK, callsign = "UKP21", typeName = "Airbus H135",
        typeCode = "EC35", registration = "G-POLF", hex = "4074b5", altitudeFt = 1200,
        speedMph = 98, distanceKm = 1.6, bearingDeg = 200.0, elevationDeg = 12.9,
        category = "A7", alertCategory = "Police Forces", updatedAt = now,
        staleAfterMs = now + 120_000,
    )

    private fun quiet() = WidgetState(status = WidgetState.Status.NO_AIRCRAFT, updatedAt = now,
        staleAfterMs = now + 120_000)

    @org.junit.Before fun window() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    }

    // ---- renders --------------------------------------------------------------

    @Test fun renderGallery() {
        val cases = listOf(
            "ba" to ba(), "ba_nophoto" to ba().copy(photoPath = null),
            "ba_nologos" to ba().copy(modelLogoPath = null, airlineLogoPath = null),
            "military" to military(), "police" to police(),
            "emergency" to ba().copy(squawkAlert = "7700 EMERGENCY", verticalRateFpm = -2400),
            "stale" to ba().copy(markedStale = true), "quiet" to quiet(),
            "nolocation" to WidgetState(status = WidgetState.Status.NO_LOCATION, updatedAt = now,
                staleAfterMs = now + 120_000),
        )
        for (night in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers(if (night) "+night" else "+notnight")
            window()
            for ((name, state) in cases) for ((tier, h) in listOf("compact" to 64f, "full" to 100f)) {
                val host = host(313f, h, FlightStrip.preview(ctx, state, 313f, listOf(h), now))
                pages(host).forEachIndexed { i, bmp ->
                    save(bmp, "${name}_${tier}_${if (night) "dark" else "light"}_$i")
                }
            }
            // A full-width Niagara widget on a typical phone
            val wide = host(380f, 100f, FlightStrip.preview(ctx, ba(), 380f, listOf(100f), now))
            pages(wide).forEachIndexed { i, bmp -> save(bmp, "ba_wide_${if (night) "dark" else "light"}_$i") }
        }
    }

    /** The static picker image (res/drawable-nodpi/strip_preview.png): no photo, light. */
    @Test fun renderPickerImage() {
        val host = host(313f, 100f, FlightStrip.preview(ctx, FlightStrip.sample(now), 313f,
            listOf(100f), now))
        save(pages(host).first(), "picker_preview")
    }

    /** Data updates keep the card and the stats where they are; a new aircraft gets a fresh card. */
    @Test fun launcherKeepsTheWidgetAcrossUpdates() {
        val host = host(313f, 100f, FlightStrip.preview(ctx, ba(), 313f, listOf(100f), now))
        val card = host.findViewById<View>(R.id.strip_card)
        val stack = host.findViewById<StackView>(R.id.strip_stats)
        update(host, FlightStrip.preview(ctx, ba().copy(altitudeFt = 8400, distanceKm = 14.1),
            313f, listOf(100f), now + 15_000))
        layout(host, 313f, 100f)
        assertSame(card, host.findViewById<View>(R.id.strip_card))
        assertSame(stack, host.findViewById<StackView>(R.id.strip_stats))
        update(host, FlightStrip.preview(ctx, police(), 313f, listOf(100f), now + 30_000))
        layout(host, 313f, 100f)
        assertNotSame(card, host.findViewById<View>(R.id.strip_card))
    }

    // ---- helpers ----------------------------------------------------------------

    private fun host(widthDp: Float, heightDp: Float, rv: RemoteViews): AppWidgetHostView {
        val info = AppWidgetProviderInfo().apply {
            provider = ComponentName(ctx, FlightStripReceiver::class.java)
            initialLayout = R.layout.strip_loading
        }
        // providerInfo is hidden API; the host needs it to find our resources
        AppWidgetProviderInfo::class.java.getField("providerInfo")
            .set(info, ctx.packageManager.getReceiverInfo(info.provider, 0))
        val host = AppWidgetHostView(ctx)
        host.setAppWidget(1, info)
        host.setPadding(0, 0, 0, 0)
        activity.setContentView(FrameLayout(ctx).apply { addView(host) })
        update(host, rv)
        layout(host, widthDp, heightDp)
        idle()
        layout(host, widthDp, heightDp) // the host re-picks its tier on the first layout
        return host
    }

    /**
     * The REAL AppWidgetHostView.updateAppWidget — Robolectric's shadow of it
     * skips the size-based layout choice and the recycling under test here.
     */
    private fun update(host: AppWidgetHostView, rv: RemoteViews) {
        Shadow.directlyOn<Unit, AppWidgetHostView>(host, AppWidgetHostView::class.java,
            "updateAppWidget", ClassParameter.from(RemoteViews::class.java, rv))
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun layout(v: View, widthDp: Float, heightDp: Float) {
        val d = ctx.resources.displayMetrics.density
        val w = (widthDp * d).toInt(); val h = (heightDp * d).toInt()
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    /**
     * One render per stats page. Robolectric leaves a RemoteViews collection
     * StackView unlaid-out, so its own adapter is re-set behind a plain
     * wrapper — the same item views, laid out normally.
     */
    private fun pages(host: AppWidgetHostView): List<Bitmap> {
        host.findViewById<ViewGroup>(R.id.strip_card)?.let { card ->
            card.layoutAnimation = null
            (0 until card.childCount).forEach { card.getChildAt(it).clearAnimation() }
        }
        val stack = host.findViewById<StackView>(R.id.strip_stats)
        if (stack == null || stack.visibility != View.VISIBLE || stack.adapter == null) return listOf(draw(host))
        val real: Adapter = stack.adapter
        stack.adapter = object : BaseAdapter() {
            override fun getCount() = real.count
            override fun getItem(p: Int): Any? = real.getItem(p)
            override fun getItemId(p: Int) = real.getItemId(p)
            override fun getView(p: Int, c: View?, g: ViewGroup?): View = real.getView(p, null, g)
        }
        val wDp = host.width / ctx.resources.displayMetrics.density
        val hDp = host.height / ctx.resources.displayMetrics.density
        return (0 until real.count).map { i ->
            stack.displayedChild = i
            repeat(3) { idle(); layout(host, wDp, hDp) }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
            layout(host, wDp, hDp)
            // On a device the frame's rounded outline clips the stack to the
            // front page; software drawing ignores outlines, so apply that clip
            // here: the widget without the pill, plus the pill through a
            // rounded mask.
            val frame = host.findViewById<View>(R.id.strip_stats_frame)
            val full = draw(host)
            frame.visibility = View.INVISIBLE
            val base = draw(host)
            frame.visibility = View.VISIBLE
            val at = IntArray(2).also { offsetIn(frame, host, it) }
            val r = android.graphics.RectF(at[0].toFloat(), at[1].toFloat(),
                (at[0] + frame.width).toFloat(), (at[1] + frame.height).toFloat())
            Canvas(base).apply {
                val clip = android.graphics.Path().apply {
                    addRoundRect(r, frame.height / 2f, frame.height / 2f, android.graphics.Path.Direction.CW)
                }
                save(); clipPath(clip); drawBitmap(full, 0f, 0f, null); restore()
            }
            base
        }
    }

    private fun offsetIn(v: View, root: View, out: IntArray) {
        var x = 0; var y = 0; var cur: View = v
        while (cur !== root) {
            x += cur.left; y += cur.top
            cur = cur.parent as? View ?: break
        }
        out[0] = x; out[1] = y
    }

    private fun draw(v: View): Bitmap =
        Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }

    /** Minimal RGBA PNG writer: the unit-test classpath has no image encoder. */
    private fun save(bmp: Bitmap, name: String) {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
        val raw = ByteArrayOutputStream()
        DeflaterOutputStream(raw).use { z ->
            val row = ByteArray(1 + 4 * w)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val c = px[y * w + x]
                    row[1 + 4 * x] = (c shr 16).toByte(); row[2 + 4 * x] = (c shr 8).toByte()
                    row[3 + 4 * x] = c.toByte(); row[4 + 4 * x] = (c ushr 24).toByte()
                }
                z.write(row)
            }
        }
        val file = ByteArrayOutputStream()
        val o = DataOutputStream(file)
        o.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        fun chunk(type: String, data: ByteArray) {
            o.writeInt(data.size)
            val t = type.toByteArray()
            o.write(t); o.write(data)
            o.writeInt(CRC32().apply { update(t); update(data) }.value.toInt())
        }
        chunk("IHDR", ByteArrayOutputStream().also { b ->
            DataOutputStream(b).apply { writeInt(w); writeInt(h); write(byteArrayOf(8, 6, 0, 0, 0)) }
        }.toByteArray())
        chunk("IDAT", raw.toByteArray())
        chunk("IEND", ByteArray(0))
        File(out, "$name.png").writeBytes(file.toByteArray())
    }
}
