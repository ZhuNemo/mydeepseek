package com.simplechat.app.ui.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.simplechat.app.R
import com.simplechat.app.data.ChatRepository
import com.simplechat.app.data.Res
import com.simplechat.app.db.ConversationEntity
import com.simplechat.app.db.ConversationSearchHit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 会话列表（侧拉抽屉）的状态与管理操作。
 *
 * 列表数据直接来自 Room 的 `Flow`，因此重命名 / 置顶 / 删除会自动反映到 UI，
 * 不需要手动刷新。
 */
data class SearchPage(val hits: List<ConversationSearchHit> = emptyList(), val hasMore: Boolean = false)

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(private val repository: ChatRepository) : ViewModel() {

    /**
     * 操作失败的提示通道。
     *
     * 抽屉里的重命名 / 置顶 / 删除都是"点一下就该成功"的动作，失败时**必须有反馈**，
     * 否则用户看到列表没变只会以为是自己没点中。显示位置交给调用方
     * （`RootScreen` 把它转成对话页那条 banner），这里只负责把消息送出去。
     */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun consumeError() { _error.value = null }

    private val queryFlow = MutableStateFlow("")
    private val searchLimit = MutableStateFlow(100)
    private var previousSearchQuery = ""
    fun loadMoreSearchResults() { searchLimit.value += 100 }


    /** 搜索关键词。空串即全量列表。 */
    var query by mutableStateOf("")
        private set

    /**
     * 抽屉列表：**全量**，不受搜索框影响。
     *
     * 搜索框只是个入口按钮（点了进搜索页），抽屉本身没有过滤态。
     */
    val conversations: StateFlow<List<ConversationEntity>> = repository.observeConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 搜索页结果：**标题或消息正文**命中，附命中的那条消息原文。
     *
     * 关键词为空时直接给空列表、不查库 —— 搜索页在空关键词下本来就只显示一句提示。
     */
    val searchPage: StateFlow<SearchPage> = combine(queryFlow, searchLimit) { keyword, limit -> keyword to limit }
        .flatMapLatest { (keyword, limit) ->
            val changed = previousSearchQuery != keyword
            previousSearchQuery = keyword
            if (keyword.isBlank()) {
                flowOf(SearchPage())
            } else {
                kotlinx.coroutines.flow.flow {
                    if (changed) emit(SearchPage())
                    kotlinx.coroutines.delay(180)
                    repository.searchConversations(keyword, limit + 1).collect { hits ->
                        emit(SearchPage(hits.take(limit), hits.size > limit))
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchPage())

    /** 多选模式。 */
    var selectionMode by mutableStateOf(false)
        private set

    val selectedIds = mutableStateListOf<String>()

    fun updateQuery(value: String) {
        query = value
        searchLimit.value = 100
        queryFlow.value = value
    }

    // ── 多选 ──────────────────────────────────────────────

    fun enterSelection(initialId: String) {
        selectionMode = true
        if (initialId !in selectedIds) selectedIds.add(initialId)
    }

    fun toggleSelection(id: String) {
        if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id)
    }

    fun exitSelection() {
        selectionMode = false
        selectedIds.clear()
    }

    // ── 单条操作 ──────────────────────────────────────────

    fun rename(id: String, title: String) {
        launchGuarded("rename") { repository.renameConversation(id, title) }
    }

    fun togglePin(conversation: ConversationEntity) {
        launchGuarded("togglePin") { repository.setPinned(conversation.id, !conversation.pinned) }
    }

    fun delete(id: String) {
        launchGuarded("delete") { repository.deleteConversations(listOf(id)) }
    }

    // ── 批量操作 ──────────────────────────────────────────

    fun pinSelected(pinned: Boolean) {
        val ids = selectedIds.toList()
        launchGuarded("pinSelected") {
            ids.forEach { repository.setPinned(it, pinned) }
            exitSelection()
        }
    }

    fun deleteSelected() {
        val ids = selectedIds.toList()
        launchGuarded("deleteSelected") {
            repository.deleteConversations(ids)
            exitSelection()
        }
    }

    /** 首次启动播种内置预设。 */
    fun seedPresets() {
        launchGuarded("seedPresets") { repository.reconcileBuiltInPresets() }
    }

    /**
     * 写库操作统一入口。
     *
     * `viewModelScope` 没有 `CoroutineExceptionHandler`，协程体里的异常会
     * **直接带走进程**。抽屉里的重命名 / 置顶 / 删除都是"点一下就该成功"的动作，
     * 一条 `SQLiteException`（磁盘满、库被锁）不该变成闪退 ——
     * 这里把它压成日志 + 一条提示，列表照常可用。
     *
     * 失败不静默：抽屉底部那条提示是用户唯一的反馈渠道。
     */
    private fun launchGuarded(
        tag: String,
        block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit,
    ) = viewModelScope.launch {
        try {
            block()
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (error: Exception) {
            android.util.Log.e("SimpleChat/History", "action failed: " + tag, error)
            // 下一次列表刷新会把真相带回来；提示只说明"这次没成功"
            _error.value = Res.get(R.string.error_action_failed, error.userMessage())
        }
    }

    companion object {
        fun factory(repository: ChatRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { HistoryViewModel(repository) }
        }
    }
}
