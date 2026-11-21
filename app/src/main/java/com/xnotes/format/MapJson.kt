package com.xnotes.format

import com.xnotes.core.model.Stroke
import com.xnotes.map.MapModel

/**
 * The `map.json` entry of a canvas bundle that holds mind maps (phase 2 of
 * docs/movinkpad/mind-map-v2-design-2026-11-21.md).
 *
 * ## Where it goes
 *
 * [CanvasCodec.write] writes this entry only when the document's [MapModel.state] is not empty. It is
 * deflated, written after the assets and the identity entry and before `manifest.json`, which stays
 * the last entry. Such a canvas's manifest says `"format": "xcanvas-map"`
 * ([CanvasCodec.FORMAT_WITH_MAP]) instead of `"xcanvas"`. Released readers (release 98 and earlier)
 * accept only `"xcanvas"`, so they refuse a canvas with maps rather than open it, drop the maps and
 * save over them. A canvas whose maps were all deleted is written as a plain `"xcanvas"` again with
 * no `map.json`.
 *
 * [CanvasCodec.read] accepts both formats. A `"xcanvas-map"` manifest without a `map.json`, a
 * `map.json` it cannot read, a newer [VERSION], a state that fails `MapInvariants`, or a root on a
 * layer the canvas does not have all throw [XCanvasFormatException] and leave nothing behind (temp
 * files created by the read are deleted, as for any failed read). Nothing is repaired or guessed: a
 * file that cannot be read exactly is refused and left untouched.
 *
 * ## Schema (version 1)
 *
 * ```
 * {
 *   "format": "xnotes-map",
 *   "version": 1,
 *   "roots": ["<node id>", ...],                      // MapState.roots order
 *   "nodes": [                                        // tree order: roots in order, depth-first, siblings in order
 *     {
 *       "id": "<node id>",
 *       "title": "...",                               // omitted when empty
 *       "style": {
 *         "shape": "rounded" | "ellipse" | "underline",
 *         "border": [r, g, b, a], "fill": [r, g, b, a] | null, "text": [r, g, b, a],
 *         "borderWidth": 2.0, "textSize": 18.0, "bold": false
 *       },
 *       "ink": { "w": 160.0, "h": 80.0, "strokes": [ <stroke> ... ] },   // omitted when NodeInk.NONE
 *       "sources": [ { "id": "...", "kind": "note_page" | "pdf_page" | "canvas",
 *                      "document": "<uuid>", "page": "<page id>" (omitted when null), "label": "..." } ],  // omitted when empty
 *       "children": ["<node id>", ...],               // omitted when none
 *       "origin": [x, y], "layout": "right" | "balanced" | "org" | "indent",   // roots only
 *       "layer": "<layer id>",                        // roots only, omitted for the default layer
 *       "offset": [dx, dy],                           // children only, omitted when none
 *       "collapsed": true                             // omitted when not collapsed (only pruned collapsed nodes are written)
 *     }
 *   ],
 *   "relations": [ { "id": "...", "from": "<node id>", "to": "<node id>",
 *                    "label": "..." (omitted when empty), "arrow": "none" | "forward" | "backward" | "both",
 *                    "color": [r, g, b, a] } ]
 * }
 * ```
 *
 * `<stroke>` is exactly the JSON [CanvasCodec] writes for a stroke item on the canvas, minus any
 * layer field, and is read back by the same parser, so node ink keeps every raw sample bit for bit
 * and is never re-encoded. Parent links are derived from the `children` lists. Unknown fields at any
 * level are ignored, so later additive fields need no version bump. Omitted optional fields take the
 * defaults the comments above imply (a missing `style` is [com.xnotes.map.NodeStyle.TOPIC], a missing
 * `relations` is none). An unknown enum value is a format error (it would need a version bump).
 *
 * ## Storage pipelines
 *
 * Every place outside [CanvasCodec] that checks a manifest's `format` against `"xcanvas"` (bundle
 * reference info, copy plans, protected canvas preparation, migration, recovery, working sessions)
 * must use [CanvasCodec.isCanvasFormat] and must carry the `map.json` entry through byte for byte,
 * exactly as it carries the other canvas entries. Each such pipeline gets a test with a canvas that
 * has maps.
 */
internal object MapJson {
    const val ENTRY = "map.json"
    const val FORMAT = "xnotes-map"
    const val VERSION = 1

    /** Write [model]'s state and pruned collapsed set. [writeStroke] writes one stroke the canvas way. */
    fun write(j: JsonWrite, model: MapModel, writeStroke: (JsonWrite, Stroke) -> Unit): Unit =
        TODO("phase 2: gpt-6.2-astra")

    /** Read a model back. [readStroke] parses one stroke the canvas way. Throws [XCanvasFormatException] on any problem listed above. */
    fun read(p: JsonPull, readStroke: (JsonPull) -> Stroke): MapModel = TODO("phase 2: gpt-6.2-astra")
}
