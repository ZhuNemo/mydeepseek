package com.simplechat.app.data

import android.content.Context
import com.simplechat.app.net.DEEPSEEK_FLASH
import com.simplechat.app.net.effectiveModelId
import com.simplechat.app.net.isOfficialDeepSeekEndpoint
import com.simplechat.app.net.ApiStyle
import com.simplechat.app.net.ModelCapabilityOverride
import com.simplechat.app.net.RemoteModelMetadata
import com.simplechat.app.net.ProviderKind

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "simplechat_settings",
)

/** 语言档位（[AppLanguage] 的枚举名）。抽到文件级，供 [readStoredLanguageSync] 共用。 */
private val LANGUAGE_KEY = stringPreferencesKey("language_id")

/**
 * 「这个端点有哪些模型」的持久化格式（换行分隔）。
 *
 * 为什么不存 JSON：这里只有一串 id，没有结构；换行在模型 id 里不可能出现
 * （OpenAI 兼容端点用 `[a-zA-Z0-9._:/-]`），于是编解码就是两行 `map`，
 * 而 JSON 要引解析器、还要处理坏数据。读取侧对任何异常输入都只说"没有列表"。
 */
internal object ModelIdList {

    /**
     * 上限。正常端点几十个，这里只为拦住"网关把整张目录表倒出来"的极端情况 ——
     * 几千个 id 会撑大 DataStore，而选择器里也没人翻得到底。
     */
    const val MAX_IDS = 500

    fun encode(ids: List<String>): String = ids
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(MAX_IDS)
        .joinToString("\n")

    fun decode(raw: String?): List<String> = raw
        ?.lineSequence()
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.distinct()
        ?.take(MAX_IDS)
        ?.toList()
        .orEmpty()
}

/**
 * 同步读语言档位 —— 只为 `attachBaseContext`：那时一切异步入口都还没起来。
 * 值就一行，阻塞读在毫秒级；读不出来（IO 异常）按 [AppLanguage.SYSTEM] 处理，不拦启动。
 */
internal fun readStoredLanguageSync(context: Context): AppLanguage = runCatching {
    runBlocking {
        AppLanguage.fromId(context.settingsDataStore.data.first()[LANGUAGE_KEY])
    }
}.getOrDefault(AppLanguage.SYSTEM)

/** 持久化的全局配置。 */
/**
 * WebDAV 同步的默认落点（相对网盘根）。
 *
 * 同步在网盘上是一个**目录**（`meta.json` + `conv/` + `media/`），不是一个文件。
 * 放在顶层而不是 companion 里：主构造参数的默认值引用同类的 companion 成员
 * 在部分编译器版本上会报初始化顺序问题，顶层常量没有这个歧义。
 */
const val DEFAULT_DAV_PATH = "myDeepSeek"

/**
 * 远程路径归一成**目录**。
 *
 * 上一版填的是文件路径（`myDeepSeek/sync.jsonl`）—— 这里自动取它的父目录，
 * 老配置不用重填，也不会因为多了一层 `sync.jsonl/` 目录而错位。
 */
internal fun normalizeDavDir(raw: String?): String {
    val trimmed = raw?.trim().orEmpty().trim('/')
    if (trimmed.isBlank()) return DEFAULT_DAV_PATH
    if (trimmed.endsWith(".json") || trimmed.endsWith(".jsonl")) {
        val parent = trimmed.substringBeforeLast('/', missingDelimiterValue = "")
        return parent.ifBlank { DEFAULT_DAV_PATH }
    }
    return trimmed
}

data class StoredSettings(
    val providerId: String = "DEEPSEEK",
    val baseUrl: String = "https://api.deepseek.com",
    val modelId: String = "deepseek-flash",
    val apiKey: String = "",
    val apiProfiles: List<ApiProfile> = emptyList(),
    val activeApiProfileId: String = "legacy",
    val searchApi: ServiceApiConfig = ServiceApiConfig(),
    val filesApi: ServiceApiConfig = ServiceApiConfig(),
    val imageGenApi: ImageGenConfig = ImageGenConfig(),
    val autoReadAloud: Boolean = false,
    val showImageSkipNotice: Boolean = true,
    val showRetrySkipNotice: Boolean = true,
    /** Include messages from inactive conversation branches in global search. */
    val searchInactiveBranches: Boolean = false,
    val searchReasoning: Boolean = false,
    val readAloudEnabled: Boolean = true,
    val reasoningLevelId: String = "OFF",
    val temperature: Float = 1.0f,
    val topP: Float = 1.0f,
    val maxTokens: Int = 0,
    /** 外观：SYSTEM / LIGHT / DARK。 */
    val themeModeId: String = "SYSTEM",
    val useSimpleCatIcon: Boolean = true,
    /** 主题色：AccentColor.name，见 ui/theme/Theme.kt。 */
    val accentColorId: String = "BLUE",
    /**
     * ⚠️ 已废弃，仅保留以免老配置报错。
     *
     * 调色板推导方式改成固定 Fidelity 了（见 `Theme.buildScheme`）：
     * 「主题色」与「调色板样式」两个入口同时在决定颜色，用户改一个看不到
     * 预期效果 —— 那是"两个设置互相打架"，不是可配置性。
     *
     * 字段保留是因为 DataStore 里可能还有这个键，删掉读取也不会报错，
     * 但留着能让旧版本回退时行为一致。
     */
    @Deprecated("配色推导方式已固定为 Fidelity，不再暴露设置")
    val paletteStyleId: String = "TonalSpot",
    /** 深色模式下把中性底压到纯黑（OLED）。只影响中性色，品牌色不动。 */
    val pureBlack: Boolean = false,

    // ── 应用锁 ───────────────────────────────────────────
    /**
     * 整机应用锁的解锁方式：NONE / PIN / DEVICE（见 `ui/lock/AppLockType`）。
     *
     * 存字符串而不是枚举：枚举改名会让旧值读不出来（`valueOf` 抛异常），
     * 而这里只需要一个稳定的 id。
     */
    val appLockTypeId: String = "NONE",
    /** 离开前台多久后重新上锁（秒）。0 = 立即。 */
    val appLockTimeoutSeconds: Int = 0,

    // ── 保险箱 ───────────────────────────────────────────
    /** PIN 的 PBKDF2 哈希与 salt（Base64）。空 = 还没设过 PIN。 */
    val vaultPinHash: String = "",
    val vaultPinSalt: String = "",
    /** 密保问题（明文，要显示给用户看）。 */
    val vaultQuestion: String = "",
    val vaultAnswerHash: String = "",
    val vaultAnswerSalt: String = "",
    /** 字号倍率。 */
    val fontScale: Float = 1.0f,
    /** 字号是否跟随系统辅助功能设置。 */
    val followSystemFont: Boolean = true,
    /** 思考内容是否自动折叠（关闭则默认展开，需手动收起）。 */
    val autoCollapseThinking: Boolean = true,
    /** 长按输入框开始语音输入（关闭后长按不再触发）。 */
    val holdToTalk: Boolean = true,
    /** 新对话默认模式：CREATIVE / ROLEPLAY。 */
    val defaultModeId: String = "CREATIVE",
    /** 界面语言：AppLanguage 的枚举名，见 data/LocaleHelper.kt。 */
    val languageId: String = "SYSTEM",
    /**
     * 协议通道：`AUTO` / `OPENAI` / `ANTHROPIC`。
     *
     * `AUTO` 按端点解析 —— DeepSeek 官方走扩展通道，其余走 OpenAI 兼容（见 `ApiStyle`）。
     */
    val apiStyleId: String = "AUTO",
    /**
     * 智能搜索（联网搜索）开关。
     *
     * 默认**关**：搜索会让这一轮变慢、变贵，而长文创作绝大多数轮次用不上。
     * 与官方 Chat Demo 的默认值一致。
     */
    val webSearch: Boolean = false,
    /**
     * 地区（用于「时间地点提醒」里的地点那一项），自由文本，留空则不填。
     *
     * 不做省/市二级联动：那套 13KB 的数据与三级选择器，收益远小于维护成本，
     * 而提醒里这一项本来就只是给模型一个「用户在哪儿」的粗略印象。
     */
    val locationRegion: String = "",
    /**
     * 朗读音色。
     *
     * - [ttsVoiceName]：系统 TTS 引擎里的 `Voice.name`（机器标识，用来精确选中）。
     *   空串 = 跟随系统默认（按界面语言挑一个本地音色）。
     * - [ttsVoiceLabel]：选中时给用户看的名字（地区 + 是否联网）。**单独存一份**，
     *   因为音色名是 `zh-cn-x-ccc-local` 这种机器 ID，界面不可能现算，而每次进设置页
     *   都去初始化一次 TTS 引擎只为显示一个名字也不划算。
     */
    val ttsVoiceName: String = "",
    val ttsVoiceLabel: String = "",

    /**
     * 当前端点 `/models` 拉回来的模型 id（[ModelIdList] 编解码）。
     *
     * 存下来而不是只放内存：拉取动作发生在「测试连接」或对话面板的刷新里，
     * 而用户随后进设置页选模型可能已经过了一次重启 —— 列表一丢，选择器就只剩
     * 内置能力表（自定义端点干脆是空的），于是"能拉到模型，却选不到模型"。
     * 端点（服务商 / Base URL）一换就清空，见 [SettingsStore.setProvider]。
     */
    val modelIds: List<String> = emptyList(),
    val modelContextWindows: Map<String, Int> = emptyMap(),
    val modelMetadata: Map<String, RemoteModelMetadata> = emptyMap(),
    val modelOverrides: Map<String, ModelCapabilityOverride> = emptyMap(),
    val onboardingComplete: Boolean = false,
    val loadingWords: List<String> = emptyList(),
    val loadingWordsUseThemeColor: Boolean = false,

    // ── WebDAV 同步 ─────────────────────────────────────
    /**
     * 服务器地址（例如坚果云的 `https://dav.jianguoyun.com/dav/`）。
     * 空串 = 没配置过同步，设置页显示为"未配置"。
     */
    val davUrl: String = "",
    val davUser: String = "",
    /** 应用密码（坚果云的「第三方应用密码」），与 API Key 一样只存密文。 */
    val davPassword: String = "",
    /** 远程**目录**（相对服务器根），同步数据都放在这里面。 */
    val davPath: String = DEFAULT_DAV_PATH,
    /** 打开应用时自动同步一次。默认关：网络与计费都该由用户决定。 */
    val davAutoSync: Boolean = false,
    /** 上次成功同步的时间（毫秒）；0 = 从未同步。 */
    val davLastSyncAt: Long = 0L,

) {
    val hasKey: Boolean get() = apiKey.isNotBlank()

    /**
     * 这一套配置**实际走的是不是扩展通道**（联网搜索等能力的唯一前提）。
     *
     * 判据与请求时用的是同一个 [`ApiStyle.resolve`]：配置档位 + 端点共同决定。
     * 用在设置页与输入面板上"要不要显示联网搜索" —— 只有扩展通道有
     * `deepseek_web_search` 这个工具，OpenAI 兼容通道没有对应入口，
     * 那就别把一个打开了也没用的开关摆在用户面前。
     */
    val resolvedApiStyle: ApiStyle
        get() = ApiStyle.resolve(
            kind = runCatching { ProviderKind.valueOf(providerId) }.getOrDefault(ProviderKind.DEEPSEEK),
            configuredId = apiStyleId,
            baseUrl = baseUrl,
        )

    /** 当前通道是否支持联网搜索。 */
    val officialFlash: Boolean get() = isOfficialDeepSeekEndpoint(baseUrl) && effectiveModelId(baseUrl, modelId) == DEEPSEEK_FLASH
    val supportsWebSearch: Boolean get() = searchApi.enabled || officialFlash
    val supportsFileReferences: Boolean get() = !isOfficialDeepSeekEndpoint(baseUrl) || officialFlash
    val useRemoteFiles: Boolean get() = filesApi.enabled && supportsFileReferences

    /** 是否已经设过保险箱 PIN。没设过时第一次进保险箱要引导设置。 */
    val hasVaultPin: Boolean get() = vaultPinHash.isNotBlank() && vaultPinSalt.isNotBlank()

    /** 是否配过密保问题（用于忘记 PIN 后重置）。 */
    val hasVaultRecovery: Boolean
        get() = vaultQuestion.isNotBlank() && vaultAnswerHash.isNotBlank() && vaultAnswerSalt.isNotBlank()

    /** 同步配置是否填全（三样缺一不可）。 */
    val syncConfigured: Boolean
        get() = davUrl.isNotBlank() && davUser.isNotBlank() && davPassword.isNotBlank()
}

/**
 * 配置存储（DataStore Preferences）。
 *
 * **API Key 不落明文**：加解密走 [SecretBox]（Android Keystore 里的
 * 不可导出 AES-256 密钥 + AES-GCM），DataStore 里只存密文。
 * 密钥随应用卸载 / 清除数据一起消失，届时解不开只会当"未设置"处理，
 * 不会崩，也不会把密文当 Key 发出去。
 */
class SettingsStore(
    private val context: Context,
    private val preferences: DataStore<Preferences> = context.settingsDataStore,
) {

    val flow: Flow<StoredSettings> = preferences.data.map { prefs ->
        StoredSettings(
            providerId = prefs[Keys.PROVIDER] ?: StoredSettings().providerId,
            baseUrl = prefs[Keys.BASE_URL] ?: StoredSettings().baseUrl,
            modelId = effectiveModelId(prefs[Keys.BASE_URL] ?: ProviderKind.DEEPSEEK.defaultBaseUrl, prefs[Keys.MODEL] ?: DEEPSEEK_FLASH),
            apiKey = readApiKey(prefs),
            apiProfiles = profileRows(prefs).map { it.copy(modelId = effectiveModelId(it.baseUrl, it.modelId)) },
            activeApiProfileId = prefs[Keys.ACTIVE_PROFILE] ?: "legacy",
            searchApi = readService(prefs, Keys.SEARCH_API),
            filesApi = readService(prefs, Keys.FILES_API),
            imageGenApi = readImageGen(prefs),
            autoReadAloud = prefs[Keys.AUTO_READ_ALOUD] ?: false,
            showRetrySkipNotice = prefs[Keys.RETRY_SKIP_NOTICE] ?: true,
            showImageSkipNotice = prefs[Keys.IMAGE_SKIP_NOTICE] ?: true,
            reasoningLevelId = prefs[Keys.REASONING] ?: StoredSettings().reasoningLevelId,
            temperature = prefs[Keys.TEMPERATURE] ?: StoredSettings().temperature,
            topP = prefs[Keys.TOP_P] ?: StoredSettings().topP,
            maxTokens = prefs[Keys.MAX_TOKENS] ?: StoredSettings().maxTokens,
            themeModeId = prefs[Keys.THEME_MODE] ?: StoredSettings().themeModeId,
            useSimpleCatIcon = prefs[Keys.USE_SIMPLE_CAT_ICON] ?: StoredSettings().useSimpleCatIcon,
            accentColorId = prefs[Keys.ACCENT_COLOR] ?: StoredSettings().accentColorId,
            paletteStyleId = prefs[Keys.PALETTE_STYLE] ?: StoredSettings().paletteStyleId,
            pureBlack = prefs[Keys.PURE_BLACK] ?: StoredSettings().pureBlack,
            appLockTypeId = prefs[Keys.APP_LOCK_TYPE] ?: StoredSettings().appLockTypeId,
            appLockTimeoutSeconds = prefs[Keys.APP_LOCK_TIMEOUT]
                ?: StoredSettings().appLockTimeoutSeconds,
            vaultPinHash = prefs[Keys.VAULT_PIN_HASH].orEmpty(),
            vaultPinSalt = prefs[Keys.VAULT_PIN_SALT].orEmpty(),
            vaultQuestion = prefs[Keys.VAULT_QUESTION].orEmpty(),
            vaultAnswerHash = prefs[Keys.VAULT_ANSWER_HASH].orEmpty(),
            vaultAnswerSalt = prefs[Keys.VAULT_ANSWER_SALT].orEmpty(),
            fontScale = prefs[Keys.FONT_SCALE] ?: StoredSettings().fontScale,
            followSystemFont = prefs[Keys.FOLLOW_SYSTEM_FONT]
                ?: StoredSettings().followSystemFont,
            autoCollapseThinking = prefs[Keys.AUTO_COLLAPSE_THINKING]
                ?: StoredSettings().autoCollapseThinking,
            holdToTalk = prefs[Keys.HOLD_TO_TALK] ?: StoredSettings().holdToTalk,
            defaultModeId = prefs[Keys.DEFAULT_MODE] ?: StoredSettings().defaultModeId,
            languageId = prefs[LANGUAGE_KEY] ?: StoredSettings().languageId,
            apiStyleId = prefs[Keys.API_STYLE] ?: StoredSettings().apiStyleId,
            webSearch = prefs[Keys.WEB_SEARCH] ?: StoredSettings().webSearch,
            locationRegion = prefs[Keys.LOCATION] ?: StoredSettings().locationRegion,
            searchReasoning = prefs[Keys.SEARCH_REASONING] ?: false,
            readAloudEnabled = prefs[Keys.READ_ALOUD_ENABLED] ?: true,
            ttsVoiceName = prefs[Keys.TTS_VOICE] ?: StoredSettings().ttsVoiceName,
            ttsVoiceLabel = prefs[Keys.TTS_VOICE_LABEL] ?: StoredSettings().ttsVoiceLabel,
            modelIds = ModelIdList.decode(prefs[Keys.MODEL_IDS]),
            davUrl = prefs[Keys.DAV_URL] ?: StoredSettings().davUrl,
            davUser = prefs[Keys.DAV_USER] ?: StoredSettings().davUser,
            davPassword = readDavPassword(prefs),
            davPath = normalizeDavDir(prefs[Keys.DAV_PATH]),
            davAutoSync = prefs[Keys.DAV_AUTO_SYNC] ?: StoredSettings().davAutoSync,
            davLastSyncAt = prefs[Keys.DAV_LAST_SYNC] ?: StoredSettings().davLastSyncAt,
            modelContextWindows = readModelContextWindows(prefs),
            modelMetadata = readModelMap<RemoteModelMetadata>(prefs, Keys.MODEL_METADATA),
            modelOverrides = readModelMap<ModelCapabilityOverride>(prefs, Keys.MODEL_OVERRIDES),
            onboardingComplete = prefs[Keys.ONBOARDING_COMPLETE] == true && prefs.hasSettingsBeyondProfileSeed(),
            loadingWords = readLoadingWords(prefs),
            loadingWordsUseThemeColor = prefs[Keys.LOADING_WORDS_USE_THEME_COLOR] ?: false,
        )
    }
        // Keystore 解密要过 Binder：放到 IO 上，别在 Compose 的组合线程里做。
        // ⚠️ 必须在 `map { }` **外面** —— 写进 lambda 里就成了对 StoredSettings 调 flowOn。
        .flowOn(Dispatchers.IO)

    // ── WebDAV 同步 ──────────────────────────────────────

    /** 服务器地址。顺手去掉首尾空白与**结尾的斜杠** —— 拼路径时不希望出现 `//`。 */
    suspend fun setDavUrl(url: String) {
        context.settingsDataStore.edit {
            it[Keys.DAV_URL] = url.trim().trimEnd('/')
        }
    }

    suspend fun setDavUser(user: String) {
        context.settingsDataStore.edit { it[Keys.DAV_USER] = user.trim() }
    }

    /** 应用密码：与 API Key 同一条路，只落密文。 */
    suspend fun setDavPassword(password: String) {
        val trimmed = password.trim()
        context.settingsDataStore.edit { prefs ->
            if (trimmed.isEmpty()) {
                prefs.remove(Keys.DAV_PASSWORD_ENC)
            } else {
                SecretBox.encrypt(trimmed)?.let { prefs[Keys.DAV_PASSWORD_ENC] = it }
            }
        }
    }

    /** 远程目录：归一成 `a/b`（无前后斜杠），空值回落到默认。 */
    suspend fun setDavPath(path: String) {
        context.settingsDataStore.edit { it[Keys.DAV_PATH] = normalizeDavDir(path) }
    }

    suspend fun setDavAutoSync(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.DAV_AUTO_SYNC] = enabled }
    }

    suspend fun setDavLastSyncAt(millis: Long) {
        context.settingsDataStore.edit { it[Keys.DAV_LAST_SYNC] = millis }
    }

    /** 读一次当前配置（同步入口用，不需要订阅）。 */
    suspend fun current(): StoredSettings = flow.first()

    /** 读 WebDAV 密码：只认密文，解不开就当没设置。 */
    private fun readDavPassword(prefs: Preferences): String =
        prefs[Keys.DAV_PASSWORD_ENC]?.takeIf { it.isNotBlank() }
            ?.let { SecretBox.decrypt(it) }.orEmpty()

    suspend fun setProvider(providerId: String, baseUrl: String, modelId: String) {
        editApi { prefs ->
            // 换了端点，旧端点拉到的模型列表就不再成立
            val endpointChanged = prefs[Keys.PROVIDER] != providerId ||
                prefs[Keys.BASE_URL] != baseUrl.trim()
            if (endpointChanged) {
                clearModelCatalog(prefs)
                prefs.remove(Keys.MODEL_OVERRIDES)
            }
            prefs[Keys.PROVIDER] = providerId
            prefs[Keys.BASE_URL] = baseUrl.trim()
            prefs[Keys.MODEL] = modelId
        }
    }

    suspend fun setBaseUrl(baseUrl: String) {
        val trimmed = baseUrl.trim()
        editApi { prefs ->
            // 只改大小写 / 前后空格不算换端点；真的改了才丢掉旧列表
            if (prefs[Keys.BASE_URL].orEmpty().trim() != trimmed) {
                clearModelCatalog(prefs)
                prefs.remove(Keys.MODEL_OVERRIDES)
            }
            prefs[Keys.BASE_URL] = trimmed
        }
    }

    suspend fun setModel(modelId: String, expectedProfileId: String? = null) {
        editApi(expectedProfileId) { it[Keys.MODEL] = modelId }
    }

    private fun readModelContextWindows(prefs: Preferences): Map<String, Int> =
        prefs[Keys.MODEL_CONTEXT_WINDOWS]?.let { raw ->
            runCatching { apiProfileJson.decodeFromString<Map<String, Int>>(raw) }.getOrNull()
        }.orEmpty()

    private inline fun <reified T> readModelMap(prefs: Preferences, key: Preferences.Key<String>): Map<String, T> =
        prefs[key]?.let { raw ->
            runCatching { apiProfileJson.decodeFromString<Map<String, T>>(raw) }.getOrNull()
        }.orEmpty()

    private fun clearModelCatalog(prefs: MutablePreferences) {
        prefs.remove(Keys.MODEL_IDS)
        prefs.remove(Keys.MODEL_CONTEXT_WINDOWS)
        prefs.remove(Keys.MODEL_METADATA)
    }

    suspend fun setApiKey(apiKey: String) {
        // 清洗在**入口**做一次：粘贴带进来的换行/空格是 OkHttp 报
        // `Unexpected char 0x0a in Authorization value` 的头号原因
        // （见 ApiKey 的说明）。非 ASCII 不在这里静默丢弃，交给界面拦。
        val trimmed = ApiKey.normalize(apiKey)
        editApi { prefs ->
            // Different credentials at the same URL can expose a different model catalog.
            clearModelCatalog(prefs)
            if (trimmed.isEmpty()) {
                prefs.remove(Keys.API_KEY_ENC)
            } else {
                prefs[Keys.API_KEY_ENC] = SecretBox.encrypt(trimmed)
                    ?: throw AppTextException(com.simplechat.app.R.string.api_profile_save_failed)
            }
            // 明文槽位彻底弃用，写入时顺手清掉历史遗留
            prefs.remove(Keys.API_KEY)
        }
    }

    /**
     * 把 v1.7 之前留下的**明文** Key 就地加密（一次性）。
     *
     * 启动时调用一次即可。老版本直接把 Key 写在 `api_key` 里，
     * 升级后那份明文必须尽快消失，否则"加密存储"只是新数据的待遇。
     */
    suspend fun migrateLegacyApiKey() {
        preferences.edit { prefs ->
            val legacy = prefs[Keys.API_KEY]
            if (!legacy.isNullOrBlank() && prefs[Keys.API_KEY_ENC] == null) {
                prefs[Keys.API_KEY_ENC] = SecretBox.encrypt(legacy)
                    ?: throw AppTextException(com.simplechat.app.R.string.api_profile_save_failed)
            }
            prefs.remove(Keys.API_KEY)
        }
    }

    suspend fun setReasoningLevel(levelId: String, expectedProfileId: String? = null) {
        editApi(expectedProfileId) { it[Keys.REASONING] = levelId }
    }

    suspend fun setTemperature(value: Float, expectedProfileId: String? = null) {
        editApi(expectedProfileId) { it[Keys.TEMPERATURE] = value }
    }

    suspend fun setTopP(value: Float, expectedProfileId: String? = null) {
        editApi(expectedProfileId) { it[Keys.TOP_P] = value }
    }

    suspend fun setMaxTokens(value: Int, expectedProfileId: String? = null) {
        editApi(expectedProfileId) { it[Keys.MAX_TOKENS] = value }
    }

    suspend fun setThemeMode(modeId: String) {
        preferences.edit { it[Keys.THEME_MODE] = modeId }
    }

    // ── 保险箱 ───────────────────────────────────────────

    /** 切换应用锁的解锁方式（NONE / PIN / DEVICE）。 */
    suspend fun setAppLockType(typeId: String) {
        context.settingsDataStore.edit { it[Keys.APP_LOCK_TYPE] = typeId }
    }

    /** 自动锁定的等待时长（秒）。0 = 离开即锁。 */
    suspend fun setAppLockTimeout(seconds: Int) {
        context.settingsDataStore.edit { it[Keys.APP_LOCK_TIMEOUT] = seconds.coerceIn(0, 3600) }
    }

    /** 设置（或更换）PIN。salt 一起换 —— 复用旧 salt 会让两次设置的哈希可比。 */
    suspend fun setVaultPin(pin: String) {
        val salt = VaultCredentials.newSalt()
        val hash = VaultCredentials.hash(pin, salt)
        context.settingsDataStore.edit {
            it[Keys.VAULT_PIN_SALT] = salt
            it[Keys.VAULT_PIN_HASH] = hash
        }
    }

    /** 密保问题与答案。答案只在设置时哈希一次，之后永不回显。 */
    suspend fun setVaultRecovery(question: String, answer: String) {
        val salt = VaultCredentials.newSalt()
        val hash = VaultCredentials.hash(answer, salt)
        context.settingsDataStore.edit {
            it[Keys.VAULT_QUESTION] = question.trim()
            it[Keys.VAULT_ANSWER_SALT] = salt
            it[Keys.VAULT_ANSWER_HASH] = hash
        }
    }

    /** 校验 PIN。命中返回 true。计算在调用方线程，PBKDF2 要一两百毫秒 —— 记得放 IO。 */
    suspend fun verifyVaultPin(pin: String): Boolean {
        val current = flow.first()
        if (!current.hasVaultPin) return false
        return VaultCredentials.matches(pin, current.vaultPinSalt, current.vaultPinHash)
    }

    /** 校验密保答案。 */
    suspend fun verifyVaultAnswer(answer: String): Boolean {
        val current = flow.first()
        if (!current.hasVaultRecovery) return false
        return VaultCredentials.matches(answer.trim(), current.vaultAnswerSalt, current.vaultAnswerHash)
    }

    suspend fun setPureBlack(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.PURE_BLACK] = enabled }
    }

    suspend fun setUseSimpleCatIcon(enabled: Boolean) {
        preferences.edit { it[Keys.USE_SIMPLE_CAT_ICON] = enabled }
    }
    suspend fun setAccentColor(colorId: String) {
        preferences.edit { it[Keys.ACCENT_COLOR] = colorId }
    }

    suspend fun setOnboardingComplete() {
        preferences.edit { it[Keys.ONBOARDING_COMPLETE] = true }
    }

    suspend fun setFontScale(scale: Float) {
        preferences.edit { it[Keys.FONT_SCALE] = scale }
    }

    suspend fun setFollowSystemFont(follow: Boolean) {
        preferences.edit { it[Keys.FOLLOW_SYSTEM_FONT] = follow }
    }

    private fun readLoadingWords(prefs: Preferences): List<String> =
        prefs[Keys.LOADING_WORDS]?.let { raw ->
            runCatching { apiProfileJson.decodeFromString<List<String>>(raw) }.getOrNull()
        }.orEmpty().filter(String::isNotBlank).distinct()

    internal suspend fun editLoadingWords(update: (List<String>) -> List<String>) {
        preferences.edit { prefs ->
            val words = update(readLoadingWords(prefs))
            if (words.isEmpty()) prefs.remove(Keys.LOADING_WORDS)
            else prefs[Keys.LOADING_WORDS] = apiProfileJson.encodeToString(words)
        }
    }

    internal suspend fun setLoadingWordsUseThemeColor(enabled: Boolean) {
        preferences.edit { it[Keys.LOADING_WORDS_USE_THEME_COLOR] = enabled }
    }

    suspend fun setAutoCollapseThinking(enabled: Boolean) {
        preferences.edit { it[Keys.AUTO_COLLAPSE_THINKING] = enabled }
    }

    suspend fun setHoldToTalk(enabled: Boolean) {
        preferences.edit { it[Keys.HOLD_TO_TALK] = enabled }
    }

    suspend fun setDefaultMode(modeId: String) {
        preferences.edit { it[Keys.DEFAULT_MODE] = modeId }
    }

    suspend fun setLanguage(languageId: String) {
        preferences.edit { it[LANGUAGE_KEY] = languageId }
    }

    suspend fun setApiStyle(styleId: String) {
        editApi { prefs ->
            if (prefs[Keys.API_STYLE] != styleId) clearModelCatalog(prefs)
            prefs[Keys.API_STYLE] = styleId
        }
    }

    suspend fun setWebSearch(enabled: Boolean) {
        editApi { it[Keys.WEB_SEARCH] = enabled }
    }

    private fun readService(prefs: Preferences, key: Preferences.Key<String>): ServiceApiConfig =
        prefs[key]?.let { apiProfileJson.decodeFromString<ServiceApiConfig>(it) } ?: ServiceApiConfig()

    private fun readImageGen(prefs: Preferences): ImageGenConfig =
        prefs[Keys.IMAGE_GEN_API]?.let { apiProfileJson.decodeFromString<ImageGenConfig>(it) } ?: ImageGenConfig()

    suspend fun setImageGenApi(config: ImageGenConfig) {
        require(config.adapter in setOf("OPENAI_IMAGES", "GEMINI", "DASHSCOPE"))
        if (config.enabled && config.modelId.isBlank()) throw AppTextException(com.simplechat.app.R.string.error_no_model)
        require(config.size.matches(Regex("[0-9]{2,5}x[0-9]{2,5}")))
        require(config.aspectRatio in setOf("1:1", "3:2", "2:3", "4:3", "3:4", "16:9", "9:16", "21:9"))
        require(config.geminiImageSize in setOf("1K", "2K", "4K"))
        preferences.edit { it[Keys.IMAGE_GEN_API] = apiProfileJson.encodeToString(config) }
    }

    suspend fun setServiceApi(files: Boolean, config: ServiceApiConfig) {
        config.endpoint("https://api.deepseek.com", if (files) config.filesPath else config.searchPath)
        if (!files) config.endpoint("https://api.deepseek.com", config.fetchPath)
        require(config.count in 1..50)
        require(config.longTextChars in 4_000..1_000_000)
        require(config.purpose in listOf("user_data", "text_recognition", "rich_layout_recognition"))
        preferences.edit { it[if (files) Keys.FILES_API else Keys.SEARCH_API] = apiProfileJson.encodeToString(config) }
    }

    fun encryptServiceKey(key: String): String {
        val normalized = ApiKey.normalize(key)
        if (!ApiKey.isHeaderSafe(normalized)) throw AppTextException(com.simplechat.app.R.string.error_api_key_chars)
        return SecretBox.encrypt(normalized)
            ?: throw AppTextException(com.simplechat.app.R.string.api_profile_save_failed)
    }

    suspend fun setLocationRegion(region: String) {
        // 存**洗过**的值：逗号会把时间地点提醒的五个字段挤成六段（见 normalizeRegion）
        preferences.edit { it[Keys.LOCATION] = normalizeRegion(region) }
    }

    /** 朗读音色：名字用来精确选中，标签用来在设置页显示（见 [StoredSettings.ttsVoiceName]）。 */
    suspend fun setTtsVoice(name: String, label: String) {
        preferences.edit { prefs ->
            if (name.isBlank()) {
                prefs.remove(Keys.TTS_VOICE)
                prefs.remove(Keys.TTS_VOICE_LABEL)
            } else {
                prefs[Keys.TTS_VOICE] = name
                prefs[Keys.TTS_VOICE_LABEL] = label
            }
        }
    }



    /** Seed the registry without mistaking the automatic default profile for a legacy setup. */
    suspend fun migrateApiProfiles() {
        preferences.edit { prefs ->
            val hasExistingSettings = prefs.hasSettingsBeyondProfileSeed()
            val onboardingComplete = prefs[Keys.ONBOARDING_COMPLETE]
            // Build 36 could mark a seed-only install complete on its second launch.
            prefs[Keys.ONBOARDING_COMPLETE] = (onboardingComplete ?: true) && hasExistingSettings
            ensureProfiles(prefs)
        }
    }

    private fun Preferences.hasSettingsBeyondProfileSeed(): Boolean = asMap().keys.any {
        it != Keys.PROFILES && it != Keys.ACTIVE_PROFILE && it != Keys.ONBOARDING_COMPLETE
    }

    suspend fun muteRetrySkipNotice() {
        preferences.edit { it[Keys.RETRY_SKIP_NOTICE] = false }
    }

    suspend fun setShowImageSkipNotice(enabled: Boolean) {
        preferences.edit { it[Keys.IMAGE_SKIP_NOTICE] = enabled }
    }

    suspend fun setSearchInactiveBranches(enabled: Boolean) {
        preferences.edit { it[Keys.SEARCH_INACTIVE_BRANCHES] = enabled }
    }

    suspend fun setSearchReasoning(enabled: Boolean) {
        preferences.edit { it[Keys.SEARCH_REASONING] = enabled }
    }

    suspend fun setReadAloudEnabled(enabled: Boolean) {
        preferences.edit { it[Keys.READ_ALOUD_ENABLED] = enabled }
    }
    suspend fun setAutoReadAloud(enabled: Boolean) {
        preferences.edit { it[Keys.AUTO_READ_ALOUD] = enabled }
    }

    private fun profileRows(prefs: Preferences): List<ApiProfile> =
        prefs[Keys.PROFILES]?.let(::decodeApiProfiles)
            ?: listOf(snapshotProfile(prefs, "legacy", com.simplechat.app.net.ProviderKind.entries
                .firstOrNull { it.name == prefs[Keys.PROVIDER] }?.displayName
                ?: com.simplechat.app.net.ProviderKind.DEEPSEEK.displayName))

    private fun snapshotProfile(prefs: Preferences, id: String, name: String, lastUsedAt: Long = 0): ApiProfile = ApiProfile(
        id = id, name = name,
        providerId = prefs[Keys.PROVIDER] ?: "DEEPSEEK",
        baseUrl = prefs[Keys.BASE_URL] ?: "https://api.deepseek.com",
        modelId = effectiveModelId(prefs[Keys.BASE_URL] ?: ProviderKind.DEEPSEEK.defaultBaseUrl, prefs[Keys.MODEL] ?: DEEPSEEK_FLASH),
        apiKeyEncrypted = prefs[Keys.API_KEY_ENC].orEmpty(),
        apiStyleId = prefs[Keys.API_STYLE] ?: "AUTO",
        modelIds = ModelIdList.decode(prefs[Keys.MODEL_IDS]),
        modelContextWindows = readModelContextWindows(prefs),
        modelMetadata = readModelMap<RemoteModelMetadata>(prefs, Keys.MODEL_METADATA),
        modelOverrides = readModelMap<ModelCapabilityOverride>(prefs, Keys.MODEL_OVERRIDES),
        reasoningLevelId = prefs[Keys.REASONING] ?: "OFF",
        temperature = prefs[Keys.TEMPERATURE] ?: 1f, topP = prefs[Keys.TOP_P] ?: 1f,
        maxTokens = prefs[Keys.MAX_TOKENS] ?: 0, webSearch = prefs[Keys.WEB_SEARCH] ?: false,
        lastUsedAt = lastUsedAt,
    )

    private fun ensureProfiles(prefs: MutablePreferences): List<ApiProfile> {
        prefs[Keys.PROFILES]?.let { return decodeApiProfiles(it) }
        val legacy = prefs[Keys.API_KEY]
        if (!legacy.isNullOrBlank() && prefs[Keys.API_KEY_ENC] == null) {
            prefs[Keys.API_KEY_ENC] = SecretBox.encrypt(legacy)
                ?: throw AppTextException(com.simplechat.app.R.string.api_profile_save_failed)
        }
        prefs.remove(Keys.API_KEY)
        val profiles = profileRows(prefs)
        prefs[Keys.PROFILES] = apiProfileJson.encodeToString(profiles)
        prefs[Keys.ACTIVE_PROFILE] = profiles.first().id
        return profiles
    }

    private suspend fun editApi(expectedProfileId: String? = null, block: (MutablePreferences) -> Unit) {
        preferences.edit { prefs ->
            val profiles = ensureProfiles(prefs)
            val activeId = checkNotNull(prefs[Keys.ACTIVE_PROFILE])
            if (expectedProfileId != null && expectedProfileId != activeId) return@edit
            block(prefs)
            prefs[Keys.PROFILES] = apiProfileJson.encodeToString(profiles.map {
                if (it.id == activeId) snapshotProfile(prefs, it.id, it.name, it.lastUsedAt) else it
            })
        }
    }

    private fun activate(prefs: MutablePreferences, profile: ApiProfile) {
        prefs[Keys.ACTIVE_PROFILE] = profile.id
        prefs[Keys.PROVIDER] = profile.providerId
        prefs[Keys.BASE_URL] = profile.baseUrl
        prefs[Keys.MODEL] = profile.modelId
        if (profile.apiKeyEncrypted.isEmpty()) prefs.remove(Keys.API_KEY_ENC)
        else prefs[Keys.API_KEY_ENC] = profile.apiKeyEncrypted
        prefs.remove(Keys.API_KEY)
        prefs[Keys.API_STYLE] = profile.apiStyleId
        prefs[Keys.MODEL_IDS] = ModelIdList.encode(profile.modelIds)
        if (profile.modelContextWindows.isEmpty()) prefs.remove(Keys.MODEL_CONTEXT_WINDOWS)
        else prefs[Keys.MODEL_CONTEXT_WINDOWS] = apiProfileJson.encodeToString(profile.modelContextWindows)
        if (profile.modelMetadata.isEmpty()) prefs.remove(Keys.MODEL_METADATA)
        else prefs[Keys.MODEL_METADATA] = apiProfileJson.encodeToString(profile.modelMetadata)
        if (profile.modelOverrides.isEmpty()) prefs.remove(Keys.MODEL_OVERRIDES)
        else prefs[Keys.MODEL_OVERRIDES] = apiProfileJson.encodeToString(profile.modelOverrides)
        prefs[Keys.REASONING] = profile.reasoningLevelId
        prefs[Keys.TEMPERATURE] = profile.temperature
        prefs[Keys.TOP_P] = profile.topP
        prefs[Keys.MAX_TOKENS] = profile.maxTokens
        prefs[Keys.WEB_SEARCH] = profile.webSearch
    }

    /** Update a profile without selecting it or changing its recency. */
    internal suspend fun updateApiProfile(id: String, transform: (ApiProfile) -> ApiProfile) {
        preferences.edit { prefs ->
            if (prefs[Keys.PROFILES] == null && id != "legacy") return@edit
            val profiles = ensureProfiles(prefs)
            val current = profiles.firstOrNull { it.id == id } ?: return@edit
            val updated = transform(current)
            require(updated.id == id) { "Cannot change a profile ID" }
            if (prefs[Keys.ACTIVE_PROFILE] == id) activate(prefs, updated)
            prefs[Keys.PROFILES] = apiProfileJson.encodeToString(profiles.map { if (it.id == id) updated else it })
        }
    }

    internal suspend fun readApiKeyForProfile(id: String): String {
        val prefs = preferences.data.first()
        if (prefs[Keys.PROFILES] == null) return if (id == "legacy") readApiKey(prefs) else ""
        return profileRows(prefs).firstOrNull { it.id == id }?.apiKeyEncrypted
            ?.takeIf { it.isNotBlank() }?.let(SecretBox::decrypt).orEmpty()
    }

    suspend fun selectApiProfile(id: String) {
        preferences.edit { prefs ->
            val profiles = ensureProfiles(prefs)
            val selected = profiles.firstOrNull { it.id == id } ?: return@edit
            val current = selected.copy(lastUsedAt = System.currentTimeMillis())
            activate(prefs, current)
            prefs[Keys.PROFILES] = apiProfileJson.encodeToString(profiles.map { if (it.id == id) current else it })
        }
    }

    /** Provider picker recalls the last used profile for that provider, including its own key. */
    suspend fun selectProvider(provider: ProviderKind) {
        preferences.edit { prefs ->
            val profiles = ensureProfiles(prefs)
            if (prefs[Keys.PROVIDER] == provider.name) return@edit
            val selected = profiles.filter { it.providerId == provider.name }.maxByOrNull { it.lastUsedAt }
                ?: ApiProfile(java.util.UUID.randomUUID().toString(), provider.displayName,
                    provider.name, provider.defaultBaseUrl, provider.defaultModel)
            val current = selected.copy(lastUsedAt = System.currentTimeMillis())
            activate(prefs, current)
            prefs[Keys.PROFILES] = apiProfileJson.encodeToString(profiles.filterNot { it.id == current.id } + current)
        }
    }

    suspend fun createApiProfile(name: String, copyCurrent: Boolean = false) {
        val title = name.trim().take(80)
        require(title.isNotEmpty())
        preferences.edit { prefs ->
            val profiles = ensureProfiles(prefs)
            val id = java.util.UUID.randomUUID().toString()
            val profile = if (copyCurrent) snapshotProfile(prefs, id, title, System.currentTimeMillis())
                else ApiProfile(id, title, "CUSTOM", "", "", lastUsedAt = System.currentTimeMillis())
            prefs[Keys.PROFILES] = apiProfileJson.encodeToString(profiles + profile)
            activate(prefs, profile)
        }
    }

    suspend fun renameApiProfile(id: String, name: String) {
        val title = name.trim().take(80)
        require(title.isNotEmpty())
        preferences.edit { prefs ->
            prefs[Keys.PROFILES] = apiProfileJson.encodeToString(ensureProfiles(prefs).map {
                if (it.id == id) it.copy(name = title) else it
            })
        }
    }

    suspend fun deleteApiProfile(id: String) {
        preferences.edit { prefs ->
            val profiles = ensureProfiles(prefs)
            require(profiles.size > 1)
            val remaining = profiles.filterNot { it.id == id }
            if (prefs[Keys.ACTIVE_PROFILE] == id) activate(prefs, remaining.maxBy { it.lastUsedAt })
            prefs[Keys.PROFILES] = apiProfileJson.encodeToString(remaining)
        }
    }

    /** 清空全部配置（「删除所有数据」时一并调用）。 */
    suspend fun clear() {
        preferences.edit { it.clear() }
    }

    /** 读 API Key：优先密文，其次兼容老版本的明文。 */
    private fun readApiKey(prefs: Preferences): String {
        prefs[Keys.API_KEY_ENC]?.takeIf { it.isNotBlank() }?.let { return SecretBox.decrypt(it) }
        return prefs[Keys.API_KEY].orEmpty()
    }

    private object Keys {
        val PROFILES = stringPreferencesKey("api_profiles_v1")
        val SEARCH_API = stringPreferencesKey("search_api_config")
        val FILES_API = stringPreferencesKey("files_api_config")
        val IMAGE_GEN_API = stringPreferencesKey("image_gen_api_config")
        val ACTIVE_PROFILE = stringPreferencesKey("active_api_profile")
        val RETRY_SKIP_NOTICE = booleanPreferencesKey("show_retry_skip_notice")
        val IMAGE_SKIP_NOTICE = booleanPreferencesKey("show_image_skip_notice")
        val LOADING_WORDS = stringPreferencesKey("loading_words")
        val LOADING_WORDS_USE_THEME_COLOR = booleanPreferencesKey("loading_words_use_theme_color")
        val SEARCH_REASONING = booleanPreferencesKey("search_reasoning")
        val READ_ALOUD_ENABLED = booleanPreferencesKey("read_aloud_enabled")
        val SEARCH_INACTIVE_BRANCHES = booleanPreferencesKey("search_inactive_branches")
        val AUTO_READ_ALOUD = booleanPreferencesKey("auto_read_aloud")
        val PROVIDER = stringPreferencesKey("provider_id")
        val BASE_URL = stringPreferencesKey("base_url")
        val MODEL = stringPreferencesKey("model_id")

        /** API Key 的**密文**（base64）。 */
        val API_KEY_ENC = stringPreferencesKey("api_key_enc")

        /** 历史遗留的明文槽位，只读一次用于迁移，之后不再写入。 */
        val API_KEY = stringPreferencesKey("api_key")
        val REASONING = stringPreferencesKey("reasoning_level")
        val TEMPERATURE = floatPreferencesKey("temperature")
        val TOP_P = floatPreferencesKey("top_p")
        val MAX_TOKENS = intPreferencesKey("max_tokens")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val USE_SIMPLE_CAT_ICON = booleanPreferencesKey("use_simple_cat_icon")
        val ACCENT_COLOR = stringPreferencesKey("accent_color")
        val PALETTE_STYLE = stringPreferencesKey("palette_style")
        val PURE_BLACK = booleanPreferencesKey("pure_black")

        // ── 保险箱 ──────────────────────────────────────
        val APP_LOCK_TYPE = stringPreferencesKey("app_lock_type")
        val APP_LOCK_TIMEOUT = intPreferencesKey("app_lock_timeout")
        val VAULT_PIN_HASH = stringPreferencesKey("vault_pin_hash")
        val VAULT_PIN_SALT = stringPreferencesKey("vault_pin_salt")
        val VAULT_QUESTION = stringPreferencesKey("vault_question")
        val VAULT_ANSWER_HASH = stringPreferencesKey("vault_answer_hash")
        val VAULT_ANSWER_SALT = stringPreferencesKey("vault_answer_salt")
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val FOLLOW_SYSTEM_FONT = booleanPreferencesKey("follow_system_font")
        val AUTO_COLLAPSE_THINKING = booleanPreferencesKey("auto_collapse_thinking")
        val HOLD_TO_TALK = booleanPreferencesKey("hold_to_talk")
        val DEFAULT_MODE = stringPreferencesKey("default_mode")
        val API_STYLE = stringPreferencesKey("api_style")
        val WEB_SEARCH = booleanPreferencesKey("web_search")
        val LOCATION = stringPreferencesKey("location_region")

        /** 朗读音色：机器标识；空 = 系统默认。 */
        val TTS_VOICE = stringPreferencesKey("tts_voice_name")

        /** 朗读音色的人类可读名字（仅用于显示）。 */
        val TTS_VOICE_LABEL = stringPreferencesKey("tts_voice_label")

        // ── WebDAV 同步 ──────────────────────────────────
        val DAV_URL = stringPreferencesKey("dav_url")
        val DAV_USER = stringPreferencesKey("dav_user")

        /** WebDAV 应用密码的**密文**。 */
        val DAV_PASSWORD_ENC = stringPreferencesKey("dav_password_enc")
        val DAV_PATH = stringPreferencesKey("dav_path")
        val DAV_AUTO_SYNC = booleanPreferencesKey("dav_auto_sync")
        val DAV_LAST_SYNC = longPreferencesKey("dav_last_sync_at")

        /** 当前端点 `/models` 拉回来的 id，换行分隔（[ModelIdList]）。 */
        val MODEL_IDS = stringPreferencesKey("model_ids")
        val MODEL_CONTEXT_WINDOWS = stringPreferencesKey("model_context_windows")
        val MODEL_METADATA = stringPreferencesKey("model_metadata")
        val MODEL_OVERRIDES = stringPreferencesKey("model_overrides")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")

    }
}
