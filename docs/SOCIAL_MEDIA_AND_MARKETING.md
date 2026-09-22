# Social media and digital marketing with Mobet

A suitability analysis: what "manage and run social media / digital marketing" decomposes
into, and for each part whether Mobet's architecture supports it, fights it, or forbids it.
Ground rule from the safety notice and threat model: automate only apps and accounts you are
authorized to use.

## The short answer

Mobet is a UI-first, on-device, human-in-the-loop automation tool with deliberately no
network access. Professional social-media management is API-first (Meta Graph API, Buffer,
Hootsuite) precisely because that is the supported, ToS-safe, scalable channel. Mobet cannot
be that product — by construction, not by missing features. What it *can* be is the
operator-assistant layer for the tail those tools leave: preparing multi-app posts on your own
device from reusable templates, driving apps that expose no API, at human pace, with every
publish reviewed and confirmed on-device, and a private audit trail of what ran.

## Use cases decomposed

### Works well, as designed

- **Cross-post the same announcement across your installed apps.** One workflow per app
  (open → create post → fill caption from `{{variables}}` → reach the review screen → stop).
  You review the composed post in the real app, then allow the final step. Variables turn one
  template into per-campaign variants without touching the workflow.
- **A posting calendar, honestly.** Run reminders fire at your chosen times, open Mobet with
  the workflow loaded, and you run it (see "not scheduled posting" below).
- **WhatsApp Business / quick-reply style flows.** Bounded, label-driven, stable UIs —
  exactly the terrain selectors were built for.
- **Analytics touchpoints.** Open Meta Business Suite / TikTok Studio to a fixed screen and
  `capture` it (consented screenshot; stays in private storage), optionally OCR a figure
  readout for your records. Reading is far safer terrain than writing.
- **Agency accountability.** Every run appends to the hash-chained, encrypted audit ledger.
  For an agency or VA arrangement "show me exactly what was run on my accounts" is answered
  with verifiable local evidence — a real differentiator.
- **Template distribution.** Exported workflow bundles share the flow across devices/brands;
  secrets and credentials are never exported, so the bundle is safe to hand over.

### Works, but expect the safety gates to engage (that is the point)

- **Publishing anything.** `post`, `publish`, `send`, `share` are consequential language to
  the risk engine — a publish tap scores ELEVATED or CRITICAL, so the step requires an
  immediately preceding `confirm`, and CRITICAL steps require typing `APPROVE`. Compose the
  draft with Mobet; verify it with your eyes; then allow the publish button. This drafts →
  review → approve pattern mirrors how marketing approval flows *should* work.
- **Boosting / ad spend.** "Pay", "checkout", "budget", card fields hit the financial and
  credential signals → CRITICAL, typed confirmation, every time. Sane, but it means ad
  managers (Meta/Google Ads) are confirm-per-action tools here, not automation targets.
- **Attaching media.** Gallery pickers are label-poor visual grids; selecting "the third
  photo" needs `tappoint` with percent coordinates — workable, but the most fragile step in
  any posting workflow (device-resolution and layout dependent, and visual fallbacks need
  `allowVisualFallbacks: true`). Prefer apps whose pickers expose list semantics.

### Poor fit — the architecture fights it

- **Feed-driven engagement (like/comment/reply at volume).** Feeds are unbounded, mutable,
  per-user personalized surfaces; selector grounding and screen fingerprints are built on
  structural stability that feeds do not offer. Even where a flow half-works, bulk engagement
  is the pattern platform anti-automation systems are tuned to catch — and un-API automation
  of engagement violates most major platforms' terms, risking account restriction. Mobet's
  authorization notice exists exactly here.
- **Fleet/agency operations across many accounts or devices.** No network, no sync by design;
  "manage 40 client accounts" is an API product's job (Buffer/own dashboards), not a
  per-device UI automation's.
- **CAPTCHA / 2FA / OTP interruption.** Credential and verification screens stop the flow by
  design (credential signal + confirmation gates). That is the tool refusing to become a
  credential-bypass tool.
- **Anything scheduled and unattended.** Reminders-only is an invariant
  (see [SCHEDULED_RUNS_REVIEW.md](SCHEDULED_RUNS_REVIEW.md)); a "post at 9am without me"
  feature is gated behind explicit maintainer sign-off — and remains ToS-fragile regardless
  of what Mobet does.

## Practical guidance

1. Author one workflow per app per campaign with `{{variables}}` for the copy; dry-run and
   validate policy first; keep `allowVisualFallbacks` off unless a media-attach step truly
   needs it, and put an explicit `confirm` before every publish/pay step (the validator will
   force the issue anyway — write it yourself so the plan reads deliberately).
2. Expect selector drift: social apps A/B test their UI weekly. Regularly inspect the target
   (`Inspect screen`), keep self-healing enabled on LOW-risk steps only, and treat a healed
   match above LOW risk as a signal to re-record, not to trust.
3. Check the platform's terms for every account you automate on: UI-driving your own
   logged-in app for your own content at human pace is a narrower claim than "automation" in
   most ToS documents, but the account risk is yours to evaluate — the audit ledger exists to
   document exactly what happened if you need to.
4. If the goal is *scale* — scheduling queues, analytics ingestion, cross-account reporting —
  use the official APIs or an API-first tool; Mobet's no-network invariant makes it the wrong
  hammer, on purpose.

## Where this sits in the docs

- Capabilities/gaps/integration register: [CAPABILITY_MAP.md](CAPABILITY_MAP.md)
- Run invariants and residual risk: [THREAT_MODEL.md](THREAT_MODEL.md)
- Scheduled-execution gate: [SCHEDULED_RUNS_REVIEW.md](SCHEDULED_RUNS_REVIEW.md)
