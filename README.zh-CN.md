# BOOX 汤姆·里德尔日记 — 硬件墨迹分支

[English](README.md) | **中文**

《哈利·波特与密室》里汤姆·里德尔的日记，跑在 BOOX 墨水屏平板上：用笔写字，
纸面把墨迹「吸走」，片刻之后日记以流畅的手写体回信。

本项目 fork 自 [billtt/boox-riddle-diary](https://github.com/billtt/boox-riddle-diary)
（其创意源自 reMarkable 平台的 [MaximeRivest/riddle](https://github.com/MaximeRivest/riddle)），
在 **BOOX Tab 10C（T10C，Android 12，固件 4.2）** 上开发和测试。

**演示视频**：[小红书](http://xhslink.com/o/ATQCemksS4M)

## 关键发现：新固件上的原生级书写延迟

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

## 本分支新增

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

## 验证设备

仅在 **BOOX Tab 10C（T10C，Android 12，固件 4.2）** 上开发和验证过。其他
Android 11+ 的 BOOX 设备未经测试——理论上同一个隐藏 API 修复应当适用
（上游作者在 Note X2 上遇到的是完全相同的症状），欢迎反馈。

## 连接 Claude 模型

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

## 上手

1. 用 Android Studio 或 `./gradlew assembleDebug` 编译（JDK 17，SDK 34）。
2. `adb install -r app-debug.apk`，然后解冻：
   `adb shell pm enable com.billtt.riddle`
3. 解锁手写管线：`adb shell settings put global hidden_api_policy 1`
4. 首次启动会弹设置：选择后端、填入 API key。任何 OpenAI 兼容接口的
   **视觉**模型均可（实测：`glm-4v-plus`；模型必须能看你的手写截图）。
5. 写字。停笔约 2 秒。等墨水回答你。

实验性功能封存在分支中：程序化羊皮纸背景（应用内滑杆调节）、
模型标注的血红关键词。

## 许可

MIT，与上游一致。字体许可见 `FONT_LICENSE_OFL.txt`。
