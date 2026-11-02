package dev.bilby.data.db

import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

@Dao
interface FeedReadPositionDao {
    @Query("SELECT * FROM feed_read_position WHERE id = 0")
    fun observe(): Flow<FeedReadPositionEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FeedReadPositionEntity)
}

@Dao
interface FeedCacheItemDao {
    @Query("SELECT * FROM feed_cache_item ORDER BY sortIndex ASC")
    suspend fun loadAllOrdered(): List<FeedCacheItemEntity>

    @Query("DELETE FROM feed_cache_item")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<FeedCacheItemEntity>)

    /**
     * 整段换掉。**清空再写,不是逐条 upsert** —— 缓存存的是"服务端最新那一页现在长什么样",
     * 而 upsert 表达不了"这一条不在了":取关之后那个人的投稿就是靠这一步消失的。
     */
    @Transaction
    suspend fun replaceAll(items: List<FeedCacheItemEntity>) {
        deleteAll()
        insertAll(items)
    }
}

@Dao
interface AgentSessionDao {
    @Insert
    suspend fun insertSession(entity: AgentSessionEntity): Long

    @Query("UPDATE agent_session SET lastActiveAt = :lastActiveAt WHERE id = :sessionId")
    suspend fun touchSession(sessionId: Long, lastActiveAt: Long)

    /** 会话列表按最近活跃倒序。找相关的会话(contextKey 非空)不进这个列表,它属于那条视频。 */
    @Query("SELECT * FROM agent_session WHERE contextKey IS NULL ORDER BY lastActiveAt DESC")
    fun observeSessions(): Flow<List<AgentSessionEntity>>

    /** 「找相关」的会话按所属视频找:一条视频至多一段。 */
    @Query("SELECT * FROM agent_session WHERE contextKey = :contextKey ORDER BY lastActiveAt DESC LIMIT 1")
    suspend fun getSessionByContext(contextKey: String): AgentSessionEntity?

    @Insert
    suspend fun insertMessages(entities: List<AgentMessageEntity>)

    @Query("SELECT * FROM agent_message WHERE sessionId = :sessionId ORDER BY seq ASC")
    suspend fun getMessages(sessionId: Long): List<AgentMessageEntity>

    @Query("SELECT COALESCE(MAX(seq), -1) FROM agent_message WHERE sessionId = :sessionId")
    suspend fun getMaxSeq(sessionId: Long): Int

    /** 按 (sessionId, seq) 去重:重试的那一轮覆盖旧行,不追加。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTurn(entity: AgentTurnEntity)

    @Query("SELECT * FROM agent_turn WHERE sessionId = :sessionId ORDER BY seq ASC")
    suspend fun getTurns(sessionId: Long): List<AgentTurnEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVideos(entities: List<AgentVideoEntity>)

    @Query("SELECT * FROM agent_video WHERE sessionId = :sessionId")
    suspend fun getVideos(sessionId: Long): List<AgentVideoEntity>

    // 消息、轮次与视频表都设了 onDelete = CASCADE,删会话自动带走。
    @Query("DELETE FROM agent_session WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)

    @Query("DELETE FROM agent_session WHERE contextKey = :contextKey")
    suspend fun deleteSessionByContext(contextKey: String)

    @Insert
    suspend fun insertMemory(entity: AgentMemoryEntity): Long

    @Query("SELECT * FROM agent_memory ORDER BY createdAt DESC")
    fun observeMemories(): Flow<List<AgentMemoryEntity>>

    @Query("SELECT * FROM agent_memory ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentMemories(limit: Int): List<AgentMemoryEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM agent_memory WHERE content = :content)")
    suspend fun hasMemory(content: String): Boolean

    @Query("DELETE FROM agent_memory WHERE id = :id")
    suspend fun deleteMemory(id: Long)
}

@Database(
    entities = [
        FeedReadPositionEntity::class,
        FeedCacheItemEntity::class,
        AgentSessionEntity::class,
        AgentMessageEntity::class,
        AgentTurnEntity::class,
        AgentVideoEntity::class,
        AgentMemoryEntity::class,
    ],
    version = 10,
    exportSchema = false,
)
@ConstructedBy(BilbyDatabaseConstructor::class)
abstract class BilbyDatabase : RoomDatabase() {
    abstract fun feedReadPositionDao(): FeedReadPositionDao
    abstract fun feedCacheItemDao(): FeedCacheItemDao
    abstract fun agentSessionDao(): AgentSessionDao

    companion object {
        /** 文件位置与 SQLite 驱动由平台给出 [builder],其余配置两端相同。 */
        fun create(builder: RoomDatabase.Builder<BilbyDatabase>): BilbyDatabase =
            builder
                .setQueryCoroutineContext(Dispatchers.IO)
                // 两张表(读到哪了、动态流缓存条目)都是可再生的派生数据,个人应用不值得为
                // 它们维护迁移脚本 —— 版本升级直接丢重建。缓存表丢了的后果只是下次进动态流
                // 又要空等一次网络(退回 v5 之前的首屏转圈),不影响正确性。
                //
                // 续接游标表(v7 删)曾在这里。它是为"翻页翻到的深度要跨进程留住"服务的,
                // 而那份深列表同时也是一份没人能删掉东西的第二真值:取关的人不会消失、
                // 头部刷新不敢覆盖游标,都是它带来的。改成只留最新一段之后它没有了用处。
                //
                // 播放进度表(v4 删)曾在这里。它的位置读自全 app 唯一的播放器,按页面身份
                // 落盘会串味,改成不存。
                //
                // agent 会话五张表(v10 回)在 v5 时被删过一次:当时只有"最近一段"一个消费方,
                // 落库没有意义。现在会话列表与跨会话记忆是真实消费方,表回来了。
                .fallbackToDestructiveMigration(dropAllTables = false)
                .build()
    }
}

/** Room 在非 Android 目标上靠它构造数据库,实现由 KSP 按平台生成。 */
@Suppress("KotlinNoActualForExpect")
expect object BilbyDatabaseConstructor : RoomDatabaseConstructor<BilbyDatabase> {
    override fun initialize(): BilbyDatabase
}
