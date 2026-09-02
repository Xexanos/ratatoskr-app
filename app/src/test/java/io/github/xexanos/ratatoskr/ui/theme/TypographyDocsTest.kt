/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.theme

import androidx.compose.ui.text.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Holds `docs/ux-design.html`'s typography table and the code to the same roles, sizes and
 * weights - [ColorRoleDocsTest]'s job, for the other table a human is the only reader of.
 *
 * That table had drifted all the way through: it assigned eyebrows, chips and time labels to
 * `labelSmall`, a role that appears nowhere in the app, while the four roles it named covered
 * fewer than half the ones actually in use. Nothing caught it, because nothing was looking
 * (issue #156).
 *
 * The three claims it checks are the three the table can be held to: a role it names is one the
 * app really reaches for, and the size and weight it quotes are that role's own. Where a call site
 * sets a different weight the table says so in prose, in the "Used for" column, which is outside
 * the guard - an override has no single value to compare against. Governing the weight column at
 * all is deliberate: left ungoverned, it is where "Regular" ended up beside the two roles
 * Material 3 ships at Medium.
 */
class TypographyDocsTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "docs/ux-design.html").isFile }
        ?: error("could not find the repository root from ${File("").absolutePath}")

    private val designDoc = File(repoRoot, "docs/ux-design.html").readText()

    @Test
    fun `the documented roles are the app's own, at the sizes and weights the doc quotes`() {
        val documented = documentedRoles()
        val used = usedRoles()

        assertTrue("no roles parsed out of the doc's #typography table", documented.isNotEmpty())
        assertTrue("no typography roles found in the sources - the parser is broken", used.isNotEmpty())

        assertEquals(
            "roles in the doc's #typography table vs roles referenced in app/src/main",
            used.sorted().joinToString(separator = "\n"),
            documented.keys.sorted().joinToString(separator = "\n"),
        )

        val mismatches = documented.mapNotNull { (role, quoted) ->
            val style = styleOf(role)
            val actual = Face(style.fontSize.value.toInt(), style.fontWeight?.weight ?: DEFAULT_WEIGHT)
            if (actual == quoted) null else "$role: doc says $quoted, Typography has $actual"
        }
        assertEquals("docs/ux-design.html disagrees with theme/Type.kt", emptyList<String>(), mismatches)
    }

    /** The size and weight one table row quotes, and what the role actually carries. */
    private data class Face(val sp: Int, val weight: Int) {
        override fun toString(): String = "$sp sp / w$weight"
    }

    // The whole Material 3 scale, so a role the doc invents is named as such rather than silently
    // skipped. The app's own [Typography] is the receiver: it overrides bodyLarge, and every other
    // role falls through to the M3 baseline.
    private val roleStyles: Map<String, TextStyle> = mapOf(
        "displayLarge" to Typography.displayLarge,
        "displayMedium" to Typography.displayMedium,
        "displaySmall" to Typography.displaySmall,
        "headlineLarge" to Typography.headlineLarge,
        "headlineMedium" to Typography.headlineMedium,
        "headlineSmall" to Typography.headlineSmall,
        "titleLarge" to Typography.titleLarge,
        "titleMedium" to Typography.titleMedium,
        "titleSmall" to Typography.titleSmall,
        "bodyLarge" to Typography.bodyLarge,
        "bodyMedium" to Typography.bodyMedium,
        "bodySmall" to Typography.bodySmall,
        "labelLarge" to Typography.labelLarge,
        "labelMedium" to Typography.labelMedium,
        "labelSmall" to Typography.labelSmall,
    )

    // The weight names the table may use. Anything else fails loudly rather than being read as
    // some default.
    private val weightNames = mapOf(
        "regular" to 400,
        "medium" to 500,
        "semibold" to 600,
        "bold" to 700,
    )

    private fun styleOf(role: String): TextStyle =
        roleStyles[role] ?: error("the doc names $role, which is not a Material 3 typography role")

    // Role -> the size and weight the table quotes. The cell has to be exactly "<n> sp - <weight>"
    // (with the doc's middot), so a row that grows an aside cannot smuggle its numbers out of the
    // guard's reach.
    private fun documentedRoles(): Map<String, Face> {
        val tableStart = designDoc.indexOf("""id="typography"""")
            .also { require(it >= 0) { "docs/ux-design.html has no table with id=\"typography\"" } }
        val table = designDoc.substring(tableStart, designDoc.indexOf("</table>", tableStart))
        return Regex("""<tr><td><code>(\w+)</code></td><td>(\d+) sp \u00b7 (\w+)</td>""")
            .findAll(table)
            .associate { match ->
                val (role, sp, weight) = match.destructured
                val named = weightNames[weight.lowercase()]
                    ?: error("the doc quotes $role at weight \"$weight\", which is no weight name")
                role to Face(sp.toInt(), named)
            }
    }

    private fun usedRoles(): Set<String> =
        File(repoRoot, "app/src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { Regex("""MaterialTheme\.typography\.(\w+)""").findAll(it.readText()) }
            .map { it.groupValues[1] }
            .toSet()

    private companion object {
        // Compose's own fallback when a TextStyle names no weight.
        const val DEFAULT_WEIGHT = 400
    }
}
