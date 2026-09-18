package ai.arena.mobet.agent

import ai.arena.mobet.automation.InspectedElement
import ai.arena.mobet.automation.ScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenFingerprintTest {
    private fun element(label: String, selector: String) =
        InspectedElement(label, "Button", selector, 1, 90, "0,0–10,10")

    @Test
    fun fingerprintIsOrderInsensitive() {
        val a = ScreenSnapshot("com.app", 0, listOf(element("A", "text: A"), element("B", "text: B")))
        val b = ScreenSnapshot("com.app", 99, listOf(element("B", "text: B"), element("A", "text: A")))
        assertEquals(ScreenFingerprint.of(a), ScreenFingerprint.of(b))
    }

    @Test
    fun differentPackagesDiffer() {
        val a = ScreenSnapshot("com.app", 0, listOf(element("A", "text: A")))
        val b = ScreenSnapshot("com.other", 0, listOf(element("A", "text: A")))
        assertNotEquals(ScreenFingerprint.of(a), ScreenFingerprint.of(b))
    }

    @Test
    fun similarityDetectsSharedStructure() {
        val a = ScreenSnapshot("com.app", 0, listOf(element("A", "text: A"), element("B", "text: B")))
        val b = ScreenSnapshot("com.app", 0, listOf(element("A", "text: A"), element("C", "text: C")))
        val similarity = ScreenFingerprint.similarity(a, b)
        assertTrue(similarity > 0.3 && similarity < 0.4)
    }

    @Test
    fun sha256IsStable() {
        assertEquals(ScreenFingerprint.sha256("mobet"), ScreenFingerprint.sha256("mobet"))
        assertEquals(64, ScreenFingerprint.sha256("mobet").length)
    }
}
