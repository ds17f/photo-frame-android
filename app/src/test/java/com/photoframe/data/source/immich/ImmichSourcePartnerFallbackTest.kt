package com.photoframe.data.source.immich

import com.photoframe.data.source.ImmichConfig
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Response

/** Delete and favorite try the main key, then the partner key, and fail only when both fail. */
class ImmichSourcePartnerFallbackTest {

    /** Fake that answers only deleteAssets, with a fixed HTTP status, and counts the calls. */
    private class FakeApi(private val code: Int) : ImmichApi {
        var deleteCalls = 0
        var updateCalls = 0
        override suspend fun deleteAssets(body: ImmichDeleteDto): Response<Unit> {
            deleteCalls++
            return if (code in 200..299) Response.success(code, Unit)
            else Response.error(code, "{}".toResponseBody())
        }
        override suspend fun albums(): List<ImmichAlbumDto> = TODO()
        override suspend fun createAlbum(body: ImmichCreateAlbumDto): ImmichAlbumDto = TODO()
        override suspend fun addAssetsToAlbum(albumId: String, body: ImmichBulkIdsDto): Response<ResponseBody> = TODO()
        override suspend fun people(withHidden: Boolean, page: Int, size: Int): ImmichPeopleResponseDto = TODO()
        override suspend fun searchMetadata(body: ImmichSearchRequestDto): ImmichSearchResponseDto = TODO()
        override suspend fun thumbnail(id: String, size: String): Response<ResponseBody> = TODO()
        override suspend fun updateAssets(body: ImmichUpdateDto): Response<Unit> {
            updateCalls++
            return if (code in 200..299) Response.success(code, Unit)
            else Response.error(code, "{}".toResponseBody())
        }
    }

    private fun source(main: FakeApi, partner: FakeApi?) = ImmichSource(
        id = "immich-test",
        config = ImmichConfig(baseUrl = "https://immich.example.com"),
        apiKey = "unused",
        api = main,
        partnerApi = partner,
    )

    @Test fun mainKeySucceeds_partnerNotTried() = runBlocking {
        val main = FakeApi(204)
        val partner = FakeApi(204)
        source(main, partner).deleteRemote("a1")
        assertEquals(1, main.deleteCalls)
        assertEquals(0, partner.deleteCalls)
    }

    @Test fun mainKeyFails_partnerSucceeds() = runBlocking {
        val main = FakeApi(404)
        val partner = FakeApi(204)
        source(main, partner).deleteRemote("a1")
        assertEquals(1, partner.deleteCalls)
    }

    @Test fun bothKeysFail_throwsWithBothCodes() = runBlocking {
        try {
            source(FakeApi(404), FakeApi(400)).deleteRemote("a1")
            fail("expected failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!, e.message!!.contains("404") && e.message!!.contains("400"))
        }
    }

    @Test fun noPartnerKey_mainFailureThrows() = runBlocking {
        try {
            source(FakeApi(404), null).deleteRemote("a1")
            fail("expected failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!, e.message!!.contains("404"))
        }
    }

    @Test fun favorite_mainKeyFails_partnerSucceeds() = runBlocking {
        val main = FakeApi(400)
        val partner = FakeApi(200)
        source(main, partner).favoriteRemote("a1")
        assertEquals(1, main.updateCalls)
        assertEquals(1, partner.updateCalls)
    }

    @Test fun favorite_bothKeysFail_throws() = runBlocking {
        try {
            source(FakeApi(400), FakeApi(403)).favoriteRemote("a1")
            fail("expected failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!, e.message!!.contains("asset.update"))
        }
    }
}
