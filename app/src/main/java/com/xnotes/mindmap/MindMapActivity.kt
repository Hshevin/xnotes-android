package com.xnotes.mindmap

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel

/** Independent document editor; the existing note workspace stays safely parked underneath. */
class MindMapActivity : ComponentActivity() {
    private class LeaseOwner:ViewModel() {
        var lease:MapDocumentLease?=null
        fun acquire(context:Context,uri:android.net.Uri):MapDocumentLease = lease ?: MapDocuments.acquire(context,uri).also {lease=it}
        override fun onCleared() {lease?.close();lease=null;super.onCleared()}
    }
    private val owner by lazy { ViewModelProvider(this,object:ViewModelProvider.Factory {
        override fun <T:ViewModel> create(modelClass:Class<T>):T {
            @Suppress("UNCHECKED_CAST") return LeaseOwner() as T
        }
    })[LeaseOwner::class.java] }
    private val session get()=requireNotNull(owner.lease).session
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data ?: run { finish(); return }
        setResult(RESULT_OK, Intent().setData(uri))
        owner.acquire(this,uri)
        val title = intent.getStringExtra("title").orEmpty()
        setContent { MindMapScreen(session, title, onClose = ::finish) }
    }
    // Only unsaved edits are written; an untouched map must not raise a conflict on backgrounding.
    override fun onStop() { owner.lease?.session?.let {if(it.dirty) it.save()}; super.onStop() }
    companion object {
        fun intent(context: Context, uri: String, title: String): Intent =
            Intent(context, MindMapActivity::class.java).setData(android.net.Uri.parse(uri)).putExtra("title", title)
    }
}
