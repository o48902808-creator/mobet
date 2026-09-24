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
    fun clear() = byPackage.clear()

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
