package com.xnotes.storage.lite

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.model.PageStyle
import com.xnotes.core.template.TemplateCatalog.Source
import com.xnotes.core.template.TemplateKind
import com.xnotes.format.TemplatePackage
import com.xnotes.kio.KFiles
import com.xnotes.kio.Uuids
import kotlin.test.*

class BundledNoteTemplatesTest {
    @Test fun bundleListsRulingsExamplesAndImageCoversAndCreatesPortableNote() {
        val fs = KFiles.system
        val root = KFiles.tempDirectory() / Uuids.random()
        val docs = (root / "Docs").also(fs::createDirectories)
        try {
            val store = LiteStore(fs, docs, root / "Private", FakeImageCodec(), FakeTextMeasurer())
            val catalog = store.templates()
            val examples = catalog.all().filter { it.source == Source.BUNDLED }
            assertEquals(listOf("lines", "dots", "grid"), catalog.all().filter { it.source == Source.BUILT_IN }.map { it.key })
            assertTrue(examples.count { it.template.kind == TemplateKind.PAGE } >= 20)
            assertTrue(examples.count { it.template.kind == TemplateKind.COVER } >= 10)
            val cornell = assertNotNull(catalog.entry("c458ac7c8a466e47"))
            val cloth = assertNotNull(catalog.entry("faf670d24d40e8ae"))
            assertEquals("Cornell notes", cornell.template.name)
            assertEquals("Book Cloth", cloth.template.name)
            val setup = LiteNoteSetup(148.0, 210.0, PageStyle(template = cornell.key), PageStyle(template = cloth.key))
            val path = assertIs<LiteOpen.Opened>(store.create(docs, "Class", setup)).session.use { it.path }
            assertIs<LiteOpen.Opened>(store.open(path)).session.use {
                val note = assertNotNull(it.note)
                assertEquals(cornell.text, note.templates[cornell.key])
                val pkg = assertNotNull(note.templatePackages[cloth.key])
                assertNotEquals(cloth.file, pkg)
                assertEquals(cloth.text, TemplatePackage.readJson(pkg))
                assertTrue(TemplatePackage.extract(pkg, "images/cloth.webp", root / "cloth.webp"))
            }
        } finally {
            fs.deleteRecursively(root)
        }
    }
}
