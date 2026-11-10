package com.xnotes.mindmap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.xnotes.R
import com.xnotes.ui.theme.Space
import com.xnotes.ui.theme.TextSize

@Composable
internal fun FileRelationEvidenceRow(row:MapFileRelationRow,enabled:Boolean,onOpen:()->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(Space.s4)) {
        Text(stringResource(R.string.file_relation_from_canvas,row.location.title),fontSize=TextSize.body,fontWeight=FontWeight.SemiBold)
        Text(fileRelationTitle(row),fontSize=TextSize.body)
        Text(stringResource(fileRelationKindText(row.relation.kind)),fontSize=TextSize.caption)
        if(row.relation.label.isNotBlank()) Text(row.relation.label,fontSize=TextSize.body)
        if(row.origin==BacklinkOrigin.LIVE) Text(stringResource(R.string.graph_backlinks_origin_live),fontSize=TextSize.caption)
        if(row.origin==BacklinkOrigin.RECOVERY) Text(stringResource(R.string.graph_backlinks_origin_recovery),fontSize=TextSize.caption)
        (row.blocked ?: if(row.conflict) BacklinkProblem.RECOVERY_CONFLICT else null)?.let {
            Text(stringResource(networkProblemText(it)),fontSize=TextSize.caption)
        }
        MapButton(R.string.file_relation_open,enabled=enabled && row.blocked==null && !row.conflict,onClick=onOpen)
        HorizontalDivider()
    }
}

@Composable
internal fun fileRelationTitle(row:MapFileRelationRow):String = stringResource(
    if(row.relation.directed) R.string.file_relation_direction else R.string.file_relation_undirected,
    row.fromTitle.ifBlank {stringResource(R.string.network_file)},row.toTitle.ifBlank {stringResource(R.string.network_file)})

internal fun fileRelationKindText(kind:RelationKind)=when(kind) {
    RelationKind.RELATED->R.string.graph_related
    RelationKind.REFERENCED_FROM->R.string.graph_referenced
    RelationKind.BELONGS_TO->R.string.graph_belongs
    RelationKind.DEPENDS_ON->R.string.graph_depends
}
