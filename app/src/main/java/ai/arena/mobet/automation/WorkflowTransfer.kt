package ai.arena.mobet.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Backup and transfer for the local workflow library.
 *
 * Mobet sets `allowBackup="false"`, so an uninstall — including the reinstall required when an
 * APK signing key changes — destroys every saved workflow. This is the only supported way to
 * get them off the device and back again.
 *
 * Security posture: an export bundle contains workflow JSON only. Secret *values* live in
 * [ai.arena.mobet.security.SecretStore] behind an Android Keystore key and are never read here;
 * a workflow that references `{{secret:name}}` exports the reference, not the secret. Sharing
 * therefore cannot exfiltrate credentials, and an imported workflow is inert until its secrets
 * are re-entered on the new device.
 */
object WorkflowTransfer {

    const val FORMAT = "mobet.workflow-bundle"
    const val VERSION = 1
    private const val LIBRARY = "library"

    /** Directory the FileProvider is scoped to. Nothing else is shareable. */
    private fun exportDir(context: Context): File =
        File(context.filesDir, "exports").apply { mkdirs() }

    // ── Export ───────────────────────────────────────────────────────────────

    /**
     * Serializes saved workflows into a portable bundle.
     *
     * Each entry is stored as parsed-and-reserialized JSON so a corrupt library row cannot
     * produce a bundle that fails to import later.
     */
    fun exportBundle(context: Context, names: Collection<String>? = null): String {
        val library = context.getSharedPreferences(LIBRARY, Context.MODE_PRIVATE)
        val selected = names?.toSet() ?: library.all.keys
        val workflows = JSONArray()
        var skipped = 0
        selected.sorted().forEach { name ->
            val source = library.getString(name, null) ?: return@forEach
            val parsed = runCatching { JSONObject(source) }.getOrNull()
            if (parsed == null) {
                skipped++
                return@forEach
            }
            workflows.put(JSONObject().put("name", name).put("workflow", parsed))
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exportedAt", System.currentTimeMillis())
            .put("count", workflows.length())
            .apply { if (skipped > 0) put("skippedUnparseable", skipped) }
            .put("workflows", workflows)
            .toString(2)
    }

    /** Writes a bundle into the shareable staging directory and returns a content:// URI. */
    fun writeShareable(context: Context, contents: String, fileName: String): Uri {
        val dir = exportDir(context)
        // Keep staging clean so stale exports are not left readable by a previously granted URI.
        dir.listFiles()?.forEach(File::delete)
        val file = File(dir, sanitizeFileName(fileName))
        file.writeText(contents)
        return FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
    }

    fun shareIntent(uri: Uri, subject: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    fun sanitizeFileName(raw: String): String {
        val cleaned = raw.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_', '.')
        val base = cleaned.ifBlank { "mobet-workflows" }.take(60)
        return if (base.endsWith(".json", ignoreCase = true)) base else "$base.json"
    }

    // ── Import ───────────────────────────────────────────────────────────────

    data class ImportedWorkflow(val name: String, val source: String, val valid: Boolean, val detail: String)

    data class ImportResult(val workflows: List<ImportedWorkflow>, val bundleVersion: Int) {
        val validCount: Int get() = workflows.count(ImportedWorkflow::valid)
    }

    /**
     * Parses a bundle, or a single bare workflow, without writing anything.
     *
     * Every entry is run through [Workflow.parse] so malformed or hostile content is surfaced
     * before the user commits it. Nothing is trusted purely because it arrived in a file.
     */
    fun parseBundle(source: String): Result<ImportResult> = runCatching {
        val root = JSONObject(source)
        // A bare exported workflow is accepted as a one-entry bundle for convenience.
        if (!root.has("workflows")) {
            val name = root.optString("name").takeIf { it.isNotBlank() } ?: "Imported workflow"
            return@runCatching ImportResult(listOf(validate(name, root.toString(2))), VERSION)
        }
        val version = root.optInt("version", 1)
        require(version <= VERSION) {
            "Bundle version $version is newer than this build supports (max $VERSION)"
        }
        val array = root.getJSONArray("workflows")
        require(array.length() > 0) { "Bundle contains no workflows" }
        require(array.length() <= MAX_ENTRIES) { "Bundle exceeds $MAX_ENTRIES workflows" }
        val entries = buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val body = item.optJSONObject("workflow") ?: continue
                val name = item.optString("name").takeIf { it.isNotBlank() }
                    ?: body.optString("name").takeIf { it.isNotBlank() }
                    ?: "Imported ${i + 1}"
                add(validate(name, body.toString(2)))
            }
        }
        require(entries.isNotEmpty()) { "Bundle contains no readable workflows" }
        ImportResult(entries, version)
    }

    /** Parses one workflow, recording why it failed rather than throwing the whole import away. */
    private fun validate(name: String, source: String): ImportedWorkflow = try {
        val flow = Workflow.parse(source)
        ImportedWorkflow(
            name = name,
            source = source,
            valid = true,
            detail = "${flow.steps.size} step${if (flow.steps.size == 1) "" else "s"} · " +
                (flow.packageName ?: "no target package")
        )
    } catch (error: Exception) {
        ImportedWorkflow(name, source, false, error.message ?: "Unparseable workflow")
    }

    /**
     * Commits validated workflows to the library.
     *
     * Existing names are suffixed rather than overwritten, so importing can never silently
     * destroy a workflow already on the device.
     */
    fun commit(context: Context, workflows: List<ImportedWorkflow>): Int {
        val library = context.getSharedPreferences(LIBRARY, Context.MODE_PRIVATE)
        val editor = library.edit()
        // Names already taken. Seeded from the library, then extended as the batch is staged:
        // editor writes are not visible to library.contains() until apply(), so a bundle
        // containing two workflows with the same name would otherwise assign both the same
        // key and silently keep only the last one while reporting that both were imported.
        val taken = library.all.keys.toMutableSet()
        var written = 0
        workflows.filter(ImportedWorkflow::valid).forEach { entry ->
            var name = entry.name
            var suffix = 2
            while (!taken.add(name)) {
                name = "${entry.name} ($suffix)"
                suffix++
            }
            editor.putString(name, entry.source)
            written++
        }
        // commit() rather than apply(): the caller reports "Imported N" immediately, and an
        // import the user was told succeeded must be on disk before that claim is made.
        editor.commit()
        return written
    }

    /**
     * Reads a user-picked file, refusing anything over [MAX_BYTES].
     *
     * The cap is enforced *while* reading rather than after. A content:// URI can be backed by
     * an arbitrarily large — or endless — provider stream, so reading it fully and then checking
     * the size would let a hostile or simply wrong pick exhaust memory before the check ran.
     */
    fun readUri(context: Context, uri: Uri): Result<String> = runCatching {
        context.contentResolver.openInputStream(uri).use { stream ->
            requireNotNull(stream) { "Could not open the selected file" }
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            while (true) {
                val read = stream.read(chunk)
                if (read <= 0) break
                require(buffer.size() + read <= MAX_BYTES) {
                    "File is larger than ${MAX_BYTES / 1024} KB"
                }
                buffer.write(chunk, 0, read)
            }
            buffer.toString(Charsets.UTF_8.name())
        }
    }

    private const val MAX_ENTRIES = 200
    private const val MAX_BYTES = 2 * 1024 * 1024
}
