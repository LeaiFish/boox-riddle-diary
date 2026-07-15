# Riddle Diary for BOOX — hardware-ink fork

[English](#english) · [中文](#中文)

Tom Riddle's diary from *Harry Potter and the Chamber of Secrets*, running on
BOOX e-ink tablets: write with the pen, the page drinks your ink, and a moment
later the diary writes back in a flowing hand.

**Demo video**: [on Xiaohongshu](http://xhslink.com/o/ATQCemksS4M)

---

## English

A fork of [billtt/boox-riddle-diary](https://github.com/billtt/boox-riddle-diary)
(itself inspired by [MaximeRivest/riddle](https://github.com/MaximeRivest/riddle)
for the reMarkable), extended and tested on a **BOOX Tab 10C (T10C, Android 12,
firmware 4.2)**.

### The key discovery: native-latency ink on new firmware

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

### What this fork adds

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

### Tested device

Developed and tested only on a **BOOX Tab 10C (T10C, Android 12, firmware
4.2)**. Other Android 11+ BOOX devices are untested — the same hidden-API fix
should in principle apply (upstream hit the identical symptom on a Note X2),
and reports are welcome.

### Using Claude models

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

### Setup

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

### License

MIT, same as upstream. Font licenses in `FONT_LICENSE_OFL.txt`.

---

## 中文

本项目 fork 自 [billtt/boox-riddle-diary](https://github.com/billtt/boox-riddle-diary)
（其创意源自 reMarkable 平台的 [MaximeRivest/riddle](https://github.com/MaximeRivest/riddle)），
在 **BOOX Tab 10C（T10C，Android 12，固件 4.2）** 上开发和测试。

### 关键发现：新固件上的原生级书写延迟

Onyx SDK 的硬件手写管线在新固件上看似失效：上游作者在 Note X2（Android 11）
上、本分支在 Tab 10C（Android 12）上都遇到同一现象——logcat 报
`RawInputReader: Empty region detected when mapping!!!!!`，笔迹回调静默。
上游因此实现了软件描墨方案（受墨水屏软件刷新机制所限，墨迹会略滞后于笔尖），
并在开发笔记中细致记录了这一症状——那份记录正是本分支得以定位根因的起点。

在 Tab 10C 上抓取完整 logcat 后定位到根因：SDK 依赖
`VMRuntime.setHiddenApiExemptions` 反射技巧来解锁 Onyx 固件的隐藏类
（`android.onyx.handwriting.PenConfig` 等），而本机日志明确显示该调用被系统
拒绝（`blocked, core-platform-api, reflection, denied`），随后这些隐藏类
`ClassNotFoundException`、区域映射失败。这与 Android 11 起收紧的
hidden API enforcement 政策相符；社区在更老设备（Android 9/10 时代）上的
成功案例也与该机制吻合（本分支未在老设备上亲测）。

在 Tab 10C 上实测有效的解法只要一条 adb 命令：

```bash
adb shell settings put global hidden_api_policy 1
```

放行之后，默认的 `TouchHelper.create()` 硬件管线即可工作（本机前后对照验证：
墨迹实时渲染 + 回调正常）。本分支默认启用硬件墨迹
（`DiaryController.HW_PEN_RENDER`），并附带一个可用 adb 驱动的最小诊断页，
书写异常时可以快速定位：

```bash
adb shell am start -n com.billtt.riddle/.PenProbeActivity
adb logcat -s PenProbe RawInputReader
```

### 本分支新增

- **硬件渲染的实时墨迹**（见上），并做了墨迹到吸收动画的**无闪交接**
  （关闭硬件层之前先把笔迹画入 View 缓冲）
- **投机预取**：停笔 0.8 秒即截图并发起 AI 请求——等 2 秒吸收动画放完，
  回信多半已经在路上；期间再落笔会自动作废在途请求
- **即回即写**：回信浮现完成的瞬间笔即恢复，落笔立刻让回信让位
- **原著对齐的汤姆·里德尔人设**，采用「页面内容 → 回信」配对 few-shot 示例
  （小型视觉模型跟示例、不跟规则）
- 西文回信使用电影气质的铜版体（Tangerine），中文使用楷书（马善政）；
  回信以左上 → 右下的对角扫掠浮现
- 设置手势加入防手掌误触；整笔橡皮擦除保持上游行为
- `mac-infra/`：可选的 Mac 端代理，让回信走 Claude 订阅的包含额度而非按量
  API 计费（详见该目录 README）

### 验证设备

仅在 **BOOX Tab 10C（T10C，Android 12，固件 4.2）** 上开发和验证过。其他
Android 11+ 的 BOOX 设备未经测试——理论上同一个隐藏 API 修复应当适用
（上游作者在 Note X2 上遇到的是完全相同的症状），欢迎反馈。

### 连接 Claude 模型

让 Claude 扮演这本日记有两条路：

- **Anthropic API（按量计费）。** 在设置里选 Anthropic 后端，填入
  console.anthropic.com 申请的 API key。按请求计费；当前所有 Claude 模型
  都能读手写截图。
- **Claude Pro/Max 订阅（不产生 API 账单）。** app 无法直接使用订阅，但
  `mac-infra/boox-diary-proxy.py` 可以架桥：在你的电脑上跑一个常驻小代理，
  内部维持一个持久的 `claude` CLI 会话，对局域网暴露 OpenAI 兼容接口——
  回信消耗的是订阅的包含额度。app 里选 OpenAI 兼容后端，Base URL 填
  `http://<电脑IP>:7272/v1`，API key 填你通过 `DIARY_PROXY_TOKEN` 设置的
  共享密钥。暖会话延迟约 4–8 秒（外加你到 Anthropic 的网络往返）；附带
  彩蛋：同一会话内日记记得你之前写过的内容。详见
  [mac-infra/README.md](mac-infra/README.md)。

其他任何 OpenAI 兼容的视觉模型接口同样可用（实测：`glm-4v-plus`）。

### 上手

1. 用 Android Studio 或 `./gradlew assembleDebug` 编译（JDK 17，SDK 34）。
2. `adb install -r app-debug.apk`，然后解冻：
   `adb shell pm enable com.billtt.riddle`
3. 解锁手写管线：`adb shell settings put global hidden_api_policy 1`
4. 首次启动会弹设置：选择后端、填入 API key。任何 OpenAI 兼容接口的
   **视觉**模型均可（实测：`glm-4v-plus`；模型必须能看你的手写截图）。
5. 写字。停笔约 2 秒。等墨水回答你。

实验性功能封存在分支中：程序化羊皮纸背景（应用内滑杆调节）、
模型标注的血红关键词。

### 许可

MIT，与上游一致。字体许可见 `FONT_LICENSE_OFL.txt`。
