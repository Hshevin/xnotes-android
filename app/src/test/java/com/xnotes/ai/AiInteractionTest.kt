package com.xnotes.ai

import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AiInteractionTest {
    private val settings = AiConfig("https://example.com/v1", "vision", "key")
    private suspend fun until(condition: () -> Boolean) = withTimeout(5000) { while (!condition()) delay(5) }
    private fun started(sessions: AiSessions, id: String) {
        sessions.chats[id] = sessions.chats.getValue(id).copy(turns = listOf(AiTurn(AiMessage("user", "question"), "answer", "已完成")))
    }
    private suspend fun fixture(block: suspend (AiHistoryStore, CoroutineScope) -> Unit) = coroutineScope {
        val directory = Files.createTempDirectory("ai-interaction").toFile()
        val job = SupervisorJob()
        try { block(AiHistoryStore(directory), CoroutineScope(coroutineContext + job)) }
        finally { job.cancelAndJoin(); directory.deleteRecursively() }
    }
    @Test fun newChatClearsUnsentPromptAndAttachmentsWithoutCreatingAnotherChat() = runBlocking {
        fixture { store, scope ->
            val pdf = AiPdf("paper.pdf", id = "d".repeat(64), pageCount = 1)
            val sessions = AiSessions(store, scope, { settings }, preparePdf = { _, _ -> pdf })
            until { sessions.ready }
            val id = sessions.newChat()
            sessions.editDraft(id, "not sent")
            sessions.addScreenshot(id, byteArrayOf(1))
            sessions.addScreenshot(id, byteArrayOf(2))
            sessions.importPdf(id, "content://paper")
            until { sessions.pdfAttachments[id] != null }
            sessions.flush()
            val version = sessions.composerVersion(id)
            repeat(3) { assertEquals(id, sessions.newChat()) }
            sessions.flush()
            assertTrue(store.readAll().first.isEmpty())
            assertEquals(setOf(id), sessions.chats.keys)
            assertTrue(sessions.drafts[id].isNullOrEmpty())
            assertNull(sessions.attachments[id]); assertNull(sessions.pdfAttachments[id]); assertNull(sessions.importStates[id])
            assertTrue(sessions.composerVersion(id) > version)
            sessions.close(); sessions.flush()
            assertTrue(sessions.chats.isEmpty()); assertTrue(store.readAll().first.isEmpty())
            assertTrue(sessions.trash.isEmpty())
        }
    }
    @Test fun closingUnstartedChatDiscardsEvenANonblankPromptAndPersistsRemoval() = runBlocking {
        fixture { store, scope ->
            val sessions = AiSessions(store, scope, { settings })
            until { sessions.ready }; val id = sessions.newChat()
            sessions.editDraft(id, "unfinished prompt")
            sessions.addScreenshot(id, byteArrayOf(2))
            sessions.flush()
            sessions.close(); sessions.flush()
            assertNull(sessions.activeId); assertFalse(sessions.chats.containsKey(id))
            assertNull(sessions.drafts[id]); assertNull(sessions.attachments[id])
            assertTrue(store.readAll().first.isEmpty())
            val restored = AiSessions(store, scope, { settings })
            until { restored.ready }; assertTrue(restored.chats.isEmpty())
        }
    }
    @Test fun establishedChatKeepsDraftAndAttachmentsOnCloseAndNewChat() = runBlocking {
        fixture { store, scope ->
            val pdf = AiPdf("paper.pdf", id = "e".repeat(64), pageCount = 2)
            val sessions = AiSessions(store, scope, { settings }, preparePdf = { _, _ -> pdf })
            until { sessions.ready }; val id = sessions.newChat(); started(sessions, id)
            sessions.editDraft(id, "next question", true)
            sessions.addScreenshot(id, byteArrayOf(3))
            sessions.importPdf(id, "content://paper"); until { sessions.pdfAttachments[id] != null }
            sessions.close(); sessions.open(id)
            val next = sessions.newChat()
            assertNotEquals(id, next)
            assertEquals("next question", sessions.drafts[id])
            assertArrayEquals(byteArrayOf(3), sessions.attachments[id]!!.single()); assertEquals(pdf, sessions.pdfAttachments[id])
            // A composer with only attachments must also survive once the chat has a turn.
            sessions.editDraft(id, ""); sessions.open(id); sessions.close(); sessions.flush()
            val saved = store.readAll().first.single { it.id == id }
            assertEquals("", saved.draft.text); assertEquals(listOf("Aw=="), saved.draft.images); assertEquals(pdf, saved.draft.pdf)
            assertTrue(saved.draft.search); assertEquals(1, saved.turns.size)
        }
    }
    @Test fun resettingUnstartedChatRejectsLateImportWithoutDroppingReplacement() = runBlocking {
        fixture { store, scope ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val returned = CompletableDeferred<Unit>()
            val sessions = AiSessions(store, scope, { settings }, preparePdf = { uri, _ ->
                if (uri.endsWith("old")) withContext(NonCancellable) { entered.complete(Unit); release.await(); returned.complete(Unit) }
                AiPdf(uri, id = "f".repeat(64), pageCount = 1)
            })
            until { sessions.ready }; val id = sessions.newChat()
            sessions.importPdf(id, "content://old"); entered.await()
            assertEquals(id, sessions.newChat())
            sessions.importPdf(id, "content://new")
            until { sessions.pdfAttachments[id]?.name == "content://new" }
            release.complete(Unit); returned.await(); sessions.flush()
            assertEquals("content://new", sessions.pdfAttachments[id]?.name)
            assertNull(sessions.importStates[id]); assertTrue(sessions.drafts[id].isNullOrEmpty())
        }
    }
    @Test fun failedDiscardReportsStorageErrorAndCanSaveClearedComposerAgain() = runBlocking {
        val directory = Files.createTempDirectory("ai-discard-failure").toFile()
        val job = SupervisorJob()
        try {
            val scope = CoroutineScope(coroutineContext + job)
            val store = AiHistoryStore(directory)
            val sessions = AiSessions(store, scope, { settings })
            until { sessions.ready }; val id = sessions.newChat()
            sessions.editDraft(id, "not sent"); sessions.flush()
            val file = java.io.File(directory, "$id.json")
            assertTrue(file.delete()); assertTrue(file.mkdir())
            java.io.File(file, "blocker").writeText("keep")
            sessions.close(); sessions.flush()
            until { sessions.storageError.isNotEmpty() && sessions.chats.containsKey(id) }
            assertEquals("keep", java.io.File(file, "blocker").readText())
            assertTrue(sessions.drafts[id].isNullOrEmpty()); assertTrue(sessions.trash.isEmpty())
            file.deleteRecursively()
            sessions.retrySave(); sessions.flush()
            assertEquals("", sessions.storageError)
            assertTrue(store.readAll().first.single().draft.text.isEmpty())
        } finally { job.cancelAndJoin(); directory.deleteRecursively() }
    }
    @Test fun switchingAndReloadRestoreDraftAttachmentsAndSearchPerChat() = runBlocking {
        fixture { store, scope ->
            val sessions = AiSessions(store, scope, { settings })
            until { sessions.ready }
            val first = sessions.newChat()
            started(sessions, first)
            sessions.editDraft(first, "第一条草稿", true)
            sessions.addScreenshot(first, byteArrayOf(1, 2, 3)); sessions.addScreenshot(first, byteArrayOf(4))
            val second = sessions.newChat()
            started(sessions, second)
            sessions.editDraft(second, "第二条草稿")
            sessions.open(first)
            assertEquals("第一条草稿", sessions.drafts[first]); assertTrue(sessions.searchEnabled[first] == true)
            assertFalse(sessions.searchEnabled[second] == true)
            sessions.flush()
            val restored = AiSessions(store, scope, { settings })
            until { restored.ready }
            assertEquals("第二条草稿", restored.drafts[second])
            assertEquals("第一条草稿", restored.drafts[first])
            assertEquals(listOf(listOf<Byte>(1, 2, 3), listOf<Byte>(4)), restored.attachments[first]!!.map { it.toList() })
            assertTrue(restored.searchEnabled[first] == true)
        }
    }
    @Test fun importSurvivesSwitchAndRestoresAfterRestart() = runBlocking {
        fixture { store, scope ->
            val release = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
            val pdf = AiPdf("paper.pdf", id = "a".repeat(64), pageCount = 20)
            val sessions = AiSessions(store, scope, { settings }, preparePdf = { _, _ -> entered.complete(Unit); release.await(); pdf })
            until { sessions.ready }; val first = sessions.newChat()
            started(sessions, first)
            sessions.importPdf(first, "content://pdf")
            entered.await(); val second = sessions.newChat()
            assertTrue(sessions.importStates[first]?.loading == true)
            release.complete(Unit); until { sessions.pdfAttachments[first] != null }
            assertEquals(second, sessions.activeId); assertNull(sessions.pdfAttachments[second])
            sessions.flush()
            val restored = AiSessions(store, scope, { settings })
            until { restored.ready }; assertEquals(pdf, restored.pdfAttachments[first])
        }
    }
    @Test fun failedImportCanRetryAndDeletedSessionCannotBeRevived() = runBlocking {
        fixture { store, scope ->
            var calls = 0
            val sessions = AiSessions(store, scope, { settings }, preparePdf = { _, _ ->
                calls++; if (calls == 1) throw IllegalArgumentException("bad PDF")
                AiPdf("ok.pdf", id = "b".repeat(64), pageCount = 2)
            })
            until { sessions.ready }; val id = sessions.newChat()
            sessions.importPdf(id, "content://pdf")
            until { sessions.importStates[id]?.error == "bad PDF" }
            sessions.retryImport(id); until { sessions.pdfAttachments[id] != null }
            sessions.editDraft(id, "draft"); sessions.delete(id)
            sessions.flush(); sessions.editDraft(id, "late callback")
            sessions.flush(); assertFalse(sessions.chats.containsKey(id))
            assertEquals("draft", store.readAll().first.single().draft.text) // the trashed record keeps the draft, not the late edit
            sessions.restore(id); sessions.flush()
            assertEquals("draft", sessions.drafts[id]); assertNotNull(sessions.pdfAttachments[id])
        }
    }
    @Test fun generationProgressAndNextDraftRemainWithOriginalChat() = runBlocking {
        fixture { store, scope ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val transport = object : AiTransport {
                override fun cancel() { release.cancel() }
                override fun search(key: String, query: String, question: String): List<AiWebSource> = emptyList()
                override fun fetch(key: String, url: String) = ""
                override suspend fun stream(config: AiConfig, messages: List<AiMessage>, delta: suspend (String) -> Unit) {
                    delta("partial"); entered.complete(Unit); release.await(); delta("done")
                }
            }
            val sessions = AiSessions(store, scope, { settings }, { transport })
            until { sessions.ready }; val first = sessions.newChat()
            sessions.editDraft(first, "question")
            sessions.send(first, AiMessage("user", "question"), false)
            entered.await()
            assertTrue(sessions.drafts[first].isNullOrEmpty())
            assertEquals("正在生成回答", sessions.chats[first]!!.turns.last().progress)
            sessions.editDraft(first, "next question")
            val second = sessions.newChat()
            release.complete(Unit); until { !sessions.isRunning(first) }; sessions.flush()
            assertEquals(second, sessions.activeId)
            val saved = store.readAll().first.single { it.id == first }
            assertEquals("next question", saved.draft.text)
            assertEquals("partialdone", saved.turns.single().answer)
            assertTrue(saved.turns.single().steps.contains("正在生成回答"))
        }
    }
    @Test fun screenshotsAccumulateUpToLimitAndAllAreSent() = runBlocking {
        fixture { store, scope ->
            val sent = CompletableDeferred<List<AiMessage>>()
            val transport = object : AiTransport {
                override suspend fun stream(config: AiConfig, messages: List<AiMessage>, delta: suspend (String) -> Unit) { sent.complete(messages); delta("ok") }
                override fun search(key: String, query: String, question: String): List<AiWebSource> = emptyList()
                override fun fetch(key: String, url: String) = ""
                override fun cancel() {}
            }
            val sessions = AiSessions(store, scope, { settings }, { transport })
            until { sessions.ready }; val id = sessions.newChat()
            repeat(AiProtocol.MAX_IMAGES) { assertTrue(sessions.addScreenshot(id, byteArrayOf(it.toByte()))) }
            assertFalse(sessions.addScreenshot(id, byteArrayOf(9)))
            assertEquals(AiProtocol.MAX_IMAGES, sessions.attachments[id]!!.size)
            sessions.removeScreenshot(id, 0)
            assertEquals((1 until AiProtocol.MAX_IMAGES).map { it.toByte() }, sessions.attachments[id]!!.map { it.single() })
            val images = sessions.attachments[id]!!.map { java.util.Base64.getEncoder().encodeToString(it) }
            sessions.editDraft(id, "看这几张")
            sessions.send(id, AiMessage("user", "看这几张", images), false)
            assertEquals(images, withTimeout(5000) { sent.await() }.last().images)
            until { !sessions.isRunning(id) }
            assertNull(sessions.attachments[id])
            assertEquals(images, sessions.chats[id]!!.turns.single().user.images)
        }
    }
    @Test fun floatingHistorySwitchesWithinTheDocument() = runBlocking {
        fixture { store, scope ->
            val sessions = AiSessions(store, scope, { settings })
            until { sessions.ready }
            val older = sessions.newChat("a", "uri-a", "doc-a", "doc-a")
            started(sessions, older)
            sessions.offerCurrentPdf(older, "file:///a.pdf", "a.pdf")
            sessions.newChat("b", "uri-b", "doc-b", "doc-b")
            val newer = sessions.newChat("a", "uri-a", "doc-a", "doc-a")
            assertEquals(listOf(newer, older).toSet(), sessions.chatsOf("doc-a").map { it.id }.toSet())
            sessions.openFloating(older)
            assertEquals(older, sessions.activeId); assertEquals("doc-a", sessions.floatingOwner)
            sessions.openFloating(newer)
            assertEquals("file:///a.pdf" to "a.pdf", sessions.currentPdfs[newer])
            sessions.close(); sessions.openFloating(older)
            assertNull(sessions.activeId)
        }
    }
    @Test fun splitScreenCapturesJoinTheOpenChatWhichKeepsItsSource() = runBlocking {
        fixture { store, scope ->
            val sessions = AiSessions(store, scope, { settings })
            until { sessions.ready }
            sessions.documents["doc-a"] = AiDocument("A", "uri-a", "doc-a", "file:///a.pdf" to "A.pdf")
            sessions.documents["doc-b"] = AiDocument("B", "uri-b", "doc-b", "file:///b.pdf" to "B.pdf")
            val id = sessions.newChat("A", "uri-a", "doc-a", "doc-a")
            assertTrue(sessions.addScreenshot(id, byteArrayOf(1), fromDocument = "doc-a"))
            assertTrue(sessions.addScreenshot(id, byteArrayOf(2), fromDocument = "doc-b"))
            assertTrue(sessions.addScreenshot(id, byteArrayOf(3), fromDocument = "doc-b"))
            val chat = sessions.chats[id]!!
            assertEquals("doc-a", chat.sourceDocumentId); assertEquals(listOf("doc-b"), chat.relatedDocuments)
            assertEquals(3, sessions.attachments[id]!!.size)
            assertEquals(listOf(id), sessions.chatsOf("doc-a").map { it.id }); assertEquals(listOf(id), sessions.chatsOf("doc-b").map { it.id })
            sessions.flush()
            assertEquals(listOf("doc-b"), store.readAll().first.single().relatedDocuments)
            // A's pane closes: the card moves to B, which this chat also drew from, rather than closing.
            sessions.reconcileFloating(setOf("doc-b"))
            assertEquals("doc-b", sessions.floatingOwner); assertEquals(id, sessions.activeId)
            // Its "new chat" now starts a chat of B, offering B's PDF, not one inherited from A's chat.
            val fresh = sessions.newChat(floatingOwner = sessions.floatingOwner)
            assertNotEquals(id, fresh)
            assertFalse(sessions.chats.containsKey(id))
            assertEquals("doc-b", sessions.chats[fresh]!!.sourceDocumentId); assertEquals("B", sessions.chats[fresh]!!.source)
            assertEquals("file:///b.pdf" to "B.pdf", sessions.currentPdfs[fresh])
            sessions.reconcileFloating(setOf("doc-c"))
            assertNull(sessions.activeId); assertNull(sessions.floatingOwner)
        }
    }
    @Test fun captureModeArmsEveryPaneAndGivesToolsBackWhenUnfolded() = runBlocking {
        fixture { store, scope ->
            val sessions = AiSessions(store, scope, { settings })
            until { sessions.ready }
            val log = mutableListOf<String>()
            val left = Any(); val right = Any()
            sessions.registerCapture(left, AiCaptureTarget({ log += "arm A" }, { log += "release A" }))
            sessions.registerCapture(right, AiCaptureTarget({ log += "arm B" }, { log += "release B" }))
            assertTrue(sessions.canCapture)
            sessions.startCapture() // no floating chat yet: nothing to capture for
            assertFalse(sessions.capturing); assertTrue(log.isEmpty())
            sessions.newChat("A", "uri-a", "doc-a", "doc-a")
            sessions.startCapture()
            assertTrue(sessions.capturing); assertTrue(sessions.floatingCollapsed)
            // Pane order is not meaningful (a hash of per-pane tokens); what matters is that each is armed once.
            assertEquals(setOf("arm A", "arm B"), log.toSet()); assertEquals(2, log.size)
            sessions.expand()
            assertFalse(sessions.capturing); assertFalse(sessions.floatingCollapsed)
            assertEquals(setOf("release A", "release B"), log.drop(2).toSet()); assertEquals(4, log.size)
            sessions.expand() // already out of capture mode: nothing released twice
            assertEquals(4, log.size)
            val id = sessions.activeId!!
            sessions.addScreenshot(id, byteArrayOf(1))
            sessions.addScreenshot(id, byteArrayOf(2), fromDocument = "doc-b")
            sessions.startCapture()
            assertEquals(id, sessions.newChat(floatingOwner = "doc-a"))
            assertFalse(sessions.capturing)
            assertNull(sessions.attachments[id])
            assertTrue(sessions.chats.getValue(id).relatedDocuments.isEmpty())
            assertEquals(setOf("release A", "release B"), log.takeLast(2).toSet())
            log.subList(4, log.size).clear()
            sessions.startCapture(); sessions.unregisterCapture(right); sessions.close()
            // The right pane left during capture mode: only the left one is given its tool back.
            assertEquals(setOf("arm A", "arm B"), log.subList(4, 6).toSet()); assertEquals("release A", log.last()); assertEquals(7, log.size)
            assertFalse(sessions.capturing); assertTrue(sessions.canCapture)
            assertFalse(sessions.chats.containsKey(id))
        }
    }
    @Test fun trashedChatsStayOutOfEveryHistoryAcrossReload() = runBlocking {
        fixture { store, scope ->
            val sessions = AiSessions(store, scope, { settings })
            until { sessions.ready }
            val kept = sessions.newChat("A", "uri-a", "doc-a", "doc-a")
            started(sessions, kept); sessions.editDraft(kept, "kept draft")
            val joined = sessions.newChat("A", "uri-a", "doc-a", "doc-a")
            sessions.addScreenshot(joined, byteArrayOf(1), fromDocument = "doc-b")
            sessions.delete(joined); sessions.flush()
            assertEquals(listOf(kept), sessions.chatsOf("doc-a").map { it.id }); assertTrue(sessions.chatsOf("doc-b").isEmpty())
            val reloaded = AiSessions(store, scope, { settings })
            until { reloaded.ready }
            assertEquals(setOf(kept), reloaded.chats.keys); assertEquals(setOf(joined), reloaded.trash.keys)
            assertTrue(reloaded.chatsOf("doc-b").isEmpty()); assertNull(reloaded.attachments[joined])
            reloaded.restore(joined); reloaded.flush()
            assertEquals(1, reloaded.attachments[joined]!!.size) // the draft screenshot comes back with it
            assertEquals(listOf(joined), reloaded.chatsOf("doc-b").map { it.id })
        }
    }
    @Test fun deletingTheFloatingChatMovesTheCardToTheDocumentsNextChat() = runBlocking {
        fixture { store, scope ->
            val sessions = AiSessions(store, scope, { settings })
            until { sessions.ready }
            sessions.documents["doc-a"] = AiDocument("A", "uri-a", "doc-a", null)
            val older = sessions.newChat("A", "uri-a", "doc-a", "doc-a")
            started(sessions, older)
            val newer = sessions.newChat("A", "uri-a", "doc-a", "doc-a")
            sessions.delete(newer)
            assertEquals(older, sessions.activeId); assertEquals("doc-a", sessions.floatingOwner)
            sessions.delete(older) // none left: a fresh chat of the same document keeps the card open
            assertNotNull(sessions.activeId); assertEquals("doc-a", sessions.chats[sessions.activeId!!]!!.sourceDocumentId)
            sessions.documents.remove("doc-a"); sessions.delete(sessions.activeId!!)
            assertNull(sessions.activeId); assertNull(sessions.floatingOwner)
            sessions.flush(); assertEquals(3, sessions.trash.size)
        }
    }
    @Test fun trashIsPurgedByRetentionAndEmptiedByHand() = runBlocking {
        fixture { store, scope ->
            val sessions = AiSessions(store, scope, { settings })
            until { sessions.ready }
            val a = sessions.newChat(); started(sessions, a)
            val b = sessions.newChat()
            sessions.delete(a); sessions.delete(b); sessions.flush()
            val now = System.currentTimeMillis()
            sessions.purgeTrash(0, now + 400 * 86_400_000L) // trash kept forever / off: nothing purged
            assertEquals(2, sessions.trash.size)
            sessions.purgeTrash(30, now + 29 * 86_400_000L); sessions.flush()
            assertEquals(2, sessions.trash.size)
            sessions.purgeTrash(30, now + 31 * 86_400_000L); sessions.flush()
            assertTrue(sessions.trash.isEmpty()); assertTrue(store.readAll().first.isEmpty())
            val c = sessions.newChat(); sessions.delete(c); sessions.emptyTrash(); sessions.flush()
            assertTrue(store.readAll().first.isEmpty())
        }
    }
    @Test fun withTrashOffDeleteIsPermanentLikeNotes() = runBlocking {
        fixture { store, scope ->
            val sessions = AiSessions(store, scope, { settings })
            until { sessions.ready }
            val kept = sessions.newChat(); started(sessions, kept); sessions.editDraft(kept, "kept draft")
            val gone = sessions.newChat(); sessions.flush()
            sessions.trashDays = 0
            assertTrue(sessions.trashOff)
            sessions.delete(gone); sessions.flush()
            assertFalse(sessions.chats.containsKey(gone)); assertTrue(sessions.trash.isEmpty())
            assertEquals(listOf(kept), store.readAll().first.map { it.id })
            sessions.trashDays = 30
            sessions.delete(kept); sessions.flush()
            assertEquals(setOf(kept), sessions.trash.keys) // back on: deletes go to Trash again
        }
    }
    @Test fun permanentDeleteFailureLeavesTheChatInHistory() = runBlocking {
        val directory = Files.createTempDirectory("ai-interaction").toFile()
        val job = SupervisorJob()
        try {
            val scope = CoroutineScope(coroutineContext + job)
            val sessions = AiSessions(AiHistoryStore(directory), scope, { settings })
            until { sessions.ready }
            val id = sessions.newChat(); sessions.editDraft(id, "keep me"); sessions.flush()
            sessions.trashDays = 0
            directory.setWritable(false)
            sessions.delete(id); sessions.flush()
            until { sessions.chats.containsKey(id) }
            assertEquals("keep me", sessions.drafts[id]); assertTrue(sessions.storageError.isNotEmpty())
        } finally { directory.setWritable(true); job.cancelAndJoin(); directory.deleteRecursively() }
    }
    @Test fun trashWriteFailureLeavesTheChatInHistory() = runBlocking {
        val directory = Files.createTempDirectory("ai-interaction").toFile()
        val job = SupervisorJob()
        try {
            val scope = CoroutineScope(coroutineContext + job)
            val sessions = AiSessions(AiHistoryStore(directory), scope, { settings })
            until { sessions.ready }
            val id = sessions.newChat(); sessions.editDraft(id, "keep me"); sessions.flush()
            directory.setWritable(false)
            sessions.delete(id); sessions.flush()
            until { sessions.chats.containsKey(id) }; assertFalse(sessions.trash.containsKey(id))
            assertEquals("keep me", sessions.drafts[id]); assertTrue(sessions.storageError.isNotEmpty())
        } finally { directory.setWritable(true); job.cancelAndJoin(); directory.deleteRecursively() }
    }
    @Test fun configurationFailureKeepsComposer() = runBlocking {
        fixture { store, scope ->
            val sessions = AiSessions(store, scope, { AiConfig() })
            until { sessions.ready }; val id = sessions.newChat()
            sessions.editDraft(id, "unsent")
            sessions.addScreenshot(id, byteArrayOf(1))
            sessions.send(id, AiMessage("user", "unsent", listOf("AQ==")), false)
            until { !sessions.isRunning(id) }; sessions.flush()
            assertEquals("unsent", sessions.drafts[id]); assertArrayEquals(byteArrayOf(1), sessions.attachments[id]!!.single())
            assertEquals("unsent", store.readAll().first.single().draft.text)
        }
    }
    @Test fun deletedChatRejectsLateImportCompletion() = runBlocking {
        fixture { store, scope ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val sessions = AiSessions(store, scope, { settings }, preparePdf = { _, _ ->
                withContext(NonCancellable) { entered.complete(Unit); release.await() }
                AiPdf("late.pdf", id = "c".repeat(64), pageCount = 1)
            })
            until { sessions.ready }; val id = sessions.newChat()
            sessions.importPdf(id, "content://pdf"); entered.await()
            sessions.delete(id); release.complete(Unit)
            sessions.flush()
            assertFalse(sessions.chats.containsKey(id)); assertNull(sessions.pdfAttachments[id]); assertNull(sessions.importStates[id])
            assertNull(store.readAll().first.single().draft.pdf)
        }
    }

    @Test fun toggleFloatingCreatesNewChatAndTogglesCollapseWithoutDiscardingDraft() = runBlocking {
        fixture { store, scope ->
            val sessions = AiSessions(store, scope, { settings })
            until { sessions.ready }
            assertFalse(sessions.isFloatingOpen)
            assertFalse(sessions.isFloatingExpanded)
            assertTrue(
                sessions.toggleFloating(
                    source = "Doc A",
                    sourceUri = "uri-a",
                    owner = "doc-a",
                    documentId = "doc-a",
                    currentPdf = "file:///a.pdf" to "Doc A.pdf",
                ),
            )
            val id = sessions.activeId!!
            assertTrue(sessions.isFloatingOpen)
            assertTrue(sessions.isFloatingExpanded)
            assertEquals("doc-a", sessions.floatingOwner)
            assertEquals("file:///a.pdf" to "Doc A.pdf", sessions.currentPdfs[id])
            // Typing a draft then collapsing via the top bar keeps both the draft and the unstarted chat.
            sessions.editDraft(id, "keep draft")
            assertTrue(sessions.toggleFloating(source = "Doc A", sourceUri = "uri-a", owner = "doc-a", documentId = "doc-a"))
            assertTrue(sessions.isFloatingOpen)
            assertTrue(sessions.floatingCollapsed)
            assertFalse(sessions.isFloatingExpanded)
            assertEquals(id, sessions.activeId)
            assertEquals("keep draft", sessions.drafts[id])
            // Tapping again expands it and ends any active capture mode.
            val log = mutableListOf<String>()
            sessions.registerCapture(Any(), AiCaptureTarget({ log += "arm" }, { log += "release" }))
            sessions.startCapture()
            assertTrue(sessions.capturing)
            assertTrue(sessions.toggleFloating(source = "Doc A", sourceUri = "uri-a", owner = "doc-a", documentId = "doc-a"))
            assertFalse(sessions.floatingCollapsed)
            assertTrue(sessions.isFloatingExpanded)
            assertFalse(sessions.capturing)
            assertEquals(listOf("arm", "release"), log)
        }
    }

    @Test fun addScreenshotAndExpandUnifiesTopBarAndFloatingCapture() = runBlocking {
        fixture { store, scope ->
            val sessions = AiSessions(store, scope, { settings })
            until { sessions.ready }
            val log = mutableListOf<String>()
            sessions.registerCapture(Any(), AiCaptureTarget({ log += "arm A" }, { log += "release A" }))
            sessions.registerCapture(Any(), AiCaptureTarget({ log += "arm B" }, { log += "release B" }))

            // 1. No floating chat open: creates one, attaches screenshot, offers PDF, opens expanded.
            assertTrue(
                sessions.addScreenshotAndExpand(
                    bytes = byteArrayOf(1),
                    source = "Doc A",
                    sourceUri = "uri-a",
                    owner = "doc-a",
                    documentId = "doc-a",
                    currentPdf = "file:///a.pdf" to "Doc A.pdf",
                ),
            )
            val id = sessions.activeId!!
            assertTrue(sessions.isFloatingExpanded)
            assertEquals(1, sessions.attachments[id]!!.size)
            assertEquals("file:///a.pdf" to "Doc A.pdf", sessions.currentPdfs[id])

            // 2. Floating chat in capture mode: adding one screenshot expands the card and releases every pane.
            sessions.startCapture()
            assertTrue(sessions.capturing)
            assertTrue(sessions.floatingCollapsed)
            assertTrue(
                sessions.addScreenshotAndExpand(
                    bytes = byteArrayOf(2),
                    source = "Doc B",
                    sourceUri = "uri-b",
                    owner = "doc-b",
                    documentId = "doc-b",
                ),
            )
            assertEquals(2, sessions.attachments[id]!!.size)
            assertFalse(sessions.capturing)
            assertFalse(sessions.floatingCollapsed)
            assertEquals(listOf("doc-b"), sessions.chats.getValue(id).relatedDocuments)
            assertEquals(setOf("release A", "release B"), log.takeLast(2).toSet())

            // 3. Manually collapsed floating chat (not in capture mode): adding a screenshot also expands it.
            sessions.floatingCollapsed = true
            assertTrue(sessions.addScreenshotAndExpand(byteArrayOf(3), "Doc A", "uri-a", "doc-a", "doc-a"))
            assertFalse(sessions.floatingCollapsed)
            assertEquals(3, sessions.attachments[id]!!.size)

            // 4. Hitting MAX_IMAGES refuses without expanding or leaving capture mode prematurely.
            repeat(AiProtocol.MAX_IMAGES - 3) {
                assertTrue(sessions.addScreenshotAndExpand(byteArrayOf((it + 4).toByte()), "Doc A", "uri-a", "doc-a", "doc-a"))
            }
            sessions.startCapture()
            assertFalse(sessions.addScreenshotAndExpand(byteArrayOf(99), "Doc A", "uri-a", "doc-a", "doc-a"))
            assertEquals(AiProtocol.MAX_IMAGES, sessions.attachments[id]!!.size)
            assertTrue(sessions.capturing)
            assertTrue(sessions.floatingCollapsed)
        }
    }

}
