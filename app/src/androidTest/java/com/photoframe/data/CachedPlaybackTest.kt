package com.photoframe.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoframe.data.cache.PhotoCache
import com.photoframe.data.db.PhotoEntity
import com.photoframe.data.db.PhotoFrameDatabase
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CachedPlaybackTest {
    private lateinit var db: PhotoFrameDatabase
    private lateinit var directory: File
    private lateinit var repository: PhotoRepository
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, PhotoFrameDatabase::class.java).build()
        repository = PhotoRepository(db.photoDao())
        directory = File(context.cacheDir, "playback-test-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After fun cleanup() { db.close(); directory.deleteRecursively() }

    private fun photo(id: String, shown: Long? = null, valid: Boolean = true, source: String = "test"): PhotoEntity {
        val file = File(directory, "$source-$id.jpg")
        if (valid) file.writeText("image")
        return PhotoEntity(source, id, file.path, null, 100, 100, null, 0, shown, 5, "READY")
    }

    @Test fun startupUsesLeastRecentlyShown_andRemembersAcrossRepositoryRestart() = runBlocking {
        val photos = listOf(photo("a", 300), photo("b", null), photo("c", 100))
        db.photoDao().insertNew(photos)
        assertEquals("b", repository.startupPhoto()?.remoteId)
        repository.markShown("test", "b", 400)
        assertEquals("c", PhotoRepository(db.photoDao()).startupPhoto()?.remoteId)
    }

    @Test fun fallbackExcludesCurrentByCompositeKey_andRotatesRetainedPool() = runBlocking {
        db.photoDao().insertNew(listOf(photo("same", source = "a"), photo("same", source = "b"), photo("last", 10)))
        assertEquals("b/same", repository.cachedFallback(listOf("a/same"))?.key)
        repository.markShown("b", "same", 20)
        assertEquals("test/last", repository.cachedFallback(listOf("a/same"))?.key)
    }

    @Test fun missingFilesAcrossMultiplePages_areRepairedWithoutBlockingPlayback() = runBlocking {
        db.photoDao().insertNew((1..100).map { photo("missing$it", valid = false) } + photo("usable", 100))
        assertEquals("usable", repository.startupPhoto()?.remoteId)
        assertEquals(1, db.photoDao().cachedCountForSource("test"))
        assertEquals(101, db.photoDao().rowCount())
    }

    @Test fun emptyOrSinglePhotoPool_returnsWithoutLooping() = runBlocking {
        assertNull(repository.startupPhoto())
        db.photoDao().insertNew(listOf(photo("only")))
        assertNull(repository.cachedFallback(listOf("test/only")))
    }

    @Test fun interruptedDownload_preservesExistingFile_andRemovesPartialFile() {
        val cache = PhotoCache(context)
        val source = "cache-test-${UUID.randomUUID()}"
        val sourceDir = cache.dirForSource(source)
        try {
            val file = cache.writeFrom(source, "photo", "jpg", "complete".byteInputStream())
            val broken = object : InputStream() {
                var reads = 0
                override fun read(): Int = if (++reads <= 10) 65 else throw IOException("disconnected")
            }
            try {
                cache.writeFrom(source, "photo", "jpg", broken)
                fail("download must fail")
            } catch (_: IOException) { }
            assertEquals("complete", file.readText())
            assertEquals(listOf("photo.jpg"), sourceDir.listFiles()!!.map { it.name })
        } finally { sourceDir.deleteRecursively() }
    }
}
