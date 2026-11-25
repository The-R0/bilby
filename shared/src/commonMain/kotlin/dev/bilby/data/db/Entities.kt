package dev.bilby.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// 播放进度曾经在这里存过一张 playback_progress 表,已删除(DB v4)。续播只认服务端的
// last_play_time,理由见 VideoRepository 的 resumeAtMillisFor:那份本地进度按 bvid 落盘、
// 数字却读自全 app 唯一的那个播放器,翻页时会把上一条的位置写到新一条头上。

/**
 * 动态流读到哪儿了(DESIGN 2.1)。存的是条目 id 而不是列表下标:下标会因为新投稿插入顶部
 * 而整体位移,id 不会。id 的取值见 [dev.bilby.data.model.FeedEntry.id] —— 视频和专栏共用
 * 这一份记录,所以不叫 bvid。
 */
@Entity(tableName = "feed_read_position")
data class FeedReadPositionEntity(
    @PrimaryKey val id: Int = SINGLE_ROW,
    val lastReadEntryId: String,
    val updatedAt: Long,
) {
    companion object {
        const val SINGLE_ROW = 0
    }
}

/**
 * 动态流本地缓存的一条(DESIGN 2.1)。进屏时先渲染这张表里的内容,后台再拿一次头部把它整段
 * 换掉,首屏才不用空等一轮网络。
 *
 * **只存最新那一段,翻页翻出来的更旧内容不落盘。** 缓存要能被服务端的一次响应整段重建,
 * 才谈得上"取关的人会消失":本地列表一旦能独立于服务端往下长,就必须自己实现删除,而那件事
 * 这里从来没有做对过。
 *
 * `sortIndex` 是抓取顺序,不是发布时间:同一批条目的发布时间可能打平,记抓取顺序才能保证
 * 恢复出来的列表和当时看到的一致。**这里不存排除名单**——排除是可撤销的设置,过滤放在读出来
 * 之后做,烤进缓存的话取消排除之后那些条目永远回不来。
 *
 * 两种投稿共用一张表,[kind] 决定哪几列有意义。分成两张表要在恢复时按 sortIndex 归并,
 * 而它们本来就是一条时间序流上的东西。
 */
@Entity(tableName = "feed_cache_item")
data class FeedCacheItemEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val sortIndex: Long,
    val title: String,
    val coverUrl: String,
    val upName: String,
    val upMid: Long,
    val upFaceUrl: String = "",
    val publishedAtEpochSeconds: Long,
    /** 以下四列只有 [kind] 为 [KIND_VIDEO] 时有意义。 */
    val durationText: String = "",
    val playCount: String = "",
    val danmakuCount: String = "",
    val chargingOnly: Boolean = false,
    /** 以下三列只有 [kind] 为 [KIND_ARTICLE] 时有意义。 */
    val summary: String = "",
    val articleId: String = "",
    val articleIsRead: Boolean = false,
) {
    companion object {
        const val KIND_VIDEO = "video"
        const val KIND_ARTICLE = "article"
    }
}

/**
 * 助理的一段会话。搜索页的对话与播放页「找相关」是同一张表,[contextKey] 区分:
 * 搜索会话是 null,找相关会话存它所属视频的 bvid —— 打开那条视频时按它找回这一段。
 */
@Entity(tableName = "agent_session")
data class AgentSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val lastActiveAt: Long,
    val title: String,
    val contextKey: String? = null,
)

/**
 * 会话内的一条消息,对应 LlmProtocol.ChatMessage,原样回放给模型续上下文(含 Gemini thought_signature)。
 */
@Entity(
    tableName = "agent_message",
    foreignKeys = [
        ForeignKey(
            entity = AgentSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class AgentMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val seq: Int,
    val role: String,
    val content: String?,
    val toolCallsJson: String?,
    val toolCallId: String?,
    val name: String?,
    val extraContentJson: String? = null,
    val createdAt: Long,
)

/** 一轮在界面上的样子:序列化的 AgentTurnState。 */
@Entity(
    tableName = "agent_turn",
    foreignKeys = [
        ForeignKey(
            entity = AgentSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sessionId", "seq"], unique = true)],
)
data class AgentTurnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val seq: Int,
    val query: String?,
    val stateJson: String,
    val createdAt: Long,
)

/** 工具返回过的视频(溯源白名单与卡片元数据)。 */
@Entity(
    tableName = "agent_video",
    primaryKeys = ["sessionId", "bvid"],
    foreignKeys = [
        ForeignKey(
            entity = AgentSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class AgentVideoEntity(
    val sessionId: Long,
    val bvid: String,
    val title: String?,
    val coverUrl: String?,
    val upName: String?,
)

/** 助理通过 save_memory 记下的跨会话偏好/事实。 */
@Entity(tableName = "agent_memory")
data class AgentMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val createdAt: Long,
)

/**
 * 用户长按标记「我不喜欢」的视频记录。
 *
 * 提交时会同步调用 B 站 App 端点踩与负反馈接口,同时保存在本地供内置 AI 助手在推荐时主动避开。
 */
@Entity(tableName = "disliked_video")
data class DislikedVideoEntity(
    @PrimaryKey val bvid: String,
    val title: String,
    val upMid: Long,
    val upName: String,
    val syncedToBili: Boolean,
    val createdAt: Long,
)
