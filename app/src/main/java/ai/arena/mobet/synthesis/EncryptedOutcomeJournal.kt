package ai.arena.mobet.synthesis

import ai.arena.mobet.security.EncryptedStateStore
import android.content.Context

/**
 * Android storage for [SelectorOutcomes], using the same AES-GCM/Keystore container as agent
 * memory and the audit ledger.
 *
 * What is persisted is deliberately narrow: a package name, a selector identity, two decayed
 * weights and a timestamp. No screen text, entered value, secret or screenshot is involved — the
 * selector string is authored-or-inspected metadata the plan itself already contains in clear.
 * Encryption is nonetheless applied because selector identities describe which apps and controls
 * the phone owner automates, which is behavioural information.
 */
class EncryptedOutcomeJournal(context: Context) : SelectorOutcomes.OutcomeJournal {

    private val store = EncryptedStateStore(context.applicationContext, NAMESPACE)

    override fun load(): String? = store.read()

    override fun save(value: String) {
        store.write(value)
    }

    companion object {
        private const val NAMESPACE = "selector_outcomes_v1"

        /**
         * Binds the process-wide tally to encrypted storage exactly once.
         *
         * Called when the accessibility service connects, which is the earliest point at which a
         * run could record anything.
         */
        @Volatile
        private var attached = false

        @Synchronized
        fun attachOnce(context: Context) {
            if (attached) return
            SelectorOutcomes.attach(EncryptedOutcomeJournal(context))
            attached = true
        }
    }
}
