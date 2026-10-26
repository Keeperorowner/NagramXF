# exteraGram 12.10.6 (71129) 逆向结论与移植计划

对象：`exteraGram-full-universal-20261004.apk`（package `com.exteragram.messenger`，versionName 12.10.6，versionCode 71129，Telegram 基线 12.10.6）。
对照方：本仓库 `canary`（Telegram 13.1.1 / `verCode 7237`）。

逆向证据：jadx 全量反编译（7 个 dex，`D:\AndroidProjects\extera-12.10.6-re\src\{classes..classes7}`）、apktool 资源（`D:\AndroidProjects\extera-12.10.6-re\apktool`）、`dexdump -d` 指令级转储（`D:\AndroidProjects\extera-12.10.6-re\_classes*.dis.txt`）、SDK 压缩包二进制字符串与符号（`D:\AndroidProjects\extera-12.10.6-re\sdk_x`、`sdk_diff.txt`）。

---

## 0. 结论速览

| 领域 | 本版有什么 | 我方现状 | 结论 | 量级 |
|---|---|---|---|---|
| Feed 宽版 | 独立「Wide Posts」特性：频道帖子/feed 帖子按整屏宽渲染，作者头像内嵌 header，媒体拉满，分组相册按行重排 | 无任何 wide 逻辑 | **值得移植**，改动集中在 ChatMessageCell | ~1450–1600 LOC |
| AI | 从「单一 REST 客户端」重构为**多后端**（REST + 端上 Gemini Nano），新增**替换 Telegram 官方 AI**（编辑器/摘要/翻译）、富文本响应面板、生成交互扩展（选区生成、Ask More、历史页）、错误分类 | 已有 AI Chat/生成/温度/流式/引用（较早期一代） | **分部移植**：先替换 Telegram AI，再后端重构；端上模型视 GMS 策略 | ~12–15 人日 |
| 插件 | 框架与我方架构同构（pip/ClassProxy/xposed/看门狗均已具备），差距是 SDK 验签、pip 解析深度、SDK 装饰器族与日志模块、若干新 UI | 已有 35 个 SDK 模块中的 34 个 + 安全模式等自有增强 | **5 处差距，按 6 个阶段补** | ~8.5–9.5 人日 |

---

## 1. Feed：宽版帖子（Wide Posts）

详细逆向报告：`D:\AndroidProjects\extera-12.10.6-re\report-feed-wide.md`（469 行，含 file:line、公式、bytecode 依据）。以下为结论要点。

### 1.1 开关与判定

| 键 | 默认 | 位置 | 作用 |
|---|---|---|---|
| `widePostsInFeed` | `true` | `ExteraConfig.java:355/1216/2087` | feed（`searchType==4`）里的频道帖走宽版 |
| `widePostsInChannels` | `false` | `ExteraConfig.java:356/1212/2083` | 频道聊天里的帖子走宽版 |
| `widePosts`（master） | — | `ChatsPreferencesActivity:719`（子项 `:725-740`） | 仅 UI 分组开关，**不参与运行时判定** |

运行时判定：`WidePosts.isEnabledFor(msg)` = `isChannelPostOrSponsored(msg)` × 对应开关（feed → `getWidePostsInFeed()`，其余 → `getWidePostsInChannels()`）。
因此**同一 ChatActivity 内可逐条混排**（feed 里普通消息仍是窄气泡）。

设置入口：feed 设置在 `FeedChannelsActivity:293/391`（键 `feedWidePosts`，文本 `R.string.WidePostsInFeed`）；渠道侧开关在 Chats 设置页；`SettingsRegistry:46` 的 `newFeatures` 含 `"widePosts"`。

### 1.2 视觉/行为规格

- 气泡右侧贴边：`getBubbleRight(width) = max(dp(120), width - dp(9))`；
- 分组相册按 1000 单位网格重排：`scaled = clamp(round(max(dp120, W-dp9) * 1000 / W), 1, 1000)`，每行由 `flags&2`/`maxX` 最大的格子吸收余量，`spanSize = pw (+1000-scaled)`；
- 头像内嵌时先剥掉 108px 左侧偏移（`removeGroupAvatarOffset`），原值存 `GROUP_POSITION_STATES`（WeakHashMap）以便 `restoreGroupedLayout` 还原；
- 图片高度：`getImageHeight = max(dp120, min(round(w*sh/sw), max(minH, min(getPhotoSize(), w+dp100))))`；
- 文本宽度总闸：`MessageObject.getMaxMessageTextWidth()` 末尾 `if (widePostsTextWidth > 0) result = widePostsTextWidth;`（无条件覆盖，写入点在 `ChatMessageCell.setMessageContent`）；
- 作者 header：feed 帖取 `FeedMessageUtils.resolveDisplayChannel()` 对应频道（`signature_profiles` 决定作者签名），评论帖取 `CommentsPostContext`，其余取 `from_id` 用户；
- 隐藏辅助按钮/分享按钮（sponsored 例外，按钮移入气泡内并有独立命中区），`shouldRemoveMessageTail()` 在宽版下一律为真。

### 1.3 代码结构与调用点

| 文件 | 规模 | 说明 |
|---|---|---|
| `com/exteragram/messenger/utils/chats/WidePosts.java` | 704 行，**25 个 public static 方法** | 判定 + 几何 + 分组重排 + 评论作者上下文 |
| `org/telegram/ui/Cells/ChatMessageCell.java` | **166 处引用 / 27 个新增方法** | 几何、header、头像命中、媒体、侧边按钮、触摸、绘制/测量 |
| `org/telegram/ui/ChatActivity.java` | 14 处 | `isWidePostsScreen()`、评论帖作者加载、`replaceMessageObjects` 上下文复制 |
| `org/telegram/messenger/MessageObject.java` | 9 处 | `widePostsTextWidth`、`GroupedMessages.calculate()` 挂载、转写高度 |
| `org/telegram/ui/LaunchActivity.java` | 3 处 | `runCommentRequest` 注入 `CommentsPostAuthorLoader` |
| `com/exteragram/messenger/feed/FeedMessageUtils.java` | +2 方法 | `resolveDisplayChannel()`、`hasProfileSignature()`（我方缺） |

### 1.4 我方缺口

| 项 | 处理 |
|---|---|
| `WidePosts` 类 | 新建，逻辑可直译 |
| `MessageObject.widePostsTextWidth` | 加字段 + getter/setter + `getMaxMessageTextWidth` 覆盖 + 复用/关闭时清零 |
| `ChatMessageCell.getGroupPhotosWidth(MessageObject)` 重载 | 我方只有无参版（`:13885`），需新增并转发 |
| `feedDisplayChannel` 字段 | 新增（feed header 头像依赖） |
| `ChatActivity.isWidePostsScreen()` | 新增 |
| `FeedMessageUtils.resolveDisplayChannel/hasProfileSignature` | 按 extera `:249-297` 补 |
| 设置 UI | 我方是 UItem/`BasePreferencesActivity` 体系，需按我方风格重写（feed 页加一行、chats 页加分组） |
| `stickerTimeRow` / `shouldRemoveMessageTail` 等 extera 特有链 | 我方无 StickerTime；尾巴开关走 `NaConfig.getRemoveMessageTail()`，宽版条件里需 `||` 合并 |

### 1.5 分期（依赖顺序，详见报告 §9）

- **P0** `WidePosts.java`（~450 LOC）：常量、`GroupPositionState`、`CommentsPostContext`、几何函数、`normalizeGroupRows`/`updateGroupedLayout` 还原版；
- **P1** `MessageObject` + 分组（~80 LOC）：字段、宽度覆盖、`calculate()` 挂载；
- **P2** `ChatMessageCell` 几何 + 媒体（~250 LOC）；
- **P3** header / 头像嵌入（~300 LOC，风险最高）；
- **P4** `ChatActivity`/`LaunchActivity` 评论接线（~150 LOC）；
- **P5** 设置 UI + 字符串（~120 LOC，需补 zh-rCN/zh-rTW/ja/ko/ru/uk）；
- **P6** feed 收尾（~120 LOC）；合计 **~1450–1600 LOC**。

### 1.6 风险（Top5）

1. 分组相册 normalize/restore：改写的是共享 `GroupedMessagePosition`，任一早退分支漏 `restoreGroupedLayout` → 相册永久变形；WeakHashMap 失效 → 同相册不同 cell 宽度不一致。
2. feed 签名作者头像：`nameAvatarBounds.set(...)` 落在 jadx 未反编译区域，需从 dexdump 自行还原坐标；点击区/badge/emoji status/a11y 全依赖它。
3. 侧边/分享按钮抑制与 `getBoundsLeft/Right` 的 48dp 让位不一致 → 点击穿透/误触。
4. 12.10.6 → 13.1.1 漂移：无 `getGroupPhotosWidth(MessageObject)` 重载、`measure` 结构不同、`getMaxMessageTextWidth` 实现可能已重构。
5. `widePostsTextWidth` 全局覆盖：消息复用或转发后残留非 0 会把普通气泡撑宽，必须提供清零路径。

### 1.7 顺带发现（不建议移植）

feed 里有一套**赞助广告注入**：`feed/ads/*`（`FeedAdController` 从频道 `-3514621311` 读取 75 条广告帖，`FeedAdParser/FeedAdFactory/FeedAdInjector` 按间隔插入），远程配置键 `feed_ads_enabled`(true)、`feed_ad_every`(30)、`feed_ad_first_after`(30)、`feed_ad_min_trailing`(2)、`feed_ad_spacing_pool1/2`。属于 extera 自身变现，本仓库无对应运营需求，跳过。

---

## 2. AI

### 2.1 配置面（`AiConfig.java`，26.4 KB）

| 键 | 类型/默认 | 我方现状 |
|---|---|---|
| `saveHistory` | bool / `true` | 有 |
| `responseStreaming` | bool / `true` | 有 |
| `temperature` | int / `10`（0–20 滑条） | 有（全局，同名语义） |
| `showResponseOnly` | bool / `false` | 有 |
| `insertAsQuote` | bool / `true` | 有 |
| `replaceTelegramEditor` | bool / 按账号，默认 `!isPremium()` | **无** |
| `replaceTelegramSummaries` | bool / 按账号，同上 | **无** |
| `onDeviceFastModel` / `onDevicePreviewModel` / `onDeviceReasoning` | bool / `false` | **无** |
| `selectedServiceId`（UUID）+ `selectedServiceHash`（旧哈希兼容） | — | 我方按 hashCode 选服务 |
| `roles` / `services` / `history` | JSON | 有 |
| `legacyConfigMigrated` / `ensureConfigMigrated()` | 迁移标记 | 无（我方无迁移需求） |

补充约定：`temperature` 存 0–20，**由 backend 缩放到各自上限**（REST `0–2`、端侧 `0–1`，`Client.java:288`），不是固定 `/10`；端侧运行时还有 6 个裸缓存键（`onDeviceFeatureStatus` 0/1/2/3、`onDeviceModelName`、`onDeviceThinkingSupported`、`onDeviceSystemPromptSupported`、`onDeviceTokenLimit`、`onDeviceOtherModelSupported`）。

### 2.2 数据模型变更

`data/Service`：新增 `id`(UUID) 与 `Kind{REST, ON_DEVICE}`，删掉显式 `protocol`（端点改由 URL 推断），保留 `url/model/key/reasoningEnabled`；`getLegacyHash()` 用于老配置迁移（读到 `selectedService` 旧 hash 后自动改写为 `selectedServiceId` 并删除旧键）。
→ 我方 `Service` 是 `protocol{OPENAI,CLAUDE,GEMINI}` + 无 id/kind，迁移时保留 `protocol` 作兜底、JSON 兼容读取后再写新结构。

⚠️ **reasoning 语义两边相反**：extera 的 `reasoningEnabled == true` 表示「不干预（AUTO）」；我方 `false` 才是「关闭」。移植时必须统一成一个枚举（AUTO/OFF/ON），否则会出现一处 true 一处 false。

### 2.3 后端抽象（`ai/network/backend/*`，~100 KB 反编译）

```java
interface Backend {                       // Backend.java
  void execute(BackendRequest, BackendSink);
  int  getHistoryCharsLimit(); int getHistoryMessagesLimit();
  int  getMaxOutputTokens();   float getMaxTemperature();
  boolean supportsImages();
}
record BackendRequest(Service, Role, List<Message> history, Message current,
                      boolean stream, float temperature, int maxOutputTokens)
```

| 实现 | 限额（历史字符/条数/输出 token/温度上限） | 要点 |
|---|---|---|
| `RestBackend` | `24000 / 32 / 4096 / 2.0` | 端点按 URL 推断：gemini → `/v1beta/openai/chat/completions`；perplexity → `/v1/agent`；以 `/responses`、`/agent` 结尾 → Responses API；否则补 `/chat/completions`。支持 `store:false`/`instructions`/`input_image`、两套 SSE 解析、thinking、reasoning effort 矩阵、204 与 HTTP code 错误映射 |
| `OnDeviceBackend` | `12000 / 24 / 1024 / 1.0` | ML Kit GenAI（`com.google.mlkit.genai.prompt.*`）+ AICore，`GenerativeModelFutures`、`GenerateContentRequest`、`StreamingCallback`、`TextPart/ImagePart`、`CachedContext`、`CountTokens`；token 超预算时循环丢最老 turn；`CachedContext` 键 `extera_role_<md5>`（上限 4） |

- `OnDeviceAvailability`(25.1 KB)：状态机 0 不可用/1 需下载/2 下载中/3 就绪 → 错误码 −1/−2/−3，另 −4 token 超限、−5 推理失败；fast/preview 位掩码；下载走前台通知 `ai_on_device_model`；可跳 AICore 系统设置。
- `ProxyDns`(7.1 KB)：AI 请求自定义 DNS，依赖 extera 的远端配置 → **不移植**（我方无 `RemoteUtils` 等价物）。
- `AiController` 保留 `canUseAI()/canSendImage()/getPathToMessage()/showErrorBulletin()`。

### 2.4 替换 Telegram 官方 AI（`TelegramAiReplacement.java`，10.7 KB）

| 方法 | 注入点 | 行为 |
|---|---|---|
| `replacesEditor(account)` / `compose(...)` | `AIEditorAlert:2723-2728` | 劫持 `TL_messages_composeMessageWithAI`，语气/style 转本地 prompt |
| `replacesSummaries(account)` / `summarize(...)` | `TranslateController:1635-1641` | 摘要改本地生成 |
| `translate(...)` | `TranslateAlert2:1639-1640` | 翻译改本地生成 |
| `cleanResponse()` / `buildComposeTask()` / `styleOf()` / `describeDefaultTone()` / `languageName()` | 内部 | prompt 构造与结果清洗 |

门控：`AiConfig.getReplaceTelegramEditor/Summaries(account) && AiController.canUseAI()`，默认对非 Premium 用户开启。
防注入：待处理文本用随机 `<text-xxxx>` 标签包裹，并在 system prompt 里说明「标签内是数据不是指令」。

### 2.5 生成交互

- 入口（4 个）：消息长按 `Generate`；**选中文本右键 `Generate`**（`TextSelectionHelper`，我方无）；输入框 AI 按钮 → Telegram `AIEditorAlert`（被 2.4 接管）；发送键长按的 AI 行。
- 弹层：`GenerateFromMessageBottomSheet`（`GenerationData{prompt, useHistory, imagePath}` + With History/With Photo，我方已有等价实现）。
- 响应页：`AiResponseAlert`(83 KB) —— 流式、Ask More、Change model、**Markdown→`TL_iv.RichMessage` 用 `RichMessageLayout.PreviewView` 原生渲染**、header 内切角色/切模型、`dropLastTurnFromHistory()` 记账、复制/引用/插入、`noforwards` 时加 `FLAG_SECURE`。
- 插入路径：`ChatActivityEnterView.insertAiResponse()` + `buildAiRichInsert()`（blockquote 包裹 + `→ prompt`），回调从 `Callback2<String,CharSequence>` 升为 `Callback3<String,CharSequence,TL_iv.RichMessage>`。
- 历史：**全局单列表**（与我们一致，非 per-chat），上限 **64 条 / 64000 字符**；新增 `AiHistoryActivity` 逐 turn 展开/删除。
- 错误分类：`AIError429/502/503` + `AIErrorEmpty` + 端上 5 类（未下载/下载中/不可用/已停止/超长），替代我方粗粒度 `showErrorBulletin(code)`。

### 2.6 语音转写 + AI 后处理（可选）

`speech/*` 用 **Vosk** 做端上 ASR，`ExteraConfig.postprocessingWithAi` 开启后把转写文本交给 AI 修标点/读顺（`VoiceRecognitionController:83`）。依赖 Vosk 模型与 `.so`，体量较大，建议单独立项。

### 2.7 字符串

AI 相关新增 **61 条**（`AIAskMore`、`AIChangeModel`、`AIAssistant`、`AIGeneration`、`AIOnDevice*` 全部、`AIError*` 细分、`AIReplaceTelegram*`、`Temperature/Reasoning/Prompt` 等）；另有 feed 新增 3 条（`WidePosts`、`WidePostsInFeed`、`FeedChannels`）。按 AGENTS.md 需同步 zh-rCN/zh-rTW/ja-rJP/ko/ru-rRU/uk。

### 2.8 分期（约 12–15 人日）

| 阶段 | 内容 | 预估 |
|---|---|---|
| **A0** | 数据/配置：`Service` 加 `id`+`kind`（`protocol` 降级兜底）、`AiConfig` 新键（`onDevice*`、`replaceTelegram*`、`selectedServiceId`）、历史键迁移 | 1–1.5 天 |
| **A1** | 后端抽象：新建 `ai/network/backend/{Backend,BackendRequest,BackendSink,RestBackend}`，`Client` 退化为调度层（温度按 backend 上限缩放、历史裁剪合并）；**RestBackend 自带 OkHttp**，`useLlmProvider` 分支继续走 `LlmConfig` 构造 Service | 2.5–3 天 |
| **A2** | 响应面板：`AiResponseAlert`（`RichMessageLayout.PreviewView` 渲染 `TL_iv.RichMessage`、切模型/角色、turn 记账）、`ChatActivityEnterView.insertAiResponse/buildAiRichInsert`、回调升 `Callback3` | 2–2.5 天 |
| **A3** | 替换 Telegram AI：`TelegramAiReplacement` + 3 处 hook（我方 `AIEditorAlert` 是单 tab，需按 tab 映射 tone/proofread/emojify） | 2 天 |
| **A4** | 端上模型（可选/feature-flag）：`com.google.mlkit:genai-prompt` 依赖、manifest `queries` + `BIND_SERVICE`、`OnDeviceBackend/OnDeviceAvailability`、通知/对话框、服务页端侧行 | 2.5–3 天 |
| **A5** | 设置面 + 历史页：`AiPreferencesActivity` 三段、`EditServiceActivity` 按 400/401/403/404 抖动定位、`AiHistoryActivity` | 1.5–2 天 |
| **A6** | 字符串：44 个 `AI*` + ~19 个 `Service*/Generate*` × 6 locale | 0.5–1 天 |
| **A7** | 语音后处理（Vosk，可选，单独立项） | 3–5 天 |

### 2.9 风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| AICore 可用性 | 仅 Pixel 8+ / 部分 OEM、Android 14+，且需 AICore 应用与 `BIND_SERVICE`；非 GMS / 国内 ROM 基本不可用 | 端侧全部能力包在 `OnDeviceAvailability` 探测后，未就绪则服务列表不出现、设置页可隐藏整段 |
| 依赖体积 | `com.google.mlkit:genai` + AICore 绑定增大包体，且可能与现有 `com.google.mlkit` 版本冲突 | 仅 release / 独立 flavor 引入，先确认坐标与版本 |
| 隐私 | 明文 key 存 SharedPreferences；图片以 base64 上传第三方 | 设置页文案区分；建议加「仅 WiFi」「不发送图片」开关 |
| reasoning 语义反转 | extera `true` = 不干预；我方 `false` = 关闭 | 统一为 AUTO/OFF/ON 枚举 |
| temperature 缩放 | 端侧 0–1、REST 0–2；我方现为固定 `/10` | 改由 backend 提供 `getMaxTemperature()`，UI 仍显示 0.0–2.0 |
| 13.1.1 漂移 | `AIEditorAlert` 我们只有单 tab；`TranslateController.pushToSummarize` 签名可能不同；`TextSelectionHelper` 位置在 `ui/Cells` | 每处 hook 先读我方版本再写，不整文件搬运 |
| 历史不分 chat | 两边都是全局历史 | 若要 per-chat 需改键为 `history_<dialogId>`，成本高，先不做 |

---

## 3. 插件系统

### 3.1 总体结论

框架与我方**架构同构**：`ClassProxy`（DexMaker 代理 + `__extera_internal_python_peer__` 契约）、`XposedHookRecord`（回调引用计数）、`PyMethodHook`/`PyMethodReplacement`（before/after/replace + filters + closed 即禁用）、`MenuItemRecord`/`MenuContextBuilder`、`models/*`、`.plugin` 解析（AST 元数据 + wheels + 加密异常）、看门狗、dev server（端口 42690，命令集相同）两边一致；我方另有安全模式（`SafeModeReason` 三态 + 文案 API）、`isPluginRuntimeOperational`、`preparePythonCompatShims`、`IntentsController`、normal flavor 桩等自研增强——这部分不要为了「对齐」而删。

真正的差距是 **5 处**：

| # | 差距 | 影响 | 优先级 |
|---|---|---|---|
| 1 | SDK 更新包**验签**（`SdkSignature`，我方整条链路缺失：无 `signature` 字段、无 `newSdk.meta`） | 安全 | 高 |
| 2 | pip 解析能力：无 PEP 508 `MarkerParser`（and/or/括号/in/not in/~=）、无 PEP 440 `VersionComparator`、wheel tag 硬编码 `py3-none-any`、无 `requires_python`、无跨插件冲突检测 | 插件可用率 | 高 |
| 3 | SDK 能力：`log_utils`、`extera_utils/classes` 装饰器族（`ClassBuilder/Method/Override/Constructor/PreConstructor` + `_Resolved*Metadata` + super bridge + `on_pre_init/on_release`）、`file_utils` 常量、`CustomSetting.Factory` | 插件 API 面 | 中 |
| 4 | 新 UI：`PluginSettingsTranslation/TranslatableText/PluginTranslateButton`（在线翻译设置文案，20 条/批）、`RequiredPluginCell/Info`（依赖插件逐项安装）、`PythonSdkUpdateAlert`、`UnknownSourcesBottomSheet` | 体验 | 中 |
| 5 | 实现方式差异（语义不变）：`HookFilter` 构造期编 `typeId` + 条件缓存、`PluginsController.executeGenericHook` 收敛 5 类 hook、watchdog 改「每线程一次性 5s ScheduledFuture + frozenExecutions」 | 性能/维护 | 低 |

`UnknownSourcesBottomSheet` 不移植（我方走 `BadgesController` 受信策略，安装页加「来源未受信」行即可）。

### 3.2 SDK 模块对照

| 项 | extera 12.10.6 | 我方 |
|---|---|---|
| 模块数 | 35 | 35 |
| 形式 | Cython 编译成 ELF `.so`（clang 21，`PyInit_*` 导出） | 明文 `.py`（3 个附 `.pyc`） |
| `v.txt` | `1.4.5.7\|0` | `1.4.6.7` |
| 差异模块 | **多 `log_utils`**；`extera_utils/classes` 多装饰器族（`ClassBuilder/Method/Override/Constructor/PreConstructor`）；`file_utils` 多 `FilesController` 常量；`ui/settings` 多 `CustomSetting.Factory` | 多 `_stubs` |

其余模块名一一对应（`_sdk_version`、`base_plugin`、`client_utils`、`dev_server`、`elyxcore/*`、`extera_utils/*`、`file_utils`、`hook_utils`、`intents`、`markdown_utils`、`plugin_settings`、`ui/*`）。

> 版本号**不可线性比较**：`1.4.5.7\|0`（`\|1` = beta）与我们的 `1.4.6.7` 是两条不同分支的重写，SDK 兼容判断不能直接 `>=` 比较。

### 3.3 新增 `log_utils`（插件日志 SDK）

公共 API：`Logger`、`LogLevel`、`get_logger`、`debug/info/warning/error/exception/log`、`add_handler/remove_handler`、`tag`、级别转换 `_coerce_level`；
实现要点：`_write_logcat`/`_split_for_logcat`（按 logcat 单条上限切分写 `android.util.Log`）、`_format_location`/`_format_exc_info`、`_plugin_logger`/`_reset_plugin_logger`（插件卸载时回收 logger）、`_Dumper`（`_JavaObject/_JavaCollection/_JavaMap/_JavaEnum/_inspect_java/_java_fields`，把 Java 对象打印成可读结构）。
`base_plugin.so` 内已 `import log_utils`，即插件可以直接 `from log_utils import get_logger`。

### 3.4 SDK 更新签名（`plugins/utils/SdkSignature.java`，5.1 KB）

ECDSA P-256 校验下载的 SDK 包：`payload = "extera-pysdk|" + version + "|" + abi + "|" + sha256(archive)`，公钥硬编码，`Signature("SHA256withECDSA")` 验证；`PythonPluginsEngine$Updater` 在落盘前调用。
我方 `Updater` **没有任何签名校验**（仅走 Telegram 频道下发 + 版本约束）。

### 3.5 Java 层差异

| 类 | extera | 我方 | 结论 |
|---|---|---|---|
| `SdkSignature` | 有（`SdkSignature.java:29` 公钥；调用点 `PythonPluginsEngine.java:315`、`:546`） | **无** | 需补，但要用我方自签密钥 + 发布侧签名流程 |
| `pip/PipController` | Kotlin 1115 行 + `MarkerParser`(11.5 KB)/`ParsedVersion`/`VersionComparator`/`WheelCandidate`/`SizeLimitedInputStream`；`pythonVersion=3.11.10`；预装白名单 7 包；`requires_python`、sha256、yanked（`==` 例外）、跨插件冲突检测、失败回滚 | Java 1034 行；wheel tag 硬编码 `py3-none-any`；`pythonVersion="3.11"`；无括号/`in`/`~=`、无 `requires_python`、无冲突检测 | **需强化**（P2），限额两边一致（registry/metadata 4 MiB、wheel 250 MiB、50k 条目/512 MiB/比率 500） |
| `HookFilter` | 构造期编 `typeId` + 实例级条件缓存 | `evaluateValue/executeCondition/isResultFilter` | 语义等价，性能优化可选 |
| `PluginsController` | `executeGenericHook`/`getInterestedPluginIds`/`notifyMenuItemsUpdated`/`notifyPluginSettingsRegistered` | 5 份 hook 循环 + 安全模式 12 方法 | 可选重构 |
| `PluginsWatchdog` | 每线程一次性 5s `ScheduledFuture` + `frozenExecutions` | 1s 全表扫描 | 我方 1s 扫描在 pip 安装时会误杀，需改 |
| `Plugin` 版本模型 | 删除 `minVersion`；`appVersion=">=12.10.6"`；`sdkVersion=">="+SDK_VERSION` | `minVersion="12.2.10"`；`"v"+SDK_VERSION` | 需要统一，否则新插件被判不兼容 |
| `xposed/*` | `XC_MethodReplacement` + priority | 同 | 语义一致 |

### 3.6 分期（约 8.5–9.5 人日）

| 阶段 | 内容 | 预估 |
|---|---|---|
| **B0** | `utils/SdkSignature.java`（新）+ `PythonPluginsEngine` Updater 段（`PythonSdkUpdateInfo.signature`、`newSdk.meta`、`isPendingUpdateVerified`），密钥换我方自签（与 `D:\tg_scraper` 审查流水线共用离线签名） | 0.5 天 |
| **B1** | `HookFilter` typeId 化、`PluginsController.executeGenericHook`、watchdog 改 per-thread 调度 | 1–1.5 天 |
| **B2** | pip 强化：`MarkerParser`/`ParsedVersion`/`VersionComparator` + wheel tag 解析 + `requires_python` + 冲突检测 | 2 天 |
| **B3** | SDK 能力：`classes.py` 装饰器族 + super 桥（复用 `ClassProxy.DexMakerHook`）、`log_utils`、`file_utils` 常量、`_plugin` 的 `get_strings/get_metainfo`；`v.txt` → `1.4.7.7` | 2–3 天 |
| **B4** | UI：翻译（`PluginSettingsTranslation`/`TranslatableText`/`PluginTranslateButton`）、`RequiredPlugin*`、`PythonSdkUpdateAlert`；`UnknownSources` 改为现有安装页加「来源未受信」行 | 2 天 |
| **B5** | 回归：`assemblePluginRelease` + `apkanalyzer` 重导类表核 `find_class`（`BANNED_RUNTIME` 5 个必挂） | 1 天 |

### 3.7 风险

- LSPlant 的 `invokeOriginalMethod` backup 被二次 hook 覆盖会递归（`PyMethodHook` 的 freeze 逻辑别改坏）；
- `PyObject` close 后抛 "closed" 异常，必须靠 `disabled` 标志兜住；
- 我方 1s watchdog 在 pip 安装/编译期会误杀插件 → P1 一并改成 per-thread 方案；
- pip 无 allowlist，插件可拉任意 PyPI 包，建议加域名/包名白名单；
- 加密 `.plugin` 目前只有异常没有密码 UI；
- `normal` flavor 的桩类需随新静态成员同步（否则 normal 包编译失败）；
- SDK 验签启用后，未签名的远端包会被拒 → 发布侧必须先改造，否则断更。

### 3.8 实施进展（canary）

| 提交 | 内容 |
|---|---|
| 07a7676aeb | 新增 `log_utils` SDK 模块（`Logger`/`LogLevel`/`LogRecord`/`get_logger`/`dump`/`timed`/handlers，logcat 分片与 `plugin.<id>` 标签），`BasePlugin.logger` 与卸载时 `_cleanup`，SDK 版本 1.4.6.7 → 1.4.7.7 |
| 45a15ce413 | pip 依赖解析：按 wheel tag（python/abi/platform）判定纯 Python 兼容性，按 PyPI `requires_python` 过滤（新增 `PluginDependencyPythonUnsupported` 文案，7 locale） |
| 83dd082a80 | 插件设置在线翻译：`PluginSettingsTranslation`（按插件单例、缓存 + pending/failed、目标语言变更失效、后台逐条翻译后回调）+ `TranslatableText`（渲染时解析译文）+ 设置页顶部「翻译设置 / 显示原文」开关（`PluginsSettingsTranslate`/`PluginsSettingsShowOriginal`，7 locale） |
| c8ee5df050 | SDK 更新验签：`utils/SdkSignature`（payload `extera-pysdk|<version>|<abi>|<sha256>`，ECDSA P-256/SHA-256，base64 DER 签名；`PUBLIC_KEY_BASE64` 为空即关闭校验）+ `PythonSdkUpdateInfo.signature` 解析（`signature=` 行）+ 下载落盘前校验 + `Tools/sign-plugin-sdk.ps1`（签名/导出公钥，已用 openssl + JVM 双向验证） |

已核对无需改动（与官方文档一致）：`extera_utils.classes` 的 `Base/java_subclass/joverride/joverload/jmethod/jMVELmethod/jMVELoverride/jclassbuilder/jfield/jgetmethod/jsetmethod/jconstructor/jpreconstructor`、`ui.settings` 的 `Header/Divider/Switch/Selector/Input/Text/EditText/Custom/SimpleSettingFactory`、`extera_utils.text_formatting.parse_text`（含 `TLEntityType`/`RawEntity`）、`file_utils.FilesController`（含 `FileInfo`/`ExtensionAlreadyRegistered` 等异常）、`ui.bulletin.BulletinHelper.show_error/show_info/...`、`base_plugin.hook_filters/fn_hook_filters`、`Plugin` 的 `app_version`/`min_version`/`sdk_version` 三套兼容判定、`PluginsWatchdog` 已是 per-thread + 5s 冻结（不是报告里的 1s 全表扫描）。

### 3.9 回归：Java 类面变化（B5）

本地 `assemblePluginDebug` 因 submodule 未初始化（`jni/td`、`third_party/absl`、`boringssl`、`wamr`、`boost-regex`、`libprisma`、`wallet-engine` 均为空目录）在 CMake configure 阶段失败，拿不到 dex；因此改用「pluginDebug 的 javac/Kotlin 产物类名」与 `D:\tg_scraper\app_classes_raw.txt`（12.10.1 plugin-release 基线）做集合比较，先回答「13.1.1 合并 + 本次改动是否删掉了插件可能引用的类」：

| 结果 | 数量 | 说明 |
|---|---|---|
| 基线类名（`org.telegram`/`com.exteragram`/`tw.nekomimi`/`xyz.nextalone`，去掉 R8 合成类） | 13896 | 12.10.1 release APK |
| 当前类名（pluginDebug 产物） | 14493 | 13.1.1 + 本次改动 |
| 消失 | 89 | 见下 |
| 新增 | 686 | 含本次新增 `WidePosts`、`TelegramAiReplacement`、`ai.network.backend.*`、`AiHistoryActivity`、`PluginSettingsTranslation`、`TranslatableText` 等 |

消失的 89 个拆解：**37 个是我改动导致匿名内部类编号变化**（`PluginSettingsActivity$18$1` 这类，插件不会引用）；**16 个是包移动/构建生成**（`org.telegram.messenger.CodeHighlighting*` → `org.telegram.utils.code.highlight.CodeHighlighting*`，`R`/`R$*` 由 aapt 生成不在 javac 产物里）；**其余约 36 个是上游 13.1.1 自身的删除/改名**：`org.telegram.ui.QrActivity*`、`org.telegram.messenger.voip.VoIPController*`、`DispatchQueueMainThreadSync*`、`DownloadController$DownloadingDocumentEntry`、`NotificationCenter$ObserversGroup$Observer`、`PasscodeView$FingerprintDialog`、`ProfileGiftsContainer$SelectGiftsBottomSheet`、`TL_bots$botVerification`（13.1.1 改名 `BotVerification`）等。**本次改动没有删除任何插件可引用的类。**

官方元数据规范（`/docs/plugin-class`）只定义 `__id__`/`__name__`/`__description__`/`__author__`/`__version__`/`__icon__`/`__app_version__`/`__sdk_version__`/`__requirements__`，**没有「依赖其它插件」的键**，所以 `RequiredPluginCell` 属于 extera 自家商店安装流，不是插件 API 的一部分，我方按需跳过。`__requirements__`、`__app_version__` 的运算符与 `__min_version__` 兼容（视作 `__app_version__ = ">=..."`）我方解析器均已支持。

权威重导仍需 CI 产物：`dev` 触发的 staging workflow 会产出 `plugin-staging` APK，届时用 `apkanalyzer dex packages --defined-only <apk> > D:\tg_scraper\app_classes_raw.txt` 再跑 `sync.ps1` 的 `final_list` 阶段核对 `BANNED_RUNTIME`（`extera_rpc`/`details_deeplink`/`altylib`/`reExtera`/`floating_rounds`）。

`SdkSignature` 已实现但**默认不启用**：`PUBLIC_KEY_BASE64` 留空时校验直接通过（行为与之前一致），发布侧用 `Tools/sign-plugin-sdk.ps1` 生成 `signature=` 行并填入公钥后即自动生效；密钥未就绪前不要填公钥，否则现有 SDK 更新通道会被全部拒绝。`RequiredPluginCell`/`RequiredPluginInfo` 依赖插件逐项安装（缺插件清单里「requires 插件」的元数据规范与下载源，无法凭猜测实现）；`PythonSdkUpdateAlert`（`PluginsInfoActivity` 已有完整 Python SDK 分区：自动更新/beta/检查/恢复/状态，弹窗冗余）；`HookFilter` 的 typeId 化与 `executeGenericHook` 重构（纯性能/维护）。

---

## 4. 建议排期与验收

| 顺序 | 内容 | 预估 | 依赖 |
|---|---|---|---|
| 1 | 插件 SDK 验签（B0） | 0.5 天 + 发布侧签名 | 需自建离线密钥 |
| 2 | AI 替换 Telegram AI（A3，复用现有 AiConfig/Client 即可落地） | ~2 天 | 无 |
| 3 | 宽版帖子 P0–P3（WidePosts + MessageObject + Cell 几何 + header） | 4–6 天 | 无 |
| 4 | 宽版帖子 P4–P6（评论接线 + 设置 + feed 收尾） | 1.5–2 天 | 3 |
| 5 | AI 数据/后端（A0+A1） | 3.5–4.5 天 | 无 |
| 6 | AI 响应面板（A2） | 2–2.5 天 | 5 |
| 7 | AI 端上模型（A4，需先定 GMS/AICore 策略） | 2.5–3 天 | 5 |
| 8 | AI 设置面 + 历史页 + 字符串（A5+A6） | 2–3 天 | 5 |
| 9 | 插件运行时对齐（B1+B2） | 3–3.5 天 | 无 |
| 10 | 插件 SDK 能力 + UI（B3+B4）+ 回归（B5） | 5–6 天 | 1,9 |
| 11 | 语音后处理（A7，可选） | 3–5 天 | Vosk 移植 |

每阶段验收：`./gradlew :TMessagesProj:compileNormalDebugKotlin :TMessagesProj:compileNormalDebugJavaWithJavac` 通过 + canary CI 绿 + 所有已翻译 locale 补齐 + 注释只写功能、不写来源（AGENTS.md 规范）；插件阶段另需 `assemblePluginRelease` + `apkanalyzer dex packages` 重导类表核 `find_class`。

---

## 5. 逆向产物

| 路径 | 内容 |
|---|---|
| `D:\AndroidProjects\extera-12.10.6-re\src\{classes..classes7}\sources` | jadx 全量 Java |
| `D:\AndroidProjects\extera-12.10.6-re\apktool` | 资源（strings/layout/drawable） |
| `D:\AndroidProjects\extera-12.10.6-re\_classes*.dis.txt` | dexdump 指令级转储（~480 MB，用于还原 jadx 失败的方法） |
| `D:\AndroidProjects\extera-12.10.6-re\report-feed-wide.md` | 宽版帖子完整逆向报告（469 行） |
| `D:\AndroidProjects\extera-12.10.6-re\report-ai.md` | AI 完整逆向报告（411 行：配置键表 / 后端 / UX / 字符串 / 分期 / 风险） |
| `D:\AndroidProjects\extera-12.10.6-re\report-plugins.md` | 插件系统完整逆向报告（397 行：类级 diff / hook 层 / pip / SDK / 分期） |
| `D:\AndroidProjects\extera-12.10.6-re\sdk_diff.txt`、`sdk_x/`、`ours-sdk/` | 插件 SDK 模块与 API 对照 |
---

## 6. 实施进展（canary）

| 提交 | 内容 |
|---|---|
| 37aea8b8ef | 移除「隐藏 AI 功能」开关（与 AI 服务替换互斥） |
| 49b684026f | Wide Posts 主体：工具类、MessageObject 宽度与分组钩子、Cell 几何/媒体/按钮抑制、feed helper、开关与设置 |
| 87626f8e57 | 宽版作者 header（内嵌作者名 + 抑制外部头像）与评论帖作者解析 |
| 1546493190 | 用自有 AI 服务接管 Telegram 编辑器/摘要/翻译（含防注入包装） |
| 6e2a543e6f | Backend 抽象（Backend/BackendRequest/BackendSink/RestBackend），响应面板改走 backend |
| 690900973 | AI 历史页（逐条查看 + 清空） |
| 65a943187 | 服务测试失败时定位到具体字段（400 模型 / 401,403 密钥 / 404 端点） |
| 2425d0db6f | 采样温度由 backend 缩放后下发 |
| 4e14cebdea | 无背景气泡（贴纸/圆视频）不参与宽版布局 |
| b768d4d910 | 宽版 header 作者头像绘制 + 点击打开作者资料 |
| a5970f2096 | AI 回答按 Markdown 解析为富草稿插入（不支持富消息时回落纯文本） |
| 72b0de9715 | Retry/Edit 仅在本次回答已存历史时才回滚该轮，避免误删旧轮 |
| 62c8abf262 | AI 设置里「消息历史」重名：开关改名为「保存历史」并带说明（新增 `AIChatSaveHistory`，7 locale） |
| df24ffb0a6 | 宽版帖不参与多选位移/不画多选圆圈/不为隐藏的分享按钮留命中区 |
| 7fc0609282 | WidePostsInFeed 默认开启（与 extera 默认一致） |
| 75d6cc7099 | 修正宽版气泡宽度公式（13.1.1 的 backgroundDrawableRight 是宽度而非右缘，原写法会逐次膨胀） |
| 98fe569c03 | 宽版宽度在 measureTime 之前写入，气泡/时间行/媒体按宽版口径测量 |

尚未实施（按计划保留）：

- 宽版：真机视觉验证（feed 已默认开启，如发现布局问题先关开关再修）；
- AI：面板内 Markdown 改用 RichMessageLayout.PreviewView 原生渲染（当前仍用 Markwon）、Ask More/切模型（面板已有模型选择器与 Edit，功能等价）、端上模型（ML Kit GenAI，依赖 GMS/AICore 决策）、Service id/kind 迁移（我方存储模型不需要）；
- 插件：按用户要求暂缓。