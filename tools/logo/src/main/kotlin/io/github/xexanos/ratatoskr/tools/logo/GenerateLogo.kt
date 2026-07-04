/*
 * Ratatoskr logo generator
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Kotlin/JTS port of the original Python (shapely/svgpathtools) tool.
 * Writes two SVGs into docs/logo/:
 *
 *   ratatoskr-knot-woven.svg - the frame: a single-strand (2,9) torus-knot ring
 *                              (Q pointed spikes, one pointing up), converted to
 *                              filled bands ("stroke to path") with an alternating
 *                              over/under crossing at each of the Q
 *                              self-intersections. Sharp spikes are preserved
 *                              (mitre joins).
 *   ratatoskr-logo.svg       - the woven knot composed with the hand-edited mark,
 *                              which is READ from ratatoskr-mark.svg (that file is
 *                              the source of truth for the mark and is never
 *                              overwritten by this tool).
 *
 * Run with: gradlew :tools:logo:run
 */
package io.github.xexanos.ratatoskr.tools.logo

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.operation.buffer.BufferOp
import org.locationtech.jts.operation.buffer.BufferParameters
import org.locationtech.jts.operation.union.UnaryUnionOp
import org.locationtech.jts.simplify.TopologyPreservingSimplifier
import java.io.File
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

// --- parameters (identical to the Python tool) ----------------------------------------

const val DARK = "#4F6B35"   // knot strand (E1 "Eschenlaub"; fallback E2 #3A5230)

const val R = 100.0          // ring radius
const val A = 14.0           // wave amplitude
const val Q = 9              // spikes / crossings (odd -> one continuous strand)
const val W = 10.0           // stroke width
const val SPIKE = 1.8        // spike amplitude relative to A
val ROT = Math.toRadians(10.0)   // spikes sit at 20 + 40k deg; +10 puts one at 270 (up)
const val CX = 128.0
const val CY = 128.0

const val GAP = 4.0          // clearance between over-strand and the gap edge (per side)
const val CUT_HALF = 20.0    // half-length of the "cutter" band along the over-strand
const val OVER_HALF = 26.0   // half-length of the restored over-band; >= CUT_HALF
const val SAMPLES_PER_SEG = 160
const val SIMPLIFY = 0.25    // node-reduction tolerance for the final filled path
const val OVER_PHASE = 0     // 0 or 1: flips which strand lies on top at every crossing

// --- knot centerline: minimal-node cubic-Bezier construction --------------------------
//
// The strand is r(t) = R + A*sin(Q*t/2) over t in [0, 4*pi]. Its extrema are the only
// anchors: even extrema become pointed spikes (corner anchors, tent profile), odd
// extrema are smooth valleys. One cubic per segment, handles along the analytic
// tangents with length chord/3.

data class P(val x: Double, val y: Double)

class Cubic(val p0: P, val c1: P, val c2: P, val p3: P) {
    fun point(u: Double): P {
        val v = 1 - u
        val x = v * v * v * p0.x + 3 * v * v * u * c1.x + 3 * v * u * u * c2.x + u * u * u * p3.x
        val y = v * v * v * p0.y + 3 * v * v * u * c1.y + 3 * v * u * u * c2.y + u * u * u * p3.y
        return P(x, y)
    }
}

fun knotSegments(): List<Cubic> {
    val slope = A * SPIKE * Q / Math.PI
    data class Anchor(val p: P, val dirIn: P, val dirOut: P)

    val anchors = (0 until 2 * Q).map { j ->
        val t = (Math.PI + 2 * Math.PI * j) / Q
        val phi = t + ROT
        val rx = cos(phi); val ry = sin(phi)
        val tx = -sin(phi); val ty = cos(phi)
        val r: Double
        val dIn: P
        val dOut: P
        if (j % 2 == 0) {                    // spike (corner anchor)
            r = R + A * SPIKE
            dIn = P(slope * rx + r * tx, slope * ry + r * ty)
            dOut = P(-slope * rx + r * tx, -slope * ry + r * ty)
        } else {                             // valley (smooth anchor)
            r = R - A
            dIn = P(r * tx, r * ty)
            dOut = dIn
        }
        fun norm(d: P): P { val n = hypot(d.x, d.y); return P(d.x / n, d.y / n) }
        Anchor(P(CX + r * rx, CY + r * ry), norm(dIn), norm(dOut))
    }

    return (0 until 2 * Q).map { j ->
        val a = anchors[j]
        val b = anchors[(j + 1) % (2 * Q)]
        val h = hypot(b.p.x - a.p.x, b.p.y - a.p.y) / 3
        Cubic(
            a.p,
            P(a.p.x + a.dirOut.x * h, a.p.y + a.dirOut.y * h),
            P(b.p.x - b.dirIn.x * h, b.p.y - b.dirIn.y * h),
            b.p,
        )
    }
}

// --- weaving: stroke -> filled bands + boolean difference ------------------------------
//
// The stroke is turned into a filled ribbon ("Kontur in Pfad umwandeln"). At every
// self-intersection the strand that passes UNDER gets the OVER strand's (slightly
// widened) band subtracted, then the over band is unioned back, so the gap is bounded
// by lines parallel to the over-strand. Over/under alternates along the strand, which
// for odd Q yields a proper alternating knot diagram.

class SampledPath(segments: List<Cubic>) {
    val pts: List<P> = buildList {
        for (seg in segments) {
            for (k in 0 until SAMPLES_PER_SEG) add(seg.point(k.toDouble() / SAMPLES_PER_SEG))
        }
        add(segments.first().p0)             // close the loop
    }
    val cum: DoubleArray = DoubleArray(pts.size).also { c ->
        for (i in 1 until pts.size) {
            c[i] = c[i - 1] + hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
        }
    }
    val length: Double get() = cum.last()

    fun pointAt(s: Double): P {
        val sc = s.coerceIn(0.0, length)
        var i = cum.binarySearch(sc).let { if (it < 0) -it - 2 else it }.coerceIn(0, pts.size - 2)
        val segLen = cum[i + 1] - cum[i]
        val u = if (segLen == 0.0) 0.0 else (sc - cum[i]) / segLen
        return P(pts[i].x + (pts[i + 1].x - pts[i].x) * u, pts[i].y + (pts[i + 1].y - pts[i].y) * u)
    }

    /** Polyline between two arc-length positions, with interpolated endpoints. */
    fun slice(s0: Double, s1: Double): List<P> = buildList {
        add(pointAt(s0))
        var i = cum.binarySearch(s0).let { if (it < 0) -it - 1 else it + 1 }
        while (i < pts.size && cum[i] < s1) {
            add(pts[i]); i++
        }
        add(pointAt(s1))
    }

    /** Arc-length positions of all self-intersections (two entries per crossing). */
    fun selfIntersections(): List<Double> {
        val n = pts.size - 1
        val events = mutableListOf<Double>()
        for (i in 0 until n) {
            for (j in i + 2 until n) {
                if (i == 0 && j == n - 1) continue        // wrap-adjacent
                val hit = segmentIntersection(pts[i], pts[i + 1], pts[j], pts[j + 1]) ?: continue
                events += cum[i] + hit.first * (cum[i + 1] - cum[i])
                events += cum[j] + hit.second * (cum[j + 1] - cum[j])
            }
        }
        return events.sorted()
    }
}

/** Parameters (t, u) of a proper crossing between two segments, or null. */
fun segmentIntersection(a: P, b: P, c: P, d: P): Pair<Double, Double>? {
    val rx = b.x - a.x; val ry = b.y - a.y
    val sx = d.x - c.x; val sy = d.y - c.y
    val denom = rx * sy - ry * sx
    if (denom == 0.0) return null
    val t = ((c.x - a.x) * sy - (c.y - a.y) * sx) / denom
    val u = ((c.x - a.x) * ry - (c.y - a.y) * rx) / denom
    return if (t > 1e-9 && t < 1 - 1e-9 && u > 1e-9 && u < 1 - 1e-9) t to u else null
}

fun wovenKnotPath(): String {
    val gf = GeometryFactory()
    val params = BufferParameters(8, BufferParameters.CAP_FLAT, BufferParameters.JOIN_MITRE, 6.0)
    fun buffer(pts: List<P>, dist: Double): Geometry =
        BufferOp.bufferOp(gf.createLineString(pts.map { Coordinate(it.x, it.y) }.toTypedArray()), dist, params)

    val path = SampledPath(knotSegments())
    val ribbon = buffer(path.pts, W / 2)

    val events = path.selfIntersections()
    require(events.size == 2 * Q) { "expected ${2 * Q} crossing events, got ${events.size}" }
    val overCenters = events.filterIndexed { k, _ -> k % 2 == OVER_PHASE }

    val cutters = overCenters.map { s -> buffer(path.slice(s - CUT_HALF, s + CUT_HALF), W / 2 + GAP) }
    val overs = overCenters.map { s -> buffer(path.slice(s - OVER_HALF, s + OVER_HALF), W / 2) }

    var woven: Geometry = ribbon
        .difference(UnaryUnionOp.union(cutters))
        .union(UnaryUnionOp.union(overs))
    if (SIMPLIFY > 0) woven = TopologyPreservingSimplifier.simplify(woven, SIMPLIFY)

    val polys = when (woven) {
        is MultiPolygon -> (0 until woven.numGeometries).map { woven.getGeometryN(it) as Polygon }
        is Polygon -> listOf(woven)
        else -> error("unexpected geometry ${woven.geometryType}")
    }
    require(polys.size == Q) { "expected $Q weave pieces, got ${polys.size}" }

    // Locale.ROOT: SVG needs dot decimal separators regardless of the system locale
    fun ring(coords: Array<Coordinate>): String {
        val head = "M%.2f,%.2f ".format(Locale.ROOT, coords[0].x, coords[0].y)
        return head + coords.drop(1).joinToString(" ") { "L%.2f,%.2f".format(Locale.ROOT, it.x, it.y) } + " Z"
    }

    val d = polys.joinToString(" ") { pg ->
        (listOf(pg.exteriorRing) + (0 until pg.numInteriorRing).map { pg.getInteriorRingN(it) })
            .joinToString(" ") { ring(it.coordinates) }
    }
    return "<path d=\"$d\" fill=\"$DARK\" fill-rule=\"evenodd\" stroke=\"none\"/>"
}

// --- output ---------------------------------------------------------------------------

val HEADER = """
    <!-- Ratatoskr logo - GPL-3.0-or-later. Derived from CC0 artwork, see upstream/README.md.
         Generated by tools/logo (gradlew :tools:logo:run). -->
""".trimIndent() + "\n"

fun main() {
    var dir = File(System.getProperty("user.dir"))
    while (!File(dir, "docs/logo").isDirectory) {
        dir = dir.parentFile ?: error("docs/logo not found above ${System.getProperty("user.dir")}")
    }
    val out = File(dir, "docs/logo")

    fun write(name: String, inner: String, box: String) {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"$box\">\n$HEADER$inner\n</svg>\n"
        File(out, name).writeText(svg)
        println("$name ${svg.length}")
    }

    val woven = wovenKnotPath()
    val mark = Regex("<path[^>]*/>").findAll(File(out, "ratatoskr-mark.svg").readText())
        .joinToString("") { it.value }

    val sc = 0.55
    val tx = 128 - sc * 128
    val ty = 128 - sc * 130

    write("ratatoskr-knot-woven.svg", woven, "-8 -8 272 272")
    write(
        "ratatoskr-logo.svg",
        woven + "<g transform=\"translate(%.1f,%.1f) scale(%s)\">%s</g>".format(Locale.ROOT, tx, ty, sc.toString(), mark),
        "-8 -8 272 272",
    )
}
