package com.xnotes.architecture

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** An intentional source dependency rule, not a runtime-behaviour substitute. */
class RecoveryLayerDependencyTest {
    @Test fun platformRecoveryDoesNotDependOnTheMindMapFeature() {
        val roots=listOf(File("src/main/java/com/xnotes/platform"),File("app/src/main/java/com/xnotes/platform"))
        val root=requireNotNull(roots.firstOrNull {it.isDirectory}) {"Cannot locate platform sources"}
        val references=root.walkTopDown().filter {it.isFile && it.extension=="kt"}.flatMap {file->
            file.readLines().asSequence().mapIndexedNotNull {index,line->
                if(Regex("\\bcom\\.xnotes\\.mindmap\\.").containsMatchIn(line)) "${file.name}:${index+1}" else null
            }
        }.toList()
        assertEquals("Platform recovery must use a feature-neutral identity description",emptyList<String>(),references)
    }
}
