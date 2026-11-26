package com.xnotes.explorer

/**
 * How the in-app explorer orders entries. The chosen key sorts within each group (folders first,
 * then files); [ExplorerView.descending] flips the direction.
 */
enum class ExplorerSortKey(val id: String) {
    NAME("name"),
    MODIFIED("modified"),
    CREATED("created"),
    SIZE("size");

    companion object {
        fun fromId(id: String): ExplorerSortKey = entries.firstOrNull { it.id == id } ?: MODIFIED
    }
}

/** The explorer's ways of laying out a folder. */
enum class ExplorerLayout(val id: String) {
    GRID("grid"),
    GALLERY("gallery"),
    LIST("list"),
    COLUMNS("columns"),
    TIMELINE("timeline");

    companion object {
        fun fromId(id: String?): ExplorerLayout? = entries.firstOrNull { it.id == id }
    }
}

/** How big the explorer draws its tiles; each step is fewer, larger columns. */
enum class TileSize(val id: String) {
    S("s"),
    M("m"),
    L("l"),
    XL("xl");

    companion object {
        fun fromId(id: String?): TileSize = entries.firstOrNull { it.id == id } ?: M
    }
}

/** A tile's thumbnail: the top of the first page, cropped square, or the whole first page. */
enum class ThumbShape(val id: String) {
    TOP("top"),
    PAGE("page");

    companion object {
        fun fromId(id: String?): ThumbShape = entries.firstOrNull { it.id == id } ?: TOP
    }
}

/** What the explorer puts its items under a heading by. */
enum class GroupBy(val id: String) {
    NONE("none"),
    DATE("date"),
    KIND("kind"),
    COLOUR("colour");

    companion object {
        fun fromId(id: String?): GroupBy = entries.firstOrNull { it.id == id } ?: DATE
    }
}

/** Where folders go among the files: in their own row above them, sorted in with them, or left out. */
enum class FolderPlacement(val id: String) {
    TOP("top"),
    MIXED("mixed"),
    HIDDEN("hidden");

    companion object {
        fun fromId(id: String?): FolderPlacement = entries.firstOrNull { it.id == id } ?: TOP
    }
}

/** Everything the View options popover sets for a folder. Loading is forgiving: anything missing takes its default. */
data class ExplorerView(
    val layout: ExplorerLayout = ExplorerLayout.GRID,
    val tileSize: TileSize = TileSize.M,
    val thumb: ThumbShape = ThumbShape.TOP,
    val showKind: Boolean = true,
    val showPages: Boolean = true,
    val showTime: Boolean = true,
    val showSize: Boolean = false,
    val showColour: Boolean = true,
    val groupBy: GroupBy = GroupBy.DATE,
    val sortKey: ExplorerSortKey = ExplorerSortKey.MODIFIED,
    val descending: Boolean = true,
    val folders: FolderPlacement = FolderPlacement.TOP,
    /** List rows without thumbnails, so more fit on screen. */
    val compactRows: Boolean = false,
    /** The Timeline places files on the day they were created, or else the day they were last saved. */
    val timelineByCreated: Boolean = true,
) {
    companion object
}
