package dev.bilby.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.agent.AgentIntent
import dev.bilby.agent.AgentLoop
import dev.bilby.agent.AgentTurnState
import dev.bilby.agent.ChatMessage
import dev.bilby.agent.TraceItem
import dev.bilby.agent.reduce
import dev.bilby.data.AgentSessionRepository
import dev.bilby.data.SearchRepository
import dev.bilby.BiliLog
import dev.bilby.data.SettingsStore
import dev.bilby.data.db.AgentMemoryEntity
import dev.bilby.data.db.AgentSessionEntity
import dev.bilby.data.SidePanelId
import dev.bilby.ui.BilbyLink
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import dev.bilby.data.SideSheetPrefs
import kotlinx.coroutines.flow.map
import dev.bilby.resources.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

/**
 * 搜索排序。快路默认综合;这几个取值来自 B 站 search/type 的 order 参数(notes 2.4)。视频与专栏
 * 各取其中一部分,见 SearchResults 的 VideoOrders / ArticleOrders。
 */
enum class SearchOrder(val apiValue: String, val labelRes: StringResource) {
    Comprehensive("totalrank", Res.string.search_order_comprehensive),
    Play("click", Res.string.search_order_click),
    NewPublished("pubdate", Res.string.search_order_pubdate),
    Danmaku("dm", Res.string.search_order_danmaku),
    Favorite("stow", Res.string.search_order_favorite),
    Comments("scores", Res.string.search_order_comments),
    /** 只有专栏有。 */
    Likes("attention", Res.string.search_order_likes),
}

/**
 * 一个框,两条路(DESIGN 2.2):回车走快路,直接打 B 站搜索接口;「问助理」拿同一个词起
 * agent 循环。助理另有自己的输入框接追问([SearchChatUiState.agentInput]),宽屏上两边同时在屏。
 *
 * **轮次只留在内存里,落库的只有普通搜索的关键词。** 这里原先写着"DESIGN 2.2 明确不做搜索
 * 历史",那是读错了:2.2 禁的是「相关推荐」和「热搜词」,而 DESIGN 的技术选型表里本来就列着
 * 要存搜索历史。两者不是一回事 —— 热搜词是把别人的热门查询推给你,搜索历史是你自己敲过的字,
 * 前者是推送式入口,后者只是省一次重复输入。
 *
 * 助理那一路的提问进的是会话表([AgentSessionRepository]),不是搜索历史:搜索历史是省一次
 * 重复输入的词的清单,会话是一段可回看的对话,两者各管各的。会话列表在助理那一侧的会话面板里。
 */
class SearchChatViewModel(
    private val searchRepository: SearchRepository,
    private val agentLoop: AgentLoop,
    private val settings: SettingsStore,
    private val sessions: AgentSessionRepository,
    /** 展开 b23.tv 短链,即 `BiliClient.resolveRedirect`。 */
    private val resolveShortLink: suspend (String) -> String,
) : ViewModel() {

    private val _open = Channel<NavKey>(Channel.BUFFERED)

    /** 搜索框里是编号或链接时要直接打开的页面,见 [directDestination]。界面收到后压栈。 */
    val open: Flow<NavKey> = _open.receiveAsFlow()

    /** 最近搜过的词,最近的在前。不设上限,见 [SettingsStore.searchHistory]。 */
    val searchHistory: StateFlow<List<String>> = settings.searchHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun removeSearchHistory(query: String) = viewModelScope.launch { settings.removeSearchHistory(query) }

    fun clearSearchHistory() = viewModelScope.launch { settings.clearSearchHistory() }

    /**
     * 点历史里的一条 = 把它填回输入框并直接搜。
     *
     * 原先只填不搜,理由是"历史里的词多半要再改一点,直接发出去想改就得重打"。搜索栏移到顶上
     * 之后那条理由不成立了:搜完词仍然留在框里(见 [send]),要改就点回框里改,而大多数时候
     * 点一个历史词就是想再看一遍那份结果 —— 多按一次发送是替少数情况让多数人付的代价。
     */
    fun searchFromHistory(query: String) {
        _state.update { it.copy(input = query, mode = SearchMode.Normal) }
        search()
    }

    /** 模型配好了没有。没配时「问助理」和宽屏的助理侧栏都不出现。 */
    val agentAvailable: StateFlow<Boolean> = settings.llmConfig
        .map { it.isConfigured }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 宽屏助理侧栏的开关与宽度;null 是还没读出来。 */
    val agentPanel: StateFlow<SideSheetPrefs?> = settings.sidePanel(SidePanelId.SearchAgent)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setAgentPanelOpen(open: Boolean) = viewModelScope.launch { settings.saveSidePanelOpen(SidePanelId.SearchAgent, open) }

    fun setAgentPanelWidth(widthDp: Float) = viewModelScope.launch { settings.saveSidePanelWidth(SidePanelId.SearchAgent, widthDp) }

    /** 可挑的会话列表与记住的事,供助理的会话面板。 */
    val agentSessions: StateFlow<List<AgentSessionEntity>> = sessions.observeSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val memories: StateFlow<List<AgentMemoryEntity>> = sessions.observeMemories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteMemory(id: Long) = viewModelScope.launch { sessions.deleteMemory(id) }

    /**
     * 会话内多轮共享的上下文(DESIGN 3.1 修订)。**只含本会话的对话与工具返回**,
     * 观看历史一个字都不会进来。会话由用户点"新会话"显式开启,不自动续接。
     *
     * 这一段同时落库([sessions]):消息是续上下文的回放,轮次是回看时的界面,
     * 视频是溯源白名单。打开旧会话就是把它三样一并读回来。
     */
    private var history: List<ChatMessage> = emptyList()
    private var seenBvids: Set<String> = emptySet()
    private var traces: Map<String, TraceItem> = emptyMap()

    /** 当前会话在库里的 id。null 是还没问过或刚点了新会话,第一轮提问时才建行。 */
    private var sessionId: Long? = null

    /**
     * 正在跑的那一轮。**开新会话和再次发问都必须先把它取消掉。**
     *
     * 循环跑在 [viewModelScope] 上,这是它能扛住切 tab 和翻到播放页的原因(那两处只是
     * composable 离开组合,ViewModel 还在)。反过来说,不显式取消就没有任何东西会停下它:
     * 旧会话会继续执行工具、继续把消息写进一个用户已经放弃的上下文,而它的
     * `updateAgent(turnId)` 还会往刚清空的状态里回写。
     */
    private var agentJob: Job? = null

    /** 开新会话:销毁旧的那一轮并丢弃它的上下文。库里的记录留着,会话列表里还能找到。 */
    fun newSession() {
        agentJob?.cancel()
        agentJob = null
        sessionId = null
        history = emptyList()
        seenBvids = emptySet()
        traces = emptyMap()
        _state.update { it.copy(agent = AgentSearchState()) }
    }

    /**
     * 打开一段旧会话:上下文(消息、白名单、展示信息)与轮次一并读回,接着问就是续着它问。
     * 正在跑的那一轮先停掉,和开新会话同一个理由。
     */
    fun openSession(id: Long) {
        if (sessionId == id) return
        agentJob?.cancel()
        agentJob = null
        viewModelScope.launch {
            val (seen, traceMap) = sessions.loadVideos(id)
            val turns = sessions.loadTurns(id).map { stored ->
                SearchTurn(id = nextTurnId++, query = stored.query.orEmpty(), result = stored.state)
            }
            sessionId = id
            history = sessions.loadMessages(id)
            seenBvids = seen
            traces = traceMap
            _state.update { it.copy(agent = AgentSearchState(turns = turns), mode = SearchMode.Agent) }
        }
    }

    /** 删掉一段会话。正在看的那一段被删等于开新会话。 */
    fun deleteSession(id: Long) {
        if (sessionId == id) newSession()
        viewModelScope.launch { sessions.deleteSession(id) }
    }

    private val _state = MutableStateFlow(SearchChatUiState())
    val state: StateFlow<SearchChatUiState> = _state.asStateFlow()

    /**
     * 普通搜索整套状态机在 [NormalSearchController] 里(标签结果页共用同一套)。它是
     * `normal` 那一格的唯一写入方,这边只镜像进 [SearchChatUiState] —— UI 仍然只看一份状态。
     */
    private val normal = NormalSearchController(viewModelScope, searchRepository)

    init {
        viewModelScope.launch {
            normal.state.collect { n -> _state.update { it.copy(normal = n) } }
        }
    }

    private var nextTurnId = 1L

    private var suggestJob: Job? = null

    /**
     * 改字时顺带取补全词。防抖 200ms,照 PiliPlus `pages/search/controller.dart`:每敲一个字
     * 都发一次,打一个词就是五六个请求,前面几个的结果还没画出来就作废了。补全只在输入框里
     * 有字时有意义,清空就一并清掉。
     */
    fun onInputChange(value: String) {
        _state.update { it.copy(input = value) }
        suggestJob?.cancel()
        val term = value.trim()
        if (term.isEmpty()) {
            _state.update { it.copy(suggestions = emptyList()) }
            return
        }
        suggestJob = viewModelScope.launch {
            delay(SUGGEST_DEBOUNCE_MS)
            val suggestions = searchRepository.suggest(term)
            // 迟到的结果对不上现在的输入就丢掉,不把上一个词的补全挂在这一个词下面。
            _state.update { if (it.input.trim() == term) it.copy(suggestions = suggestions) else it }
        }
    }

    /** 点一条补全词:填进框里并直接搜,同点历史。 */
    fun searchSuggestion(term: String) {
        _state.update { it.copy(input = term, mode = SearchMode.Normal) }
        search()
    }

    fun onAgentInputChange(value: String) = _state.update { it.copy(agentInput = value) }

    /** 窄屏回到普通搜索。助理那段对话留着,再问一次还接得上。 */
    fun leaveAgent() = _state.update { it.copy(mode = SearchMode.Normal) }

    /**
     * 重按搜索 tab:清掉当前查询与输入框,翻回着陆页(居中的框 + 自己敲过的词)。
     * 助理那一屏开着的话一并退回普通模式;助理的对话不动,它有自己的会话。
     */
    fun backToLanding() {
        suggestJob?.cancel()
        normal.clear()
        _state.update { it.copy(input = "", suggestions = emptyList(), mode = SearchMode.Normal) }
    }

    /**
     * 普通搜索是一次查询一份结果,不累积轮次:它就是一个搜索页。留着上一次的结果只会让人往上翻,
     * 而翻上去的东西和这次要找的无关。排序沿用上一次选的那档 —— 新起一份 NormalSearchState()
     * 会把它悄悄弹回综合。
     *
     * **查询词留在输入框里**,只把两端空白去掉。普通搜索的结果页没有别处显示当前查到的是什么,
     * 清空之后这一屏就没有任何东西说明这些结果从何而来,改一个字也得重打。
     */
    fun search() {
        val query = _state.value.input.trim()
        if (query.isEmpty()) return
        suggestJob?.cancel()
        _state.update { it.copy(input = query, suggestions = emptyList()) }

        // 编号与链接直接打开,不记进历史:一条粘贴进来的链接再点一次的机会几乎没有,
        // 留在历史里只会把自己敲过的词挤开。
        directDestination(query)?.let { destination ->
            _open.trySend(destination)
            return
        }
        val link = BilbyLink.extractUrl(query)
        if (link != null && BilbyLink.isShortLink(link)) {
            viewModelScope.launch {
                val destination = runCatching { resolveShortLink(link) }
                    .onFailure { BiliLog.w("搜索框里的短链展开失败", it) }
                    .getOrNull()
                    ?.let(BilbyLink::destinationOf)
                // 展开失败或指向站内没有的页面(番剧之类)时照常搜,至少给一页结果。
                if (destination != null) _open.send(destination) else runSearch(query)
            }
            return
        }
        runSearch(query)
    }

    private fun runSearch(query: String) {
        viewModelScope.launch { settings.addSearchHistory(query) }
        normal.search(query)
    }

    /**
     * 「问助理」:拿搜索框里的词起助理,搜索框不动。窄屏同时切进助理那一屏;框是空的就只切过去,
     * 让人在助理自己的输入框里问。宽屏的侧栏由界面打开,这里不管。
     */
    fun askAgent() {
        val query = _state.value.input.trim()
        _state.update { it.copy(mode = SearchMode.Agent) }
        if (query.isNotEmpty()) startAgentTurn(query)
    }

    /**
     * 助理自己的输入框发出的追问。发完**清空**:它是一段对话,发出去的话已经作为一轮留在上面了,
     * 输入框里再留一份就是同一句话印两遍。
     */
    fun sendToAgent() {
        val query = _state.value.agentInput.trim()
        if (query.isEmpty()) return
        _state.update { it.copy(agentInput = "") }
        startAgentTurn(query)
    }

    private fun startAgentTurn(query: String) {
        val turnId = nextTurnId++
        _state.update { state ->
            state.copy(
                agent = state.agent.copy(
                    turns = state.agent.turns + SearchTurn(turnId, query, AgentTurnState(running = true)),
                ),
            )
        }
        runAgent(turnId, query)
    }

    fun loadMore() = normal.loadMore()

    fun onOrderChanged(order: SearchOrder) = normal.onOrderChanged(order)
    fun onDurationChanged(duration: SearchDuration) = normal.onDurationChanged(duration)
    fun onArticleOrderChanged(order: SearchOrder) = normal.onArticleOrderChanged(order)
    fun onPubTimeChanged(pubTime: SearchPubTime) = normal.onPubTimeChanged(pubTime)
    fun onZoneChanged(zone: SearchZone) = normal.onZoneChanged(zone)
    fun onUserOrderChanged(order: SearchUserOrder) = normal.onUserOrderChanged(order)
    fun onTabSelected(tab: SearchTab) = normal.selectTab(tab)

    /** 普通搜索的重试与下拉刷新:当前栏从第一页重来。 */
    fun refresh() = normal.retry()

    /** 助理最后一轮重跑。宽屏上两边同时在屏,重试不能再按模式分派。 */
    fun retryAgent() {
        val turn = _state.value.agent.turns.lastOrNull() ?: return
        updateAgent(turn.id) { AgentTurnState(running = true) }
        runAgent(turn.id, turn.query)
    }

    /**
     * 跑一轮并把整段落库。会话在第一轮提问时才建行(标题取问题开头),空的"新会话"不会
     * 留下一行占位 —— 那正是会话列表里不该出现的东西。
     */
    private fun runAgent(turnId: Long, query: String) {
        // 上一轮还在跑就先停掉:同一个会话里两个循环并行会交替往 history 上追加,
        // 拼出一段谁也没说过的对话。
        agentJob?.cancel()
        agentJob = viewModelScope.launch {
            try {
                // 库坏了不让整轮跟着死:记忆与会话只是回放层的副本,跑不动就退回纯内存,
                // 与落库之前的行为一致。
                val id = runCatching {
                    sessionId
                        ?: sessions.newSession(query.take(SESSION_TITLE_LENGTH)).also { sessionId = it }
                }.onFailure { BiliLog.w("助理会话建行失败,本轮只留在内存", it) }.getOrNull()
                val memories = runCatching { sessions.recentMemories() }
                    .onFailure { BiliLog.w("读助理记忆失败", it) }
                    .getOrElse { emptyList() }
                agentLoop.run(
                    intent = AgentIntent.Query(query),
                    history = history,
                    priorBvids = seenBvids,
                    priorTraces = traces,
                    memories = memories,
                    onTurnComplete = { newMessages, seen, newTraces ->
                        history = history + newMessages
                        seenBvids = seen
                        traces = newTraces
                        // 与 history 同步落一份:下一轮回放的是库里的消息,不是内存这份。
                        if (id != null) viewModelScope.launch {
                            sessions.appendMessages(id, newMessages)
                            sessions.upsertVideos(id, seen, newTraces)
                        }
                    },
                ).collect { event ->
                    updateAgent(turnId) { it.reduce(event) }
                }
            } finally {
                // **收在 finally 里**:上面那句 cancel 打断的正是这个协程,写在 collect 之后
                // 的话被取消的那一轮永远停在 running=true,界面上是一个再也停不下来的转圈。
                // `_state.update` 不挂起,取消中照样执行;轮次已被"新会话"清掉时
                // [updateAgent] 找不到这个 id,自然什么都不做。
                updateAgent(turnId) { it.copy(running = false) }
                persistTurn(turnId, query)
            }
        }
    }

    /**
     * 把这一轮的界面状态写进库,供重新打开会话时原样回看。按轮次下标覆盖:重试的那一轮
     * 落的是同一条记录,不是第二条。
     */
    private fun persistTurn(turnId: Long, query: String) {
        val sid = sessionId ?: return
        val index = _state.value.agent.turns.indexOfFirst { it.id == turnId }
        val turn = _state.value.agent.turns.getOrNull(index) ?: return
        viewModelScope.launch {
            runCatching { sessions.saveTurn(sid, index, query, turn.result.copy(running = false)) }
                .onFailure { BiliLog.w("助理轮次落库失败", it) }
        }
    }

    private inline fun updateAgent(turnId: Long, crossinline block: (AgentTurnState) -> AgentTurnState) {
        _state.update { state ->
            state.copy(
                agent = state.agent.copy(
                    turns = state.agent.turns.map { turn ->
                        if (turn.id == turnId) turn.copy(result = block(turn.result)) else turn
                    },
                ),
            )
        }
    }

    private companion object {
        const val SUGGEST_DEBOUNCE_MS = 200L

        /** 会话标题取第一个问题的开头,列表里认得出是哪一段就够。 */
        const val SESSION_TITLE_LENGTH = 24
    }
}

