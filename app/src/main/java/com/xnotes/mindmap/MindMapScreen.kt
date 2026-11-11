package com.xnotes.mindmap

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.xnotes.R
import com.xnotes.settings.LiveSettings
import com.xnotes.settings.SettingsRepository
import com.xnotes.ui.theme.*

internal val LocalInkBusy=staticCompositionLocalOf {false}
private val LocalMapActionFocus=staticCompositionLocalOf<()->Boolean> {{true}}

@Composable
fun MindMapScreen(session:MapSession,title:String,onClose:()->Unit,presentation:MapPresentationState?=null,hostHooks:MapHostHooks?=null) {
    val presentationState=presentation ?: remember(session) {MapPresentationState()}
    presentationState.synchronize(session)
    val navigationBusy=hostHooks?.busy?.invoke()==true
    val navigationFeedback=hostHooks?.feedback?.invoke()
    val context = LocalContext.current
    val appearance = remember { LiveSettings.get(SettingsRepository(context)).prefs.uiAppearance }
    val dark = when (appearance) { "light" -> false; "dark", "oled" -> true; else -> isSystemInDarkTheme() }
    XnotesTheme(monochromePalette(dark)) {
      CompositionLocalProvider(LocalInkBusy provides (session.inkContact || navigationBusy),
          com.xnotes.ui.LocalToolbarFocused provides (hostHooks?.focused?.invoke()!=false),
          LocalMapActionFocus provides (hostHooks?.requestFocus ?: {true})) screen@{
        var pendingSourceRequest by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
        var activeExistingSource by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
        val sourceController=session.sourcePicker
        val sourceTargets=session.sourceTargetEdits
        val targetPicker=LocalSourceTargetPickerHost.current ?: rememberSourceTargetPickerHost()
        val pendingTargetPickerId=targetPicker.pendingId
        val graphEpoch=session.interactionEpoch
        var graphTools by remember(graphEpoch) { mutableStateOf(false) }
        var pendingRelation by remember(graphEpoch) {mutableStateOf<MapRelation?>(null)}
        var relationToEdit by remember(graphEpoch) {mutableStateOf<String?>(null)}
        var connectingFrom by remember {mutableStateOf<String?>(null)}
        var connectedPickerId by androidx.compose.runtime.saveable.rememberSaveable {mutableStateOf<String?>(null)}
        val connectedSourcePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->
            val request=connectedPickerId;connectedPickerId=null
            if(request!=null) sourceController.receive(request,uri)
        }
        var addFree by remember { mutableStateOf(false) }
        var layoutMenu by remember(graphEpoch) { mutableStateOf(false) }
        var proposed by remember(graphEpoch) { mutableStateOf<MindMapDocument?>(null) }
        var geometryChanged by remember(graphEpoch) { mutableStateOf(false) }
        val sourcePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val request=pendingSourceRequest;pendingSourceRequest=null
            if(request!=null) {activeExistingSource=request;sourceController.receive(request,uri)}
        }
        LaunchedEffect(sourceController.state,pendingSourceRequest) {
            if(sourceController.state==null && pendingSourceRequest==null) activeExistingSource=null
        }
        var view by remember { mutableStateOf<MindMapView?>(null) }
        var viewRevision by remember { mutableIntStateOf(0) }
        var editing by remember { mutableStateOf<String?>(null) }
        var propertiesFor by remember {mutableStateOf<String?>(null)}
        var deleting by remember { mutableStateOf<MapNodeSelectionCapture?>(null) }
        var discardingRecovery by remember { mutableStateOf(false) }
        var choosing by remember { mutableStateOf(false) }
        var reparenting by remember { mutableStateOf(false) }
        var help by remember { mutableStateOf(false) }
        val saveCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) session.saveCopy(uri) {}
        }
        // The host parks only the visible editor. OS picker slots above retain their original owner.
        if(!presentationState.visible) return@screen
        val close:()->Unit = {
            val closeEntry=hostHooks?.closeEntry
            if(closeEntry!=null) closeEntry()
            else if(session.document==null || !session.dirty) onClose() else session.save(onClose)
        }
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val density = LocalDensity.current
        val imeBottom = WindowInsets.ime.getBottom(density)
        val textDraft = session.textDraft
        val textRestore = session.textRestore
        fun editExistingRelation(id:String) {
            if(session.document?.relations?.none {it.id==id}!=false || !session.selectRelation(id)) return
            relationToEdit=id;pendingRelation=null;graphTools=true
        }
        fun endText(confirm:Boolean) {
            val draft=session.textDraft ?: return
            if(confirm && !session.finishTextDraft(draft.token)) return
            if(!confirm) session.cancelTextDraft(draft.token)
            focus.clearFocus();keyboard?.hide()
        }
        // The transaction is owned by the session, so IME/host recomposition keeps composition and selection.
        LaunchedEffect(editing,view,viewRevision) {
            val id=editing ?: return@LaunchedEffect
            val graph=view ?: return@LaunchedEffect
            if(graph.width>0 && graph.height>0 && session.document?.nodes?.any {it.id==id && it.kind==NodeKind.TEXT}==true &&
                session.beginText(id,graph.viewport(),graph.width,graph.height,graph.resources.displayMetrics.density)) {
                editing=null
            }
        }
        // Exiting has one path, including configuration recreation with an outstanding restore.
        val geometry=remember(viewRevision,view) {Pair(view?.width ?: 0,view?.height ?: 0)}
        var cameraReady by remember(view) {mutableStateOf(false)}
        var textOwnedCamera by remember(view) {mutableStateOf(false)}
        fun showSources(id:String?):Boolean {
            val graph=view ?: return false
            if(id==null || navigationBusy) return false
            return presentationState.showSources(session,graph,id,imeBottom,cameraReady)
        }
        fun openSource(ticket:MapSourceNavigationTicket) {
            val hooks=hostHooks ?: return
            if(!cameraReady || navigationBusy) return
            val bookmark=session.captureSourceReturn(ticket) ?: return
            // Close first: a synchronous failure may already reopen this exact source panel.
            presentationState.parkSourcePanel();focus.clearFocus();keyboard?.hide()
            if(!hooks.openSource(ticket,bookmark)) presentationState.reopenSourcePanel(session,ticket)
        }
        fun editNode(id:String) {
            if(session.closing || navigationBusy || hostHooks?.requestFocus?.invoke()==false) return
            val node=session.document?.nodes?.firstOrNull {it.id==id} ?: return
            if(node.kind!=NodeKind.REFERENCE) {editing=id;return}
            if(!showSources(id)) return
            if(node.sources.size==1 && hostHooks!=null) {
                val source=node.sources.single()
                session.captureSourceNavigation(id,source.id,source.target)?.let(::openSource)
            }
        }
        val latestIme by rememberUpdatedState(imeBottom)
        val latestCameraReady by rememberUpdatedState(cameraReady)
        // Capture the key at composition: the AndroidView factory publishes `view` during the same apply,
        // before effects run, so reading it inside the effect would let the key change dispose the live View.
        val composedView=view
        DisposableEffect(composedView,presentationState,session) {
            onDispose {
                if(composedView!=null && latestCameraReady) presentationState.capture(session,composedView,latestIme)
            }
        }
        LaunchedEffect(view,graphEpoch,session.document?.id,imeBottom,geometry,textDraft?.token,textRestore?.token,presentationState.restoreVersion,
            presentationState.pendingBacklinkFocus,presentationState.pendingFileRelationFocus,session.selectedNodes,session.nodeSelectionMode,session.selectedRelation) {
            val graph=view ?: return@LaunchedEffect
            if(textDraft!=null || textRestore!=null) {
                // Text transactions own their pre-edit camera and their existing restoration path.
                textOwnedCamera=true;cameraReady=true;return@LaunchedEffect
            }
            if(imeBottom!=0 || graph.width<=0 || graph.height<=0) return@LaunchedEffect
            val size=Pair(graph.width,graph.height);val densityAtLayout=graph.resources.displayMetrics.density
            repeat(2) {withFrameNanos {}}
            if(size!=Pair(graph.width,graph.height) || densityAtLayout!=graph.resources.displayMetrics.density ||
                session.interactionEpoch!=graphEpoch || graph.document.id!=session.document?.id) return@LaunchedEffect
            if(!textOwnedCamera) presentationState.restore(session,graph)
            cameraReady=true
            presentationState.applyPendingBacklinkFocus(session,graph,imeBottom,cameraReady)
            presentationState.capture(session,graph)
        }
        var preparedText by remember(view,textDraft?.token) {mutableStateOf(false)}
        LaunchedEffect(view,textDraft?.token,geometry) {
            val draft=textDraft ?: return@LaunchedEffect
            val graph=view ?: return@LaunchedEffect
            if(!preparedText && graph.width>0 && graph.height>0) {
                preparedText=true
                graph.prepareTextEditing(draft.nodeId)
            }
        }
        LaunchedEffect(textRestore?.token,view,imeBottom,geometry) {
            val restore=textRestore ?: return@LaunchedEffect
            focus.clearFocus();keyboard?.hide()
            val graph=view ?: return@LaunchedEffect
            if(imeBottom!=0 || graph.width<=0 || graph.height<=0) return@LaunchedEffect
            val size=Pair(graph.width,graph.height)
            repeat(2) {withFrameNanos {}}
            if(size!=Pair(graph.width,graph.height)) return@LaunchedEffect
            graph.restoreViewport(restore.viewport,restore.width,restore.height,restore.density)
            session.completeTextRestore(restore.token)
        }
        BackHandler(enabled=hostHooks?.focused?.invoke()!=false) {
            if(navigationBusy) hostHooks?.cancel?.invoke()
            else if(sourceController.state!=null) sourceController.cancel(sourceController.state!!)
            else if(session.connectedDraft!=null) session.cancelConnectedNode(session.connectedDraft!!.token)
            else if(session.textDraft!=null) endText(false)
            else if(session.textRestore!=null) Unit
            else if(editing!=null) editing=null
            else if(connectingFrom!=null) view?.cancelConnection()
            else if(session.inkContact) {if(session.nodeSelectionMode) view?.cancelNodeSelectionGesture() else view?.inkInput?.cancel()}
            else if (!session.closing) close()
        }
        val palette = LocalPalette.current
        val enlarged=editing?.let { id -> session.document?.nodes?.any {it.id==id && it.kind==NodeKind.INK} } == true
        val paneFocused=hostHooks?.focused?.invoke()!=false
        val keyboardReady=cameraReady && editing==null && textDraft==null && textRestore==null &&
            session.connectedDraft==null && !navigationBusy && !session.closing && !session.replacingDocument
        DisposableEffect(view,paneFocused,keyboardReady,hostHooks?.keyboardFocusGeneration) {
            val graph=view
            if(graph==null || !paneFocused || !keyboardReady) onDispose {}
            else {
                val takeFocus=Runnable {
                    if(presentationState.visible && hostHooks?.focused?.invoke()!=false && graph===view &&
                        graph.isAttachedToWindow && graph.isEnabled && graph.width>0 && graph.height>0 &&
                        editing==null && session.textDraft==null && session.textRestore==null && session.connectedDraft==null &&
                        !session.closing && !session.replacingDocument) graph.requestFocus()
                }
                val listener=object:android.view.View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v:android.view.View) {graph.post(takeFocus)}
                    override fun onViewDetachedFromWindow(v:android.view.View) {graph.removeCallbacks(takeFocus)}
                }
                graph.addOnAttachStateChangeListener(listener)
                graph.post(takeFocus)
                onDispose {graph.removeCallbacks(takeFocus);graph.removeOnAttachStateChangeListener(listener)}
            }
        }
        Surface(color = palette.panel.toComposeColor(), modifier = Modifier.fillMaxSize()) {
          CompositionLocalProvider(LocalInkBusy provides (session.inkContact || enlarged || textRestore!=null || session.replacingDocument || navigationBusy)) {
          Box(Modifier.fillMaxSize().then(if(hostHooks?.embedded==true) Modifier
                else Modifier.windowInsetsPadding(WindowInsets.systemBars).imePadding())) {
            Column(Modifier.fillMaxSize()) {
                if(textDraft!=null) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=Space.s8)) {
                        MapButton(R.string.map_done) {endText(true)}
                        MapButton(R.string.cancel) {endText(false)}
                        Text(stringResource(R.string.map_edit),fontSize=TextSize.title,modifier=Modifier.align(Alignment.CenterVertically).padding(horizontal=Space.s12))
                        session.problemText()?.let {Text(stringResource(it),fontSize=TextSize.body,maxLines=1,modifier=Modifier.align(Alignment.CenterVertically))}
                    }
                } else {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.s8), verticalAlignment = Alignment.CenterVertically) {
                    MapButton(R.string.map_close, enabled = !session.closing) { close() }
                    hostHooks?.splitDocument?.let {split->
                        MapButton(R.string.map_split_open_beside,enabled=!session.closing && !session.hasUnconfirmedContent) {split()}
                    }
                    hostHooks?.returnToSourceMap?.let {back->MapButton(R.string.graph_return_parent_map,
                        enabled=!session.closing && !session.hasUnconfirmedContent) {back()}}
                    hostHooks?.returnToNetwork?.let {back->MapButton(R.string.network_return,
                        enabled=!session.closing && !session.hasUnconfirmedContent) {back()}}
                    Text(title.ifBlank { stringResource(R.string.map_title) }, fontSize = TextSize.title, modifier = Modifier.padding(horizontal = Space.s12))
                    Text(stringResource(when {
                        session.loading -> R.string.map_loading
                        session.loadFailed -> R.string.map_open_error
                        session.saving -> R.string.map_saving
                        session.saveFailed -> R.string.map_save_error
                        session.dirty -> R.string.map_unsaved
                        else -> R.string.map_saved
                    }), fontSize = TextSize.caption)
                    MapButton(R.string.save, enabled = session.document != null && !session.saving) { session.save() }
                    MapButton(R.string.map_save_copy, enabled = session.document != null && !session.saving) { saveCopy.launch("${title.ifBlank { context.getString(R.string.map_title) }}.xmap") }
                    MapButton(R.string.map_help) { help = true }
                }
                }
                if(navigationBusy || navigationFeedback!=null) Row(Modifier.fillMaxWidth().padding(horizontal=Space.s8),verticalAlignment=Alignment.CenterVertically) {
                    Text(stringResource(navigationFeedback ?: R.string.graph_source_opening),fontSize=TextSize.body,modifier=Modifier.weight(1f))
                    if(navigationBusy) CompositionLocalProvider(LocalInkBusy provides false) {MapButton(R.string.cancel) {hostHooks?.cancel?.invoke()}}
                }
                HorizontalDivider()
                val doc = session.document
                val node = doc?.nodes?.firstOrNull { it.id == session.selected }
                val relation=doc?.relations?.firstOrNull {it.id==session.selectedRelation}
                val nodeMode=session.nodeSelectionMode
                if (doc != null && textDraft==null) {
                    val actionCapture=session.captureNodes(doc,session.selectedNodes)
                    fun add(kind: NodeKind, sibling: Boolean) {
                        val parent = if (sibling) node?.parent else node?.id
                        if (parent != null) {
                            var added: String? = null
                            val accepted = session.operate {
                                val (next, id) = it.add(parent, kind, context.getString(if (kind == NodeKind.TEXT) R.string.map_topic else R.string.map_ink_node))
                                added = id; next
                            }
                            if (accepted) { session.selected = added; editing = added }
                        }
                    }
                    // One structure row: node-selection mode swaps the creation actions for alignment.
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=Space.s8)) {
                        MapButton(R.string.graph_node_selection,selected=nodeMode,enabled=!session.closing) {session.setNodeSelectionMode(!nodeMode)}
                        if(nodeMode) {
                            Text(stringResource(R.string.graph_selected_nodes,session.selectedNodes.size),fontSize=TextSize.body,modifier=Modifier.align(Alignment.CenterVertically))
                            for(alignment in MapAlignment.entries) MapButton(when(alignment) {
                                MapAlignment.LEFT->R.string.graph_align_left;MapAlignment.CENTER_X->R.string.graph_align_center_x
                                MapAlignment.RIGHT->R.string.graph_align_right;MapAlignment.TOP->R.string.graph_align_top
                                MapAlignment.CENTER_Y->R.string.graph_align_center_y;MapAlignment.BOTTOM->R.string.graph_align_bottom
                            },enabled=actionCapture!=null && doc.canAlignNodes(actionCapture.ids) && !session.closing) {
                                actionCapture?.let {session.alignNodes(it,alignment)}
                            }
                        } else {
                            MapButton(R.string.map_add_text, enabled = node != null && !session.closing) { add(NodeKind.TEXT, false) }
                            MapButton(R.string.map_add_ink, enabled = node != null && !session.closing) { add(NodeKind.INK, false) }
                            MapButton(R.string.map_add_sibling, enabled = node?.parent != null && !session.closing) { add(node?.kind ?: NodeKind.TEXT, true) }
                            MapButton(when {relation!=null->R.string.graph_edit_relation;node?.kind==NodeKind.INK->R.string.ink_enlarge;else->R.string.map_edit},
                                enabled = (node != null || relation!=null) && !session.closing) {
                                if(relation!=null) editExistingRelation(relation.id) else editing = node?.id
                            }
                            MapButton(R.string.ink_properties,enabled=node!=null){propertiesFor=node?.id}
                            MapButton(if (node?.let {session.isEffectivelyCollapsed(it.id)} == true) R.string.map_expand else R.string.map_collapse, enabled = node != null && doc.children(node.id).isNotEmpty()) {
                                node?.let {session.toggleCollapse(it.id)}
                            }
                        }
                        MapButton(R.string.delete, enabled = actionCapture!=null && actionCapture.ids.any {it!=doc.centralRoot?.id} && !session.closing) {deleting=actionCapture}
                        MapButton(R.string.undo, enabled = session.canUndo && !session.closing) { session.undo() }
                        MapButton(R.string.redo, enabled = session.canRedo && !session.closing) { session.redo() }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.s8)) {
                        MapButton(R.string.graph_free,enabled=!nodeMode) { addFree=true }
                        MapButton(R.string.graph_detach,enabled=!nodeMode && node?.parent!=null) { node?.let { n -> session.operate { it.detach(n.id) } } }
                        MapButton(R.string.graph_relations,enabled=!nodeMode) { relationToEdit=null;pendingRelation=null;graphTools=true }
                        MapButton(R.string.graph_connect_canvas,enabled=!nodeMode && node!=null,selected=connectingFrom!=null) {
                            if(connectingFrom!=null) view?.cancelConnection() else node?.let {view?.beginConnection(it.id)}
                        }
                        MapButton(R.string.graph_sources,enabled=cameraReady && !nodeMode && node!=null) { showSources(node?.id) }
                        MapButton(R.string.map_nodes,enabled=!nodeMode) { choosing = true; reparenting = false }
                        MapButton(R.string.map_change_parent, enabled = !nodeMode && node?.parent != null && !session.closing) { choosing = true; reparenting = true }
                        MapButton(R.string.map_layout, enabled = !nodeMode && !session.closing) { layoutMenu=true }
                        MapButton(R.string.map_fit) { view?.fit() }
                        MapButton(R.string.map_zoom_in) { view?.zoomBy(1.25f) }
                        MapButton(R.string.map_zoom_out) { view?.zoomBy(.8f) }
                        Text(node?.text.orEmpty().take(80), fontSize = TextSize.body,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.CenterVertically).padding(horizontal = Space.s12))
                    }
                }
                val problem = if(geometryChanged && !session.limitError) R.string.graph_geometry_changed else session.problemText()
                HorizontalDivider()
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                    when {
                        doc != null -> AndroidView(factory = { c -> MindMapView(c).also { view = it } }, modifier = Modifier.fillMaxSize().alpha(if(cameraReady) 1f else 0f),
                            onRelease = { released -> if(view===released) view=null }, update = { v ->
                            v.isEnabled=cameraReady && !(navigationBusy && !session.hasUnconfirmedContent) && !enlarged && textDraft==null && textRestore==null && session.connectedDraft==null && !session.closing && !session.replacingDocument
                            v.onViewportChanged={
                                viewRevision++
                                if(cameraReady) presentationState.capture(session,v,imeBottom)
                            }
                            v.onConnectionModeChanged={connectingFrom=it}
                            v.onConnectionTarget={from,to->relationToEdit=null;pendingRelation=MapRelation(from=from,to=to);graphTools=true}
                            v.onConnectionBlank={from,point->session.beginConnectedNode(from,point)}
                            v.editingText=textDraft?.nodeId
                            v.inkInput.mode=session.inkMode;v.inkInput.width=session.inkWidth
                            v.onInkContact={session.inkContact=it};v.onInkLimit=session::reportInkLimit;v.onInk={id,old,next ->
                                session.commitInk(id,old,next).also { accepted -> if(accepted) session.document?.let {v.document=it} }
                            }
                            v.documentEpoch=graphEpoch
                            v.documentRevision=session.contentRevision;v.nodeSelectionMode=nodeMode
                            v.expandedAncestors=session.navigationExpandedAncestors;v.visibilityRevision=session.visibilityRevision
                            v.onGraphContact={
                                geometryChanged=false
                                presentationState.cancelPendingBacklinkFocus()
                                if(v.isEnabled && hostHooks?.requestFocus?.invoke()!=false) v.requestFocus()
                            }
                            v.document = proposed ?: doc; v.dark = dark
                            if(session.selectedRelation!=null) v.selectedRelation=session.selectedRelation else v.setSelectedNodes(session.selectedNodes)
                            v.onCaptureNodes=session::captureNodes;v.onMoveNodes=session::moveNodes;v.onSelectNodes={capture,ids->session.selectNodes(capture,ids)}
                            v.contentDescription = context.getString(R.string.map_canvas_description)
                            v.onSelect = { session.selected = it }; v.onSources={showSources(it)}; v.onEdit = ::editNode
                            v.onChange = {next->geometryChanged=session.interactionEpoch!=graphEpoch || !session.commitNodeDrag(doc,next)}
                            v.onSelectRelation={session.selectRelation(it)};v.onEditRelation=::editExistingRelation
                        })
                        session.loading -> Text(stringResource(R.string.map_loading), modifier = Modifier.align(Alignment.Center), fontSize = TextSize.body)
                        else -> Text(stringResource(R.string.map_open_error), modifier = Modifier.align(Alignment.Center).padding(Space.s24), fontSize = TextSize.body)
                    }
                    if(textDraft!=null) view?.let {MapTextEditor(session,textDraft,it,dark,viewRevision)}
                    // In-place ink settings appear only for a selected ink node, over the graph so the
                    // native View keeps its size (and any stroke) when the selection changes.
                    if(!nodeMode && !enlarged && textDraft==null && connectingFrom==null) node?.takeIf {it.kind==NodeKind.INK}?.let {n->
                        Box(Modifier.align(Alignment.BottomCenter).padding(Space.s8)) {MapInkToolbar(session,n,floating=true)}
                    }
                    problem?.takeIf {textDraft==null}?.let { message -> Column(Modifier.align(Alignment.TopStart)) {
                        Text(stringResource(message),fontSize=TextSize.body,modifier=Modifier.fillMaxWidth().background(palette.panel.toComposeColor()).padding(Space.s12))
                        if(session.saveFailed && session.recoverySafe) MapButton(R.string.map_close_recovery) {
                            hostHooks?.closeRecoveryEntry?.invoke() ?: onClose()
                        }
                        if(session.conflict) {
                            MapButton(R.string.map_conflict_keep, enabled = !session.saving) { session.save(overwrite = true) }
                            MapButton(R.string.map_conflict_use_file, enabled = !session.saving) { discardingRecovery = true }
                        }
                    } }
                    if(connectingFrom!=null) Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(palette.panel.toComposeColor()).padding(horizontal=Space.s8),verticalAlignment=Alignment.CenterVertically) {
                        Text(stringResource(R.string.graph_connect_hint),fontSize=TextSize.body,modifier=Modifier.weight(1f))
                        MapButton(R.string.cancel) {view?.cancelConnection()}
                    }
                }
                if(textDraft==null) Text(stringResource(if(nodeMode) R.string.graph_node_selection_hint else R.string.map_hint), fontSize = TextSize.caption, modifier = Modifier.fillMaxWidth().padding(Space.s8))
            }
            // Enlarged ink covers the whole pane, structure rows included, while the graph View keeps its size.
            editing?.let { id -> session.document?.nodes?.firstOrNull {it.id==id && it.kind==NodeKind.INK}?.let { n ->
                MapInkEditor(session,n) {editing=null}
            } }
          }
          }
        }
        propertiesFor?.let { id -> session.document?.nodes?.firstOrNull { it.id == id }?.let { node ->
            MapNodeDialog(node, onDismiss = { editing = null;propertiesFor=null }, onConfirm = { edited ->
                session.editNodeProperties(id,edited).also { if (it) {editing = null;propertiesFor=null} }
            },previewAssets=session.document?.previewAssets.orEmpty())
        } }
        session.connectedDraft?.let {draft->ConnectedNodeDialog(session,draft,connectedPickerId!=null || session.connectedSourceRequest!=null) {
            if(connectedPickerId==null) session.beginConnectedSource(draft.token)?.let {request->
                connectedPickerId=request.id
                try {connectedSourcePicker.launch(arrayOf("application/octet-stream","application/zip"))}
                catch(_:Exception) {connectedPickerId=null;session.failConnectedSource(request.id)}
            }
        }}
        if(addFree) AlertDialog(onDismissRequest={addFree=false},title={Text(stringResource(R.string.graph_free),fontSize=TextSize.heading)},text={Column {
            for(k in NodeKind.entries) MapButton(when(k){NodeKind.TEXT->R.string.map_text_node;NodeKind.INK->R.string.map_ink_node;NodeKind.REFERENCE->R.string.graph_reference}) {
                var added: String?=null
                val accepted=session.operate { d -> val n=d.addFree(k,if(k==NodeKind.REFERENCE) "" else context.getString(R.string.map_topic),(d.visible().maxOfOrNull {it.bounds.right} ?: -100.0)+100,(d.centralRoot?.y ?: 0.0)+300+d.nodes.count { it.free }*100);added=n.second;n.first }
                if(accepted) { session.selected=added;addFree=false;if(k==NodeKind.REFERENCE) showSources(added) else editing=added }
            }
        }},confirmButton={MapButton(R.string.cancel){addFree=false}})
        if(layoutMenu) AlertDialog(onDismissRequest={layoutMenu=false},title={Text(stringResource(R.string.map_layout),fontSize=TextSize.heading)},text={Column {
            for(direction in MapDirection.entries) MapButton(when(direction){MapDirection.BOTH->R.string.graph_both;MapDirection.RIGHT->R.string.graph_right;MapDirection.DOWN->R.string.graph_vertical}) {
                session.document?.let { proposed=MapLayout.arrange(it.copy(direction=direction)) };layoutMenu=false
            }
        }},confirmButton={MapButton(R.string.cancel){layoutMenu=false}})
        proposed?.let { preview -> AlertDialog(onDismissRequest={proposed=null},title={Text(stringResource(R.string.graph_preview),fontSize=TextSize.heading)},
            text={Text(stringResource(R.string.graph_preview_notice),fontSize=TextSize.body)},
            confirmButton={MapButton(R.string.map_done){
                geometryChanged=session.interactionEpoch!=graphEpoch || !session.operate {latest->MapLayout.arrange(latest.copy(direction=preview.direction))}
                proposed=null
            }},dismissButton={MapButton(R.string.cancel){proposed=null}}) }
        if(graphTools) session.document?.let { current->
            key(graphEpoch) {
                GraphRelationsDialog(current,session.selected,{initial,next->
                    if(session.interactionEpoch!=graphEpoch) RelationEditResult.Conflict else session.changeRelation(initial,next).also {result->
                        if(result is RelationEditResult.Duplicate && !session.selectRelation(result.relation.id)) session.selectRelation(null)
                    }
                },pendingRelation,relationToEdit,onSelectExisting={id->
                    if(session.interactionEpoch==graphEpoch && !session.selectRelation(id)) session.selectRelation(null)
                }){graphTools=false;pendingRelation=null;relationToEdit=null}
            }
        }
        val pendingSourceFocus=presentationState.sourceFocus
        val currentSourceFocus=pendingSourceFocus?.takeIf {session.currentSourceNavigation(it.source)!=null}
        SideEffect {if(pendingSourceFocus!=null && currentSourceFocus==null) presentationState.consumeSourceFocus(pendingSourceFocus.token)}
        if(!session.hasUnconfirmedContent && !enlarged) presentationState.sourcesFor?.let { id -> session.sourceEditSnapshot(id)?.let { sourceEdit ->
            val editor=remember(presentationState,session,id,sourceEdit.epoch,sourceEdit.documentId) {presentationState.bindSourceEditor(session,sourceEdit)}
            GraphSourcesDialog(sourceEdit,editor, {
                if(pendingSourceRequest==null) session.beginExistingSource(id)?.let {request->
                    pendingSourceRequest=request
                    try {sourcePicker.launch(arrayOf("application/octet-stream","application/zip"))}
                    catch(_:Exception) {pendingSourceRequest=null;session.failExistingSource(request)}
                }
            },addEnabled=pendingSourceRequest==null && sourceController.state==null && !sourceTargets.hasPending && pendingTargetPickerId==null,
                open=if(hostHooks==null || !cameraReady) null else ::openSource,focus=currentSourceFocus,
                focusHandled=presentationState::consumeSourceFocus,feedback=navigationFeedback,
                targetEditsEnabled=pendingSourceRequest==null && connectedPickerId==null && sourceController.state==null && !sourceTargets.hasPending && pendingTargetPickerId==null) {
                listOfNotNull(pendingSourceRequest,activeExistingSource).distinct().forEach(session::cancelSourceRequest)
                // Keep the system launch occupied until ITS result consumes the old ticket.
                activeExistingSource=null;presentationState.closeSources();focus.clearFocus();keyboard?.hide()
            }
        } }
        sourceController.state?.let {GraphSourceChoiceDialog(it,sourceController)}
        sourceTargets.state?.let {shown->SourceTargetEditDialog(sourceTargets,shown,
            filePickerAvailable=pendingTargetPickerId==null && pendingSourceRequest==null && connectedPickerId==null) {slot->
                if(pendingTargetPickerId!=null || pendingSourceRequest!=null || connectedPickerId!=null) sourceTargets.receivePicker(slot,null)
                else targetPicker.launch(slot)
            }
        }
        if (discardingRecovery) AlertDialog(onDismissRequest = { discardingRecovery = false },
            title = { Text(stringResource(R.string.map_conflict_use_file), fontSize = TextSize.heading) },
            text = { Text(stringResource(R.string.map_conflict_use_file_confirm), fontSize = TextSize.body) },
            confirmButton = { MapButton(R.string.map_conflict_use_file) { session.useFileVersion(); discardingRecovery = false } },
            dismissButton = { MapButton(R.string.cancel) { discardingRecovery = false } })
        deleting?.let {captured->AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.delete), fontSize = TextSize.heading) },
            text = { Text(stringResource(R.string.graph_delete_nodes_confirm), fontSize = TextSize.body) },
            confirmButton = { MapButton(R.string.delete) { session.deleteNodes(captured);deleting = null } },
            dismissButton = { MapButton(R.string.cancel) { deleting = null } })}
        if (choosing) {
            val doc = session.document
            val id = session.selected
            val excluded = if (reparenting && doc != null && id != null) doc.descendants(id) else emptySet()
            AlertDialog(onDismissRequest = { choosing = false },
                title = { Text(stringResource(if (reparenting) R.string.map_change_parent else R.string.map_nodes), fontSize = TextSize.heading) },
                text = {
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = MindMapNodeListHeight)) {
                        val nodes = session.effectiveVisible().filter { it.id !in excluded }
                        items(nodes.size, key = { nodes[it].id }) { index ->
                            val n = nodes[index]
                            TextButton(onClick = {
                                if (reparenting && id != null) session.operate { it.reparent(id, n.id) }
                                else { session.selected = n.id; view?.centerOn(n.id) }
                                choosing = false
                            }, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)) {
                                Text(n.text.ifBlank { stringResource(if (n.kind == NodeKind.INK) R.string.map_ink_node else R.string.map_topic) },
                                    fontSize = TextSize.body, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                        }
                    }
                }, confirmButton = { MapButton(R.string.cancel) { choosing = false } })
        }
        if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text(stringResource(R.string.map_help), fontSize = TextSize.heading) },
            text = { Text(stringResource(R.string.map_help_text), fontSize = TextSize.body) },
            confirmButton = { MapButton(R.string.map_done) { help = false } })
      }
    }
}

@Composable
internal fun MapButton(label: Int, enabled: Boolean = true, selected: Boolean = false, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val fg = palette.text.toComposeColor(); val bg = palette.panel.toComposeColor()
    val highlighted=selected && com.xnotes.ui.LocalToolbarFocused.current
    val takeFocus=LocalMapActionFocus.current
    TextButton({if(takeFocus()) onClick()}, enabled = enabled && !LocalInkBusy.current, modifier = Modifier.heightIn(min = MinTouchTarget).widthIn(min = MinTouchTarget)
        .then(if (highlighted) Modifier.background(fg) else Modifier),
        colors = ButtonDefaults.textButtonColors(contentColor = if (highlighted) bg else fg)) {
        Text(stringResource(label), fontSize = TextSize.body)
    }
}
