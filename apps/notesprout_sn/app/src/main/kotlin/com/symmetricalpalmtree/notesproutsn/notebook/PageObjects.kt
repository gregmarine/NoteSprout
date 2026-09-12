package com.symmetricalpalmtree.notesproutsn.notebook

import android.graphics.drawable.Drawable

/**
 * The visible page's **texts, shapes and sticky icons** (arc 28 / H1): three working copies and
 * the three [com.symmetricalpalmtree.gpaper.core.render.ContentRenderer]s that paint them, held
 * together in one small object so [NotebookActivity] grows by three fields instead of twelve.
 *
 * It is a view-model, not a store: nothing here writes a row. Every mutation is the in-memory half
 * of an act whose rows [NotebookActivity] writes through `session.texts` / `session.shapes` /
 * `session.stickies`, which is where the rest of the screen's store calls already are. The one
 * suspending method is [load], and it only reads.
 *
 * **Draw order (D8) is the caller's**, not this class's: the three renderers are registered by the
 * screen, in z-order, interleaved with the two older ones —
 * headings · [textRenderer] · [shapeRenderer] · links · [stickyRenderer], then the engine's ink.
 * Exposing them rather than registering them here is what keeps that one ordering readable in one
 * place.
 *
 * **The repaint is the caller's too.** Every method here re-hands the working copies to the
 * renderers ([sync]) and stops: `paper.notifyContentChanged()` belongs to whoever knows whether
 * this act owns a frame — the eraser tool asks for one, a scribble must not, and a page load's
 * `loadStrokes` is already the frame that paints them (the K1 ordering: content **before**
 * `loadStrokes`).
 *
 * Working copies are `LinkedHashMap`s, like `liveHeadings` / `liveLinks`: z-order is the row
 * order, and it has to survive a mutation.
 */
class PageObjects(
    private val density: Float,
    private val scaledDensity: Float,
    /** `R.drawable.ic_sticker_2`, already `mutate()`d by the caller — one Drawable per thread. */
    stickyIcon: Drawable,
) {

    val textRenderer = TextRenderer(density, scaledDensity)
    val shapeRenderer = ShapeRenderer(density)
    val stickyRenderer = StickyRenderer(stickyIcon)

    /** The visible page's text objects, in z-order. */
    var texts: MutableMap<String, PageText> = linkedMapOf()
        private set

    /** The visible page's shapes, in z-order. */
    var shapes: MutableMap<String, PageShape> = linkedMapOf()
        private set

    /** The visible page's sticky **icons**, in z-order — content is never held here (D2). */
    var stickies: MutableMap<String, PageSticky> = linkedMapOf()
        private set

    /** One page's three lists, read off IO and applied on Main by [set]. */
    class Loaded(
        val texts: List<PageText>,
        val shapes: List<PageShape>,
        val stickies: List<PageSticky>,
    )

    /** What a set of selected/erased ids turned out to hold — the split every act starts with. */
    class Split(
        val textIds: List<String>,
        val shapeIds: List<String>,
        val stickyIds: List<String>,
    ) {
        val isEmpty: Boolean get() = textIds.isEmpty() && shapeIds.isEmpty() && stickyIds.isEmpty()
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    /** Whether [id] is one of ours — the "does this selection hold a new kind" question. */
    fun holds(id: String): Boolean = id in texts || id in shapes || id in stickies

    /** Split [contentIds] into the three kinds, keeping each kind's working-copy order. */
    fun split(contentIds: Collection<String>): Split = Split(
        contentIds.filter { it in texts },
        contentIds.filter { it in shapes },
        contentIds.filter { it in stickies },
    )

    fun textsIn(ids: Collection<String>): List<PageText> = ids.mapNotNull { texts[it] }

    fun shapesIn(ids: Collection<String>): List<PageShape> = ids.mapNotNull { shapes[it] }

    /** The **icons** for [ids] — no content. A caller that needs an undo snapshot reads the
     *  children itself (`StickyStore.withContent`), because that read suspends. */
    fun stickiesIn(ids: Collection<String>): List<PageSticky> = ids.mapNotNull { stickies[it] }

    /**
     * Read [pageId]'s three kinds — **off Main, in the load phase**, beside the strokes, headings
     * and links. Texts come back re-measured for this device ([remeasured]); shapes and stickies
     * are what the rows say.
     */
    suspend fun load(session: NotebookSession, pageId: String, pageWidth: Int): Loaded = Loaded(
        texts = remeasured(session.texts.loadPage(pageId), pageWidth),
        shapes = session.shapes.loadPage(pageId),
        stickies = session.stickies.loadPage(pageId),
    )

    // ── Mutations (Main) ─────────────────────────────────────────────────────

    /** A page load: replace all three working copies and hand them to the renderers. */
    fun set(loaded: Loaded) {
        texts = loaded.texts.associateByTo(linkedMapOf()) { it.id }
        shapes = loaded.shapes.associateByTo(linkedMapOf()) { it.id }
        stickies = loaded.stickies.associateByTo(linkedMapOf()) { it.id }
        sync()
    }

    /** Add or replace objects (a paste, an insert, an unwrapped link's children coming back). */
    fun put(
        texts: List<PageText> = emptyList(),
        shapes: List<PageShape> = emptyList(),
        stickies: List<PageSticky> = emptyList(),
    ) {
        texts.forEach { this.texts[it.id] = it }
        shapes.forEach { this.shapes[it.id] = it }
        // Icons only on the page: a snapshot arriving with content keeps it in the row, not here.
        stickies.forEach { this.stickies[it.id] = it.copy(strokes = emptyList()) }
        sync()
    }

    /** Take objects off the page (a delete, an erase, a wrap — the row half is the caller's). */
    fun drop(split: Split) {
        split.textIds.forEach { texts.remove(it) }
        split.shapeIds.forEach { shapes.remove(it) }
        split.stickyIds.forEach { stickies.remove(it) }
        sync()
    }

    /**
     * The in-memory half of a finished drag: shift each named object by the same delta. A sticky's
     * icon moves and its content does not — [PageSticky.translated] is where that lives.
     */
    fun translate(split: Split, dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        split.textIds.forEach { id -> texts[id]?.let { texts[id] = it.translated(dx, dy) } }
        split.shapeIds.forEach { id -> shapes[id]?.let { shapes[id] = it.translated(dx, dy) } }
        split.stickyIds.forEach { id -> stickies[id]?.let { stickies[id] = it.translated(dx, dy) } }
        sync()
    }

    /** Re-hand the working copies to the renderers. The repaint is the caller's (see the KDoc). */
    fun sync() {
        textRenderer.texts = texts.values.toList()
        shapeRenderer.shapes = shapes.values.toList()
        stickyRenderer.stickies = stickies.values.toList()
    }

    // ── Measuring ────────────────────────────────────────────────────────────

    /**
     * `remeasureForDevice` for text objects (the heading precedent, N3): a box measured on the
     * writing device and stored in page px disagrees with what *this* device lays out after a font
     * scale change or on the other Supernote, which would leave stale hit and selection bounds
     * around text that draws at a different size. Position is authored (kept), size is derived
     * (recomputed) — **in memory only**; the row is corrected whenever the text is next written.
     */
    fun remeasured(list: List<PageText>, pageWidth: Int): List<PageText> {
        if (list.isEmpty()) return list
        return list.map { t ->
            val (w, h) = measure(t.text, t.x, pageWidth)
            if (w == t.width && h == t.height) t else t.copy(width = w, height = h)
        }
    }

    /** The one sizing call: og's available width is `pageWidth − x`, never the page width. */
    fun measure(text: String, x: Float, pageWidth: Int): Pair<Float, Float> =
        TextRenderer.measure(text, (pageWidth - x).toInt(), density, scaledDensity)
}
