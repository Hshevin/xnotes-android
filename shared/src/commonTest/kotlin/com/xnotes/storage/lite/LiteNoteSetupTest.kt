package com.xnotes.storage.lite

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.model.Document
import com.xnotes.core.model.PageMargins
import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.resolvedTemplate
import com.xnotes.core.pal.asAssetFile
import com.xnotes.core.pal.assetPath
import com.xnotes.core.template.TemplateCatalog
import com.xnotes.core.template.TemplateCatalog.Source
import com.xnotes.format.DocumentCodec
import com.xnotes.format.TemplatePackage
import com.xnotes.kio.KFiles
import com.xnotes.kio.Sha256
import com.xnotes.kio.Uuids
import okio.Path
import okio.use
import kotlin.test.*
import kotlin.time.Clock

class LiteNoteSetupTest {
    private val fs = KFiles.system
    private val root = KFiles.tempDirectory() / Uuids.random()
    private val documents = root / "Documents"
    private val folder = (documents / "School").also(fs::createDirectories)
    private val imports = (documents / ".xnote" / "templates").also(fs::createDirectories)
    private val bundled = root / "resources"
    private val examples = (bundled / "examples").also(fs::createDirectories)
    private val privateDir = root / "Private"
    private val store = LiteStore(fs, documents, privateDir, FakeImageCodec(), FakeTextMeasurer(), bundled)

    @AfterTest fun cleanup() = fs.deleteRecursively(root)

    private fun json(name: String) = """{"xtemplate":1,"name":"$name","items":[]}"""
    private fun write(path: Path, text: String) = fs.write(path) { writeUtf8(text) }
    private fun bytes(path: Path) = fs.read(path) { readByteArray() }
    private fun key(path: Path) = Sha256.hex(bytes(path)).take(16)

    private fun imageCover(): Path {
        val picture = root / "pic.jpg"
        fs.write(picture) { write(byteArrayOf(1, 7, 9)) }
        val path = imports / "Cover.xtemplate"
        val json = """{"xtemplate":1,"name":"Cover","kind":"cover","items":[{"type":"image","src":"images/pic.jpg"}]}"""
        TemplatePackage.write(path.asAssetFile(), json, listOf("images/pic.jpg" to picture.asAssetFile()))
        return path
    }

    @Test fun catalogUsesRulingIdsAndContentKeysAcrossSources() {
        for (id in listOf("grid", "lines", "dots")) write(bundled / "$id.xtemplate", json(id))
        val example = examples / "page.xtemplate"
        write(example, json("Example"))
        write(imports / "same example.xtemplate", json("Example"))
        val imported = imports / "renamed.xtemplate"
        write(imported, json("Added"))
        val cover = imageCover()
        write(imports / "broken.xtemplate", "invalid")
        write(imports / "ignored.txt", json("Ignored"))
        val nested = (folder / ".xnote" / "templates").also(fs::createDirectories)
        write(nested / "wrong root.xtemplate", json("Wrong root"))

        val catalog = store.templates()
        assertEquals(listOf("lines", "dots", "grid", key(example), key(imported), key(cover)), catalog.all().map { it.key })
        assertEquals(Source.BUILT_IN, catalog.entry("grid")?.source)
        assertEquals(Source.BUNDLED, catalog.entry(key(example))?.source)
        assertEquals(Source.IMPORTED, catalog.entry(key(cover))?.source)
        assertNull(catalog.entry("Cover"))
        fs.delete(imported)
        assertNull(store.templates().entry(TemplateCatalog.keyOf(json("Added"))))
    }

    @Test fun coverBodyAndAssetsSurviveSourceRemovalAndDocumentCodecRoundTrip() {
        val sourcePage = examples / "page.xtemplate"
        val pageJson = json("Page")
        write(sourcePage, pageJson)
        val sourceCover = imageCover()
        val coverBytes = bytes(sourceCover)
        val style = PageStyle(Rgba(200, 210, 220), key(sourcePage), Rgba(100, 80, 60), 48.0,
            params = mapOf("depth" to 2.0), colors = mapOf("ink" to Rgba(10, 20, 30)))
        val cover = PageStyle(pageColor = Rgba(8, 9, 10), template = key(sourceCover))
        val setup = LiteNoteSetup(254.0, 127.0, style, cover)
        val before = Clock.System.now().toEpochMilliseconds()
        fun check(doc: Document) {
            assertEquals(150, doc.dpi)
            assertEquals(List(Document.DEFAULT_NEW_PAGES + 1) { 1500.0 to 750.0 }, doc.pages.map { it.width to it.height })
            assertEquals(listOf(true, false), doc.pages.map { it.cover })
            assertEquals(style, doc.style)
            assertEquals(cover, doc.pages.first().style)
            assertEquals(PageStyle(), doc.pages.last().style)
            assertEquals(PageMargins(0.0, 0.0, 0.0, 0.0), doc.pages.first().margins)
            assertEquals(PageMargins(), doc.pages.last().margins)
            assertEquals(listOf(cover.template, style.template), doc.pages.map { it.resolvedTemplate(doc) })
            assertEquals(mapOf(requireNotNull(style.template) to pageJson), doc.templates)
            val file = doc.templatePackages.getValue(cover.template!!)
            assertContentEquals(coverBytes, bytes(file.assetPath()))
            assertTrue(doc.hasReferenceAnchors)
            assertEquals(2, doc.pages.map { assertNotNull(it.referenceIdentity.id) }.toSet().size)
            assertTrue(assertNotNull(doc.created) in before..Clock.System.now().toEpochMilliseconds())
            assertTrue(doc.flow.isEmpty)
            assertEquals(Document.blank().flow.defaultSizePt, doc.flow.defaultSizePt)
        }

        val session = assertIs<LiteOpen.Opened>(store.create(folder, "Cover note", setup)).session
        val identity = session.use {
            fs.delete(sourcePage)
            fs.delete(sourceCover)
            val note = assertNotNull(it.note)
            check(note)
            Triple(note.identity, note.pages.map { p -> p.referenceIdentity.id }, note.created)
        }
        val assets = (root / "Decoded").also(fs::createDirectory).asAssetFile()
        val codec = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())
        val note = fs.source(folder / "Cover note.xnote").use { codec.readComplete(it, assets, assets).document }
        check(note)
        assertEquals(identity, Triple(note.identity, note.pages.map { it.referenceIdentity.id }, note.created))
    }

    @Test fun noCoverCreatesOnePortraitSheetAndNeverEmbedsRulingOrNone() {
        val style = PageStyle(template = "dots")
        for (key in listOf("dots", "none", null)) {
            val selected = style.copy(template = key)
            val setup = LiteNoteSetup(127.0, 254.0, selected)
            assertIs<LiteOpen.Opened>(store.create(folder, "Paper", setup)).session.use {
                val note = assertNotNull(it.note)
                val page = note.pages.single()
                assertEquals(750.0 to 1500.0, page.width to page.height)
                assertFalse(page.cover)
                assertNotNull(page.referenceIdentity.id)
                assertEquals(selected, note.style)
                assertTrue(note.templates.isEmpty() && note.templatePackages.isEmpty())
            }
        }
        assertEquals(setOf("Paper.xnote", "Paper (2).xnote", "Paper (3).xnote"), fs.list(folder).map { it.name }.toSet())
    }

    @Test fun changingOrRemovingSelectedPackageCannotPublishAnIncompleteNote() {
        val path = imageCover()
        val key = key(path)
        val catalog = store.templates()
        val assets = (root / "Assets").also(fs::createDirectory)
        val doc = Document.blank()
        write(path, json("Different template"))
        assertFalse(catalog.embed(doc, key, assets))
        assertTrue(doc.templatePackages.isEmpty() && fs.list(assets).isEmpty())
        val setup = LiteNoteSetup(100.0, 100.0, PageStyle(), PageStyle(template = key))
        assertIs<LiteOpen.Refused>(store.create(folder, "Paper", setup))
        assertTrue(fs.list(folder).isEmpty() && fs.list(privateDir).isEmpty())
        assertFalse(fs.list(documents).any { it.name.startsWith(".create-") })
    }
}
