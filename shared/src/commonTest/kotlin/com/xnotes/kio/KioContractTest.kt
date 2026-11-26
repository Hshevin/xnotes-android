package com.xnotes.kio

import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract for the portable IO layer (docs/movinkpad/process/ipados-migration-plan-2026-11-23.md, "W1-A").
 * Runs on the JVM (Android unit tests) and on iosSimulatorArm64; both must pass unchanged.
 */
class KioContractTest {

    private val fs = KFiles.system
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = KFiles.tempDirectory() / "kio-contract-${Uuids.random()}"
        fs.createDirectories(dir)
    }

    @AfterTest
    fun tearDown() {
        fs.deleteRecursively(dir)
    }

    private val fixtureText = "XNotes raw deflate fixture. ".repeat(20).encodeToByteArray()

    /** [fixtureText] compressed by zlib at level 6 with no header, as java.util.zip.Deflater(6, true) writes it. */
    private val jvmDeflated = byteArrayOf(
        -117, -16, -53, 47, 73, 45, 86, 40, 74, 44, 87, 72, 73, 77, -53, 73, 44, 73, 85, 72, -53, -84, 40, 41,
        45, 74, -43, 83, -120, 24, -107, 27, -107, 67, -109, 3, 0,
    )

    // ---- Crc32 ----

    @Test
    fun crc32MatchesStandardCheckValue() {
        assertEquals(0xCBF43926L, Crc32.of("123456789".encodeToByteArray()))
        assertEquals(0L, Crc32.of(ByteArray(0)))
    }

    @Test
    fun crc32AccumulatesAcrossUpdatesAndResets() {
        val crc = Crc32()
        crc.update(fixtureText, 0, 100)
        crc.update(fixtureText, 100, fixtureText.size - 100)
        assertEquals(0xA605E4C0L, crc.value)
        crc.reset()
        crc.update("123456789".encodeToByteArray(), 0, 9)
        assertEquals(0xCBF43926L, crc.value)
    }

    // ---- Sha256 ----

    @Test
    fun sha256MatchesKnownVectors() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc".encodeToByteArray()))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(ByteArray(0)))
    }

    @Test
    fun sha256AccumulatesAcrossUpdates() {
        val digest = Sha256()
        var at = 0
        while (at < fixtureText.size) {
            val n = minOf(37, fixtureText.size - at)
            digest.update(fixtureText, at, n)
            at += n
        }
        assertEquals("75d1601f5ffec16e5f09dba2f14fff71be8553cae5e70e5f445c6a79e845f6cd", digest.hex())
    }

    // ---- RawDeflate ----

    @Test
    fun inflatesBytesDeflatedOnTheJvm() {
        assertContentEquals(fixtureText, RawDeflate.inflate(jvmDeflated))
    }

    @Test
    fun deflateRoundTripsAndIsHeaderless() {
        val packed = RawDeflate.deflate(fixtureText)
        assertTrue(packed.size < fixtureText.size)
        // A zlib header would start with 0x78; raw DEFLATE from this input does not.
        assertNotEquals(0x78.toByte(), packed[0])
        assertContentEquals(fixtureText, RawDeflate.inflate(packed))
        assertContentEquals(ByteArray(0), RawDeflate.inflate(RawDeflate.deflate(ByteArray(0))))
    }

    @Test
    fun inflateRejectsCorruptInput() {
        val broken = jvmDeflated.copyOf(jvmDeflated.size - 6)
        assertFailsWith<KioException> { RawDeflate.inflate(broken) }
    }

    // ---- Uuids ----

    @Test
    fun randomUuidsAreLowercaseVersion4AndDistinct() {
        val shape = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        val seen = HashSet<String>()
        repeat(200) {
            val id = Uuids.random()
            assertTrue(shape.matches(id), id)
            assertTrue(Uuids.isValid(id))
            assertTrue(seen.add(id))
        }
        assertTrue(Uuids.isValid("6F9619FF-8B86-D011-B42D-00C04FC964FF"))
        assertFalse(Uuids.isValid("6f9619ff-8b86-d011-b42d-00c04fc964f"))
        assertFalse(Uuids.isValid("not-a-uuid"))
    }

    // ---- atomicWrite ----

    @Test
    fun atomicWriteCreatesAndReplaces() {
        val target = dir / "note.xnote"
        atomicWrite(fs, target) { it.write("first".encodeToByteArray()) }
        assertEquals("first", fs.read(target) { readUtf8() })
        atomicWrite(fs, target) { it.write("second".encodeToByteArray()) }
        assertEquals("second", fs.read(target) { readUtf8() })
        assertEquals(listOf(target), fs.list(dir))
    }

    @Test
    fun failedAtomicWriteKeepsOriginalAndLeavesNoTemporaryFile() {
        val target = dir / "note.xnote"
        atomicWrite(fs, target) { it.write("original".encodeToByteArray()) }
        val failure = IllegalStateException("writer failed")
        val thrown = assertFailsWith<IllegalStateException> {
            atomicWrite(fs, target) {
                it.write("partial".encodeToByteArray())
                throw failure
            }
        }
        assertEquals(failure.message, thrown.message)
        assertEquals("original", fs.read(target) { readUtf8() })
        assertEquals(listOf(target), fs.list(dir))
    }

    @Test
    fun failedFirstAtomicWriteCreatesNothing() {
        val target = dir / "new.xnote"
        assertFailsWith<IllegalStateException> { atomicWrite(fs, target) { throw IllegalStateException("no") } }
        assertFalse(fs.exists(target))
        assertTrue(fs.list(dir).isEmpty())
    }

    // ---- fileIdentity ----

    @Test
    fun identityFollowsTheFileNotItsName() {
        val a = dir / "a.xnote"
        val b = dir / "b.xnote"
        fs.write(a) { writeUtf8("same bytes") }
        fs.write(b) { writeUtf8("same bytes") }
        val idA = assertNotNull(fileIdentity(a))
        val idB = assertNotNull(fileIdentity(b))
        assertNotEquals(idA, idB, "equal content must not make two files one")

        val renamed = dir / "renamed.xnote"
        fs.atomicMove(a, renamed)
        assertEquals(idA, fileIdentity(renamed))
        assertNull(fileIdentity(a))
    }

    @Test
    fun hardLinksShareIdentity() {
        val a = dir / "a.xnote"
        fs.write(a) { writeUtf8("linked") }
        val link = dir / "link.xnote"
        createHardLinkForTest(a, link)
        assertEquals(fileIdentity(a), fileIdentity(link))
    }

    @Test
    fun missingFileHasNoIdentity() {
        assertNull(fileIdentity(dir / "absent.xnote"))
        assertNull(fileIdentity("/definitely/not/here/${Uuids.random()}".toPath()))
    }

    // ---- KFileHandle ----

    @Test
    fun fileHandleReadsAndWritesAtPositions() {
        val path = dir / "window.bin"
        fs.write(path) { write(ByteArray(10) { it.toByte() }) }
        openReadWrite(path).use { h ->
            assertEquals(10L, h.size())
            h.write(8, byteArrayOf(80, 90, 100, 110), 0, 4)
            assertEquals(12L, h.size())
            val into = ByteArray(6)
            assertEquals(6, h.read(6, into, 0, 6))
            assertContentEquals(byteArrayOf(6, 7, 80, 90, 100, 110), into)
            assertEquals(-1, h.read(12, into, 0, 1))
            h.truncate(4)
            assertEquals(4L, h.size())
            h.sync()
        }
        assertContentEquals(byteArrayOf(0, 1, 2, 3), fs.read(path) { readByteArray() })
    }

    @Test
    fun readOnlyHandleRefusesWrites() {
        val path = dir / "ro.bin"
        fs.write(path) { writeUtf8("abc") }
        openRead(path).use { h ->
            val into = ByteArray(3)
            assertEquals(3, h.read(0, into, 0, 3))
            assertEquals("abc", into.decodeToString())
            assertFailsWith<KioException> { h.write(0, byteArrayOf(1), 0, 1) }
        }
    }

    @Test
    fun shortReadReturnsWhatIsLeft() {
        val path = dir / "short.bin"
        fs.sink(path).buffer().use { it.write(byteArrayOf(1, 2, 3)) }
        openRead(path).use { h ->
            val into = ByteArray(8)
            assertEquals(2, h.read(1, into, 4, 4))
            assertEquals(2.toByte(), into[4])
            assertEquals(3.toByte(), into[5])
        }
    }

    @Test
    fun openingMissingFileForReadFails() {
        assertFailsWith<KioException> { openRead(dir / "absent.bin") }
    }
}

/** Test-only: hard-link [existing] at [link]. Implemented per platform in the test source sets (POSIX `link`). */
expect fun createHardLinkForTest(existing: Path, link: Path)
