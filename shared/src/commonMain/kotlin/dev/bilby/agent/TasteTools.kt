package dev.bilby.agent

import dev.bilby.BiliLog
import dev.bilby.api.BiliResult
import dev.bilby.data.AgentSessionRepository
import dev.bilby.data.DislikeRepository
import dev.bilby.data.FavOrder
import dev.bilby.data.FavRepository
import dev.bilby.data.FollowOrder
import dev.bilby.data.FollowRepository
import dev.bilby.data.HistoryRepository
import dev.bilby.formatDurationSeconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val MAX_HISTORY_ITEMS = 20
private const val MAX_FAV_ITEMS = 20
private const val MAX_FOLLOWING_ITEMS = 30
private const val MAX_DISLIKE_ITEMS = 30

/**
 * 个人口味发现工具集:仅在用户向内置 AI 助手询问个性化推荐或品味总结时由模型主动调用。
 * 全部复用现有仓库与本地库,不引入额外接口。
 */
fun createTasteTools(
    historyRepository: HistoryRepository,
    favRepository: FavRepository,
    followRepository: FollowRepository,
    sessionRepository: AgentSessionRepository,
    dislikeRepository: DislikeRepository,
): List<Tool> = listOf(
    getWatchHistoryTool(historyRepository),
    getFavoritesTool(favRepository),
    getFollowingsTool(followRepository),
    getUserTastesTool(sessionRepository, dislikeRepository),
)

private fun getWatchHistoryTool(historyRepository: HistoryRepository): Tool = object : Tool {
    override val name = "get_watch_history"
    override val description =
        "读取用户最近看过的 B 站观看历史(最多 20 条),用来了解用户近期关注的话题、风格与常看 UP 主。" +
            "推荐新视频时请以此为种子去搜同类型或延伸内容,避免重复推荐用户已经看过的条目。"
    override val parameters = tasteSchema(
        """
        {
          "type": "object",
          "properties": {
            "limit": {"type": "integer", "description": "返回条数,默认 15,最多 20"}
          }
        }
        """,
    )
    override val kind = StepKind.Taste
    override fun subject(arguments: JsonObject, names: NameBook) = "最近观看历史"

    override suspend fun execute(arguments: JsonObject): ToolResult = runCatching {
        val limit = (arguments["limit"]?.jsonPrimitive?.intOrNull ?: 15).coerceIn(1, MAX_HISTORY_ITEMS)
        when (val result = historyRepository.loadPage(max = 0L, viewAt = 0L)) {
            is BiliResult.Ok -> {
                val items = result.value.items.take(limit)
                if (items.isEmpty()) {
                    ToolResult(forModel = "近期观看历史为空")
                } else {
                    val lines = items.joinToString("\n") { item ->
                        val status = if (item.isFinished) "已看完" else "看到${formatDurationSeconds(item.progressSeconds)}"
                        "${item.bvid} | ${item.title} | UP:${item.upName}(mid:${item.upMid}) | ${item.durationText} | $status"
                    }
                    ToolResult(
                        forModel = "最近看过的视频(推荐新内容时请避免重复推这些已看视频,可用来做相关延伸):\n$lines",
                        bvids = items.mapTo(mutableSetOf()) { it.bvid },
                        upNames = items.filter { it.upMid > 0L }.associate { it.upMid to it.upName },
                    )
                }
            }
            is BiliResult.ApiError -> ToolResult(forModel = "读取观看历史失败(${result.code}): ${result.message}")
            is BiliResult.Failure -> ToolResult(forModel = "读取观看历史异常: ${result.cause.message}")
        }
    }.getOrElse {
        BiliLog.w("工具 $name 执行异常", it)
        ToolResult(forModel = "读取观看历史异常:${it.message}")
    }
}

private fun getFavoritesTool(favRepository: FavRepository): Tool = object : Tool {
    override val name = "get_favorites"
    override val description =
        "查看用户的收藏夹。不传 media_id 时列出全部收藏夹名称与条数;传入 media_id 时读取该收藏夹最近收藏的视频(最多 20 条),用来发现用户深度喜欢的内容类型。"
    override val parameters = tasteSchema(
        """
        {
          "type": "object",
          "properties": {
            "media_id": {"type": "integer", "description": "收藏夹 id;不传则列出全部收藏夹"}
          }
        }
        """,
    )
    override val kind = StepKind.Taste
    override fun subject(arguments: JsonObject, names: NameBook): String {
        val mediaId = arguments["media_id"]?.jsonPrimitive?.longOrNull
        return if (mediaId != null && mediaId > 0L) "收藏夹 $mediaId" else "收藏夹列表"
    }

    override suspend fun execute(arguments: JsonObject): ToolResult = runCatching {
        val mediaId = arguments["media_id"]?.jsonPrimitive?.longOrNull ?: 0L
        if (mediaId <= 0L) {
            when (val result = favRepository.folderPage(1)) {
                is BiliResult.Ok -> {
                    val folders = result.value.items
                    if (folders.isEmpty()) {
                        ToolResult(forModel = "用户没有收藏夹")
                    } else {
                        val lines = folders.joinToString("\n") { f ->
                            "media_id:${f.id} | ${f.title} | ${f.count}条" +
                                if (f.intro.isNotBlank()) " | ${f.intro.take(60)}" else ""
                        }
                        ToolResult(forModel = "用户收藏夹列表:\n$lines")
                    }
                }
                is BiliResult.ApiError -> ToolResult(forModel = "读取收藏夹列表失败(${result.code}): ${result.message}")
                is BiliResult.Failure -> ToolResult(forModel = "读取收藏夹列表异常: ${result.cause.message}")
            }
        } else {
            when (val result = favRepository.folderContents(mediaId, page = 1, order = FavOrder.Mtime)) {
                is BiliResult.Ok -> {
                    val items = result.value.items.filter { it.playable }.take(MAX_FAV_ITEMS)
                    if (items.isEmpty()) {
                        ToolResult(forModel = "该收藏夹暂无可用视频")
                    } else {
                        val lines = items.joinToString("\n") { v ->
                            "${v.bvid} | ${v.title} | UP:${v.upName}(mid:${v.upMid}) | ${formatDurationSeconds(v.durationSeconds)}"
                        }
                        ToolResult(
                            forModel = "收藏夹内视频(可用于理解用户高认可度口味并查找同类型新视频):\n$lines",
                            bvids = items.mapTo(mutableSetOf()) { it.bvid },
                            upNames = items.filter { it.upMid > 0L }.associate { it.upMid to it.upName },
                        )
                    }
                }
                is BiliResult.ApiError -> ToolResult(forModel = "读取收藏夹内容失败(${result.code}): ${result.message}")
                is BiliResult.Failure -> ToolResult(forModel = "读取收藏夹内容异常: ${result.cause.message}")
            }
        }
    }.getOrElse {
        BiliLog.w("工具 $name 执行异常", it)
        ToolResult(forModel = "读取收藏夹异常:${it.message}")
    }
}

private fun getFollowingsTool(followRepository: FollowRepository): Tool = object : Tool {
    override val name = "get_followings"
    override val description =
        "列出用户最常访问的已关注 UP 主(最多 30 位),用来了解用户长期喜欢的创作者与内容方向。"
    override val parameters = tasteSchema(
        """
        {
          "type": "object",
          "properties": {}
        }
        """,
    )
    override val kind = StepKind.Taste
    override fun subject(arguments: JsonObject, names: NameBook) = "常看关注 UP"

    override suspend fun execute(arguments: JsonObject): ToolResult = runCatching {
        when (val result = followRepository.followings(page = 1, order = FollowOrder.Frequent)) {
            is BiliResult.Ok -> {
                val ups = result.value.items.take(MAX_FOLLOWING_ITEMS)
                if (ups.isEmpty()) {
                    ToolResult(forModel = "关注列表为空")
                } else {
                    val lines = ups.joinToString("\n") { up ->
                        "mid:${up.mid} | ${up.name}" +
                            if (up.sign.isNotBlank()) " | ${up.sign.replace('\n', ' ').take(60)}" else ""
                    }
                    ToolResult(
                        forModel = "用户最常访问的关注 UP 主:\n$lines",
                        upNames = ups.associate { it.mid to it.name },
                    )
                }
            }
            is BiliResult.ApiError -> ToolResult(forModel = "读取关注列表失败(${result.code}): ${result.message}")
            is BiliResult.Failure -> ToolResult(forModel = "读取关注列表异常: ${result.cause.message}")
        }
    }.getOrElse {
        BiliLog.w("工具 $name 执行异常", it)
        ToolResult(forModel = "读取关注列表异常:${it.message}")
    }
}

private fun getUserTastesTool(
    sessionRepository: AgentSessionRepository,
    dislikeRepository: DislikeRepository,
): Tool = object : Tool {
    override val name = "get_user_tastes"
    override val description =
        "读取本地保存的用户长期偏好记忆,以及用户标记过「我不喜欢」的视频/UP 主清单。推荐前务必避开这些雷区。"
    override val parameters = tasteSchema(
        """
        {
          "type": "object",
          "properties": {}
        }
        """,
    )
    override val kind = StepKind.Memory
    override fun subject(arguments: JsonObject, names: NameBook) = "偏好记忆与不喜欢清单"

    override suspend fun execute(arguments: JsonObject): ToolResult = runCatching {
        val memories = sessionRepository.recentMemories(AgentSessionRepository.INJECT_LIMIT)
        val dislikes = dislikeRepository.recentDisliked(MAX_DISLIKE_ITEMS)
        val text = buildString {
            if (memories.isNotEmpty()) {
                appendLine("已记录的用户偏好:")
                memories.forEach { appendLine("- $it") }
            } else {
                appendLine("已记录的用户偏好: 暂无")
            }
            if (dislikes.isNotEmpty()) {
                appendLine()
                appendLine("用户标记为「我不喜欢」的视频(严禁推荐这些视频,并避开同 UP 或同类雷区):")
                dislikes.forEach { d ->
                    val upPart = if (d.upName.isNotBlank()) " | UP:${d.upName}" else ""
                    appendLine("- ${d.bvid} | ${d.title.ifBlank { "(无标题)" }}$upPart")
                }
            } else {
                appendLine("用户标记为「我不喜欢」的视频: 暂无")
            }
        }.trim()
        ToolResult(forModel = text)
    }.getOrElse {
        BiliLog.w("工具 $name 执行异常", it)
        ToolResult(forModel = "读取本地偏好失败:${it.message}")
    }
}

private fun tasteSchema(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject
