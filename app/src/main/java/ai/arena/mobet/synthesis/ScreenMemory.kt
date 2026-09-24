package ai.arena.mobet.synthesis

import ai.arena.mobet.automation.InspectedElement
import ai.arena.mobet.automation.ScreenSnapshot

/** A screen this device has actually shown, retained so later clauses can be grounded in it. */
data class RememberedScreen(
    val packageName: String,
    /** Structural identity of the screen: the sorted selector set, hashed to a short id. */
    val screenId: String,
    val elements: List<InspectedElement>,
    val observedAt: Long
)

/** Read side of the screen graph, so generation can be tested without Android state. */
interface ScreenMemory {
    fun screens(packageName: String): List<RememberedScreen>

    /**
     * Screens observed to follow [fromScreenId], most frequently observed first.
     *
     * This is what makes multi-screen planning a *route* rather than a guess: after grounding
     * "open Settings", the next clause should be grounded in the screen that opening Settings
     * actually led to, not in whichever screen happens to be most recent.
     */
    fun successors(packageName: String, fromScreenId: String): List<RememberedScreen> = emptyList()

    companion object {
        /** Memory is ignored entirely. */
        val EMPTY: ScreenMemory = object : ScreenMemory {
            override fun screens(packageName: String): List<RememberedScreen> = emptyList()
        }
    }
}

/**
 * Bounded, in-memory graph of screens observed during this session.
 *
 * Why this exists: grounding could previously only see the screen the user was standing on, so a
 * perfectly ordinary goal — "open Settings then tap Wi-Fi then tap Add network" — was unplannable
 * unless every target happened to be visible at once. Most real routes span screens.
 *
 * Retention is deliberately modest: a bounded number of screens per package, evicted
 * least-recently-seen, held only for the life of the process and never written to disk. Screens
 * are keyed by structural identity, so revisiting one refreshes rather than duplicates it.
 */
object SessionScreenMemory : ScreenMemory {

    /** Screens kept per package. */
    const val MAX_SCREENS_PER_PACKAGE = 12

    /** Packages tracked at once. */
    private const val MAX_PACKAGES = 8

    /** Transitions retained per package; the least-observed edge is evicted first. */
    const val MAX_EDGES_PER_PACKAGE = 64

    /** Directed edges `from → to` with an observation count, bounded per package. */
    private val edges = object : LinkedHashMap<String, MutableMap<Pair<String, String>, Int>>(8, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, MutableMap<Pair<String, String>, Int>>
        ): Boolean = size > MAX_PACKAGES
    }

    /** Screen the last `remember` call recorded, per package, so the next one closes an edge. */
    private val lastSeen = mutableMapOf<String, String>()

    private val byPackage = object : LinkedHashMap<String, LinkedHashMap<String, RememberedScreen>>(8, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, LinkedHashMap<String, RememberedScreen>>
        ): Boolean = size > MAX_PACKAGES
    }

    @Synchronized
    fun remember(snapshot: ScreenSnapshot) {
        if (snapshot.packageName.isBlank() || snapshot.elements.isEmpty()) return
        val screens = byPackage.getOrPut(snapshot.packageName) {
            object : LinkedHashMap<String, RememberedScreen>(8, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, RememberedScreen>
                ): Boolean = size > MAX_SCREENS_PER_PACKAGE
            }
        }
        val id = identify(snapshot.elements)
        // A transition is only recorded between two *different* consecutive screens of the same
        // app: re-observing the same screen is not a route, and self-edges would swamp the graph.
        lastSeen[snapshot.packageName]?.let { previous ->
            if (previous != id) {
                val packageEdges = edges.getOrPut(snapshot.packageName) { mutableMapOf() }
                val key = previous to id
                packageEdges[key] = (packageEdges[key] ?: 0) + 1
                if (packageEdges.size > MAX_EDGES_PER_PACKAGE) {
                    packageEdges.entries.minByOrNull { it.value }?.let { packageEdges.remove(it.key) }
                }
            }
        }
        lastSeen[snapshot.packageName] = id
        screens[id] = RememberedScreen(
            packageName = snapshot.packageName,
            screenId = id,
            elements = snapshot.elements,
            observedAt = snapshot.capturedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
        )
    }

    /** Most recently observed first, so the newest evidence about an app wins. */
    @Synchronized
    override fun screens(packageName: String): List<RememberedScreen> =
        byPackage[packageName]?.values?.sortedByDescending { it.observedAt }.orEmpty()

    @Synchronized
    override fun successors(packageName: String, fromScreenId: String): List<RememberedScreen> {
        val known = byPackage[packageName] ?: return emptyList()
        return edges[packageName].orEmpty()
            .filterKeys { it.first == fromScreenId }
            .entries
            .sortedByDescending { it.value }
            .mapNotNull { known[it.key.second] }
    }

    @Synchronized
    fun clear() {
        byPackage.clear()
        edges.clear()
        lastSeen.clear()
    }

    @Synchronized
    fun summary(): String {
        if (byPackage.isEmpty()) return "No screens remembered this session"
        return byPackage.entries.joinToString(" · ") { (pkg, screens) ->
            "${pkg.substringAfterLast('.')}: ${screens.size} screen(s)"
        }
    }

    /**
     * Structural identity: the sorted selector set. Two visits to the same screen with different
     * dynamic text still hash alike, while a different screen of the same app does not.
     */
    fun identify(elements: List<InspectedElement>): String {
        val canonical = elements.map { it.selector }.sorted().joinToString("|")
        var hash = 1_125_899_906_842_597L
        canonical.forEach { character -> hash = hash * 31 + character.code }
        return java.lang.Long.toHexString(hash)
    }
}
