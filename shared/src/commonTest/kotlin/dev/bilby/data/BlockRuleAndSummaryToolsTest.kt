package dev.bilby.data

import dev.bilby.agent.formatTranscriptForModel
import dev.bilby.data.db.BlockRuleEntity
import dev.bilby.player.SubtitleCue
import dev.bilby.ui.components.parseTimestampMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockRuleAndSummaryToolsTest {

    @Test
    fun `本地屏蔽匹配器同时支持不喜欢 bvid、屏蔽 UP 主和忽略大小写关键词`() {
        val rules = listOf(
            BlockRuleEntity(id = 1, kind = BlockRuleEntity.KIND_UP, value = "42", label = "某营销号", createdAt = 1L),
            BlockRuleEntity(id = 2, kind = BlockRuleEntity.KIND_KEYWORD, value = "震惊", label = "震惊", createdAt = 2L),
            BlockRuleEntity(id = 3, kind = BlockRuleEntity.KIND_KEYWORD, value = "clickbait", label = "ClickBait", createdAt = 3L),
        )
        val matcher = ContentBlockMatcher.fromRules(rules)

        assertTrue(matcher.matchesVideo(bvid = "BV1disliked", dislikedBvids = setOf("BV1disliked")))
        assertTrue(matcher.matchesVideo(bvid = "BV1a", upMid = 42L, upName = "某营销号", title = "正常标题"))
        assertTrue(matcher.matchesVideo(bvid = "BV1b", upMid = 99L, upName = "路人", title = "太震惊了！必看"))
        assertTrue(matcher.matchesVideo(bvid = "BV1c", upMid = 99L, upName = "ClickBait Studio", title = "普通视频"))
        assertFalse(matcher.matchesVideo(bvid = "BV1d", upMid = 99L, upName = "硬核UP", title = "Compose 源码解析"))
        assertTrue(matcher.matchesUser(mid = 42L, name = "改了名字"))
        assertTrue(matcher.matchesUser(mid = 77L, name = "震惊部"))
        assertFalse(matcher.matchesUser(mid = 77L, name = "科普君"))
    }

    @Test
    fun `时间戳解析支持分秒与时分秒并拒绝非法数值`() {
        assertEquals(0L, parseTimestampMillis("00:00"))
        assertEquals(135_000L, parseTimestampMillis("02:15"))
        assertEquals(3_930_000L, parseTimestampMillis("1:05:30"))
        assertNull(parseTimestampMillis("02:60"))
        assertNull(parseTimestampMillis("1:65:00"))
        assertNull(parseTimestampMillis("abc"))
    }

    @Test
    fun `字幕转录格式化按窗口聚合并在超预算时保留首尾均匀采样`() {
        val shortCues = listOf(
            SubtitleCue(0L, 3_000L, "开场介绍"),
            SubtitleCue(3_500L, 6_000L, "主题说明"),
            SubtitleCue(12_000L, 15_000L, "核心结论"),
        )
        val shortText = formatTranscriptForModel(shortCues, charBudget = 1_000)
        assertTrue(shortText.contains("[00:00] 开场介绍 主题说明"))
        assertTrue(shortText.contains("[00:12] 核心结论"))

        val longCues = (0 until 120).map { i ->
            val start = i * 10_000L
            SubtitleCue(start, start + 9_000L, "第${i}段详细讲解内容" + "测试".repeat(20))
        }
        val sampled = formatTranscriptForModel(longCues, charBudget = 800)
        assertTrue(sampled.length <= 800)
        assertTrue(sampled.startsWith("[00:00]"))
        assertTrue(sampled.contains("第119段") || sampled.contains("第118段"))
    }
}
