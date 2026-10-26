# Nagram XF 维护者 Onboarding Checklist

> 目标：让一个新维护者从"能编译"到"敢改、能定位、会发版"。
> 配套文档：`ARCHITECTURE.md`（架构图/模块依赖）、`AGENTS.md`（构建/环境/规范，**改前须问用户**）。
> 勾选方式：`[ ]` 未完成 → `[x]` 完成。每项都给出**可验证的通过标准**，不要"感觉会了"。

---

## Phase 0 · 环境与心理准备（0.5 天）

- [ ] **读三份文档**：`README.md` → `AGENTS.md` → `ARCHITECTURE.md`。能用自己的话复述：这是 Telegram fork、双 flavor、插件靠 Python+ART hook。
- [ ] **确认工具链版本**（不一致会报 CXX1104 或 CI 拉全量 NDK）：
  - [ ] JDK **21**
  - [ ] NDK **27.2.12479018**（`local.properties` 的 `ndk.dir`、根 `build.gradle` 的 `ndkVersion`、CI 安装的 `ndk;27.2.12479018` **三处一致**）
  - [ ] CMake **3.31.6+**
  - [ ] Gradle **9.4.0**（wrapper 自带）
  - [ ] 构建 Chaquopy 桥需要 **Python 3.11 + Cython 精确 3.0.11**
- [ ] **拉取子模块**：`git submodule update --init --recursive --depth=1`（共 23 个，含 18 个 native 三方库）。
- [ ] **准备 `local.properties`**：`sdk.dir` / `ndk.dir` / `cmake.dir` + `TELEGRAM_APP_ID` / `TELEGRAM_APP_HASH`（[my.telegram.org](https://my.telegram.org/auth)）+ 签名三项。
- [ ] **知道红线**：`.plugin` 只支持 arm；**debug 包 hook 不生效，调插件必须 release**；`AGENTS.md` 是活文档，改动前问用户。

**通过标准**：能说出"插件功能在 debug 上为什么不可用"以及"NDK 版本不一致会发生什么"。

---

## Phase 1 · 能编译（1–2 天）

- [ ] 跑通普通包（跳过无关 ABI 加速）：
  ```powershell
  $env:NATIVE_TARGET="arm64-v8a"; ./gradlew :TMessagesProj:assembleNormalDebug
  ```
- [ ] 跑通插件包（**注意用 release**）：
  ```powershell
  $env:NATIVE_TARGET="arm64-v8a"; ./gradlew :TMessagesProj:assemblePluginRelease
  ```
- [ ] 找到产物：`TMessagesProj/build/outputs/apk/<normal|plugin>/<debug|staging|release>/`
- [ ] 理解**三条构建命令的区别**：
  - [ ] `assembleNormalRelease` → 4 个 APK（armv7/arm64/x86_64/universal），无插件
  - [ ] `assemblePluginRelease` → 2 个 APK（arm 系），含 `libaliuhook.so` + Python 运行时
  - [ ] `assembleRelease` → 两种包一起
- [ ] **复现一次"约束"**：理解 `NATIVE_TARGET` 模式下**不能**再设 `ndk.abiFilters`（否则 `Conflicting configuration`）。
- [ ] 安装到真机/模拟器，正常登录、发消息、收发文件。

**通过标准**：无参数错误、无 NDK/CMake 报错，能独立产出可安装 APK。

---

## Phase 2 · 读懂骨架（3–5 天）

- [ ] **入口链路**：`AndroidManifest.xml` → `ApplicationLoader`（`attachBaseContext` 加载 `libtmessages.49.so`）→ `ExteraConfig.init()` → `PluginsController.init()`。
- [ ] **五大公共设施**（能指出各自文件位置）：
  - [ ] `NotificationCenter`（事件总线）——注意重复注册/泄漏/跨账号
  - [ ] `AccountInstance`（多账号隔离模型）
  - [ ] `DispatchQueue` / `DispatchQueuePoolBackground`（线程模型）
  - [ ] `BaseFragment` + `ListView`/`Cells`/`ActionBar`（自研 UI，**不是 Compose**）
  - [ ] `LocaleController`（五套 strings 前缀）
- [ ] **协议层**：`org.telegram.tgnet`（Java 封装）+ native `tgnet`；TL 代码由 `buildSrc/.../tlrpc` 从 `schemas/*.json` 生成。
- [ ] **数据层**：`org.telegram.SQLite`（主库）+ `com.radolyn.ayugram.database.AyuDatabase`（Room，schema 版本 21…35）。
- [ ] **功能归属地图**（抽查读 1–2 个类）：
  - [ ] `tw.nekomimi.nekogram`（Nekogram）
  - [ ] `com.exteragram.messenger`（exteraGram）
  - [ ] `com.radolyn.ayugram`（AyuGram）
  - [ ] `xyz.nextalone.nagram`（nextalone）
- [ ] **UI 规范**（`AGENTS.md` 硬规则）：设置页普通行与 `ConfigCellHeader` 之间必须显式插 `ConfigCellDivider`；前一行用 `cellGroup.needSetDivider(...)` 决定 1px 底线。

**通过标准**：任意给一个功能名（如"已删除消息"、"翻译"、"feed"），能说出它落在哪个包、依赖哪个上层设施。

---

## Phase 3 · 掌握构建系统（3–5 天，本项目的硬门槛）

- [ ] **Gradle/AGP**：
  - [ ] 读懂 `flavorDimensions 'runtime'` 下 `normal`/`plugin` 的差异配置
  - [ ] 读懂 `splits.abi` 与 `ndk.abiFilters` 的互斥规则
  - [ ] 读懂 `R8 fullMode` / `resource shrinking` / `nonTransitiveRClass` / `nonFinalResIds` 的影响
- [ ] **buildSrc 自定义插件**（`buildSrc/src/main/kotlin/`）：
  - [ ] `TelegramBuildPlugin`（`generate*LocalizationUtilsJava`）
  - [ ] `TelegramBuildAppPlugin`（`TelegramStringsTask` / `StringResourceIds` / `LottieMeta`）
  - [ ] 知道 AGP 版本为何由 buildSrc 提供（根脚本不能再声明版本）
- [ ] **代码生成链**：TL scheme、五套 strings、lottie 元数据、字符串 ID 资产——能各说清输入/输出。
- [ ] **Chaquopy 流水线**（`ChaquopyTasks.kt` + `TMessagesProj/build.gradle`）：
  - [ ] `cythonizeChaquopy*` → `compileChaquopy*`（NDK clang 直编直链）
  - [ ] `stageChaquopyStdlib/Requirements/Bootstrap/BuildJson`
  - [ ] 记住**三个不能破坏的点**：`CYTHON_PEP489_MULTI_PHASE_INIT 0`、`-L -lpython3.11`、JNI 导出必带 `JNIEXPORT`（27 个）
- [ ] **改动验证法**：`llvm-nm` 查导出、`llvm-dwarfdump` 查行号表、以原 `.so` 为唯一真身对账。

**通过标准**：能安全地修改一处 Gradle 配置（如新增一个代码生成任务或调整 ABI 过滤）而全量编译通过。

---

## Phase 4 · Native / JNI（3–5 天）

- [ ] 读懂 `jni/CMakeLists.txt`：全局 flags、`TMESSAGES_LINKER_FLAGS`、静态库 `--exclude-libs`、`exports.map` 版本脚本、16KB page 设置。
- [ ] 读懂 `libtmessages.49.so` 的静态库链接清单（tgnet/sqlite/ffmpeg/vpx/dav1d/opus/tgcalls/tde2e/wallet-engine/breakpad…）。
- [ ] 读懂 `jni/aliuhook/aliuhook.cpp` 全流程：
  - [ ] `JNI_OnLoad` **故意不初始化**
  - [ ] `InitRuntime`：mmap W+X 探针 → shadowhook_init → dlopen libart → hidden API 豁免 → lsplant::Init → ctor 缓存
  - [ ] `DisableHiddenApiRestrictionsInternal`（`setHiddenApiExemptions(["L"])`）
  - [ ] `InlineHooker`/`InlineUnhooker` 的 target→stub 映射
  - [ ] `NativeCrashHandler`：信号处理只做 async-signal-safe 操作，re-raise 原 handler
- [ ] **动手**：改一个日志/加一个 JNI 导出，验证编译 + 符号存在。

**通过标准**：能解释"为什么 Hook 必须晚于 JNI_OnLoad"，并能独立排查 native crash 栈。

---

## Phase 5 · 插件子系统（5–7 天，最有区分度）

- [ ] **source set 双实现**：`src/plugin`（真实）vs `src/normal`（桩）——列出哪些类有两份，改代码时两侧都要顾及。
- [ ] **Java 宿主**：`PluginsController` → `PluginsHooks`（`HookRecord`/`EventHookRecord`/`MenuItemRecord`/`XposedHookRecord`）；`PyMethodHook`/`PyMethodReplacement`；`ClassProxy`(dexmaker)/`MethodCache`。
- [ ] **Chaquopy Java 桥**：`com.chaquo.python.*`（`PyObject`/`Reflector`/`MethodCache`）为何不能用上游 AAR（缺 `Reflector.dirAll()` 等）。
- [ ] **Python SDK**（`sdk_pysrc/`）：`elyxcore`（`_engine`/`_plugin_engine`/`_importer`/`_install`/`_plugin`）、`base_plugin`、`plugin_settings`、`android_utils`、`client_utils`、`ui`、`extera_utils`。
- [ ] **SDK 打包约束**：
  - [ ] Python **3.11**（字节码 magic 对齐）、必须 `.pyc`、**字节可复现**（固定时间戳）
  - [ ] `v.txt` 由 `_sdk_version.__version__` 派生
  - [ ] 重新生成：`py -3.11 Tools/build-plugin-sdk-zip.py`；校验：`--check`
  - [ ] **替换 `plugins_pysdk/*.zip` 后必须重新 `assembleRelease`** 才打进 APK
- [ ] **写一个最小插件**（`src/debug/assets/plugins/smoke_test.plugin` 可参考）：注册一个 hook / 菜单项 / 设置项，观察生效。
- [ ] **稳定性机制**：safe mode（`PREF_PENDING_CRASH` / `PREF_CRASHED_PLUGIN_ID`）、`PluginsWatchdog`、`NativeCrashHandler`。

**通过标准**：能完整讲清"从 `.plugin` 安装 → 实例化 → hook 命中 Java 方法 → 回调进 Python"的一条链路，并能自造插件跑通。

---

## Phase 6 · 逆向与安全审查（视需要，2–3 天）

- [ ] 从二进制重建源码的方法论：`llvm-nm` 取符号 → IDA 无头反编译 → `llvm-dwarfdump` 还原行号/结构。
- [ ] `.plugin` 审查：提取内嵌 base64 DEX（`dex\n035` 魔数）→ `jadx -d out x.dex` → 按包名归属。
- [ ] 兼容性判据：`apkanalyzer dex packages --defined-only <plugin-release.apk>` 导类表核对 `find_class`/import。
- [ ] 审查流水线：`Tools/` 下的脚本 + 受信频道判定（`BadgesController` 单 id ∪ 远程 metadata）。

**通过标准**：能对一个可疑插件给出"是否恶意"的判断依据（如明文 HTTP 外发 uid）。

---

## Phase 7 · 工程规范与发布（1–2 天）

- [ ] **版本号**：发版 = 单个 `Version Bump` 提交，只改 `TMessagesProj/build.gradle` 的 `verCode`(+1)。
- [ ] **分支模型**：`canary` 验证稳定 → `git push --force <remote> canary:dev`；**不要往 dev 单独 cherry-pick**。
- [ ] **CI**：`dev`→`staging.yml`（`assembleStaging`，normal-staging + plugin-staging）；`canary`→`canary.yml`（`assembleNormalStaging`）。
- [ ] **资源规范**：新增 string 必须同步所有已翻译 locale（5 套前缀 × 各语言）。
- [ ] **注释规范**：只写"做什么"，禁止来源/历史/逆向痕迹（recovered/decompile/pydump 等）。
- [ ] **提交卫生**：docx 一律不入仓。
- [ ] **能改 `AGENTS.md`**：发现过时/错误条目 → **先问用户** → 实测后修改；可复用经验归纳成"结论 + 关键命令"。

**通过标准**：能独立走完一次"改代码 → 本地构建 → 触发 CI → 产出对账"的闭环。

---

## 速查卡（打印贴墙）

| 目的 | 命令 |
|---|---|
| 普通包（快） | `$env:NATIVE_TARGET="arm64-v8a"; ./gradlew :TMessagesProj:assembleNormalDebug` |
| 插件包（调插件必用 release） | `$env:NATIVE_TARGET="arm64-v8a"; ./gradlew :TMessagesProj:assemblePluginRelease` |
| 全部包 | `./gradlew :TMessagesProj:assembleRelease` |
| 只跑单测（flavor 限定） | `./gradlew :TMessagesProj:testNormalDebugUnitTest`（另有 `testPluginReleaseUnitTest` 等，共 6 个变体） |
| 重建 SDK zip | `py -3.11 Tools/build-plugin-sdk-zip.py` |
| 校验 SDK zip | `py -3.11 Tools/build-plugin-sdk-zip.py --check` |
| 查 so 导出 | `llvm-nm -D --defined-only <so> \| grep Java_` |
| 导插件类表 | `apkanalyzer dex packages --defined-only <apk>` |

| 别踩的坑 | 说明 |
|---|---|
| NDK 三处不一致 | CXX1104 / CI 拉全量 NDK |
| `NATIVE_TARGET` 下再设 `abiFilters` | 配置期 `Conflicting configuration` |
| debug 包调插件 | hook 不生效，必须 release |
| 只改 `src/plugin` 不改 `src/normal` | normal 包编译失败 |
| 换 SDK zip 不重构建 | 不会打进 APK |
| 只加默认英文 string | 其他 locale 缺失 |
| 往 dev 单独 cherry-pick | 破坏 canary/dev 镜像关系 |

---

## 里程碑自检（给自己打分）

| 里程碑 | 你能做到… | 对应 Phase |
|---|---|---|
| M1 能编译 | 独立产出 4+2 个 APK、能装能登录 | 0–1 |
| M2 能定位 | 任意功能能定位到包/文件/依赖设施 | 2 |
| M3 敢改构建 | 安全改一处 Gradle/代码生成并全量通过 | 3–4 |
| M4 会插件 | 自造插件跑通、讲清 hook 全链路 | 5 |
| M5 能发版 | 独立走完改码→CI→对账闭环 | 7 |

> 建议节奏：**M1 一周内**，**M2–M3 再两周**，**M4 一个月**，M5 随实际发版。
