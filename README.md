# Riddle Diary for BOOX — hardware-ink fork

**English** | [中文](README.zh-CN.md)

Tom Riddle's diary from *Harry Potter and the Chamber of Secrets*, running on
BOOX e-ink tablets: write with the pen, the page drinks your ink, and a moment
later the diary writes back in a flowing hand.

A fork of [billtt/boox-riddle-diary](https://github.com/billtt/boox-riddle-diary)
(itself inspired by [MaximeRivest/riddle](https://github.com/MaximeRivest/riddle)
for the reMarkable), extended and tested on a **BOOX Tab 10C (T10C, Android 12,
firmware 4.2)**.

**Demo video**: [on Xiaohongshu](http://xhslink.com/o/ATQCemksS4M)

## The key discovery: native-latency ink on new firmware

The Onyx SDK's hardware pen pipeline appears dead on recent firmware: the
upstream author on a Note X2 (Android 11) and this fork on a Tab 10C
(Android 12) both hit the same symptom — `RawInputReader: Empty region
detected when mapping!!!!!` in logcat, with pen callbacks staying silent.
Upstream therefore implemented software-rendered live ink (necessarily a
little behind the pen, an e-ink refresh limitation) and documented the
symptom carefully in the dev notes — that record is what made the root-cause
diagnosis in this fork possible.

A full logcat capture on the Tab 10C pinned down the root cause: the SDK
relies on the `VMRuntime.setHiddenApiExemptions` reflection trick to unlock
Onyx's hidden framework classes (`android.onyx.handwriting.PenConfig` and
friends), and the device log shows that call being refused
(`blocked, core-platform-api, reflection, denied`), followed by
`ClassNotFoundException` for those classes and the failed region mapping.
This matches Android's hidden-API enforcement, tightened since Android 11;
community success stories on older devices (the Android 9/10 era) are also
consistent with this mechanism (not verified on old hardware by this fork).

The fix, verified before/after on the Tab 10C, is one adb command:

```bash
adb shell settings put global hidden_api_policy 1
```

With the policy relaxed, the default `TouchHelper.create()` hardware pipeline
works (verified on this device: real-time ink plus normal callbacks). This
fork ships with hardware ink enabled (`DiaryController.HW_PEN_RENDER`), plus
a minimal diagnostic activity you can drive over adb if writing ever breaks:

```bash
adb shell am start -n com.billtt.riddle/.PenProbeActivity
adb logcat -s PenProbe RawInputReader
```

## What this fork adds

- **Hardware-rendered live ink** (see above) with a seamless handoff into the
  absorb animation (view buffer pre-painted before raw drawing is released)
- **Speculative capture**: the page is photographed and the AI request fired
  after 0.8s of pen rest — by the time the 2s absorb animation finishes, the
  reply is usually already there. Writing again cancels the in-flight request.
- **Write-through replies**: the pen is live the moment the reply finishes
  revealing; touching pen to page dismisses it instantly
- **Canonical Tom Riddle persona** with content→reply paired few-shot examples
  (small vision models follow examples, not rules)
- Movie-style copperplate reply font (Tangerine) for Western text,
  楷书 (Ma Shan Zheng) for Chinese; diagonal top-left→bottom-right reveal sweep
- Palm rejection for the settings gesture; whole-stroke eraser unchanged
- `mac-infra/`: optional proxy to run replies on a Claude subscription quota
  instead of pay-per-use API billing (see its README)

## Tested device

Developed and tested only on a **BOOX Tab 10C (T10C, Android 12, firmware
4.2)**. Other Android 11+ BOOX devices are untested — the same hidden-API fix
should in principle apply (upstream hit the identical symptom on a Note X2),
and reports are welcome.

## Using Claude models

Two ways to have Claude play the diary:

- **Anthropic API (pay-per-use).** Pick the Anthropic backend in the settings
  dialog and paste an API key from console.anthropic.com. Replies bill per
  request; every current Claude model can read the handwriting image.
- **Claude Pro/Max subscription (no API billing).** The app can't use a
  subscription directly, but `mac-infra/boox-diary-proxy.py` bridges it: a
  small always-on proxy on your computer keeps a persistent `claude` CLI
  session warm and exposes an OpenAI-compatible endpoint on your LAN, so
  replies count against the subscription's included quota. In the app choose
  the OpenAI-compatible backend, base URL `http://<computer-ip>:7272/v1`, and
  the shared secret you set via `DIARY_PROXY_TOKEN`. Warm-turn latency is
  ~4-8s (plus your network's round-trip to Anthropic); as a bonus, the diary
  remembers earlier entries within a session. Details in
  [mac-infra/README.md](mac-infra/README.md).

Any other OpenAI-compatible vision endpoint works the same way (tested:
`glm-4v-plus`).

## Setup

1. Build with Android Studio or `./gradlew assembleDebug` (JDK 17, SDK 34).
2. `adb install -r app-debug.apk`, then un-freeze:
   `adb shell pm enable com.billtt.riddle`
3. Unlock the pen pipeline: `adb shell settings put global hidden_api_policy 1`
4. First launch shows settings: pick a backend, paste an API key. Any
   OpenAI-compatible endpoint with a **vision** model works
   (tested: `glm-4v-plus`; the model must see your handwriting).
5. Write. Rest the pen ~2s. Wait for the ink to answer.

Experimental shelved features live in branches: procedural parchment paper
background with in-app sliders, and blood-red accent keywords in replies.

## License

MIT, same as upstream. Font licenses in `FONT_LICENSE_OFL.txt`.
