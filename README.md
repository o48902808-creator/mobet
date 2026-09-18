# Mobet

Mobet is an Android-first, on-device mobile automation prototype. It uses Android's Accessibility API to locate controls and run explicit JSON workflows that the phone owner starts.

## Current MVP

- Launch an installed application by package name
- Locate controls by visible text, accessibility description, or resource ID
- Tap controls (including a clickable parent)
- Focus and fill text fields
- Scroll, wait, delay, Back, and Home actions
- Per-step polling, bounded retries, timeout, status, Stop, and fail-fast handling
- Variables and conditional `ifText` / `unlessText` execution
- AES-GCM secrets protected by a non-exportable Android Keystore key
- Blocking, user-visible confirmation steps for consequential actions
- Editable workflow JSON stored locally on the device
- Named local workflow library with Save and Load
- Installed-app picker for recording
- Tap and scroll recorder that walks up parent controls to find stable selectors
- Last-screen visual inspector with roles, bounds, uniqueness counts, and selector confidence
- Duplicate-selector warnings and copyable selector diagnostics
- Persistent rolling execution diagnostics (last 100 status events)
- Display-relative tap/swipe fallback with coordinates clamped to safe screen bounds
- Consent-gated Android 11+ screenshots stored only in private app storage
- In-app screenshot viewer and deletion control
- Bundled on-device Latin-script OCR with line bounds and confidence heuristics
- Consent-gated `ocrWait` and `visualTap` workflow actions
- Visual text matching with normalized whitespace and exact-match preference
- Included harmless Android Settings demonstration

No data is sent off-device. This prototype does not include a network permission.

## Build and install

Prerequisites: Android Studio Ladybug or newer, Android SDK 35, and JDK 17.

1. Open this directory in Android Studio.
2. Let Gradle sync and install SDK 35 if prompted.
3. Run the `app` configuration on an Android 8.0+ physical device or emulator.
4. In Mobet, tap **Open accessibility settings**, select **Mobet automation**, and enable it.
5. Return to Mobet and run the included Settings demo.

> The repository intentionally does not commit the generated Gradle wrapper JAR. Android Studio can sync the project directly; you can also run `gradle wrapper` with Gradle 8.9 installed.

## Workflow format

```json
{
  "name": "Profile update",
  "package": "com.example.app",
  "steps": [
    { "action": "wait", "text": "Profile", "timeoutMs": 5000 },
    { "action": "tap", "text": "Profile" },
    { "action": "fill", "viewId": "com.example.app:id/name", "value": "Ama Mensah" },
    { "action": "scroll", "viewId": "com.example.app:id/form" },
    { "action": "tap", "description": "Save" },
    { "action": "delay", "delayMs": 1000 },
    { "action": "back" }
  ]
}
```

Selectors can use `text`, `viewId`, and/or `description`. Multiple selector properties are combined. Supported actions are `wait`, `tap`, `fill`, `scroll`, `delay`, `confirm`, `tapPoint`, `swipe`, `capture`, `ocrWait`, `visualTap`, `back`, and `home`. Timeouts are capped at 60 seconds, delays at 10 seconds, retries at 10, and gestures at 5 seconds.

Define non-sensitive values in the root `variables` object and reference them as `{{var:name}}`. Save sensitive values from **Secrets** and reference them as `{{secret:name}}`; plaintext secret values are never stored in workflow JSON. Add `ifText` or `unlessText` to conditionally execute a step based on the current screen. Place a `confirm` step immediately before any consequential action:

```json
{
  "action": "fill",
  "viewId": "com.example.app:id/password",
  "value": "{{secret:account_password}}",
  "retries": 2
},
{
  "action": "confirm",
  "message": "Submit this form to Example App?"
},
{
  "action": "tap",
  "text": "Submit",
  "unlessText": "Already submitted"
}
```

## Safety and platform notes

- Android displays a strong warning when enabling accessibility access because this capability can read and operate screen content. Only enable services you trust.
- Mobet runs only a workflow explicitly started in its foreground UI and offers Stop. Use blocking `confirm` steps before sensitive actions; remote triggers and AI planning remain disabled.
- `QUERY_ALL_PACKAGES` supports user-authored package targets in sideloaded builds. Google Play restricts this permission; a Play-distributed edition should use declared package visibility or a user-selected app model.
- Secure fields, CAPTCHAs, biometrics, protected windows, and apps with poor accessibility metadata may not be automatable and should not be bypassed.
- iOS does not permit an ordinary installed app to control arbitrary other apps; Android is the initial target.

## Recording a workflow

Tap **Record**, choose a target app, and perform the taps and scrolls you want Mobet to reproduce. Return to Mobet using Android's app switcher and tap **Stop / import recording**. Recorded selectors are appended to the editor. For privacy, typed text is never recorded; add `fill` steps and values manually. Use **Save** to keep a named workflow locally.

After visiting a target app, select **Inspect last app screen** to review actionable elements exposed by Android. Mobet scores selectors by stability and uniqueness, warns when multiple nodes match, and lets you copy selector details. **Diagnostics** shows the rolling local execution log.

## Visual fallbacks and captures

Use semantic selectors whenever possible. For inaccessible canvases only, `tapPoint` accepts `xPercent` and `yPercent`; `swipe` also requires `endXPercent` and `endYPercent`. Values are display-relative and clamped to 2–98% of screen bounds. Both actions require an immediately preceding approved `confirm` step.

The Android 11+ `capture` action also requires an immediately preceding confirmation. PNG files remain in private app storage with no sharing or network upload. Use **Captures** to inspect, OCR, or delete the latest image.

`ocrWait` verifies that specified `text` is visually present. `visualTap` finds that text and taps its recognized center. Both are Android 11+ fallbacks, run through a bundled on-device OCR model, and require an immediately preceding confirmation. Accessibility selectors remain preferred because OCR can misread stylized, low-contrast, or non-Latin text.

```json
{ "action": "confirm", "message": "Use OCR to locate Continue?" },
{ "action": "visualTap", "text": "Continue" }
```

## Next milestones

1. Constrained AI planning with package/action policies
3. Automated Android tests, signed APK pipeline, and device compatibility suite
4. Performance, compatibility, and accessibility hardening across real devices
