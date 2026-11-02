package dev.bilby.data

import dev.bilby.agent.AgentTurnState
import dev.bilby.agent.ChatMessage
import dev.bilby.agent.ToolCall
import dev.bilby.agent.TraceItem
import dev.bilby.data.db.AgentMemoryEntity
import dev.bilby.data.db.AgentMessageEntity
import dev.bilby.data.db.AgentSessionEntity
import dev.bilby.data.db.AgentTurnEntity
import dev.bilby.data.db.AgentVideoEntity
import dev.bilby.data.db.BilbyDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

/**
 * 助理会话的持久化层。只负责存取,不做会话管理(打开/删除的动作在调用方)。
 *
 * 一段会话存三样东西,各自伺候各自的消费方:
 * - 消息:OpenAI 协议原样存,续上下文时整段回放给模型;
 * - 轮次:渲染好的 AgentTurnState,重新打开时照原样上屏;
 * - 视频:工具返回过的 bvid,下一轮溯源校验的白名单,展示信息顺带做卡片。
 *
 * 「找相关」的会话挂在视频上(contextKey = bvid),不进搜索页的会话列表。
 */
class AgentSessionRepository(
    private val db: BilbyDatabase,
    private val json: Json,
) {
    private val dao get() = db.agentSessionDao()

    /**
     * 搜索页可挑的会话列表。只列搜索会话(contextKey 为空):找相关的那些属于各自的视频页,
     * 在这里出现点过去也找不到上下文。
     */
    fun observeSessions(): Flow<List<AgentSessionEntity>> = dao.observeSessions()

    suspend fun newSession(title: String, contextKey: String? = null): Long {
        val now = System.currentTimeMillis()
        return dao.insertSession(
            AgentSessionEntity(createdAt = now, lastActiveAt = now, title = title, contextKey = contextKey),
        )
    }

    /** 「找相关」的会话按视频找,没有就是没问过。 */
    suspend fun getSessionByContext(contextKey: String): AgentSessionEntity? =
        dao.getSessionByContext(contextKey)

    suspend fun appendMessages(sessionId: Long, messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        val now = System.currentTimeMillis()
        var seq = dao.getMaxSeq(sessionId)
        dao.insertMessages(
            messages.map { message ->
                seq += 1
                AgentMessageEntity(
                    sessionId = sessionId,
                    seq = seq,
                    role = message.role,
                    content = message.content,
                    toolCallsJson = message.toolCalls?.let { json.encodeToString(it) },
                    toolCallId = message.toolCallId,
                    name = message.name,
                    createdAt = now,
                )
            },
        )
        dao.touchSession(sessionId, now)
    }

    /**
     * 一轮的界面状态整份存。[seq] 是它在会话里的下标(轮次列表里的位置),重试覆盖同一行。
     * [state] 传入前已停跑(running = false 由调用方归置)。
     */
    suspend fun saveTurn(sessionId: Long, seq: Int, query: String?, state: AgentTurnState) {
        dao.upsertTurn(
            AgentTurnEntity(
                sessionId = sessionId,
                seq = seq,
                query = query,
                stateJson = json.encodeToString(state),
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * 记下这一轮工具返回过的视频。[seenBvids] 是溯源白名单的全部,[traces] 里有展示信息的
     * 才填展示列 —— 搜索用户这类返回只有 bvid。
     */
    suspend fun upsertVideos(sessionId: Long, seenBvids: Set<String>, traces: Map<String, TraceItem>) {
        if (seenBvids.isEmpty()) return
        dao.upsertVideos(
            seenBvids.map { bvid ->
                val trace = traces[bvid]
                AgentVideoEntity(
                    sessionId = sessionId,
                    bvid = bvid,
                    title = trace?.title,
                    coverUrl = trace?.coverUrl,
                    upName = trace?.upName,
                )
            },
        )
    }

    /** 还原成 AgentLoop 能直接塞回 LLM 请求的形状。 */
    suspend fun loadMessages(sessionId: Long): List<ChatMessage> =
        dao.getMessages(sessionId).map { entity ->
            ChatMessage(
                role = entity.role,
                content = entity.content,
                toolCalls = entity.toolCallsJson?.let { json.decodeFromString<List<ToolCall>>(it) },
                toolCallId = entity.toolCallId,
                name = entity.name,
            )
        }

    /** 还原轮次的界面状态。坏掉的一行跳过:丢一轮比整段会话打不开好。 */
    suspend fun loadTurns(sessionId: Long): List<StoredTurn> =
        dao.getTurns(sessionId).mapNotNull { entity ->
            val state = runCatching { json.decodeFromString<AgentTurnState>(entity.stateJson) }
                .getOrNull() ?: return@mapNotNull null
            StoredTurn(query = entity.query, state = state.copy(running = false))
        }

    /** 白名单与展示信息一并读出:展示列缺一个就不进 traces,只留在白名单里。 */
    suspend fun loadVideos(sessionId: Long): Pair<Set<String>, Map<String, TraceItem>> {
        val rows = dao.getVideos(sessionId)
        val traces = rows.mapNotNull { entity ->
            if (entity.title == null || entity.coverUrl == null || entity.upName == null) {
                null
            } else {
                entity.bvid to TraceItem(
                    bvid = entity.bvid,
                    title = entity.title,
                    coverUrl = entity.coverUrl,
                    upName = entity.upName,
                )
            }
        }.toMap()
        return rows.mapTo(mutableSetOf()) { it.bvid } to traces
    }

    suspend fun deleteSession(sessionId: Long) = dao.deleteSession(sessionId)

    /** 「重新检索」时连会话一起清:下一段找相关是全新的一段。 */
    suspend fun deleteSessionByContext(contextKey: String) = dao.deleteSessionByContext(contextKey)

    // ---- 跨会话记忆 ----

    fun observeMemories(): Flow<List<AgentMemoryEntity>> = dao.observeMemories()

    /** 注入 prompt 的那份:最近 [limit] 条,旧的在前(读到的是一份从远到近的清单)。 */
    suspend fun recentMemories(limit: Int = INJECT_LIMIT): List<String> =
        dao.recentMemories(limit).map { it.content }.reversed()

    /** @return false 表示同样的内容已经记过,不写第二遍。 */
    suspend fun addMemory(content: String): Boolean {
        if (dao.hasMemory(content)) return false
        dao.insertMemory(AgentMemoryEntity(content = content, createdAt = System.currentTimeMillis()))
        return true
    }

    suspend fun deleteMemory(id: Long) = dao.deleteMemory(id)

    companion object {
        /** 取给 prompt 的记忆条数。AgentLoop 那边还有一道同值的硬封顶,两层同一条规矩。 */
        const val INJECT_LIMIT = 30
    }
}

/** 库里还原出来的一轮:用户的提问与它的界面状态。 */
data class StoredTurn(val query: String?, val state: AgentTurnState)
