# MC Reflex Tools

[English](README.md) | **简体中文**

在 Minecraft Java 版 26.2 中启用 NVIDIA Reflex 低延迟，走 Minecraft 自带的 Vulkan 渲染器和官方
NVIDIA Streamline SDK。

> **本项目与 NVIDIA、Mojang、Microsoft、FabricMC 均无隶属关系。** 本仓库不包含任何 NVIDIA SDK
> 代码，也不包含任何 Minecraft 代码。Streamline SDK 需要你自行提供 —— 见 [NOTICE.md](NOTICE.md)。

## 状态

早期项目，并且如实说明。一个功能可用且已用 NVIDIA 官方工具验证过，其余受阻。

| 功能 | 状态 |
| --- | --- |
| Reflex 低延迟 | **可用。** 已针对 NVIDIA Reflex Test Utility 完成端到端验证 |
| Reflex 帧率限制 | 可用 —— `customFrameLimitFps` 通过 Reflex 生效 |
| 游戏内设置 | 可用 —— **NVIDIA Reflex Tool** 设置界面与 `/reflex` 命令 |
| DLSS 超分辨率 | **不可用。** 没有 NGX context，也没有实现资源标记 |
| DLSS-G 帧生成 | **不可用。** 同一个阻塞；请保持 `OFF` |

Reflex Test Utility 里的 `PC Latency` 从 `0.000`（未集成）变为 280 FPS 下的 `21.5–23.1 ms`，且
输入到模拟这一段非零。完整记录 —— 证据、每个测量值可信到什么程度、以及试过并被推翻的结论 —— 在
[docs/reflex-verification.md](docs/reflex-verification.md)。重新调查任何 Reflex 行为之前请先读它。

## 环境要求

- Windows x64
- 支持 Reflex 的 NVIDIA GPU 与驱动
- Minecraft Java 版 **26.2**，运行它的 **Vulkan** 渲染器
- **NVIDIA Streamline SDK v2.14.1** —— 不随本项目分发，请自行从 NVIDIA 获取
- 构建需要 JDK 25；重建原生桥还需要 CMake 和 MSVC C++20 工具链

Minecraft 26.2 默认使用 OpenGL。在游戏目录的 `options.txt` 里设置下面这行，否则 Mod 会报告
`UNSUPPORTED_RENDERER`，所有功能保持不可用：

```
preferredGraphicsBackend:"vulkan"
```

## 获取 Streamline SDK

从 NVIDIA 下载 Streamline SDK，解压，然后让 `NVIDIA_STREAMLINE_ROOT` 指向解压根目录：

```bash
export NVIDIA_STREAMLINE_ROOT='<path-to-streamline-sdk-v2.14.1>'
```

构建时用它定位头文件和 `sl.interposer.lib`；运行时 Streamline 的插件 DLL 必须在
`<root>/bin/x64` 下可达。jar 里没有写死任何与本机相关的路径 —— 运行时该变量缺失时，Mod 会报告
自身不可用，游戏照常运行。

## 构建

```bash
./gradlew :fabric:build
```

jar 输出在 `fabric/build/libs/`。它内嵌了预编译的原生桥，所以没有 C++ 工具链也能构建。要从源码
重建原生桥 —— 修改 `nvidia-sdk-native/src/jni_exports.cpp` 之后必须重建 —— 见
[docs/native-build.md](docs/native-build.md)。

## 自动构建与发布

推送到 `main` 的每次提交都会在 GitHub Actions 上构建，jar 作为 artifact 上传，可以在该次运行的
摘要页下载。

打形如 `v0.1.2-SNAPSHOT` 的 tag（必须与 `gradle.properties` 里的 `mod_version` 一致）会触发
[release.yml](.github/workflows/release.yml)：构建，并创建 **Release** 并附上 jar。版本号仍带
`-SNAPSHOT` 后缀，所以在去掉它之前，这些都应视为开发版。

## 运行开发客户端

```bash
export NVIDIA_STREAMLINE_ROOT='<path-to-streamline-sdk-v2.14.1>'
./gradlew :fabric:runClient
```

配置位于 `config/mc_reflex_tools.json`，首次运行时自动生成：

```json
{
  "reflexMode": "ON_PLUS_BOOST",
  "customFrameLimitFps": 0,
  "dlssMode": "MAX_QUALITY",
  "dlssGMode": "OFF",
  "debug": false
}
```

`customFrameLimitFps = 0` 表示关闭帧率限制。`debug = true` 会把生命周期日志的频率从每 300 帧
收紧到每 30 帧。**在 NGX context 存在之前请保持 `dlssGMode` 为 `OFF`** —— 开启它会让 Reflex
Test Utility 的帧生成环节失败。

## 游戏内设置

无需重启即可修改配置的两条路径。两者都写入配置文件，随后每 tick 的应用循环会在下一帧把改动
交给 `slReflexSetOptions`。

**选项 → 视频设置 → NVIDIA Reflex Tool** 打开设置界面，包含：

- **Reflex 模式** —— 关闭 / 开启 / 开启 + 增强 (Boost)。
- **Reflex 锁帧控制** —— 0–999 FPS 滑条，右侧配一个数字输入框。输入框在按 **Enter**、失去焦点
  或关闭界面时生效，而不是每敲一个字符就生效。`0` 表示关闭限制器。
- **调试生命周期追踪** —— 上面提到的 30 帧/300 帧开关。
- 一行实时遥测：延迟、GPU 渲染耗时、当前驱动的帧号。

**`/reflex`** 是同一个控制面在聊天栏的入口：

```
/reflex status
/reflex mode off|on|boost
/reflex framelimit <fps>     # 0 表示关闭限制器
/reflex debug 0|1
```

所有参数都带补全，子命令不带参数时会打印用法提示，而不是毫无反应。

## 工作原理

Minecraft 26.2 通过 `GpuDevice` / `GpuSurface` 抽象层在 `VulkanDevice` / `VulkanGpuSurface` 之上
渲染。Mod 通过 Mixin accessor 借用 Minecraft 自己的 `VkInstance`、`VkDevice`、图形队列和
swapchain，交给 Streamline。它从不创建第二个设备、队列、swapchain 或 Present 循环。

Reflex 的 sleep 挂在 `RenderSystem.pollEvents()` 的 HEAD —— 即上一帧 present 之后、输入采样之前；
六个 PCL 标记分别打在模拟、队列提交和 present 边界上。驱动的带外延迟 ping 通过子类化游戏窗口过程
来应答，因为 Minecraft 把窗口过程交给了 GLFW。

设置界面继承 Minecraft 自己的 `OptionsSubScreen`，并通过 Mixin 注入视频设置页，因此它的滚动、
缩放和键盘导航行为都和内建设置项一致，而不是浮在它们之上。

设计决策记录在 [docs/architecture.md](docs/architecture.md)，已验证与未验证的内容记录在
[docs/reflex-verification.md](docs/reflex-verification.md)。

## 已知阻塞

- **DLSS 需要 NGX context。** `slInit` 调用时没有传 `applicationId`，所以 `sl.common` 不会创建
  context，所有 `kFeatureDLSS` / `kFeatureDLSS_G` 调用都返回 "context is missing"。资源标记、
  `slSetConstants` 和 `slEvaluateFeature` 也都没有实现，所以即使有了 context，DLSS 也不会影响
  最终画面。
- **NVIDIA Reflex HUD 不会叠加在 Minecraft 窗口上。** 那个浮层是驱动画的，不是 Streamline 画的，
  也不是这个 Mod 画的；本仓库里的任何东西都影响不了它。
- **`VK_NV_low_latency_2` 是刻意不启用的。** 文档给出的手动 hook 修复方案确实有效，但它可测量地
  让 Reflex 变差，所以它被 `-Dmc_reflex_tools.vulkanLowLatency2=true` 挡在后面，默认关闭。退化的
  原因目前不明。见 [docs/reflex-verification.md](docs/reflex-verification.md) §7.3。

## 路线图

- **M0–M1** —— 脚手架、安全闸门、Minecraft 自有 Vulkan context 的发现与校验。已完成。
- **M2** —— 官方 NVIDIA Reflex，端到端验证通过。已完成。
- **M3** —— DLSS 超分辨率，待 NGX context 和资源标记就绪。
- **M4** —— 帧生成评估，仅在 Reflex 和 DLSS 稳定之后。

逐项清单见 [docs/roadmap.md](docs/roadmap.md)。

## 法务

NVIDIA、Reflex、DLSS、G-SYNC 和 Streamline 是 NVIDIA Corporation 的商标。Minecraft 是 Mojang
Synergies AB 的商标。本项目与 NVIDIA、Mojang、Microsoft、FabricMC 均无隶属、背书或赞助关系。
见 [NOTICE.md](NOTICE.md)。

## 作者

由 **Kyresn114** 开发与维护。

## 许可证

MIT —— 见 [LICENSE](LICENSE)。仅覆盖本仓库的源代码。
