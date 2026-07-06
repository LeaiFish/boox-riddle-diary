# CLAUDE.md — Development Notes

Engineering notes for the Riddle Diary app (BOOX / Onyx e-ink, developed and
tested on a **BOOX Note X2**, Android 11 / API 30, arm64). Read this before
touching the ink/refresh pipeline — most of it is hard-won device-specific
behavior that is not in the Onyx SDK docs.

## Module layout

```
app/src/main/java/com/billtt/riddle/
├── MainActivity.kt      # Full-screen entry; gestures (long-press = settings); settings dialog; pen attach timing
├── DiaryController.kt    # State machine (write→absorb→await→reveal→linger→fade); TouchHelper wiring; animation driver
├── DiaryView.kt          # Page rendering: live ink, banded absorb cache, reply reveal; page PNG capture
├── Oracle.kt             # Backend interface + OracleFactory + shared persona prompts
├── AnthropicOracle.kt    # Anthropic backend (official Java SDK, Claude vision)
├── OpenAiOracle.kt       # OpenAI backend (Chat Completions; any OpenAI-compatible endpoint via base URL)
├── ReplyTypesetter.kt    # Reply layout: wrap, center, CJK-per-char / Western-per-word tokenization
├── Stroke.kt             # Stroke data model
├── EInk.kt               # EpdController wrapper (DU4 fast refresh / GC full refresh)
└── Prefs.kt              # Provider / key / model persistence
```

## The pen-input story (most important)

The single biggest device-specific finding: **on the Note X2, the Onyx pen
callbacks (`RawInputCallback.onBeginRawDrawing` etc.) only fire in
`FEATURE_APP_TOUCH_RENDER` mode.** The default SurfaceFlinger hardware-draw mode
(plain `TouchHelper.create(view, callback)`) — and a `SurfaceView` host — both
leave the callbacks silent under this firmware (you see `RawInputReader: Empty
region detected when mapping` and `nativeRawReader` starts, but no callbacks).
The system Notes app can hardware-draw ink because it uses a system-level path
that is not reachable through the public `onyxsdk-pen` API.

Consequences baked into the current design:

- **`TouchHelper.create(view, TouchHelper.FEATURE_APP_TOUCH_RENDER, callback)`** —
  this is the only mode that delivers pen points here. Do not "simplify" it back
  to the default create overload or a SurfaceView host; ink stops working.
- **No hardware live ink.** In app-render mode Onyx does not draw the ink itself.
  `EpdController.lineTo/quadTo` were also tried and did **not** paint on this
  device. So live ink during writing is drawn in **software**: `DiaryView`
  accumulates the in-progress stroke and, throttled (`LIVE_THROTTLE_MS`), does a
  **local** `invalidate(rect)` over just the new segment's bounding box. This is
  slightly behind the pen (e-ink software limit) but it is the only "ink appears
  as you write" path available.
- **Attach timing:** attach `TouchHelper` only after the window has focus
  (`onWindowFocusChanged`), so the view's on-screen position is final. Attaching
  before focus contributes to the empty-region problem.

## Refresh / animation pipeline

E-ink refresh is slow (high-quality GU is ~300ms), so naïve per-frame gradients
stutter. The pipeline:

- **DU4 fast refresh** as the view's default update mode during animation
  (`EInk.beginAnimation` → `EpdController.setViewDefaultUpdateMode(view, DU4)`,
  ~150ms). Refresh a frame with a plain `view.invalidate()` (triggers `onDraw`);
  do **not** use `EpdController.postInvalidate` — it refreshes the ink layer
  without triggering `onDraw`, so nothing you drew appears.
- **Ink levels quantized to 5 steps** (`quantizeAlpha`) to match DU4 and avoid
  meaningless sub-step redraws.
- **Change-gated frames:** `runStagedFade` scans the timeline with a fine sample
  step (`SAMPLE_MS`) but only issues a real refresh when some element crosses a
  quantization step. Dead frames are skipped; every refresh is a visible jump.
- **GC full refresh** once at the end of a cycle to clear ghosting.

### Absorb animation (offscreen banded cache)

Redrawing hundreds of stroke segments per frame is what made absorption stutter.
Instead, `prepareAbsorb()` splits strokes **in write order** into `ABSORB_BANDS`
bands, renders each band into a small bitmap (bounding-box sized), and the fade
staggers the bands. Each frame then draws only a few bitmaps. This keeps the
"absorbed head-to-tail" ordering while making the redraw cost trivial. Reply
reveal/fade uses `drawText` directly (few elements, already cheap).

Tuning knobs live in `DiaryController.companion` (`FRAME_MS`, `SAMPLE_MS`,
`FADE_MS`, `ABSORB_BAND_FADE_MS`, `ABSORB_BAND_STAGGER_MS`, `REVEAL_WORD_MS`,
`lingerMillisFor`) and `DiaryView.ABSORB_BANDS`.

## Backends

`OracleFactory.create(prefs)` returns an `Oracle` for the selected provider, or
`null` if that provider's key is unset. Both backends send the page PNG plus the
shared persona/instruction from `OraclePrompts`. The OpenAI backend uses raw
OkHttp + `org.json` (no OpenAI SDK) so it works against any OpenAI-compatible
endpoint via a configurable base URL. The request intentionally sets **no**
token-limit parameter (avoids the `max_tokens` vs `max_completion_tokens`
incompatibility across models/gateways); reply length is bounded by the prompt.

## Build

Needs Android Studio, or JDK 17 + Android SDK 34 (`compileSdk 34`, `minSdk 28`).

- **Onyx SDK** comes from the official Maven repo
  `http://repo.boox.com/repository/maven-public/` (declared in `settings.gradle`
  with `allowInsecureProtocol`). Versions: `onyxsdk-pen:1.5.4`,
  `onyxsdk-device:1.3.5`. (The demo's older `1.4.11`/`1.2.29` also work for input
  but `1.5.4` was used during device debugging.)
- **Jetifier is required** (`android.enableJetifier=true`): `onyxsdk-device:1.3.5`
  pulls in the legacy Android Support Library, which collides with AndroidX
  without it.
- **jniLibs conflict:** `onyxsdk-pen` and its `mmkv` dependency both ship
  `libc++_shared.so`; `packagingOptions.jniLibs.pickFirsts` resolves it.

```bash
./gradlew assembleDebug   # or Run from Android Studio
```

`local.properties` (`sdk.dir=…`) is generated by Android Studio; for a
command-line build point it at your own SDK.

## Install & debug on the device

- **BOOX auto-freeze:** newly installed apps are frozen by the launcher's
  optimization (EAC). After `adb install`, run `adb shell pm enable
  com.billtt.riddle` before `am start`, or the activity launch fails with
  "Activity … does not exist".
- **Logs:** the controller logs under tag `RiddleDiary` (attach state, pen
  begin/end). `adb logcat -s RiddleDiary`.
- **USB debugging** must be enabled on the device (Settings → About → tap build
  number; then enable USB debugging and authorize the host).

## Notes / possible future work

- Erase is whole-stroke deletion, not pixel-level.
- The reply is font-rendered reveal, not stroke-level handwriting animation.
- UI strings (`res/values/strings.xml`) are Chinese (the device user's language);
  everything else — code comments and docs — is English.
- Truly hardware-latency live ink would need a lower-level Onyx path (e.g.
  `onyxsdk-scribble`) or system-level access beyond `onyxsdk-pen`.
