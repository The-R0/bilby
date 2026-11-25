package dev.bilby.data

import dev.bilby.data.db.BilbyDatabase
import dev.bilby.data.db.DislikedVideoEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 「我不喜欢」反馈仓库:一边真实调用 B 站 App 端点踩与不感兴趣接口,一边落库供内置 AI 助手避开。
 */
class DislikeRepository(
    private val db: BilbyDatabase,
    private val videoActionRepository: VideoActionRepository,
) {
    private val dao get() = db.dislikedVideoDao()

    fun observeDisliked(): Flow<List<DislikedVideoEntity>> = dao.observeAll()

    fun observeDislikedBvids(): Flow<Set<String>> = dao.observeAll().map { rows ->
        rows.mapTo(mutableSetOf()) { it.bvid }
    }

    suspend fun recentDisliked(limit: Int = 50): List<DislikedVideoEntity> = dao.recent(limit)

    suspend fun allDislikedBvids(): Set<String> = dao.allBvids().toSet()

    suspend fun dislike(
        bvid: String,
        title: String,
        upMid: Long = 0L,
        upName: String = "",
    ): DislikeOutcome {
        val outcome = videoActionRepository.dislikeVideo(bvid = bvid, upMid = upMid)
        dao.upsert(
            DislikedVideoEntity(
                bvid = bvid,
                title = title,
                upMid = upMid,
                upName = upName,
                syncedToBili = outcome.syncedToBilibili,
                createdAt = System.currentTimeMillis(),
            ),
        )
        return outcome
    }

    suspend fun undoDislike(bvid: String, upMid: Long = 0L): Boolean {
        dao.delete(bvid)
        return videoActionRepository.cancelDislikeVideo(bvid = bvid, upMid = upMid)
    }

    suspend fun removeLocalRecord(bvid: String) {
        dao.delete(bvid)
    }
}
