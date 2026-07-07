/*
 * Ratatoskr logo generator
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Kotlin/JTS port of the original Python (shapely/svgpathtools) tool.
 * Writes into docs/logo/:
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
 *   ratatoskr-lockup.svg     - the framed logo above the "Ratatoskr" wordmark. The
 *                              wordmark outlines are read from the committed
 *                              ratatoskr-wordmark.svg, so the normal run stays
 *                              offline and deterministic.
 *   ratatoskr-logo-dark.svg / ratatoskr-lockup-dark.svg
 *                            - the same artwork generated for dark backgrounds: the frame
 *                              swaps to FRAME_DARK and the mark is painted from the shifted
 *                              copper ramp (see COPPER_RAMP / DARK_SHIFT).
 *
 * It also writes the framed logo into the app as vector drawables, so the in-app
 * logo has the same single source as the SVGs:
 *
 *   app/src/main/res/drawable/ratatoskr_logo.xml       - light
 *   app/src/main/res/drawable-night/ratatoskr_logo.xml - dark
 *
 * Run with: gradlew :tools:logo:run
 *
 * Regenerating the wordmark (only needed when text, font or size change):
 *   gradlew :tools:logo:run --args="--regen-wordmark"
 * downloads the Norse font from the author's official site into the gitignored
 * .fontcache/ (SHA-256 pinned), extracts the glyph outlines via java.awt and
 * rewrites ratatoskr-wordmark.svg. The font license permits using it for
 * logos/artwork but forbids redistributing the font FILE - hence cache, not repo.
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

const val FRAME_LIGHT = "#4F6B35"   // knot strand (E1 "Eschenlaub"; fallback E2 #3A5230)
const val FRAME_DARK = "#8CAB64"    // knot strand on dark backgrounds

// The mark's copper ramp, darkest (body) to lightest (tail tip). The artwork is generated
// for both themes straight from this one ramp: each mark path in ratatoskr-mark.svg carries
// a data-ramp index i, painted COPPER_RAMP[i] on light backgrounds and
// COPPER_RAMP[i + DARK_SHIFT] on dark ones, so the tail keeps fading. Generating both from
// the ramp means there is no light -> dark color map (and none of its source-is-also-target
// aliasing) to keep in sync.
val COPPER_RAMP = listOf(
    "#A93B28", "#C56A4F", "#CF7B60", "#D98C71", "#E29D82",
    "#EBAE93", "#F1BEA4", "#F5CDB5", "#F8DCC6",
)
const val DARK_SHIFT = 2   // steps toward the light end of the ramp on dark backgrounds

// A theme is the frame color plus the ramp shift applied to the mark.
data class Theme(val frame: String, val shift: Int)

val LIGHT = Theme(FRAME_LIGHT, 0)
val DARK = Theme(FRAME_DARK, DARK_SHIFT)

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

fun wovenKnotD(): String {
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

    return polys.joinToString(" ") { pg ->
        (listOf(pg.exteriorRing) + (0 until pg.numInteriorRing).map { pg.getInteriorRingN(it) })
            .joinToString(" ") { ring(it.coordinates) }
    }
}

// --- wordmark: Norse Bold outlines, regenerated on demand ------------------------------

const val WORDMARK_TEXT = "Ratatoskr"
const val WORDMARK_WIDTH = 200.0        // normalized advance width in the fragment
const val FONT_URL = "https://www.joelcarrouche.com/media/pages/fonts/norse/5df431c716-1675783618/norse.zip"
const val FONT_ZIP_ENTRY = "Norsebold.otf"
const val FONT_SHA256 = "ae5a73067219b3adcfce5a4fa804cd61e49ff94a677ce80fe566cf650c1ed88d"

fun cachedFont(toolDir: File): File {
    val cached = File(toolDir, ".fontcache/$FONT_ZIP_ENTRY")
    if (!cached.isFile || sha256(cached.readBytes()) != FONT_SHA256) {
        println("downloading $FONT_URL ...")
        val request = java.net.http.HttpRequest.newBuilder(java.net.URI(FONT_URL))
            .header("User-Agent", "ratatoskr-logo-tool").build()
        val zipBytes = java.net.http.HttpClient.newHttpClient()
            .send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray()).body()
        val fontBytes = java.util.zip.ZipInputStream(zipBytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.first { it.name == FONT_ZIP_ENTRY }
            zip.readBytes()
        }
        val actual = sha256(fontBytes)
        require(actual == FONT_SHA256) {
            "unexpected $FONT_ZIP_ENTRY hash $actual - the font changed upstream; " +
                "review the wordmark and update FONT_SHA256 deliberately"
        }
        cached.parentFile.mkdirs()
        cached.writeBytes(fontBytes)
    }
    return cached
}

fun sha256(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

/** Extract "Ratatoskr" as outlines, normalized to WORDMARK_WIDTH, baseline at y=0. */
fun wordmarkPaths(font: File): String {
    System.setProperty("java.awt.headless", "true")
    val awtFont = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, font).deriveFont(40f)
    val frc = java.awt.font.FontRenderContext(null, true, true)
    val glyphs = awtFont.createGlyphVector(frc, WORDMARK_TEXT)
    val advance = glyphs.getGlyphPosition(glyphs.numGlyphs).x
    val scale = WORDMARK_WIDTH / advance

    val d = StringBuilder()
    val it = glyphs.getOutline(0f, 0f)
        .getPathIterator(java.awt.geom.AffineTransform.getScaleInstance(scale, scale))
    val c = DoubleArray(6)
    fun f(v: Double) = "%.2f".format(Locale.ROOT, v)
    while (!it.isDone) {
        when (it.currentSegment(c)) {
            java.awt.geom.PathIterator.SEG_MOVETO -> d.append("M${f(c[0])},${f(c[1])} ")
            java.awt.geom.PathIterator.SEG_LINETO -> d.append("L${f(c[0])},${f(c[1])} ")
            java.awt.geom.PathIterator.SEG_QUADTO -> d.append("Q${f(c[0])},${f(c[1])} ${f(c[2])},${f(c[3])} ")
            java.awt.geom.PathIterator.SEG_CUBICTO ->
                d.append("C${f(c[0])},${f(c[1])} ${f(c[2])},${f(c[3])} ${f(c[4])},${f(c[5])} ")
            java.awt.geom.PathIterator.SEG_CLOSE -> d.append("Z ")
        }
        it.next()
    }
    return "<path fill-rule=\"evenodd\" d=\"${d.toString().trim()}\"/>"
}

// --- shared mark parsing --------------------------------------------------------------
//
// One parse of the mark into its paths, shared by the SVG composition (paintMarkSvg) and
// the VectorDrawable emitter. Parsing once with a single set of assumptions keeps the two
// outputs from drifting: a path the parser can't read - e.g. Inkscape rewrote <path/> as
// <path></path>, or dropped the data-ramp index - fails here instead of silently vanishing
// from one output while erroring in the other. Each path carries only its ramp index; its
// color is chosen per theme from COPPER_RAMP (see rampColor).

data class MarkPath(val raw: String, val ramp: Int, val evenOdd: Boolean, val d: String)

fun parseMark(svg: String): List<MarkPath> =
    Regex("<path\\b[^>]*>").findAll(svg).map { m ->
        fun attr(name: String) = Regex("$name=\"([^\"]*)\"").find(m.value)?.groupValues?.get(1)
        val ramp = (attr("data-ramp") ?: error("mark path has no data-ramp index: ${m.value}"))
            .toIntOrNull() ?: error("mark path has a non-integer data-ramp: ${m.value}")
        MarkPath(m.value, ramp, attr("fill-rule") == "evenodd", attr("d") ?: error("mark path has no d: ${m.value}"))
    }.toList()

// The ramp color for a path under a theme. An out-of-range index (data-ramp too large for
// the shift, or negative) throws, so a mis-indexed source fails loudly rather than shipping
// a wrong or unpainted mark.
fun rampColor(path: MarkPath, theme: Theme): String = COPPER_RAMP[path.ramp + theme.shift]

// Paint the parsed mark as an SVG fragment for a theme: each path is re-emitted with its
// data-ramp index dropped and its placeholder fill replaced by the ramp color, leaving the
// hand-authored geometry (and its exact formatting) untouched.
fun paintMarkSvg(mark: List<MarkPath>, theme: Theme): String =
    mark.joinToString("") { p ->
        p.raw
            .replace(" data-ramp=\"${p.ramp}\"", "")
            .replace(Regex("fill=\"[^\"]*\""), "fill=\"${rampColor(p, theme)}\"")
    }

// --- Android vector drawable ------------------------------------------------------------
//
// The framed logo again, as a VectorDrawable for the app. Group nesting mirrors the SVG:
// SVG "translate(t) scale(s)" equals a VectorDrawable group with scale s and translate t,
// because a VectorDrawable group applies scale before translation.

val DRAWABLE_HEADER = """
    <!-- The framed Ratatoskr logo (docs/logo/ratatoskr-logo.svg) as a vector drawable.
         GPL-3.0-or-later. Generated by tools/logo (gradlew :tools:logo:run); do not
         edit by hand. -->
""".trimIndent() + "\n"

fun vectorDrawable(knotD: String, mark: List<MarkPath>, sc: Double, tx: Double, ty: Double, theme: Theme): String {
    fun fmt(v: Double) = "%.1f".format(Locale.ROOT, v)
    fun path(indent: String, fill: String, evenOdd: Boolean, d: String) =
        "$indent<path android:fillColor=\"$fill\"" +
            (if (evenOdd) " android:fillType=\"evenOdd\"" else "") +
            " android:pathData=\"$d\"/>\n"

    val markPaths = mark.joinToString("") { path("            ", rampColor(it, theme), it.evenOdd, it.d) }

    return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" + DRAWABLE_HEADER +
        "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
        "    android:width=\"272dp\" android:height=\"272dp\"\n" +
        "    android:viewportWidth=\"272\" android:viewportHeight=\"272\">\n" +
        "    <group android:translateX=\"8\" android:translateY=\"8\">\n" +
        path("        ", theme.frame, true, knotD) +
        "        <group android:scaleX=\"$sc\" android:scaleY=\"$sc\" " +
        "android:translateX=\"${fmt(tx)}\" android:translateY=\"${fmt(ty)}\">\n" +
        markPaths +
        "        </group>\n" +
        "    </group>\n" +
        "</vector>\n"
}

// --- output ---------------------------------------------------------------------------

val HEADER = """
    <!-- Ratatoskr logo - GPL-3.0-or-later. Derived from CC0 artwork, see upstream/README.md.
         Generated by tools/logo (gradlew :tools:logo:run). -->
""".trimIndent() + "\n"

val WORDMARK_HEADER = """
    <!-- "Ratatoskr" set in Norse Bold by Joel Carrouche, converted to outlines.
         The font license permits use in logos/artwork; the font FILE itself must not
         be committed to this repository. Regenerate via the regen-wordmark flag of
         the :tools:logo Gradle module (see GenerateLogo.kt). -->
""".trimIndent() + "\n"

// --- spinner: real woven knot (static) + a copper "squirrel" running the strand (SMIL) ----

/** The strand centreline as one continuous cubic-Bezier path (the ribbon's own spine). */
fun centerlinePath(): String {
    val f = { v: Double -> "%.2f".format(Locale.ROOT, v) }
    val segs = knotSegments()
    val sb = StringBuilder("M${f(segs.first().p0.x)},${f(segs.first().p0.y)} ")
    for (c in segs) sb.append("C${f(c.c1.x)},${f(c.c1.y)} ${f(c.c2.x)},${f(c.c2.y)} ${f(c.p3.x)},${f(c.p3.y)} ")
    return sb.append("Z").toString()
}

/** Self-contained animated spinner: the real woven knot as a static background, with a copper
 *  squirrel + comet trail running the strand centreline as an SMIL overlay (docs/ux-design.html).
 *  VectorDrawable has no SMIL, so the app redraws this in Compose; the SVG is the reference. */
fun spinnerSvg(): String {
    val copper = COPPER_RAMP.first()
    val dur = "2.4s"
    val k = 6
    val trail = 220
    val route = centerlinePath()
    val bg = "<path d=\"${wovenKnotD()}\" fill=\"$FRAME_LIGHT\" fill-rule=\"evenodd\" stroke=\"none\"/>"
    val layers = (1..k).joinToString("\n") { i ->
        val len = trail * i / k
        "  <use href=\"#ratatoskr-route\" stroke=\"$copper\" stroke-width=\"7\" stroke-linecap=\"round\" stroke-linejoin=\"round\" opacity=\"0.30\" stroke-dasharray=\"$len ${1000 - len}\">\n" +
            "    <animate attributeName=\"stroke-dashoffset\" dur=\"$dur\" repeatCount=\"indefinite\" calcMode=\"linear\" values=\"$len;${len - 1000}\"/>\n" +
            "  </use>"
    }
    val runner =
        "  <circle class=\"rat-runner\" r=\"12\" fill=\"$copper\" opacity=\"0.22\">\n" +
            "    <animateMotion dur=\"$dur\" repeatCount=\"indefinite\" calcMode=\"paced\"><mpath href=\"#ratatoskr-route\"/></animateMotion>\n" +
            "  </circle>\n" +
            "  <circle class=\"rat-runner\" r=\"6.5\" fill=\"$copper\">\n" +
            "    <animateMotion dur=\"$dur\" repeatCount=\"indefinite\" calcMode=\"paced\"><mpath href=\"#ratatoskr-route\"/></animateMotion>\n" +
            "  </circle>"
    return "  <style>\n" +
        "    @media (prefers-reduced-motion: reduce) { .rat-spin { display: none; } }\n" +
        "  </style>\n" +
        "  $bg\n" +
        "  <defs><path id=\"ratatoskr-route\" d=\"$route\" fill=\"none\" pathLength=\"1000\"/></defs>\n" +
        "  <g class=\"rat-spin\">\n" +
        layers + "\n" +
        runner + "\n" +
        "  </g>"
}

fun main(args: Array<String>) {
    var dir = File(System.getProperty("user.dir"))
    while (!File(dir, "docs/logo").isDirectory) {
        dir = dir.parentFile ?: error("docs/logo not found above ${System.getProperty("user.dir")}")
    }
    val out = File(dir, "docs/logo")
    val toolDir = File(dir, "tools/logo")

    fun write(name: String, inner: String, box: String, header: String = HEADER) {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"$box\">\n$header$inner\n</svg>\n"
        File(out, name).writeText(svg)
        println("$name ${svg.length}")
    }

    if ("--regen-wordmark" in args) {
        write("ratatoskr-wordmark.svg", wordmarkPaths(cachedFont(toolDir)), "0 -45 200 55", WORDMARK_HEADER)
    }

    val knotD = wovenKnotD()
    val markPaths = parseMark(File(out, "ratatoskr-mark.svg").readText())
    // The wordmark's paths inherit their fill from the enclosing <g fill=...>, so they carry
    // no per-path fill and are only ever embedded verbatim (never turned into a drawable).
    val wordmark = Regex("<path[^>]*/>").findAll(File(out, "ratatoskr-wordmark.svg").readText())
        .joinToString("") { it.value }

    val sc = 0.55
    val tx = 128 - sc * 128
    val ty = 128 - sc * 130

    // The framed logo and the wordmark lockup, generated per theme straight from the ramp.
    fun woven(theme: Theme) = "<path d=\"$knotD\" fill=\"${theme.frame}\" fill-rule=\"evenodd\" stroke=\"none\"/>"
    fun logo(theme: Theme) = woven(theme) +
        "<g transform=\"translate(%.1f,%.1f) scale(%s)\">%s</g>"
            .format(Locale.ROOT, tx, ty, sc.toString(), paintMarkSvg(markPaths, theme))
    fun lockup(theme: Theme) = "<g transform=\"translate(8,8)\">${logo(theme)}</g>" +
        "<g fill=\"${theme.frame}\" transform=\"translate(36,330)\">$wordmark</g>"

    write("ratatoskr-knot-woven.svg", woven(LIGHT), "-8 -8 272 272")
    write("ratatoskr-logo.svg", logo(LIGHT), "-8 -8 272 272")
    write("ratatoskr-spinner.svg", spinnerSvg(), "-8 -8 272 272")
    write("ratatoskr-logo-dark.svg", logo(DARK), "-8 -8 272 272")
    write("ratatoskr-lockup.svg", lockup(LIGHT), "0 0 272 350", HEADER + WORDMARK_HEADER)
    write("ratatoskr-lockup-dark.svg", lockup(DARK), "0 0 272 350", HEADER + WORDMARK_HEADER)

    for ((qualifier, theme) in listOf("drawable" to LIGHT, "drawable-night" to DARK)) {
        val xml = vectorDrawable(knotD, markPaths, sc, tx, ty, theme)
        val file = File(dir, "app/src/main/res/$qualifier/ratatoskr_logo.xml")
        file.parentFile.mkdirs()
        file.writeText(xml)
        println("$qualifier/ratatoskr_logo.xml ${xml.length}")
    }
}
