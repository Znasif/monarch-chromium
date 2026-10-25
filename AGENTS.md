# AGENTS.md — Monarch Tactile Browser Hackathon Guidelines & Hardware Knowledge

This file captures our hackathon engineering patterns, Gemini API rules, and critical Monarch hardware & KeySoft SDK conventions so no agent or subagent repeats solved problems.

---

## 1. Hackathon Workflow Patterns & Generalization Rules

1. **Zero Site-Specific Presets (`No Code Names a Site`)**:
   - Never hardcode hostnames or site-specific branches in browser or tactile viewer code.
   - All DOM normalization, image-to-button promotion, scanned-page OCR/figure extraction, SVG parsing, and `Data Navigator` spatial graph generation must operate on standard web signals (`<img>`, `<svg>`, `<canvas>`, `<figure>`, viewport bitmaps) so they generalize across books (`archive.org`, Google Books, Gutenberg, OpenStax), Wikipedia, newspapers, and webcomics.
2. **Closed Verification Loops**:
   - Every module must be verified against a concrete pass/fail signal (`./gradlew testDebugUnitTest`, `./gradlew assembleDebug`, and Python verification scripts for the Gemini 4 Argon spatial compiler).
3. **Uncluttered 96×40 Tactile Rendering**:
   - Keep raised-pin fill density **≤ 20%–25%** on the 96×35 tactile canvas at any zoom level.
   - Never use raw Floyd-Steinberg pixel dithering on photographs; always use **Gemini 4 Argon semantic component decomposition + component-masked adaptive 1-pin contours**.
4. **Gemini 4 Argon & Managed Agent (`antigravity-preview-09-2026`) Rules**:
   - Use `gemini-4-argon` (and `antigravity-preview-09-2026` with `agent_config={"type": "antigravity", "model": "gemini-4-argon"}`, `environment="remote"`) for orchestration, spatial component decomposition, and verification.
   - Use `gemini-3.8-flash` for fast secondary summarization if needed.
   - Never use deprecated `gemini-2.5-*`, `gemini-2.0-*`, or `gemini-1.5-*` models or legacy `generateContent` SDK calls.

---

## 2. Built-in Monarch KeySoft SDK & Hardware Rules (Must Follow)

1. **`SelfBraillingWidget` Full-Screen & Focus Rule**:
   - When in **Text View**, `SelfBraillingWidget.visibility = View.GONE` and `WebView.visibility = View.VISIBLE` so KeySoft's native screen reader renders multi-line Braille and handles single-letter HTML quick-nav (`B` for image buttons, `H` for headings, `L` for links).
   - When switching to **Tactile Image Viewer Mode**, hide the `WebView` (`View.GONE`), show `SelfBraillingWidget` (`View.VISIBLE`, `alpha = 1f`), and post `ACTION_ACCESSIBILITY_FOCUS` + `TYPE_VIEW_ACCESSIBILITY_FOCUSED` so `SelfBraillingManager.displayDots()` owns the physical pins and point-and-click gestures (`onDoubleTapAtBraillePosition`) are delivered.
2. **Registering Hardware Keys via `res/raw/commands.xml`**:
   - Call `WriteCommandsXmlFileToInternalMemoryStorageExecutor(this).execute()` in `Application.onCreate()`.
   - Define `<context viewIdResourceName="SelfBraillingWidget">` substitutions in `res/raw/commands.xml` for `ZoomIn` (`168`), `ZoomOut` (`169`), `RightDpad_Up/Down/Left/Right` (`59|19..22`), `Up/Down/Left/Right` (`19..22`), `PanningUp/Down` (`92/93`), `Dots8` (`66`), and Braille chord commands (always ending in `9` for Space in `<key>Dots...9</key>`).
3. **Braille Caption Region (`Rows 36–39`) & `Translator` Delay**:
   - Wait at least **1,000 ms** after `Translator.bindService()` before calling `translator.translate(query)`, and sanitize `\n`, `\r`, `\t` before translating.
   - Reserve row `35` as a blank separator row (`dots[35].fill(0)`) and rows `36..39` for the 4-row `BrailleCaptionRegion` (3-column cell stride = 32 Braille cells across 96 columns).
4. **First-Finger Ownership (`FingerOwnership`) & Haptic Feedback**:
   - Poll `manager.touchedPinCoordinates` at 25ms intervals (40Hz).
   - Acquire tracking only when a single finger lands from an empty state (`previousCount == 0 && touches.size == 1`).
   - Require 3 consecutive empty polls before declaring lift, and clamp step distance (`squaredDistance <= 36` when two hands seen, `<= 400` for one hand).
   - Subtract `3` from `pointX` in `onSingleTapAtBraillePosition` / `onDoubleTapAtBraillePosition` to compensate for fingertip width.
   - Trigger `VibrationUtils.startVibration(context, 30)` when the tracked finger enters a new component, and `SoundUtils.errorBeep(context)` when hitting a viewport or `Data Navigator` boundary.

---

## 3. Managed-Agent Linux Sandbox Build & Proxy Rules (Pattern 5)

1. **Preserving the Sandbox `mitmproxy` CA Certificate When Installing OpenJDK**:
   - Each step starts in a fresh Ubuntu 24.04 container without Java pre-installed. The sandbox routes outbound HTTPS traffic through `http://10.100.0.1:8081` and uses a `mitmproxy` CA certificate for `github.com` and `api.github.com`.
   - **Critical quirk**: Running `apt-get install -y --no-install-recommends openjdk-21-jdk-headless` triggers `update-ca-certificates`, which regenerates `/etc/ssl/certs/ca-certificates.crt` from `/usr/local/share/ca-certificates/` and **wipes out** the pre-baked `mitmproxy` cert unless it is saved to `/usr/local/share/ca-certificates/mitmproxy.crt` first. Without this, `git push`, `curl https://api.github.com`, and Gradle wrapper downloads (which redirect from `services.gradle.org` to `github.com`) will fail with TLS certificate errors.
   - Always run this exact sequence before using Java/Gradle in a fresh sandbox:
     ```bash
     openssl s_client -proxy 10.100.0.1:8081 -connect github.com:443 -showcerts </dev/null 2>/dev/null \
       | awk '/BEGIN CERTIFICATE/{i++} i==2{print}' > /usr/local/share/ca-certificates/mitmproxy.crt
     apt-get update -qq && apt-get install -y --no-install-recommends openjdk-21-jdk-headless
     update-ca-certificates
     export JAVA_TOOL_OPTIONS="-Dhttp.proxyHost=10.100.0.1 -Dhttp.proxyPort=8081 -Dhttps.proxyHost=10.100.0.1 -Dhttps.proxyPort=8081"
     ```
2. **JVM Proxy Flags (`JAVA_TOOL_OPTIONS`)**:
   - Java's `HttpURLConnection` (used by `gradle-wrapper.jar`) ignores `HTTP_PROXY` / `HTTPS_PROXY` environment variables. Always export `JAVA_TOOL_OPTIONS="-Dhttp.proxyHost=10.100.0.1 -Dhttp.proxyPort=8081 -Dhttps.proxyHost=10.100.0.1 -Dhttps.proxyPort=8081"` (or configure `systemProp.http.proxyHost` / `systemProp.https.proxyHost` in `gradle.properties`).
