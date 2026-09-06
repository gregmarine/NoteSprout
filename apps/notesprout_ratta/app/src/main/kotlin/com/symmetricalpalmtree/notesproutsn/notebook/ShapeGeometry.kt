package com.symmetricalpalmtree.notesproutsn.notebook

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import com.symmetricalpalmtree.gpaper.core.model.Bounds
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The outline of a [PageShape] in **absolute page coordinates** (arc 28 / H1, D3) — pure geometry
 * the renderer, the PDF bake, the hit-test and the lasso box all read from, so a shape can never
 * draw in one place and be hit in another.
 *
 * Local space: the un-rotated box is centred on the origin, `±width/2` by `±height/2`. The
 * rotation (clockwise, about the centre) is applied **last**, then the translation to
 * ([PageShape.cx], [PageShape.cy]). [outline] and [aabb] are pure Kotlin (JVM-tested on every type
 * at 0° and 37°); [pathFor] is the thin `android.graphics.Path` over the same numbers.
 *
 * Type recipes (og's, in words not code): rectangle = 4 corners · ellipse = the box's oval ·
 * triangle = apex at top-centre, base along the bottom · star = alternating outer / inner vertices
 * from the top, inner radius [STAR_INNER_RATIO] of the outer, [PageShape.pointCount] points ·
 * line = `(L, cy) → (R, cy)`, height ignored · arrow = that line plus two arms at the right tip,
 * ±150° from the shaft, [ARROW_ARM_FRACTION] of the width long and capped.
 *
 * **Hit-testing a rotated shape uses its AABB**, not the rotated outline — og's accepted caveat,
 * recorded in the plan's derived rules.
 */
object ShapeGeometry {

    const val STAR_INNER_RATIO = 0.5f
    const val ARROW_ARM_FRACTION = 0.3f
    const val ARROW_ARM_MAX_PX = 48f
    private const val ARROW_ARM_DEG = 150.0

    /** A pure point in page px. */
    data class Pt(val x: Float, val y: Float)

    /** One polyline of the outline; [closed] joins the last point back to the first. */
    data class Poly(val points: List<Pt>, val closed: Boolean)

    /**
     * The outline as polylines in page coordinates — everything but the ellipse, which has no
     * vertices ([outline] answers its 4 rotated box corners so a caller that only wants an extent
     * still gets one; [pathFor] draws the true oval). A rectangle, triangle or star is one closed
     * polygon; a line is one open segment; an arrow is the shaft plus two open arms.
     */
    fun outline(s: PageShape): List<Poly> {
        val hw = s.width / 2f
        val hh = s.height / 2f
        val local: List<Poly> = when (s.type) {
            ShapeType.RECTANGLE, ShapeType.ELLIPSE -> listOf(
                Poly(listOf(Pt(-hw, -hh), Pt(hw, -hh), Pt(hw, hh), Pt(-hw, hh)), closed = true),
            )
            ShapeType.TRIANGLE -> listOf(
                Poly(listOf(Pt(0f, -hh), Pt(hw, hh), Pt(-hw, hh)), closed = true),
            )
            ShapeType.STAR -> listOf(Poly(starPoints(hw, hh, s.pointCount), closed = true))
            ShapeType.LINE -> listOf(Poly(listOf(Pt(-hw, 0f), Pt(hw, 0f)), closed = false))
            ShapeType.ARROW -> {
                val arm = min(s.width * ARROW_ARM_FRACTION, ARROW_ARM_MAX_PX)
                val a = Math.toRadians(ARROW_ARM_DEG)
                val ax = (arm * cos(a)).toFloat()
                val ay = (arm * sin(a)).toFloat()
                listOf(
                    Poly(listOf(Pt(-hw, 0f), Pt(hw, 0f)), closed = false),
                    Poly(listOf(Pt(hw + ax, -ay), Pt(hw, 0f), Pt(hw + ax, ay)), closed = false),
                )
            }
        }
        val rad = Math.toRadians(s.rotationDeg.toDouble())
        val c = cos(rad).toFloat()
        val sn = sin(rad).toFloat()
        return local.map { poly ->
            Poly(poly.points.map { p -> Pt(s.cx + p.x * c - p.y * sn, s.cy + p.x * sn + p.y * c) }, poly.closed)
        }
    }

    /**
     * The axis-aligned box of the **rotated** outline, inflated by `max(strokeWidth / 2, 4 dp)` —
     * what `hitTargets()` reports, what the lasso box shows, what the clipboard's placement uses.
     * The ellipse's extent is analytic (the rotated oval's true half-extents), not its box's.
     */
    fun aabb(s: PageShape, density: Float): Bounds {
        val tight = tightBounds(s)
        val pad = max(s.strokeWidth / 2f, HIT_PAD_DP * density)
        return tight.inflated(pad)
    }

    /** The rotated outline's point-tight box, no padding. */
    fun tightBounds(s: PageShape): Bounds {
        if (s.type == ShapeType.ELLIPSE) {
            val rad = Math.toRadians(s.rotationDeg.toDouble())
            val a = s.width / 2f
            val b = s.height / 2f
            val c = cos(rad).toFloat()
            val sn = sin(rad).toFloat()
            val ex = sqrt(a * a * c * c + b * b * sn * sn)
            val ey = sqrt(a * a * sn * sn + b * b * c * c)
            return Bounds(s.cx - ex, s.cy - ey, s.cx + ex, s.cy + ey)
        }
        var l = Float.POSITIVE_INFINITY; var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY; var btm = Float.NEGATIVE_INFINITY
        for (poly in outline(s)) for (p in poly.points) {
            if (p.x < l) l = p.x
            if (p.x > r) r = p.x
            if (p.y < t) t = p.y
            if (p.y > btm) btm = p.y
        }
        if (l > r || t > btm) return Bounds(s.cx, s.cy, s.cx, s.cy)
        return Bounds(l, t, r, btm)
    }

    /** The `android.graphics.Path` of [outline] in page coordinates — the rotation matrix about
     *  the centre applied last, exactly as [outline] does it. Stroke it; never fill it. */
    fun pathFor(s: PageShape): Path {
        val path = Path()
        if (s.type == ShapeType.ELLIPSE) {
            val hw = s.width / 2f
            val hh = s.height / 2f
            path.addOval(RectF(-hw, -hh, hw, hh), Path.Direction.CW)
            val m = Matrix()
            m.postRotate(s.rotationDeg)
            m.postTranslate(s.cx, s.cy)
            path.transform(m)
            return path
        }
        for (poly in outline(s)) {
            poly.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
            if (poly.closed) path.close()
        }
        return path
    }

    private fun starPoints(hw: Float, hh: Float, count: Int): List<Pt> {
        val n = count.coerceIn(ShapeFlags.MIN_POINTS, ShapeFlags.MAX_POINTS)
        val pts = ArrayList<Pt>(n * 2)
        val step = Math.PI / n
        for (i in 0 until n * 2) {
            val ang = -Math.PI / 2 + i * step
            val ratio = if (i % 2 == 0) 1f else STAR_INNER_RATIO
            pts += Pt((hw * ratio * cos(ang)).toFloat(), (hh * ratio * sin(ang)).toFloat())
        }
        return pts
    }

    private const val HIT_PAD_DP = 4f
}
