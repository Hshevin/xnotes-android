package com.xnotes.storage.lite

import android.provider.DocumentsContract
import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.Rgba
import com.xnotes.kio.KFiles
import com.xnotes.pen.runParkingScenario
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.platform.TemplateLibrary
import com.xnotes.ui.Editor
import java.io.File
import okio.Path.Companion.toOkioPath
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class AndroidNoteSetupParityTest {
    @Test fun sameFormCreatesEquivalentPortableNotesThroughBothPublishers() = runParkingScenario { f ->
        val resources = File("src/main/assets/templates").absoluteFile
        TemplateLibrary.init(f.context)
        fun example(name: String) = TemplateLibrary.import(File(resources, "examples/$name.xtemplate"), null)
        val page = PageStyle(pageColor = Rgba(245, 238, 223), template = example("cornell").key, spacing = 55.0)
        val cover = PageStyle(template = example("cover-book-cloth").key)
        val tree = DocumentsContract.buildTreeDocumentUri(f.authority, "root").toString()
        val setup = Editor.NewNoteSetup(297.0, 210.0, page, cover)
        assertNotNull(f.editor.createNoteFile(tree, "root", "Paper", setup))
        val android = f.target.inputStream().use { f.codec.readComplete(it, f.root, f.root).document }

        val docs = File(f.root, "Documents").apply { mkdirs() }.toOkioPath()
        val store = LiteStore(KFiles.system, docs, File(f.root, "Private").toOkioPath(),
            AndroidImageCodec(), AndroidTextMeasurer(), resources.toOkioPath())
        val result = store.create(docs, "Paper", LiteNoteSetup(setup.widthMm, setup.heightMm, page, cover))
        (result as LiteOpen.Opened).session.use { session ->
            val lite = requireNotNull(session.note)
            assertNotEquals(android.identity, lite.identity)
            assertNotNull(android.created)
            assertNotNull(lite.created)
            assertEquals(android.dpi, lite.dpi)
            assertEquals(android.style, lite.style)
            assertEquals(android.margins, lite.margins)
            assertEquals(android.templates, lite.templates)
            assertEquals(android.templatePackages.keys, lite.templatePackages.keys)
            val key = requireNotNull(cover.template)
            assertArrayEquals(android.templatePackages.getValue(key).readBytes(), lite.templatePackages.getValue(key).readBytes())
            assertTrue(android.hasReferenceAnchors && lite.hasReferenceAnchors)
            assertEquals(android.pages.size, lite.pages.size)
            android.pages.zip(lite.pages).forEach { (a, b) ->
                assertEquals(a.width to a.height, b.width to b.height)
                assertEquals(a.style, b.style)
                assertEquals(a.margins, b.margins)
                assertEquals(a.cover, b.cover)
                assertNotNull(a.referenceIdentity.id)
                assertNotNull(b.referenceIdentity.id)
            }
        }
    }
}
