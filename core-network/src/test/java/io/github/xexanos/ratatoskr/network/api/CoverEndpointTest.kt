/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.GET

class CoverEndpointTest {

    private val endpoint = CoverEndpoint("https://ratatoskr.home:8080")

    // --- resolve: contract 1.3.x sends a path relative to the server origin; the wrapper
    // --- absorbs that so the domain model always carries a loadable URL --------------------

    @Test
    fun `relative coverUrl resolves against the base url`() {
        assertEquals(
            "https://ratatoskr.home:8080/v1/library/items/42/cover",
            endpoint.resolve("/v1/library/items/42/cover"),
        )
    }

    @Test
    fun `trailing slash on the base url does not double the separator`() {
        assertEquals(
            "https://ratatoskr.home:8080/v1/library/items/42/cover",
            CoverEndpoint("https://ratatoskr.home:8080/").resolve("/v1/library/items/42/cover"),
        )
    }

    @Test
    fun `absolute coverUrl passes through untouched`() {
        // Pins the tolerant-reader branch documented on CoverEndpoint.resolve: an already-absolute
        // value is never re-prefixed.
        assertEquals(
            "https://elsewhere.example/cover.jpg",
            endpoint.resolve("https://elsewhere.example/cover.jpg"),
        )
    }

    @Test
    fun `null coverUrl stays null`() {
        assertEquals(null, endpoint.resolve(null))
    }

    // --- matches: host- and version-agnostic recognition of the cover endpoint -------------

    @Test
    fun `matches the canonical cover path`() {
        assertTrue(CoverEndpoint.matches("https://ratatoskr.home:8080/v1/library/items/42/cover"))
    }

    @Test
    fun `matches with a query string present`() {
        assertTrue(CoverEndpoint.matches("https://ratatoskr.home:8080/v1/library/items/42/cover?h=256"))
    }

    @Test
    fun `matches regardless of the version prefix`() {
        assertTrue(CoverEndpoint.matches("https://ratatoskr.home:8080/library/items/42/cover"))
    }

    @Test
    fun `does not match a non-cover image`() {
        assertFalse(CoverEndpoint.matches("https://ratatoskr.home:8080/img/banner.png"))
    }

    @Test
    fun `does not match a cover suffix outside the items path`() {
        assertFalse(CoverEndpoint.matches("https://ratatoskr.home:8080/cover"))
    }

    // --- drift guard: matches must recognise the endpoint the generated client (regenerated
    // --- from the contract) actually declares - a contract rename turns this red ------------

    @Test
    fun `matches the cover path declared by the generated contract client`() {
        val libraryApi = Class.forName("io.github.xexanos.ratatoskr.network.generated.api.LibraryApi")
        val coverPath = libraryApi.methods.single { it.name == "getLibraryItemCover" }
            .getAnnotation(GET::class.java)!!.value
        val relative = coverPath.replace("{itemId}", "sample-id")

        val resolved = endpoint.resolve(relative)!!

        assertTrue(
            "CoverEndpoint.matches must recognise the contract cover path '$relative'; " +
                "update CoverEndpoint if the contract renamed the endpoint",
            CoverEndpoint.matches(resolved),
        )
    }
}
