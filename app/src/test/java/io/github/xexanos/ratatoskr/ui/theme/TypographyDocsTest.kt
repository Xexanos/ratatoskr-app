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
 * Holds `docs/ux-design.html`'s typography table and the code to the same roles and sizes -
 * [ColorRoleDocsTest]'s job, for the other table a human is the only reader of.
 *
 * That table had drifted all the way through: it assigned eyebrows, chips and time labels to
 * `labelSmall`, a role that appears nowhere in the app, while the four roles it named covered
 * fewer than half the ones actually in use. Nothing caught it, because nothing was looking
 * (issue #156).
 *
 * It checks the two claims the table makes that can be checked: that a role it names is one the
 * app really reaches for, and that the sp it quotes is the sp that role really carries. Weight is
 * deliberately out of scope - it is applied per call site, not by the theme, so there is no single
 * value to compare against.
 */
class TypographyDocsTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "docs/ux-design.html").isFile }
        ?: error("could not find the repository root from ${File("").absolutePath}")

    private val designDoc = File(repoRoot, "docs/ux-design.html").readText()

    @Test
    fun `the documented roles are the roles the app uses, at the sizes the doc quotes`() {
        val documented = documentedRoles()
        val used = usedRoles()

        assertTrue("no roles parsed out of the doc's #typography table", documented.isNotEmpty())
        assertTrue("no typography roles found in the sources - the parser is broken", used.isNotEmpty())

        assertEquals(
            "roles in the doc's #typography table vs roles referenced in app/src/main",
            used.sorted().joinToString("\n"),
            documented.keys.sorted().joinToString("\n"),
        )

        val mismatches = documented.mapNotNull { (role, documentedSp) ->
            val actual = styleOf(role).fontSize.value.toInt()
            if (actual == documentedSp) null else "$role: doc says $documentedSp sp, Typography has $actual sp"
        }
        assertEquals("docs/ux-design.html disagrees with theme/Type.kt", emptyList<String>(), mismatches)
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

    private fun styleOf(role: String): TextStyle =
        roleStyles[role] ?: error("the doc names $role, which is not a Material 3 typography role")

    // Role -> the sp the table quotes. The size is read from the front of the cell, because a cell
    // may go on to mention a tracking value in sp as well.
    private fun documentedRoles(): Map<String, Int> {
        val tableStart = designDoc.indexOf("""id="typography"""")
            .also { require(it >= 0) { "docs/ux-design.html has no table with id=\"typography\"" } }
        val table = designDoc.substring(tableStart, designDoc.indexOf("</table>", tableStart))
        return Regex("""<tr><td><code>(\w+)</code></td><td>(\d+) sp""")
            .findAll(table)
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
    }

    private fun usedRoles(): Set<String> =
        File(repoRoot, "app/src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { Regex("""MaterialTheme\.typography\.(\w+)""").findAll(it.readText()) }
            .map { it.groupValues[1] }
            .toSet()
}
