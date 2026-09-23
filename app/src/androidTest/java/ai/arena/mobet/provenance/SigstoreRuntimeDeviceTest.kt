package ai.arena.mobet.provenance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.sigstore.KeylessVerifier
import dev.sigstore.TrustedRootProvider
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SigstoreRuntimeDeviceTest {
    @Test fun pinnedTrustRootInitializesOfflineOnApi29() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "device-test-sigstore-root.json")
        context.assets.open(SigstoreProvenance.TRUST_ROOT_ASSET).use { input ->
            root.outputStream().use(input::copyTo)
        }
        val verifier = KeylessVerifier.builder()
            .trustedRootProvider(TrustedRootProvider.from(root.toPath()))
            .build()
        assertTrue(root.length() > 1_000)
        assertTrue(verifier != null)
    }
}
