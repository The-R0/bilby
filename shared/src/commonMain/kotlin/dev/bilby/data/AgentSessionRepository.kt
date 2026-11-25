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
import kotlinx.serialization.json.JsonObject

/**
 * 助理会话与跨会话记忆的持久化层。
 *
 * 一段会话存三样东西:
 * - 消息: OpenAI 协议原样存(含 `extra_content`,保证 Gemini thought_signature 跨会话完整回放);
 * - 轮次: 渲染好的 AgentTurnState;
 * - 视频: 工具返回过的 bvid 溯源白名单与展示元数据。
 */
class AgentSessionRepository(
    private val db: BilbyDatabase,
    private val json: Json,
) {
    private val dao get() = db.agentSessionDao()

    fun observeSessions(): Flow<List<AgentSessionEntity>> = dao.observeSessions()

    suspend fun newSession(title: String, contextKey: String? = null): Long {
        val now = System.currentTimeMillis()
        return dao.insertSession(
            AgentSessionEntity(createdAt = now, lastActiveAt = now, title = title, contextKey = contextKey),
        )
    }

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
                    extraContentJson = message.extraContent?.let { json.encodeToString(it) },
                    createdAt = now,
                )
            },
        )
        dao.touchSession(sessionId, now)
    }

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

    suspend fun loadMessages(sessionId: Long): List<ChatMessage> =
        dao.getMessages(sessionId).map { entity ->
            ChatMessage(
                role = entity.role,
                content = entity.content,
                toolCalls = entity.toolCallsJson?.let { raw ->
                    runCatching { json.decodeFromString<List<ToolCall>>(raw) }.getOrNull()
                },
                toolCallId = entity.toolCallId,
                name = entity.name,
                extraContent = entity.extraContentJson?.let { raw ->
                    runCatching { json.decodeFromString<JsonObject>(raw) }.getOrNull()
                },
            )
        }

    suspend fun loadTurns(sessionId: Long): List<StoredTurn> =
        dao.getTurns(sessionId).mapNotNull { entity ->
            val state = runCatching { json.decodeFromString<AgentTurnState>(entity.stateJson) }
                .getOrNull() ?: return@mapNotNull null
            StoredTurn(query = entity.query, state = state.copy(running = false))
        }

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

    suspend fun deleteSessionByContext(contextKey: String) = dao.deleteSessionByContext(contextKey)

    // ---- 跨会话偏好记忆 ----

    fun observeMemories(): Flow<List<AgentMemoryEntity>> = dao.observeMemories()

    suspend fun recentMemories(limit: Int = INJECT_LIMIT): List<String> =
        dao.recentMemories(limit).map { it.content }.reversed()

    suspend fun addMemory(content: String): Boolean {
        if (dao.hasMemory(content)) return false
        dao.insertMemory(AgentMemoryEntity(content = content, createdAt = System.currentTimeMillis()))
        return true
    }

    suspend fun deleteMemory(id: Long) = dao.deleteMemory(id)

    companion object {
        const val INJECT_LIMIT = 30
    }
}

data class StoredTurn(val query: String?, val state: AgentTurnState)
