/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.covers

import coil3.ColorImage
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.size.Dimension
import coil3.size.Size
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The interceptor's wiring: it must call [CoverEndpoint.matches] on the request's URL and, only
 * for a cover, append the bucketed `?h=` for the height Coil resolved. The bucket arithmetic
 * itself is covered by [CoverHeightBucketsTest]; this pins that it is applied to the right URL
 * at the right size - the seam the pure helpers cannot reach on their own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CoverHeightInterceptorTest {

    private val context = RuntimeEnvironment.getApplication()
    private val interceptor = CoverHeightInterceptor()

    private fun dataReaching(url: String, heightPx: Int): Any? {
        var proceededData: Any? = null
        val chain = RecordingChain(
            request = ImageRequest.Builder(context).data(url).build(),
            size = Size(Dimension.Undefined, Dimension.Pixels(heightPx)),
        ) { proceededData = it.data }
        runBlocking { interceptor.intercept(chain) }
        return proceededData
    }

    @Test
    fun `appends the bucketed h to a cover url`() {
        assertEquals(
            "https://srv/v1/library/items/42/cover?h=256",
            dataReaching("https://srv/v1/library/items/42/cover", 168),
        )
    }

    @Test
    fun `leaves a non-cover url untouched`() {
        assertEquals(
            "https://srv/img/banner.png",
            dataReaching("https://srv/img/banner.png", 168),
        )
    }

    private class RecordingChain(
        override val request: ImageRequest,
        override val size: Size,
        private val onProceed: (ImageRequest) -> Unit,
    ) : Interceptor.Chain {
        override fun withRequest(request: ImageRequest): Interceptor.Chain =
            RecordingChain(request, size, onProceed)

        override fun withSize(size: Size): Interceptor.Chain =
            RecordingChain(request, size, onProceed)

        override suspend fun proceed(): ImageResult {
            onProceed(request)
            return ErrorResult(ColorImage(), request, RuntimeException("stub"))
        }
    }
}
