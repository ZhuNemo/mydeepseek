package com.simplechat.app.data.sync

import android.content.Context
import com.simplechat.app.R
import com.simplechat.app.data.AttachmentFiles
import com.simplechat.app.data.BackupConversation
import com.simplechat.app.data.ChatRepository
import com.simplechat.app.data.Res
import com.simplechat.app.data.SettingsStore
import com.simplechat.app.data.StoredSettings
import com.simplechat.app.data.userMessage
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** 同步进行到哪一步（界面据此给一句话 + 一个转圈）。 */
enum class SyncStage { DOWNLOAD, MERGE, UPLOAD }

/**
 * 同步的对外状态。
 *
 * 刻意做成**一个不可变快照**而不是几个独立的 flag：界面上「正在上传」和
 * 「上次同步失败」同时成立，只会让人以为还在跑。
 */
data class SyncState(
    val running: Boolean = false,
    val stage: SyncStage? = null,
    /** 上一次结果（成功 / 失败都写在这里）。null = 本次启动还没同步过。 */
    val message: String? = null,
    val failed: Boolean = false,
    /**
     * 上一次同步**从远端拉下来**（新增或覆盖）的会话数。
     *
     * 界面据此决定要不要把当前打开的会话从库里重读一遍：
     * 那条会话可能刚被远端版本整条换掉，内存里的消息树已经过期。
     */
    val pulled: Int = 0,
    /** 当前阶段的进度（已处理 / 总数）。总数 0 表示没有可数的进度。 */
    val done: Int = 0,
    val total: Int = 0,
)

/**
 * WebDAV 同步（**逐会话增量**）。
 *
 * ### 网盘上是"一个会话一个文件"
 *
 * ```
 * <远程目录>/
 * ├─ meta.json                    预设与版本（很小）
 * ├─ conv/<id>.<updatedAt>.json    会话，**文件名自带版本**
 * └─ media/<sha256>.jpg            图片，内容寻址
 * ```
 *
 * 之前是"整库一个大文件"：历史一大，单次 PUT 就是几十上百兆，实测传到一半被
 * 服务端 503 掐断，重试又从头再来。拆开之后每次 PUT 只有几十 KB，而且：
 *
 * - **只传变过的会话**：远端已有同名文件（id + updatedAt 都对得上）就跳过；
 * - **断了能续**：已传上去的下次自动跳过；
 * - **不需要清单**：`PROPFIND` 列一下 `conv/` 就是远端状态；
 * - **内存有界**：一次只读/写/传一个会话。
 *
 * ### 冲突策略：按会话 last-write-wins
 *
 * 以 `conversations.updatedAt` 为准：远端更新就覆盖本地这一条，本地更新就留着，
 * 然后把本地这一版传上去（旧版本的远端文件顺手删掉）。
 * 不做逐条消息 diff —— 消息树一旦逐条合并，很容易拼出跨设备的断链。
 *
 * 已知限制（见 README）：**删除不传播**。A 设备删掉的会话，B 设备下次同步会把它
 * 再传回网盘。
 */
class SyncManager(
    private val context: Context,
    private val repository: ChatRepository,
    private val settings: SettingsStore,
    private val client: WebDavClient = WebDavClient(),
) {

    /** 自己的作用域：同步要能活过设置页的销毁（用户切走再回来，状态还在）。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /**
     * 同一时刻只跑一次。
     *
     * 自动同步与手动点击可能同时发生；两路并发会把同一份远端读两遍、
     * 再互相覆盖着写回去，浪费流量还可能丢合并结果。
     */
    private val running = Mutex()

    /**
     * 本次运行里已经确认过（传过、或远端已有）的图片名。
     *
     * 只为了避免同一张被多条会话引用的图重复发 HEAD；每次同步开始时清空 ——
     * 上一次可能传到一半失败，留着会让它永远被跳过。
     */
    private val uploadedMedia = mutableSetOf<String>()

    /** 手动「立即同步」。 */
    fun startSync() {
        scope.launch { sync() }
    }

    /**
     * 启动时的自动同步：**只在用户打开开关且配置齐全时**触发一次。
     *
     * 失败静默留在状态里（设置页看得到），不弹窗 —— 启动路径上的任何弹窗
     * 都是打扰，而且这时候用户多半正要开始写字。
     */
    fun syncOnLaunchIfEnabled() {
        scope.launch {
            val stored = settings.current()
            if (stored.davAutoSync && stored.syncConfigured) sync()
        }
    }

    /** 试连。返回一句可直接显示的话。 */
    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        val stored = settings.current()
        if (!stored.syncConfigured) return@withContext Res.get(R.string.sync_need_config)
        runCatching { client.probe(stored.davConfig()) }.fold(
            onSuccess = { Res.get(R.string.sync_test_ok) },
            onFailure = { it.userMessage() },
        )
    }

    // ── 主流程 ───────────────────────────────────────────

    /**
     * 从云端删掉这些会话的**全部版本**。
     *
     * 本地删了会话却不动远端，下次同步会把它当成"远端更新"重新拉回来 ——
     * 用户看到的就是「删了又自己回来了」。所以删除动作要能延伸到网盘。
     *
     * 三个设计点：
     *
     * - **按 id 删所有版本**：远端可能因为并发/失败留下 `<id>.<旧时间>.json`，
     *   只删"最新那版"会留残骸，下次照样被认出来。
     * - **与同步互斥**：两边同时动同一批文件时，会出现"同步刚传上去、这边又删掉"
     *   或者相反。用同一把 [running] 锁串起来。
     * - **失败静默**：删本地是主操作，云端删不掉最多是下次又冒出来；
     *   不该让一次网络故障把用户点了的"删除"整个回滚。
     */
    suspend fun deleteRemoteConversations(ids: Set<String>) {
        if (ids.isEmpty()) return
        withContext(Dispatchers.IO) {
            val stored = settingsStore.current()
            if (!stored.syncConfigured) return@withContext
            val config = stored.davConfig()
            val root = stored.davPath
            running.withLock {
                runCatching {
                    client.listNames(config, SyncLayout.conversationDir(root))
                        .mapNotNull { SyncLayout.parseConversationName(it) }
                        .filter { it.id in ids }
                        .forEach { client.delete(config, SyncLayout.conversationPath(root, it.name)) }
                }
            }
        }
    }

    private suspend fun sync() {
        if (!running.tryLock()) return
        val work = File(context.cacheDir, SyncWorkDir).apply { mkdirs() }
        uploadedMedia.clear()
        val conversationFile = File(work, "conversation.json")
        val incoming = File(work, "incoming.jsonl")
        val metaFile = File(work, "meta.json")

        try {
            val stored = settings.current()
            if (!stored.syncConfigured) {
                _state.value = SyncState(message = Res.get(R.string.sync_need_config), failed = true)
                return
            }
            val config = stored.davConfig()
            val root = config.path

            // ── 1. 远端状态：meta（预设）+ conv/ 目录清单 ──
            _state.value = SyncState(running = true, stage = SyncStage.DOWNLOAD)
            val meta = if (client.downloadToFile(config, SyncLayout.metaPath(root), metaFile)) {
                readMeta(metaFile)
            } else {
                null
            }
            val remoteById = client.listNames(config, SyncLayout.conversationDir(root))
                .mapNotNull(SyncLayout::parseConversationName)
                .groupBy { it.id }
                .mapValues { (_, versions) -> versions.maxByOrNull { it.updatedAt }!! }

            // ── 2. 拉取：只下比本地新的会话 ──
            val localStamps = repository.syncStamps().associate { it.id to it.updatedAt }
            val toPull = remoteById.values.filter { shouldPull(it.updatedAt, localStamps[it.id]) }
            var pulled = 0
            val overwriting = mutableListOf<String>()

            if (toPull.isNotEmpty()) {
                // 下一个、写一行到临时文件、再下一个：磁盘占位，内存只过一条
                SyncPayloadWriter(incoming.bufferedWriter()).use { writer ->
                    for ((index, entry) in toPull.withIndex()) {
                        _state.value = SyncState(
                            running = true,
                            stage = SyncStage.MERGE,
                            done = index,
                            total = toPull.size,
                        )
                        val path = SyncLayout.conversationPath(root, entry.name)
                        if (!client.downloadToFile(config, path, conversationFile)) continue
                        val conversation = readConversation(conversationFile) ?: continue
                        if (localStamps.containsKey(conversation.id)) overwriting += conversation.id
                        writer.writeConversation(adoptMedia(config, conversation))
                        pulled++
                    }
                }
                if (pulled > 0) {
                    keepDrafts(overwriting)
                    repository.importSyncStream(
                        conversations = SyncPayload.conversations(incoming),
                        presetsToWrite = newerPresets(repository.syncPresets(), meta?.presets.orEmpty()),
                    )
                }
            }

            // ── 3. 上传：只传"远端还没有这一版"的会话 ──
            //
            // 远端 `media/` 有哪些图只查**一次**（PROPFIND 列目录）。
            // 之前是每张图发一次 HEAD —— 500 张图 = 500 个多余请求，
            // 正好撞在网盘的"请求频率"风控上。
            var remoteMedia: Set<String>? = null
            val afterPull = repository.syncStamps()
            var uploadedConversations = 0
            var uploadedImages = 0

            for ((index, stamp) in afterPull.withIndex()) {
                _state.value = SyncState(
                    running = true,
                    stage = SyncStage.UPLOAD,
                    done = index,
                    total = afterPull.size,
                )
                val name = SyncLayout.conversationName(stamp.id, stamp.updatedAt)
                val existing = remoteById[stamp.id]
                if (existing?.name == name) continue // 远端已经是这一版

                val conversation = repository.backupConversationForSync(stamp.id) ?: continue
                val (externalized, mediaNames) = externalizeMedia(conversation)
                if (mediaNames.isNotEmpty() && remoteMedia == null) {
                    remoteMedia = runCatching {
                        client.listNames(config, SyncLayout.mediaDir(root)).toSet()
                    }.getOrDefault(emptySet())
                }
                // 图片先走：不然另一端可能先读到"引用了还不存在的图片"的会话
                uploadedImages += uploadMedia(config, mediaNames.toList(), remoteMedia.orEmpty())

                writeConversation(conversationFile, externalized)
                client.uploadFile(
                    config = config,
                    remotePath = SyncLayout.conversationPath(root, name),
                    file = conversationFile,
                    mediaType = WebDavClient.SyncPayloadMedia,
                )
                uploadedConversations++

                // 旧版本文件顺手删掉，免得远端越积越多
                if (existing != null) {
                    runCatching { client.delete(config, SyncLayout.conversationPath(root, existing.name)) }
                }
            }

            // ── 4. 元信息最后写（预设 / 版本），失败不影响会话数据 ──
            //
            // 内容没变就**不写、也不传**。
            //
            // 原先这里每次都塞 `exportedAt = now()`，于是 meta 的字节永远不同，
            // 哪怕一个会话都没改，每次下拉刷新也要多发一个 PUT —— 网盘上看是
            // "什么都没变却又写了一次"，也白白占用请求配额。
            // 现在只比 presets（meta 里唯一有语义的字段），一致就整段跳过。
            val localPresets = repository.syncPresets()
            if (meta == null || meta.presets != localPresets) {
                writeMeta(
                    metaFile,
                    SyncHeader(exportedAt = System.currentTimeMillis(), presets = localPresets),
                )
                client.uploadFile(
                    config = config,
                    remotePath = SyncLayout.metaPath(root),
                    file = metaFile,
                    mediaType = WebDavClient.SyncPayloadMedia,
                )
            }

            settings.setDavLastSyncAt(System.currentTimeMillis())
            _state.value = SyncState(
                message = Res.get(R.string.sync_done, pulled, uploadedConversations, uploadedImages),
                pulled = pulled,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _state.value = SyncState(message = error.userMessage(), failed = true)
        } finally {
            running.unlock()
            listOf(conversationFile, incoming, metaFile).forEach { runCatching { it.delete() } }
        }
    }

    // ── 图片 ─────────────────────────────────────────────

    /**
     * 让远端会话里的图片**在本地可用**，并把附件改写成"本地文件名"。
     *
     * 远端载荷里的图片只有文件名（内容哈希）。本地没有这个文件时拉一次 ——
     * 文件名就是内容哈希，所以"有"就等于"对"，不需要再校验。
     */
    private suspend fun adoptMedia(
        config: WebDavClient.Config,
        conversation: BackupConversation,
    ): BackupConversation = conversation.copy(
        messages = conversation.messages.map { message ->
            if (message.attachments.none { it.isImage }) return@map message
            message.copy(
                attachments = message.attachments.map { attachment ->
                    if (!attachment.isImage) return@map attachment
                    // 老数据可能还把图片内联在 data 里：先落到本地，再统一成文件名
                    val name = attachment.path?.takeIf { it.isNotBlank() }
                        ?: AttachmentFiles.contentAddressed(context, attachment)
                        ?: return@map attachment.copy(data = "", path = null)
                    ensureMediaFile(config, name)
                    attachment.copy(data = "", path = name)
                },
            )
        },
    )

    /** 本地没有就拉一次；拉不到（远端也缺）就留着空引用，界面显示"原件缺失"。 */
    private suspend fun ensureMediaFile(config: WebDavClient.Config, name: String) {
        val target = AttachmentFiles.fileOf(context, name) ?: return
        if (target.isFile && target.length() > 0L) return
        runCatching { client.downloadToFile(config, mediaRemotePath(name), target) }
    }

    /** 图片 → 内容寻址的文件名；文本附件保持内联（它们本来就小）。 */
    private fun externalizeMedia(
        conversation: BackupConversation,
    ): Pair<BackupConversation, Set<String>> {
        val names = linkedSetOf<String>()
        val rewritten = conversation.copy(
            messages = conversation.messages.map { message ->
                if (message.attachments.none { it.isImage }) return@map message
                message.copy(
                    attachments = message.attachments.map { attachment ->
                        if (!attachment.isImage) return@map attachment
                        val name = AttachmentFiles.contentAddressed(context, attachment)
                        if (name == null) {
                            // 原件缺失（用户清过数据 / 老数据坏了）：不把坏引用传上去
                            attachment.copy(data = "", path = null)
                        } else {
                            names += name
                            attachment.copy(data = "", path = name)
                        }
                    },
                )
            },
        )
        return rewritten to names
    }

    /**
     * 上传这一批会话引用到的图片，返回**实际传上去**的张数。
     *
     * 判重完全在内存里做（[remoteMedia] 是本次同步开头列目录拿到的远端名单，
     * [uploadedMedia] 记着本次已经处理过的），**一个 HEAD 都不发**。
     *
     * 内容寻址让这张表永远有效：名字 = 内容哈希，所以"远端有这个名字"就等于
     * "这张图已经在远端了"。同一张图被十条会话引用也只传一次。
     */
    private suspend fun uploadMedia(
        config: WebDavClient.Config,
        names: List<String>,
        remoteMedia: Set<String>,
    ): Int {
        var uploaded = 0
        for (name in names) {
            if (name in remoteMedia) continue
            // 已传过、或本次已在队列里的跳过（linkedSet 顺便当去重表）
            if (!uploadedMedia.add(name)) continue
            val file = AttachmentFiles.fileOf(context, name) ?: continue
            if (!file.isFile || file.length() == 0L) continue
            client.uploadFile(config, mediaRemotePath(name), file)
            uploaded++
        }
        return uploaded
    }

    /** 被替换的会话：先存草稿 → 删会话 → 导入完再写回。 */
    private suspend fun keepDrafts(ids: List<String>) {
        if (ids.isEmpty()) return
        val drafts = ids.mapNotNull { id -> repository.findDraft(id)?.let { id to it } }
        repository.deleteConversations(ids)
        drafts.forEach { (id, text) -> repository.saveDraft(id, text) }
    }

    private companion object {
        const val SyncWorkDir = "sync"
    }
}

/** 设置 → 连接参数。集中在一处，避免每个调用点各拼一次。 */
internal fun StoredSettings.davConfig() = WebDavClient.Config(
    server = davUrl,
    user = davUser,
    password = davPassword,
    path = davPath,
)
