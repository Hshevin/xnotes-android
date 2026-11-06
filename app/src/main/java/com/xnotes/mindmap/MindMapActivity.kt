package com.xnotes.mindmap

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider

/** Independent document editor; the existing note workspace stays safely parked underneath. */
class MindMapActivity : ComponentActivity() {
    private val session by lazy { ViewModelProvider(this)[MapSession::class.java] }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data ?: run { finish(); return }
        setResult(RESULT_OK, Intent().setData(uri))
        session.open(this, uri)
        val title = intent.getStringExtra("title").orEmpty()
        setContent { MindMapScreen(session, title, onClose = ::finish) }
    }
    override fun onStop() { session.save(); super.onStop() }
    companion object {
        fun intent(context: Context, uri: String, title: String): Intent =
            Intent(context, MindMapActivity::class.java).setData(android.net.Uri.parse(uri)).putExtra("title", title)
    }
}
