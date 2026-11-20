# MicUp-Custom

手机端「虚拟声卡 / 音效台」：配合 Shizuku（ADB 级权限）与系统内录能力，把系统播放音频或麦克风输入实时处理（增益 / 三段 EQ / 预设音效）后路由输出，可外放监听、可作为语音素材源。

## 功能一览

- **三种采集源**：麦克风 / 系统内录（MediaProjection，Android 10+）/ Shizuku 增强
- **实时 DSP**：总增益（-24 ~ +12dB）、三段 EQ（120Hz / 1.2kHz / 6kHz）、软限幅，运行中热更新
- **预设音效**：原声 / 电台音 / 低沉音 / 清亮音（真实 DSP 参数，非占位）
- **液态玻璃 UI**：氛围光斑 + 半透明磨砂大圆角；可一键切换 Material 3 纯色材质
- **主题**：跟随系统 / 浅色（低饱和粉紫调）/ 深色（深蓝灰 + 紫点缀）
- **可视化**：实时波形 / 频谱柱（48 段对数分桶 FFT）+ 峰值保持 + 电平表
- **持久化**：全部设置走 DataStore Preferences，重启保留

## 云端构建（无需本地 Android Studio）

1. 新建 GitHub 仓库，把本目录**所有文件原样上传**（保持目录结构，含 `.github/`）。
2. 打开 Actions → 「MicUp-Custom CI」→ Run workflow（push 到 main/master 也会自动触发）。
3. 构建完成后，在 Run 详情页 **Artifacts** 下载 `MicUp-Custom-APK`（内含 debug 与 release 两个 APK）。
4. release APK 使用自动生成的 debug 密钥签名，可直接安装。

工作流说明：`permissions: contents: write` + `actions/upload-artifact@v4`；Gradle 8.10.2 由 `gradle/actions/setup-gradle@v4` 在 CI 内置，仓库无需 wrapper；推 tag 构建时 APK 自动附加到 GitHub Release。

## 目录结构

```
MicUp-Custom/
├── .github/workflows/build.yml        ← 云端构建流水线
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/micup/custom/
│       │   ├── MainActivity.kt        ← 入口 + 权限编排 + 页面导航
│       │   ├── data/SettingsRepository.kt  ← DataStore 持久化 + 设置模型
│       │   ├── audio/Dsp.kt           ← RBJ 双二阶 EQ / 软限幅 / FFT
│       │   ├── audio/AudioEngine.kt   ← 采集→处理→路由 实时引擎
│       │   ├── service/CaptureService.kt ← 前台服务（mediaProjection|microphone）
│       │   ├── shizuku/ShizukuGate.kt ← Shizuku 接入与 shell 级增强
│       │   └── ui/
│       │       ├── theme/Theme.kt     ← 双主题配色 + 材质 CompositionLocal
│       │       ├── GlassKit.kt        ← 液态玻璃组件库（卡片/滑杆/开关/按钮）
│       │       ├── Visualizer.kt      ← 波形 / 频谱 Canvas
│       │       ├── MainScreen.kt      ← 主控台
│       │       └── SettingsScreen.kt  ← 设置页
│       └── res/                       ← 图标 / 主题 / 文案
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── gradle/libs.versions.toml
```

## 权限与 Shizuku

- 麦克风采集：`RECORD_AUDIO` 运行时授权（首次点开始时弹出）。
- 系统内录：系统投屏授权弹窗（Android 14 起必须先启动 mediaProjection 类型前台服务，本工程已按此顺序实现）。
- **Shizuku 增强**：授权 Shizuku 后，App 以 shell 身份执行
  - `pm grant <包名> android.permission.RECORD_AUDIO` —— 麦克风免弹窗直授；
  - `appops set <包名> PROJECT_MEDIA allow` —— 多数 ROM 投屏授权自动通过。
  
  任一失败都会回退标准授权流程，不影响正常使用。

## 已知边界（诚实说明）

- 无 root 时，Android 系统不允许第三方应用把音频直接“注入”到其他应用的麦克风通道；本项目以「采集 → 处理 → 外放监听 + Shizuku 预授权加速」落地，虚拟麦克风注入为实验方向。
- 内录仅捕获 `USAGE_MEDIA / GAME / UNKNOWN` 的播放；DRM 保护或声明禁止捕获的应用无法被采集。
- 监听输出走系统提示音通道（`USAGE_ASSISTANCE_SONIFICATION`），不会进入自身内录的捕获范围，避免回授啸叫。
- 麦克风模式 + 外放监听 = 开环，请佩戴耳机使用。

## 技术版本

| 项 | 值 |
| --- | --- |
| AGP | 8.7.3 |
| Kotlin | 2.0.21（Compose 编译器插件） |
| Compose BOM | 2024.12.01 |
| Gradle（CI 内置） | 8.10.2 |
| JDK | 17 |
| minSdk / target & compileSdk | 26 / 35 |
| Shizuku API | 13.1.5 |
| 持久化 | DataStore Preferences 1.1.1 |
