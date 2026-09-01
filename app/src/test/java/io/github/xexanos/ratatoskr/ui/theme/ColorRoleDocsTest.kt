/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Holds `docs/ux-design.html` and `ui/theme/Color.kt` to the same values.
 *
 * The design doc is the palette's contract, and for one release it silently lied: after Compose
 * 1.12's contrast check forced `OutlineLight` from `#85736C` to `#7F6D67`, the doc kept quoting
 * the old tone in four places. Nothing caught it, because a human is the only reader an HTML
 * table has. This is that reader.
 *
 * It checks the wiring rather than the constants: the role name comes from the `lightColorScheme`
 * / `darkColorScheme` call, so renaming a constant is fine and re-pointing a role is not.
 */
class ColorRoleDocsTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "docs/ux-design.html").isFile }
        ?: error("could not find the repository root from ${File("").absolutePath}")

    private val colorKt = File(repoRoot, "app/src/main/java/io/github/xexanos/ratatoskr/ui/theme/Color.kt").readText()
    private val themeKt = File(repoRoot, "app/src/main/java/io/github/xexanos/ratatoskr/ui/theme/Theme.kt").readText()
    private val designDoc = File(repoRoot, "docs/ux-design.html").readText()

    @Test
    fun `every wired colour role is documented with the value the code uses`() {
        val documented = documentedRoles()
        val wiredLight = wiredRoles("lightColorScheme")
        val wiredDark = wiredRoles("darkColorScheme")

        assertTrue("Color.kt yielded no constants - the parser is broken, not the palette", constants().isNotEmpty())
        assertTrue("no roles parsed out of the doc's #color-roles table", documented.isNotEmpty())
        assertEquals(
            "the two schemes wire different role sets",
            wiredLight.keys.sorted(),
            wiredDark.keys.sorted(),
        )

        assertEquals(
            "roles in the doc's #color-roles table vs roles wired in Theme.kt",
            wiredLight.keys.sorted().joinToString("\n"),
            documented.keys.sorted().joinToString("\n"),
        )

        val mismatches = documented.mapNotNull { (role, documentedPair) ->
            val actual = hexOf(wiredLight.getValue(role)) to hexOf(wiredDark.getValue(role))
            if (actual == documentedPair) null else "$role: doc says $documentedPair, code has $actual"
        }
        assertEquals("docs/ux-design.html disagrees with Color.kt", emptyList<String>(), mismatches)
    }

    private fun constants(): Map<String, String> =
        Regex("""val\s+(\w+)\s*=\s*Color\(0x[fF][fF]([0-9a-fA-F]{6})\)""")
            .findAll(colorKt)
            .associate { it.groupValues[1] to "#" + it.groupValues[2].uppercase() }

    private fun hexOf(constant: String): String =
        constants()[constant] ?: error("Theme.kt wires $constant, which Color.kt does not define")

    // The argument list of `= lightColorScheme(...)` / `= darkColorScheme(...)`, read to its
    // matching paren so the dynamic-colour calls elsewhere in the file cannot be picked up.
    private fun wiredRoles(factory: String): Map<String, String> {
        val open = themeKt.indexOf("= $factory(").let {
            if (it < 0) error("no `= $factory(` declaration in Theme.kt") else it + "= $factory(".length
        }
        var depth = 1
        var end = open
        while (depth > 0) {
            when (themeKt[end]) {
                '(' -> depth++
                ')' -> depth--
            }
            if (depth > 0) end++
        }
        return Regex("""(\w+)\s*=\s*(\w+)\s*,""")
            .findAll(themeKt.substring(open, end))
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun documentedRoles(): Map<String, Pair<String, String>> {
        val tableStart = designDoc.indexOf("""id="color-roles"""")
            .also { require(it >= 0) { "docs/ux-design.html has no table with id=\"color-roles\"" } }
        val table = designDoc.substring(tableStart, designDoc.indexOf("</table>", tableStart))
        return Regex(
            """<tr><td><code>(\w+)</code></td>""" +
                """<td><code>(#[0-9a-fA-F]{6})</code></td>""" +
                """<td><code>(#[0-9a-fA-F]{6})</code></td></tr>""",
        )
            .findAll(table)
            .associate {
                it.groupValues[1] to (it.groupValues[2].uppercase() to it.groupValues[3].uppercase())
            }
    }
}
