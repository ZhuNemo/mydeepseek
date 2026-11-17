# 代码审查记录（v1.2.0）

审查对象：从 SimpleChat 1.1.3 fork 出来的本仓库。范围是两个层面：

1. **功能改动会不会踩到原有设计的坑**（图片外移、按月分组、同步）
2. **原代码本身的缺陷**，尤其是会丢数据 / 泄漏 / 卡主线程的那类

> ⚠️ 本次是**静态审查**。当前开发环境里没有 JDK 与 Android SDK，`./gradlew` 没能跑起来，
> 所以下面每一条都是读代码得出的结论，不是编译器或测试给的。CI 上的第一次构建
> （`.github/workflows/android.yml`，含新增的 `SyncMergePlanTest`）才是这批改动的验收。
> 注：目前已跑CI，能构建，无语法问题

## 结论

原代码整体质量偏高：迁移全是显式 `Migration`、SSE 取消有明确的内存模型、
消息树用纯函数表达、注释把"为什么"写得很清楚。但有一处**能真正丢数据**的缺陷，
以及几处资源泄漏与主线程 IO。合计修复 6 处。

| # | 严重度 | 问题 | 状态 |
|---|---|---|---|
| 1 | 高 | 用户发送的图片以 base64 内联进库，超过 `CursorWindow` 2MB 上限后整条会话读不出来，图片被兜底逻辑截断 | 已修 |
| 2 | 中 | 删除单条消息不回收它独占的图片文件与引用登记（磁盘只增不减） | 已修 |
| 3 | 中 | `SettingsStore.flow` 在收集线程（Compose 组合线程）里做 Keystore 解密 | 已修 |
| 4 | 中低 | 全屏看图按 `1080.dp` 解码：3 倍屏上是 3240px，单张位图 ~40MB | 已修 |
| 5 | 低 | 多图查看的起始页只按「文件名 + data」定位，落盘图会翻错页 | 已修 |
| 6 | 低 | 用户气泡的手势挂在气泡本身，图片移到气泡外后纯图片消息失去编辑 / 删除入口 | 已修（随重构） |
| 7 | 中 | 「覆盖同名」导入不回收被替换会话的图片文件 | 未修，已记录 |
| 8 | — | 数据库里 `conversations.provider/model` 是只写不读的死列 | 保留（删列有级联风险） |
| 9 | 高 | 朗读初始化调用 `TextToSpeech.setVoice(null)`，部分 ROM（华为）直接 NPE，**一开就闪退** | 已修 |

---

## 1（高）发送的图片没有落盘

**位置**：`data/ChatRepository.kt` · `addSibling()`，以及 `data/Attachment.kt` 的注释。

**现象**：`Attachment` 有两条路存图片字节 —— 内联 `data`（base64）或落盘 `path`。
**导入**路径（`writeConversation` → `withOffloadedImages()`）一直会落盘，
但**发送**路径直接 `messages.upsert(newMessageEntity(...))`，拍照 / 相册来的图片原样内联。

**为什么后果是"丢数据"而不是"慢"**：一条消息的附件列和正文在同一行。3MB 的照片
base64 之后约 4MB，超过了 Android `CursorWindow` 的 2MB 上限 →
读取该会话抛 `SQLiteBlobTooBigException` → `loadMessagesSafely` 回落到
`getAllForCapped(400_000)`，把 `attachments` 截断 → 附件解析失败 → 按约定当"没有附件"。
**用户看到的是：图片没了，还找不回来**（原图已不在库里）。

原代码其实已经知道这个机制（`AttachmentRefEntity` 的类注释里写了同一个异常），
只是漏在了发送路径上。

**修复**：`addSibling()` 统一走 `.withOffloadedImages()`，并把落盘路径登记进
`attachment_refs`（删除会话时据此清文件）。编辑重发、重新生成共用 `addSibling`，
所以一处修好，三条发送链路都覆盖。

## 2（中）删消息不回收图片

**位置**：`data/ChatRepository.kt` · `deleteMessage()`。

删除会话时会按引用表清文件，但**删单条消息**只 `deleteByIds` —— 文件留在
`filesDir/attachments/`，`attachment_refs` 里也还留着登记。于是这些字节永远不会被回收，
而且用户完全看不到这部分占用（它不出现在任何"对话"里）。

**修复**：删消息时收集该子树上所有 `path`，先按路径清登记（新增
`AttachmentRefDao.clearPaths`），**再看引用表里是否还有别的消息在用**，没有才删文件。
判据用引用表而不是"这条消息用过"，是为了不误删被编辑重发复制进另一条消息的同一张图。

## 3（中）设置流在组合线程里解密

**位置**：`data/SettingsStore.kt` · `flow`。

`mapped` 里调 `SecretBox.decrypt(...)`，而 `flow` 被 `LaunchedEffect { store.flow.collect {} }`
收集 —— **在 Compose 的组合线程（主线程）上**。Keystore 调用要过 Binder，
是毫秒级但**会在快速连续的配置变更里累积**（拖动字号滑块时每一帧都可能触发一次）。

**修复**：`.flowOn(Dispatchers.IO)`。顺带把 WebDAV 密码也纳入同一套加密存储，
两个密文的解密都不再落在主线程。

> 附带观察：`SecretBox` 的缓存只记**一条**密文。现在有两个密钥（API Key + WebDAV 密码），
> 每次配置变化会 miss 一次、多解一次。已经不在主线程上，暂时不值得为它改成多槽缓存。

## 4（中低）全屏看图的内存放大

**位置**：`ui/chat/Attachments.kt` · `AttachmentViewerDialog`。

```kotlin
// 修改前
val target = with(density) { 1080.dp.roundToPx() }   // 3 倍屏 = 3240px
```

注释写的是"屏幕最长边的像素数"，实现却是 **1080dp** —— 单位错了。3240×3240 的 ARGB 位图
约 40MB，`HorizontalPager` 会同时组合左右邻页，中端机连续翻几张就有 OOM 风险。

**修复**：取 `LocalConfiguration` 的屏幕长边（dp）再转 px，回到"刚好填满屏幕"的量级。

## 5（低）多图查看的起始页

`pages.indexOfFirst { it.name == initial.name && it.data == initial.data }` ——
落盘附件的 `data` 是空串，于是两张都叫 `image.jpg` 的落盘图会被判成同一张，
点第二张却从第一张开始翻。**修复**：把 `path` 纳入比较。

## 6（低）纯图片消息失去菜单入口

这是**改动引入的**（不是原有缺陷）：图片从气泡里搬到气泡外之后，如果消息只有图片、
没有文字，气泡本身不存在，而手势原先挂在气泡上 —— 用户既点不到"编辑"，也长按不出菜单，
等于删不掉这条消息。

**修复**：手势（点击 = 编辑，长按 = 菜单）上移到"内容簇"这一层（气泡 + 图片 + 中间的空隙），
宽度仍是 `widthIn(max = 0.82 × 屏宽)` 且右对齐，所以点左侧空白不会误触。
图片自己保留"点击看大图"，长按显式转给消息菜单。

## 7（中，未修）「覆盖同名」导入的磁盘泄漏

`writeConversation()` 在 `ImportMode.REPLACE_SAME` 下走 `deleteConversationRecords(clash)`，
而那个函数只删会话行与草稿 —— **不删附件文件、不清 `attachment_refs`**。
反复用「覆盖同名」恢复同一份备份，旧图会一直堆在私有目录里。

没修的原因：这条路径在导入事务里，而回收文件要走 `deleteConversations()`
（自带 `withTransaction`）。嵌套事务在这套 Room 版本上是否安全，需要真机验证；
为一个"量很小、且只在特定导入模式下发生"的泄漏去动导入事务，风险大于收益。
真要修，正确做法是把"回收文件"做成**事务提交之后**的统一步骤（收集 → 提交 → 删文件）。

## 8（保留）只写不读的死列

`ConversationEntity.provider/model` 建会话时写入、备份也带上，但 `openConversation`
从不读它们（运行时用 DataStore 里的全局配置）。原作者的注释解释了为什么删不掉：
`messages.conversationId` 有 `ON DELETE CASCADE`，而 SQLite 删表/删列的实现会连带级联，
`PRAGMA foreign_keys` 又不能改（迁移跑在事务里）。**认同这个判断，保留。**

---

## 9（高）朗读初始化闪退（真机抓到的）

**位置**：`ui/chat/SpeechOutput.kt` · `initialize()` / `applyVoice()`。

**现象**（华为 HarmonyOS 真机 logcat，进应用即崩）：

```
java.lang.NullPointerException: Attempt to invoke virtual method
'java.lang.String android.speech.tts.Voice.getName()' on a null object reference
    at android.speech.tts.TextToSpeech.lambda$setVoice$13$TextToSpeech(TextToSpeech.java:1696)
    at tt2.onInit(...)          ← 我们的 onInit 回调
```

**根因**：初始化回调里写了 `engine?.voice = null`，本意是"先回到系统默认音色再按偏好挑"。
`TextToSpeech.setVoice(null)` 在 AOSP 的多数实现里确实只是被忽略，但有些 ROM
在 `runAction` 内部**无条件** `voice.getName()` —— 于是这一句就是一次启动崩溃。
`applyVoice()` 的 else 分支里有同样一句。

**为什么本地/模拟器发现不了**：Pixel / 模拟器上 `setVoice(null)` 不抛；只有这类 ROM 才会。
而它在 **onInit 回调**里，回调跑在主线程 Handler 上 → 异常直接终结进程，连错误提示都来不及给。

**修复**：
- 删掉两处 `setVoice(null)`。回到默认音色的正确做法是**只设语言**
  （`tts.language = preferredLocale()`），由引擎挑该语言的默认音色。
- 两处 `language` / `voice` 赋值都包了 `runCatching`：换引擎、音色被删、ROM 差异
  都不该让进程挂掉 —— 朗读是可选功能，最坏结果应该是"点了没声"。

## 10（高，v1.2.1）同步 OOM：载荷格式重做

**现象**：真机第一次同步直接 `OutOfMemoryError`。

**根因**（三处叠加，都是"整份在内存里"）：

1. `exportBackup()` 生成一个几兆～上百兆的 `String`；
2. 图片以 base64 内联在 `messages.attachments` 里，一张 3MB 的照片 = 4MB 文本；
3. 远端下载是 `response.body.bytes()`，本地导出又要建第二个完整副本（一次同步最多同时存在
   5~6 份数据：下载字节 → 解析后的对象 → 本地字符串 → 解析后的对象 → 合并结果 → 上传字节）。

**改法**（`data/sync/`）：

| 以前 | 现在 |
|---|---|
| 一个大 JSON（`BackupFile`） | **JSONL**：首行 header（含预设），之后一行一个会话 |
| 图片 base64 内联 | 图片按 **sha256 前 32 位**命名，单独存 `media/`；JSON 只留文件名 |
| `bytes()` 下载 → `ByteArray` | `downloadToFile()` 边读边写临时文件 |
| 上传 `ByteArray.toRequestBody()` | `File.asRequestBody()`，OkHttp 自己流式发 |
| `messages.getAllRaw()` 全量读 | `stamps()` 只取 id+时间戳，再逐个 `getAllFor(id)` |
| 合并需要两份完整对象 | 逐行读 → 逐条判定 → 写成 incoming 临时文件 → `Sequence` 流式导入 |

**顺带**：内容寻址让"同一张图"在两端名字一致（不重复传、天然去重）；
单行坏掉只跳过那一行并计数，不会让整次同步失败。

**第二轮（真机 503）**：JSONL 解决了内存，但**整库还是一个文件** —— 历史一大，
单次 PUT 几十上百兆，传到一半被服务端 503 掐断，而且重试从头再来。于是再拆一层：

| 第二轮之前 | 现在 |
|---|---|
| 一个 `sync.jsonl`（几十 MB） | **一会话一文件**：`conv/<id>.<updatedAt>.json` |
| 每次同步重传整份 | 远端已有同名文件就跳过（**增量**），传一半断了下次续上 |
| 需要下载全量再逐行比 | `PROPFIND conv/` 拿清单，只下载更新的那几条 |
| 旧版本无从清理 | 传新版本后 `DELETE` 旧文件，远端不积压 |

文件名自带 `updatedAt` 是关键：无需清单文件，"名字对不对得上"就是"版本对不对得上"。
`parseConversationName` 还额外拒绝带斜杠/`.`/`..` 的名字 —— 它会被拼进远端路径，必须防穿越。

**新增测试**：`SyncPayloadTest`（12 条）钉住"一行一个会话 / 图片只留文件名 / 坏行隔离 /
旧格式拒绝 / 文件名解析与拒绝 / 路径拼装"，以及 `shouldPull` / `newerPresets` 的边界
（时间相等保留本地）。

## 11（高，v1.2.3）首次全量同步被网盘限流（503）

**现象**：会话拆成一文件后不再 503 于体积，但图片一多又挂 —— 网盘对**单位时间请求数**
有阈值，同步天生是密集请求（几百个会话 + 每张图一个请求）。

**三处放大请求量**：

1. 每张图上传前先 `HEAD` 判存在 → 请求数直接翻倍（500 张图 = 1000 个请求）；
2. 请求之间零间隔，形成突发；
3. `503` 被翻译成"服务器返回 HTTP 503"**直接判死整次同步** —— 用户重试，等于再次冲击。

**改法**：

| 以前 | 现在 |
|---|---|
| 每张图一次 `HEAD` | 进上传阶段先 `PROPFIND media/` **一次**，之后全在内存里比 |
| 请求零间隔 | `call()` 里统一节流：两次请求至少隔 120ms（≈ 每分钟 500 次） |
| 503 → 整次同步失败 | 429/503/509 **指数退避重试 5 次**（1.2s→2.4s→…封顶 60s，尊重 `Retry-After`）|
| — | 网络 `IOException` 同样重试（所有方法都幂等，重试安全）|

顺带补了一条给"首次全量"的旁路：`tools/seed_webdav.py` 把备份转成网盘目录结构，
用户在电脑上手动拖上去（**零 API 请求**），app 之后只做增量。

**为什么不是"把图片也打包成 zip"**：那会毁掉内容寻址 —— 压缩包内容依赖批次组成，
换台设备就算不出同样的名字，去重和"远端有没有这张图"的判断全部失效。
节流 + 一次列目录已经够用，不值得为省几十个请求换掉整个去重模型。

**测试**：`SyncPayloadTest` 增加一条"脚本产物能被原样读回"的契约测试
（紧凑 JSON、图片认成已落盘、`data` 必须为空串），钉住脚本与 app 的命名/字段约定。

## 其它记录（不改，但值得知道）

- **`AppContainer.openCodeSessionId`** 每次启动随机生成，而它被当作 OpenCode Go 的
  `x-opencode-session` 发出去。作者注释里已标为待办；对普通对话无影响。
- **`App.onCreate` 的明文 Key 迁移**是 fire-and-forget 协程，进程被杀可能不完成。
  好在该操作幂等，下次启动会再来一次。
- **`exportBackup()` / 导入**仍会把整份备份建在内存里（含图片 base64）：几百个带图会话时
  有 OOM 风险。**同步已经在 v1.2.1 改成流式**（见第 10 条），但「导出所有数据」这条链
  没动 —— 它要产出"一份能被整份导入的文件"，格式本身就是大 JSON，改流式得连读写协议一起换。
  已在 README 的「限制」里写明。
- **`HistoryViewModel.query` 与 `queryFlow`** 是同一份状态的两份拷贝，靠 `updateQuery` 手动同步。
  当前只有一个写入口，没有实际漂移；属于"能接受但脆"的写法。
- **月份分组的"当前"语义**：分组只在 `conversations` 变化时重算，跨月时标题不会自己刷新。
  因为标题是绝对月份（`2026年11月`）而不是"本月 / 上月"，跨月**不会出现错误标签**，
  最多是列表里多出一组新月份 —— 下次数据变化时自动归位。

## 值得保留的好设计

- SSE 的取消模型（`invokeOnCompletion { call.cancel() }` + `emittedAny` 决定能否重发）——
  比常见的"包一层 Flow 再 try/catch"正确得多。
- `CursorWindow` 的两处防御（`getAllForCapped` 兜底、`attachment_refs` 绕开大列）。
- 消息树全部是纯函数（`MessageTree`），因此分支 / 版本 / 删除都能脱离数据库单测。
- `AppTextException` + `Res`：数据层不碰资源、单测不需要真机，文案又不丢。
- 长消息不挂 `clip` / `graphicsLayer` 的那条判据（超长节点上图形层会失效、命中测试只认到某个高度）——
  注释里连实测数据都记了，属于"踩过一次就再也不用踩"的知识。

## 本次新增代码的验证点

- `data/sync/SyncManager.kt` 的合并决策抽成了纯函数 `planMerge`，
  单测在 `app/src/test/.../sync/SyncMergePlanTest.kt`：远端更新 / 更旧 / 时间相等 /
  本地独有 / 预设各自判定 / 无变化。
- `WebDavClient` 只做 `PUT` / `GET` / `MKCOL` / `PROPFIND`，没有单元测试（要真服务端）；
  首次接入时请先点「测试连接」，再观察同步结果与网盘里的文件。
