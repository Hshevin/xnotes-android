package com.xnotes.platform

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PdfCloseAwaitTest {
    @Test fun waitingCloseAcknowledgesTheActualQueuedCloseAndIsIdempotent() {
        val worker=PdfiumThread("pdf-close-test")
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        worker.submit(PdfPriority.INTERACTIVE) {entered.countDown();release.await()}
        assertTrue(entered.await(5,TimeUnit.SECONDS))
        val constructor=PdfiumDocument::class.java.getDeclaredConstructor(File::class.java,PdfiumThread::class.java).apply {isAccessible=true}
        val document=constructor.newInstance(File("unused.pdf"),worker)
        document.close() // Existing callers keep their nonblocking close.
        val completed=CountDownLatch(1)
        val closer=Thread {document.closeAndAwait();document.closeAndAwait();completed.countDown()}
        closer.start()
        try {
            assertFalse(completed.await(80,TimeUnit.MILLISECONDS))
            closer.interrupt() // Interruption cannot abandon the native close and release its lease early.
            assertFalse(completed.await(80,TimeUnit.MILLISECONDS))
        } finally {release.countDown()}
        assertTrue(completed.await(5,TimeUnit.SECONDS));assertTrue(closer.isInterrupted)
    }

    @Test fun textCloseWaitsForAnAlreadyRunningReaderAndDropsItsCallback() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val completed=CountDownLatch(1)
        var callback=false
        val cache=PageTextCache {entered.countDown();while(release.count>0) try {release.await()} catch(_:InterruptedException) {};null}
        cache.request(0) {callback=true}
        assertTrue(entered.await(5,TimeUnit.SECONDS))
        Thread {cache.closeAndAwait();cache.closeAndAwait();completed.countDown()}.start()
        try {assertFalse(completed.await(80,TimeUnit.MILLISECONDS))} finally {release.countDown()}
        assertTrue(completed.await(5,TimeUnit.SECONDS));assertFalse(callback)
    }
}
