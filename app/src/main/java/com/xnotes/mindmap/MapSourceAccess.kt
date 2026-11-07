package com.xnotes.mindmap

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.xnotes.core.util.DocumentKind
import com.xnotes.storage.NotebookStorage

/** Read identity only: choosing a source must never migrate or rewrite the source file. IO only. */
internal object MapSourceAccess {
    fun inspect(context: Context, uri: Uri,checkActive:()->Unit={}): MapSource {
        checkActive()
        val name=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else null } ?: uri.lastPathSegment.orEmpty()
        val kind=DocumentKind.ofName(name)
        val storage=NotebookStorage.get(context)
        val id=when(kind) {
            DocumentKind.MIND_MAP -> MapFiles.read(context,uri.toString()).id
            DocumentKind.NOTE,DocumentKind.CANVAS -> requireNotNull(storage.identity(uri.toString(),checkActive)).documentId
            else -> error("Unsupported or unregistered source")
        }
        checkActive()
        val source=MapSource(documentId=id,fileName=name).also { it.validate() }
        runCatching { context.contentResolver.takePersistableUriPermission(uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        storage.registry.register(id,requireNotNull(kind).extension,name,uri.toString())
        require(storage.registry.locations(id).size==1)
        return source
    }
}
