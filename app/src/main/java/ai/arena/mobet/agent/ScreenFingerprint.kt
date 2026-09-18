package ai.arena.mobet.agent

import ai.arena.mobet.automation.ScreenSnapshot
import java.security.MessageDigest

/**
 * Stable, order-insensitive screen identity used by the world model and the runner's loop guard.
 * The fingerprint hashes the target package plus the sorted set of stable selectors and roles, so
 * cosmetic reordering or transient text does not create a new identity.
 */
object ScreenFingerprint {
    fun of(snapshot: ScreenSnapshot): String {
        val signature = buildString {
            append(snapshot.packageName)
            snapshot.elements.map { "${it.selector}#${it.role}" }.sorted().take(48)
                .forEach { append('|').append(it) }
        }
        return sha256(signature).substring(0, 16)
    }

    /** Jaccard similarity over selector sets; 1.0 means structurally identical screens. */
    fun similarity(a: ScreenSnapshot, b: ScreenSnapshot): Double {
        val left = a.elements.map { it.selector }.toSet()
        val right = b.elements.map { it.selector }.toSet()
        if (left.isEmpty() && right.isEmpty()) return if (a.packageName == b.packageName) 1.0 else 0.0
        if (left.isEmpty() || right.isEmpty()) return 0.0
        return left.intersect(right).size.toDouble() / left.union(right).size
    }

    fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
