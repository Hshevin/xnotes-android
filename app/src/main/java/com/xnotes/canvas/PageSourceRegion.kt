package com.xnotes.canvas

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page

/** Fixed page-local geometry on actual runtime owners, never an ordinal or selected-object identity. */
internal class PageSourceRegion(val document:Document,val page:Page,val region:Rect,private val valid:()->Boolean={true}) {
    fun current()=valid() && document.pages.count {it===page}==1
}
