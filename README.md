# myDeepSeek

> 一个把对话留在本机的 Android DeepSeek 客户端。Jetpack Compose 原生实现，release 经 R8 压缩后约 4 MB。

**中文 | [English](README.en.md)**

![Version](https://img.shields.io/badge/version-1.2.0-blue)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)
![Platform](https://img.shields.io/badge/Android-8.0%2B%20%C2%B7%20API%2026-3ddc84)

**仓库**：<https://github.com/ZhuNemo/mydeepseek>

## 它是什么

myDeepSeek 是一个独立的 Android 聊天客户端：接任意 OpenAI 兼容端点（内置 DeepSeek 官方），
消息、图片、朗读全部留在设备上 —— 没有账号体系，对话内容不经过任何第三方服务器；
要跨设备，就把聊天记录同步到你自己的网盘。

三条设计原则：

| 原则 | 落到实处的样子 |
|---|---|
| **数据是你的** | 本地数据库与私有目录就是唯一事实。没有云账号，导出永远可用，卸载即彻底删除。 |
| **长会话必须能用** | 几百条消息、几十万字的会话，打开、滚动、切换分支都不该卡；超长气泡的点击不能失效。 |
| **细节贴近官方** | 图片独立成块而不是把气泡撑变形；历史按月归档；朗读可以用官方音色。 |

和"自己填个 API Key 的壳"相比，差别主要在这里：

- **同步到你自己的网盘**：不需要注册第三方账号。网盘上是一个会话一个文件、图片按内容哈希存放，
  只传变化过的那些，传一半断了下次接着传。
- **图片真的落盘**：拍照 / 相册进来的图先写进私有目录再入库，不会因为一行超过 Android
  `CursorWindow` 的 2MB 上限而让整个会话读不出来（这是原实现里一个会丢数据的坑）。
- **历史按月归档**：长期使用之后，九成会话不会全被压进同一个「更早」。
- **删除即回收**：删消息时被它独占的图片文件一并清掉，不会默默占着空间。

## 特性

**对话与模型**

- **多服务端**：任意 OpenAI 兼容端点，内置 DeepSeek 与 OpenCode Go 配置；DeepSeek 官方端点可走 **Anthropic 兼容**扩展通道（智能搜索 / 链接阅读 / 正文引用 / 时间地点提醒）。
- **流式输出**：SSE 逐字渲染，Markdown 实时渲染，定稿前后版式不跳。
- **思考区**：`reasoning_content` 折叠展示，只计真实思考时长。
- **继续生成**：被停止或被输出上限截断的回复可以接着写。
- **数学公式 / Mermaid**：Compose 原生渲染公式；Mermaid 随包离线渲染，可切回源码。
- **消息树**：`parentId` 组织分支，"重新生成"不删历史；可切换版本、会话间分叉。
- **上下文管理**：CJK 加权 token 估算、输入栏占用圆环、滚动压缩。
- **附件**：拍照 / 相册 / 文件三个入口，均无需额外权限；支持图片、纯文本、docx、PDF。
- **数据出口**：JSON 备份、ZIP 归档（备份 + 每个会话的 Markdown 文稿）、Markdown 导出；可导入本应用备份、Chatbox 备份、DeepSeek 官方导出。
- **外观 / 语言**：深色模式、主题色、字号倍率、中英双语。

**图片、归档与同步**

- **图片独立成块**：用户消息里的图片排在气泡**上方**，固定正方形预览（104dp、`Crop`、最多三张一排），
  点击整屏查看，长按出消息菜单；只发图片的消息不再画一个空气泡。
- **历史按月分组**：抽屉是「置顶 + 按月」，标题形如 `2026年11月`（英文 `November 2026`）。
- **WebDAV 同步**：见下文「云端同步（WebDAV）」。

## 快速开始

安装包名是 `com.zhunemo.mydeepseek`，与上游 Simple Chat（`com.simplechat.app`）**不同**：
两者可以同时装在一台设备上，互不覆盖，数据也各自独立。

### 环境要求

- **JDK 21**（Android Studio 自带的 JBR 21 正好；17 能编译，但跑不了单元测试 ——
  `highlights` 依赖是 Java 21 字节码）
- Android SDK platform 37、build-tools 37.0.0
- Gradle 由 wrapper 自动下载

### 本地构建

```bash
git clone https://github.com/ZhuNemo/mydeepseek.git
cd myDeepSeek
./gradlew assembleDebug          # 产物：app/build/outputs/apk/debug/
```

Windows（PowerShell）：

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
```

跑单元测试：

```bash
./gradlew testDebugUnitTest
```

## 用 GitHub Actions 构建 APK

仓库里已经放好 [`.github/workflows/android.yml`](.github/workflows/android.yml)：

- 触发条件：推到 `main`、开 PR、打 `v*` 标签、或手动 `Run workflow`
- 流程：装 JDK 17 + Android SDK → 还原签名密钥 → 单元测试 → `assembleRelease` → 上传 APK 产物
- 打 `v1.2.0` 这类标签时会自动创建 Release 并把 APK 挂上去

产物在 Actions 运行详情页底部的 **Artifacts** 里（`myDeepSeek-apk-<构建序号>`）。

> 流水线里的 SDK 版本（`platforms;android-37`、`build-tools;37.0.0`）必须和
> `app/build.gradle.kts` 的 `compileSdk` / `buildToolsVersion` 保持一致。升级 AGP 时两处一起改。

## 配置正式签名密钥

**为什么必须做**：Android 只允许「同一包名 + 同一签名」的 APK 覆盖安装。用 debug 签名发的包，
将来换成正式签名时用户必须卸载重装，**应用数据会一起消失**。所以第一次发布前就把密钥定下来，
并把它备份好 —— 密钥丢了就再也发不出同一个应用的更新。

### 1. 生成密钥（一次性，只做一次）

在任意目录执行（`keytool` 在 JDK 的 `bin` 下）：

```bash
keytool -genkeypair -v \
  -keystore mydeepseek-release.jks \
  -alias mydeepseek \
  -keyalg RSA -keysize 4096 -validity 10000
```

会依次问你：密钥库口令、姓名与组织（可随便填）、以及最后的确认。
记下 **keystore 口令**、**alias（`mydeepseek`）**、**key 口令**（通常与 keystore 口令相同）。

> 建议把 `mydeepseek-release.jks` 放到**仓库之外**的加密备份里（密码管理器 / 私有网盘），
> 并在密码管理器里同时记下三个口令。这个文件一旦丢失，无法补救。
>
> 另外：下面生成的 `keystore.b64` 是同一个文件的**文本形态**（只受口令保护），
> 同样不能提交、不能外传。贴进 GitHub Secret 之后，本地那份建议删掉。

### 2. 转成 base64（给 GitHub Secrets 用）

macOS / Linux：

```bash
base64 -i mydeepseek-release.jks | tr -d '\n' > keystore.b64
```

Windows PowerShell：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("mydeepseek-release.jks")) | Set-Content keystore.b64 -NoNewline
```

### 3. 写进 GitHub Secrets

仓库 → **Settings → Secrets and variables → Actions → New repository secret**，添加四个：

| Secret 名称 | 值 |
|---|---|
| `KEYSTORE_BASE64` | 上一步 `keystore.b64` 的**全部内容**（一长串，不要换行） |
| `KEYSTORE_PASSWORD` | keystore 口令 |
| `KEY_ALIAS` | `mydeepseek` |
| `KEY_PASSWORD` | key 口令 |

添加完之后，下一次构建会自动生成 `keystore/release.jks` 与 `keystore.properties`，用正式密钥签名。
**没配这四个 Secret 时流水线不会失败**，会打一个 debug 签名的 release 包并在日志里给出警告 ——
方便 fork 之后先跑通流程。

### 4. 本地构建正式包（可选）

在项目根目录建 `keystore.properties`（已在 `.gitignore` 里，不会入库）：

```properties
storeFile=keystore/mydeepseek-release.jks
storePassword=你的keystore口令
keyAlias=mydeepseek
keyPassword=你的key口令
```

把 `.jks` 放到 `keystore/` 目录下，然后：

```bash
./gradlew assembleRelease
```

### 5. 校验签名

```bash
# 用 Android SDK 的 apksigner
$ANDROID_HOME/build-tools/37.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk

# 或者直接看密钥库
keytool -list -v -keystore mydeepseek-release.jks -alias mydeepseek
```

两次输出的 SHA-256 指纹一致，就说明这个包确实由你的密钥签名。

## 云端同步（WebDAV）

设置 → 账户 → **云端同步**，填四项：

| 项 | 说明 |
|---|---|
| 服务器地址 | 例如 `https://dav.jianguoyun.com/dav/` |
| 账号 | 网盘账号 |
| 应用密码 | 网盘的「应用密码」，**不是**登录密码 |
| 远程文件 | 默认 `myDeepSeek/backup.json`；父目录不存在会自动创建 |

### 坚果云

1. 网页登录坚果云 → 右上角账户信息 → **安全选项** → 「第三方应用管理」→ 添加应用 → 得到一串应用密码
2. 服务器地址填 `https://dav.jianguoyun.com/dav/`
3. 账号填注册邮箱，密码填刚生成的应用密码
4. 回来点「测试连接」，通过后点「立即同步」

### Nextcloud / 群晖 / 其它

- Nextcloud：`https://<你的域名>/remote.php/dav/files/<用户名>/`
- 群晖 WebDAV Server：`https://<地址>:5006/`（依套件设置而定）
- 只要服务端支持 WebDAV + Basic 认证即可；客户端只发 `PUT` / `GET` / `MKCOL` / `PROPFIND`
- **只支持 `https://`**：Android 9 起默认禁止明文 HTTP，本应用没有开 `usesCleartextTraffic`。
  局域网自建（群晖、树莓派）请配好证书，或前面挂一层 https 反代。

### 网盘上的文件长什么样

```
myDeepSeek/
├─ meta.json                      格式 / 版本 / 预设（很小）
├─ conv/
│  ├─ 8f14e45f-….1700000000123.json   一个会话一个文件，文件名自带 updatedAt
│  └─ 3c9d1a2b-….1700000000456.json
└─ media/
   ├─ 3f2a…c1.jpg                 图片按内容哈希命名（sha256 前 32 位）
   └─ 8b9d…4e.png
```

两条设计都是为了"装得下、传得完、接得上"：

- **一个会话一个文件**：整库装进一个 JSON 时，历史一大单次 PUT 就是几十上百兆，
  传到一半被服务端 503 掐断，重试还得从零开始。现在单次请求只有几十 KB。
- **文件名里带 `updatedAt`**：改一次就换一个名字，于是"远端有没有这一版"用一个
  目录列举就能判断 —— **没变过的会话根本不传**，第二次同步通常只有几条。
- **图片不进 JSON**：一张 3MB 的照片 base64 之后是 4MB。现在 JSON 里只有文件名，
  字节单独上传；文件名就是内容哈希，所以"同名"即"同内容"，天然去重。

### 同步是怎么做的

1. `GET meta.json` + `PROPFIND conv/`（两个小请求），拿到远端的会话清单
2. 逐条比较 `updatedAt`：只下载比本地新的会话，每下一份就删旧版、导入新版（草稿保留）
3. 上传方向同理：只传"远端没有这一版"的会话，先补它的图片再传会话本身
4. 最后写 `meta.json`；过期的旧版本文件顺手删掉，远端不会越积越多

**断了能续**：已经传上去的会话下次会被自动跳过；没有任何一步需要"从头再来"。
内存峰值只跟**最大的那一个会话**有关，与库有多大无关。

### 首次同步被网盘限流了怎么办（可选）

坚果云这类服务端限的是**单位时间请求数**。首次同步要把整个库传一遍 —— 几百个会话、
每张图一个请求 —— 很容易被判定为异常流量，返回 `503` 或者干脆断连。
应用里已经做了两道防护（请求间隔 120ms + 429/503/509 指数退避重试），但
**历史特别大的话，最省事的办法是让电脑帮你传**。

网盘上那套东西是纯静态文件，用网盘客户端拖上去**不经过 API，也就不会被限流**：

1. 在手机上「设置 → 数据 → 导出所有数据」，把备份文件拷到电脑
2. 转换格式（只依赖 Python 标准库）：

```bash
python tools/seed_webdav.py 备份.zip -o myDeepSeek
```

```
myDeepSeek/
├─ meta.json
├─ conv/<id>.<updatedAt>.json
└─ media/<sha256前32位>.jpg
```

3. 把这个 `myDeepSeek` 整个目录上传到网盘的「远程目录」位置（覆盖旧的）
4. 回 app 点「立即同步」—— 远端已经是最新版本，只会发几个请求就结束

> 转换脚本和应用共享同一套命名规则（文件夹名 = `id.updatedAt`，图片名 = 内容哈希），
> 所以两边对"这是不是同一版"的判断完全一致。`SyncPayloadTest` 里有一条测试专门钉住这个契约。

### 限制（务必知道）

- **删除不传播**：A 设备删掉的会话，会在 B 设备下次同步时被重新带回来（除非两台都删过）。
  做对需要墓碑记录，代价是给消息表加一套"已删除但保留"的状态，对个人使用不划算。
- **删除也不回收网盘上的图片**：图片是内容寻址的独立文件，删会话不会动 `media/`。
  好处是不会误删别的会话还在用的图；代价是网盘会留一些孤儿文件（每张几 MB 以内），
  需要时手动清理即可。会话本身不会积压 —— 上传新版本时会删掉旧的同名文件。
- **首次同步仍然要传一遍全库**：只是被切成几百个小请求，慢但不至于失败；
  之后每次只有变化的那几条。
- **原件缺失的图片不会同步**：如果本地图片文件被清掉（清应用数据、换机没带文件），
  那条附件会以"无图"的形式同步过去，不会把坏数据传上去。
- **同一时刻只跑一次**：自动同步与手动点击会互斥，不会并发写。
- **网盘上的是明文**：`sync.jsonl` 是可直接读的 JSON，图片也是原图。如果介意，
  请只用自己信任的网盘，或不要开启自动同步。

## 数据与隐私

- API Key 与 WebDAV 密码以 Android Keystore 的 AES-256-GCM 保存密文，密钥按 UID 隔离，仓库不含任何密钥。
- 对话请求从设备直连所配置的端点，无第三方中转。
- 同步只在你配置的网盘与设备之间发生，本应用不提供任何中转服务。
- 数据库迁移使用显式 `Migration`，不会 `fallbackToDestructiveMigration` —— 升级不会静默清库。

## 技术栈

| 项 | 版本 |
|---|---|
| Gradle | 9.7.1 |
| AGP | 9.4.1（内置 Kotlin） |
| Kotlin | 2.4.10 |
| Compose BOM | 2026.09.00 |
| compileSdk / targetSdk / minSdk | 37 / 37 / 26 |
| Room / OkHttp / kotlinx.serialization | 2.8.5 / 5.5.0 / 1.11.0 |

完整依赖见 [`gradle/libs.versions.toml`](gradle/libs.versions.toml)。

## 项目结构

```text
app/src/main/java/com/simplechat/app/
├─ data/          仓库层：会话粘合、备份导入导出、附件处理、Keystore 加解密、token 估算
│  ├─ import/     Chatbox / DeepSeek 备份、docx 抽文本、图片尺寸
│  └─ sync/       WebDAV 同步：客户端（PUT/GET/MKCOL/PROPFIND）+ 合并决策（纯函数，有单测）
├─ db/            Room：实体、DAO、显式迁移、消息树纯函数
├─ net/           DTO、SSE 客户端与行解析、Provider 与模型能力表
└─ ui/
   ├─ chat/       对话页：消息列表（含图片预览）、输入栏、各面板、编辑弹窗
   ├─ history/    会话抽屉（按月分组）与全文搜索
   ├─ settings/   设置页（API 配置、数据管理、云端同步、外观、关于）
   ├─ markdown/   Markdown / 公式 / Mermaid 渲染
   ├─ common/     控件与视觉 token
   └─ theme/      颜色、字体、形状
```

## 已知限制与后续

- 同步不支持删除传播、也不回收网盘上的孤儿图片（见上）
- 「从设置返回对话」不会自动触发同步；只有手动按钮与启动时自动同步
- 备份导入的「覆盖同名」模式不会回收被替换会话的图片文件（已知磁盘泄漏，量很小）
- 「导出所有数据 / 导入」仍是**整份 JSON**（带 base64 图片），几个 GB 的库会有 OOM 风险；
  同步已经改流式，导出还没（那是另一条链，改动面更大）
- 数据库 `conversations.provider/model` 是只写不读的死列，删列会触发外键级联风险，暂留

## 代码审查

见 [`CODE_REVIEW.md`](CODE_REVIEW.md)：按严重程度列出发现的问题、已修复项与保留的风险点。

## 出处与许可

以 MIT 许可证发布（见 [LICENSE](LICENSE)），保留原作者版权声明。

这个应用的界面骨架、消息树、Markdown 渲染与协议实现来自 **Simple Chat**：

- 参考实现：<https://github.com/Pokanshuu/Simple-Chat>

对话框、同步、图标与本文档是本仓库相对它做的改动，逐条记录在
[`CODE_REVIEW.md`](CODE_REVIEW.md)。本仓库是独立维护的版本，不是上游官方发行版 ——
问题请提到[本仓库](https://github.com/ZhuNemo/mydeepseek/issues)。
