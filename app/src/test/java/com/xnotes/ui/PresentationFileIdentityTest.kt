package com.xnotes.ui

import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

class PresentationFileIdentityTest:PresentationFileIdentityContract() {
    override fun attributes(path:Path):BasicFileAttributes {
        val actual=super.attributes(path)
        // Android's Unix provider returns mtime when birth time is unsupported.
        return object:BasicFileAttributes by actual {
            override fun creationTime()=actual.lastModifiedTime()
        }
    }
}
