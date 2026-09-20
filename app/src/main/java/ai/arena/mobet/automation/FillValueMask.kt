package ai.arena.mobet.automation

/**
 * Display masking for `fill` values on any read-only surface (dry-run reports, step cards).
 *
 * Placeholders like `{{secret:pin}}` and `{{var:label}}` are shown verbatim: they are
 * *references*, not values — the stored secret is never read here, and hiding the name would
 * leave the user unable to tell which credential a step uses. Everything else is a literal a
 * user typed — quite possibly a password pasted straight into JSON — so it renders as a fixed
 * run of bullets instead. Length is coarsened to eight so the shape of a literal leaks as
 * little as possible while still looking intentional rather than broken.
 *
 * One shared implementation so the report and the builder cannot drift: a field masked in one
 * place and shown in the other would be the worst of both worlds.
 */
object FillValueMask {
    fun mask(value: String): String =
        if (value.contains("{{")) value else "•".repeat(value.length.coerceAtMost(MAX_MASK_LENGTH))

    private const val MAX_MASK_LENGTH = 8
}
