package com.xnotes.mindmap

import android.net.Uri
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.*
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Duration

/** Real composed actions, without replacing the production callbacks or document actor. */
internal class MapComposeReviewFixture(document:MindMapDocument):AutoCloseable {
    private val host=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
    val activity=host.get()
    val file=File.createTempFile("map-compose-review-",".xmap",activity.cacheDir).apply {writeBytes(MapCodec.encode(document))}
    val session=MapSession().also {it.open(activity,Uri.fromFile(file))}
    init {await { !session.loading };assertFalse(session.loadFailed)}
    fun content(body:@Composable ()->Unit) {activity.setContent(content=body);settle()}
    fun screen(onClose:()->Unit={}) {
        content {MindMapScreen(session,"Review graph",onClose)}
    }
    fun settle() {
        repeat(4) {
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            windowRoots().forEach {view->
                view.measure(View.MeasureSpec.makeMeasureSpec(1800,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1400,View.MeasureSpec.EXACTLY))
                view.layout(0,0,1800,1400)
                view.viewTreeObserver.dispatchOnPreDraw()
            }
            // Compose also schedules snapshot/frame work outside this virtual looper. Yielding
            // alone can keep a whole-suite worker ahead of that work for every retry.
            Thread.sleep(1)
        }
    }
    fun await(condition:()->Boolean) {
        repeat(400) {shadowOf(Looper.getMainLooper()).idle();if(condition()) return;Thread.sleep(5)}
        fail("Actual graph operation did not settle")
    }
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup) (0 until view.childCount).flatMap {views(view.getChildAt(it))} else emptyList()
    private fun belongsToActivity(view:View):Boolean {
        var context:Context?=view.context
        val seen=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Context,Boolean>())
        while(context!=null && seen.add(context)) {
            if(context===activity) return true
            context=(context as? ContextWrapper)?.baseContext
        }
        return false
    }
    private fun windowRoots():List<View> = (WindowInspector.getGlobalWindowViews()+activity.window.decorView).distinct().filter {root->
        root.isAttachedToWindow && (belongsToActivity(root) || views(root).any(::belongsToActivity))
    }
    private fun ownedViews()=windowRoots().flatMap(::views).filter {it.isAttachedToWindow && belongsToActivity(it)}
    fun graph():MindMapView=ownedViews().filterIsInstance<MindMapView>().single()
    private fun nodes(node:SemanticsNode):List<SemanticsNode> = listOf(node)+node.children.flatMap(::nodes)
    private fun semantics():List<SemanticsNode> = ownedViews().mapNotNull {view->
        view.javaClass.methods.firstOrNull {it.name=="getSemanticsOwner" && it.parameterCount==0}?.invoke(view) as? SemanticsOwner
    }.flatMap {nodes(it.rootSemanticsNode)}
    fun clickAction(id:Int):()->Boolean {
        val label=activity.getString(id)
        repeat(30) {
            semantics().firstNotNullOfOrNull {node->
                val config=node.config
                if(config.getOrNull(SemanticsProperties.Text)?.any {it.text==label}==true && !config.contains(SemanticsProperties.Disabled))
                    config.getOrNull(SemanticsActions.OnClick)?.action else null
            }?.let {return it}
            settle()
        }
        error("No composed action for $label; available ${semantics().mapNotNull {it.config.getOrNull(SemanticsProperties.Text)}}")
    }
    fun click(id:Int) {assertTrue(clickAction(id)());settle()}
    fun enter(value:String) {
        repeat(30) {
            semantics().firstNotNullOfOrNull {it.config.getOrNull(SemanticsActions.SetText)?.action}?.let {action->
                assertTrue(action(AnnotatedString(value)));settle();return
            }
            settle()
        }
        error("No actual composed text field")
    }
    fun visible(id:Int)=semantics().any {node->node.config.getOrNull(SemanticsProperties.Text)?.any {it.text==activity.getString(id)}==true}
    override fun close() {
        activity.setContent {};settle()
        session.save();await {!session.saving}
        host.pause().stop().destroy()
        file.delete()
    }
}
