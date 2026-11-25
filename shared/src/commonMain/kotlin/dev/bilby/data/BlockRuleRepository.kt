package dev.bilby.data

import dev.bilby.data.db.BilbyDatabase
import dev.bilby.data.db.BlockRuleEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 本地内容屏蔽匹配器（按 UP 主 mid 与标题/UP 名关键词，大小写不敏感）。
 */
data class ContentBlockMatcher(
    val blockedUpMids: Set<Long> = emptySet(),
    val blockedKeywords: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = blockedUpMids.isEmpty() && blockedKeywords.isEmpty()

    fun matchesVideo(
        bvid: String,
        upMid: Long = 0L,
        upName: String = "",
        title: String = "",
        dislikedBvids: Set<String> = emptySet(),
    ): Boolean {
        if (bvid.isNotEmpty() && bvid in dislikedBvids) return true
        if (upMid != 0L && upMid in blockedUpMids) return true
        if (blockedKeywords.isNotEmpty()) {
            for (kw in blockedKeywords) {
                if (title.contains(kw, ignoreCase = true) || upName.contains(kw, ignoreCase = true)) {
                    return true
                }
            }
        }
        return false
    }

    fun matchesUser(mid: Long, name: String): Boolean {
        if (mid != 0L && mid in blockedUpMids) return true
        if (blockedKeywords.isNotEmpty()) {
            for (kw in blockedKeywords) {
                if (name.contains(kw, ignoreCase = true)) return true
            }
        }
        return false
    }

    companion object {
        val EMPTY = ContentBlockMatcher()

        fun fromRules(rules: List<BlockRuleEntity>): ContentBlockMatcher {
            if (rules.isEmpty()) return EMPTY
            val mids = mutableSetOf<Long>()
            val keywords = mutableListOf<String>()
            for (rule in rules) {
                when (rule.kind) {
                    BlockRuleEntity.KIND_UP -> rule.value.toLongOrNull()?.takeIf { it > 0L }?.let { mids += it }
                    BlockRuleEntity.KIND_KEYWORD -> rule.value.trim().takeIf { it.isNotEmpty() }?.let { keywords += it }
                }
            }
            return ContentBlockMatcher(blockedUpMids = mids, blockedKeywords = keywords)
        }
    }
}

/**
 * 本地屏蔽规则仓库：屏蔽指定 UP 主或标题关键词，在动态、搜索和内置 AI 助手中实时生效。
 */
class BlockRuleRepository(private val db: BilbyDatabase) {
    private val dao get() = db.blockRuleDao()

    fun observeRules(): Flow<List<BlockRuleEntity>> = dao.observeAll()

    fun observeMatcher(): Flow<ContentBlockMatcher> = dao.observeAll().map(ContentBlockMatcher::fromRules)

    suspend fun currentMatcher(): ContentBlockMatcher = ContentBlockMatcher.fromRules(dao.all())

    suspend fun addUpBlock(mid: Long, name: String) {
        if (mid <= 0L) return
        dao.upsert(
            BlockRuleEntity(
                kind = BlockRuleEntity.KIND_UP,
                value = mid.toString(),
                label = name.ifBlank { "UID $mid" },
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun removeUpBlock(mid: Long) {
        if (mid <= 0L) return
        dao.deleteByKindAndValue(BlockRuleEntity.KIND_UP, mid.toString())
    }

    suspend fun addKeywordBlock(keyword: String): Boolean {
        val normalized = keyword.trim()
        if (normalized.isEmpty()) return false
        dao.upsert(
            BlockRuleEntity(
                kind = BlockRuleEntity.KIND_KEYWORD,
                value = normalized.lowercase(),
                label = normalized,
                createdAt = System.currentTimeMillis(),
            ),
        )
        return true
    }

    suspend fun removeRule(id: Long) {
        dao.deleteById(id)
    }

    suspend fun promptSummary(limit: Int = 20): List<String> =
        dao.all().take(limit).map { rule ->
            when (rule.kind) {
                BlockRuleEntity.KIND_UP -> "屏蔽 UP 主：${rule.label}"
                else -> "屏蔽关键词：${rule.label}"
            }
        }
}
