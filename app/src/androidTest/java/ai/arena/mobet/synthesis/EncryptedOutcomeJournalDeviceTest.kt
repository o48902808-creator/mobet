package ai.arena.mobet.synthesis

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The selector-outcome journal round-tripping through the real Android Keystore.
 *
 * JVM tests cover the tally's arithmetic with a fake journal, but they cannot exercise AES-GCM,
 * keystore key generation or SharedPreferences — precisely the parts that decide whether learned
 * priors actually survive a restart on a device.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedOutcomeJournalDeviceTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val pkg = "com.example.device"
    private val selector = SelectorSpec("viewId", "com.example.device:id/next")

    @Before
    fun reset() {
        SelectorOutcomes.detach()
        SelectorOutcomes.clear()
        EncryptedOutcomeJournal(context).save("[]")
    }

    @After
    fun cleanUp() {
        EncryptedOutcomeJournal(context).save("[]")
        SelectorOutcomes.detach()
        SelectorOutcomes.clear()
    }

    @Test
    fun learnedPriorsSurviveAColdStart() {
        SelectorOutcomes.attach(EncryptedOutcomeJournal(context))
        repeat(4) { SelectorOutcomes.recordSuccess(pkg, selector) }
        SelectorOutcomes.flush()

        // Cold start: fresh journal instance, empty in-memory state.
        SelectorOutcomes.clear()
        SelectorOutcomes.attach(EncryptedOutcomeJournal(context))

        assertNotNull(SelectorOutcomes.outcomeOf(pkg, selector))
        assertTrue(SelectorOutcomes.adjustment(pkg, selector) > 0.0)
    }

    @Test
    fun whatLandsOnDiskIsCiphertext() {
        SelectorOutcomes.attach(EncryptedOutcomeJournal(context))
        SelectorOutcomes.recordSuccess(pkg, selector)
        SelectorOutcomes.recordSuccess(pkg, selector)
        SelectorOutcomes.flush()

        val raw = context.getSharedPreferences("secure_state_selector_outcomes_v1", android.content.Context.MODE_PRIVATE)
            .all.values.joinToString(" ")
        assertTrue("nothing was persisted", raw.isNotBlank())
        assertNotEquals("", raw)
        // The package and selector must not be readable in the stored payload.
        assertTrue("selector leaked in cleartext", !raw.contains("com.example.device"))
        assertTrue("selector leaked in cleartext", !raw.contains("id/next"))
    }

    @Test
    fun anEmptyJournalIsHarmless() {
        SelectorOutcomes.attach(EncryptedOutcomeJournal(context))
        assertTrue(SelectorOutcomes.adjustment(pkg, selector) == 0.0)
    }
}
