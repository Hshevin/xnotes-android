package com.xnotes.iosapp.home

import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSURL
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypePDF
import platform.darwin.NSObject

/**
 * Asks Files for one PDF and hands back a private copy of it (the picker copies it into the app's inbox),
 * or null when the user cancels. The caller deletes the copy when done.
 */
internal object PdfPicker {
    // The picker holds its delegate weakly; this keeps it alive until the user answers.
    private var pending: Delegate? = null

    fun pick(from: UIViewController, done: (Path?) -> Unit) {
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypePDF), asCopy = true)
        picker.allowsMultipleSelection = false
        val delegate = Delegate { pending = null; done(it) }
        pending = delegate
        picker.delegate = delegate
        from.presentViewController(picker, animated = true, completion = null)
    }

    private class Delegate(private val done: (Path?) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
            done((didPickDocumentsAtURLs.firstOrNull() as? NSURL)?.path?.toPath())
        }

        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = done(null)
    }
}
