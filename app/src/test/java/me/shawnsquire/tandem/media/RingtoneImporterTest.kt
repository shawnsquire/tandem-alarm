package me.shawnsquire.tandem.media

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
class RingtoneImporterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val uri = Uri.parse("content://x/1")
    private val bytes = ByteArray(5000) { (it % 251).toByte() }
    private val released = mutableListOf<Uri>()
    private val ringtones get() = File(tmp.root, "ringtones")

    private fun importer(
        name: String? = "Wake up.mp3",
        open: (Uri) -> InputStream? = { ByteArrayInputStream(bytes) },
    ) = RingtoneImporter(tmp.root, open, { name }, { released += it })

    @Test fun copiesIntoRingtonesDir() = runTest {
        val out = importer().import(uri)
        val f = File(Uri.parse(out).path!!)
        assertTrue(out.startsWith("file:"))
        assertEquals(ringtones.canonicalFile, f.canonicalFile.parentFile)
        assertTrue(f.name.endsWith("-Wake up.mp3"))
        assertEquals(listOf(f.name), ringtones.list()!!.toList())
    }

    @Test fun contentsAreCopied() = runTest {
        val f = File(Uri.parse(importer().import(uri)).path!!)
        assertTrue(bytes.contentEquals(f.readBytes()))
    }

    @Test fun nameIsSanitized() = runTest {
        val out = importer("../../etc/pa:ss*wd?<x>\\y.mp3").import(uri)
        val f = File(Uri.parse(out).path!!)
        assertEquals(ringtones.canonicalFile, f.canonicalFile.parentFile)
        assertEquals("etcpasswdxy.mp3", f.name.substring(37))
    }

    @Test fun nameIsCappedAndFallsBackToSound() = runTest {
        val long = File(Uri.parse(importer("a".repeat(500) + ".mp3").import(uri)).path!!)
        assertTrue(long.name.length <= 37 + 64)
        val none = File(Uri.parse(importer(null).import(uri)).path!!)
        assertEquals("sound", none.name.substring(37))
        val junk = File(Uri.parse(importer("///***").import(uri)).path!!)
        assertEquals("sound", junk.name.substring(37))
    }

    @Test fun tmpNamedFileIsNotSweepable() = runTest {
        val f = File(Uri.parse(importer("ring.tmp").import(uri)).path!!)
        assertFalse(f.extension == "tmp")
    }

    @Test fun releaseCalledAfterSuccess() = runTest {
        importer().import(uri)
        assertEquals(listOf(uri), released)
    }

    @Test fun releaseNotCalledAfterFailureAndNothingLeft() = runTest {
        val i = importer(open = { null })
        assertThrows(java.io.IOException::class.java) { kotlinx.coroutines.runBlocking { i.import(uri) } }
        val i2 = importer(open = { object : InputStream() { override fun read(): Int = throw java.io.IOException("boom") } })
        assertThrows(java.io.IOException::class.java) { kotlinx.coroutines.runBlocking { i2.import(uri) } }
        assertTrue(released.isEmpty())
        assertTrue(ringtones.list().orEmpty().isEmpty())
    }

    @Test fun failingReleaseStillReturnsPath() = runTest {
        val i = RingtoneImporter(tmp.root, { ByteArrayInputStream(bytes) }, { "a.mp3" }, { throw SecurityException("x") })
        assertTrue(File(Uri.parse(i.import(uri)).path!!).isFile)
    }

    @Test fun sweepsStaleTmpFiles() = runTest {
        ringtones.mkdirs()
        val stale = File(ringtones, "dead.tmp").apply { writeText("x") }
        val keep = File(ringtones, "keep.mp3").apply { writeText("x") }
        importer().import(uri)
        assertFalse(stale.exists())
        assertTrue(keep.exists())
    }

    @Test fun deleteRefusesOutsideAndTraversal() = runTest {
        val i = importer()
        val inside = i.import(uri)
        val outside = File(tmp.root, "other.mp3").apply { writeText("x") }
        assertFalse(i.delete(outside.absolutePath))
        assertFalse(i.delete(File(ringtones, "../other.mp3").path))
        assertFalse(i.delete(ringtones.path)) // the directory itself
        assertTrue(outside.exists())
        assertTrue(i.delete(inside)) // file:// URI form
        assertEquals(0, ringtones.list()!!.size)
    }

    @Test fun deleteAcceptsPlainPath() = runTest {
        val i = importer()
        val path = Uri.parse(i.import(uri)).path!!
        assertTrue(i.delete(path))
        assertFalse(i.delete(path))
    }

    @Test fun titleOfCopiedFile() = runTest {
        val context: Context = RuntimeEnvironment.getApplication()
        val i = RingtoneImporter(context.filesDir, { ByteArrayInputStream(bytes) }, { "Wake up.mp3" }, {})
        val out = i.import(uri)
        try {
            assertEquals("Wake up.mp3", ringtoneTitle(context, out))
            assertNull(RingtoneImporter.titleOf(context.filesDir, Uri.parse("file:///elsewhere/x-y.mp3")))
        } finally {
            i.delete(out)
        }
    }
}
