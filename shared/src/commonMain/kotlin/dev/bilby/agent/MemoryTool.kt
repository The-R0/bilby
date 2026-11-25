package dev.bilby.agent

import dev.bilby.BiliLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val MAX_FACT_LENGTH = 200

/**
 * 长期记忆写入口:记住用户喜欢的视频题材、风格、UP 主,或明确表示不想看的内容类型/雷区。
 */
fun saveMemoryTool(save: suspend (String) -> Boolean): Tool = object : Tool {
    override val name = "save_memory"
    override val description =
        "把一条关于用户的长期偏好事实存进记忆(喜欢看的内容类型/风格/UP主、偏好的时长深度,或明确不想看/避开的主题)。" +
            "只在用户流露出稳定偏好、明确要求记住,或整理兴趣画像时调用;单次临时提问或普通检索结果不需要记。"
    override val parameters = Json.parseToJsonElement(
        """
        {
          "type": "object",
          "properties": {
            "fact": {"type": "string", "description": "要记住的偏好事实,一句话,陈述句"}
          },
          "required": ["fact"]
        }
        """,
    ).jsonObject

    override val kind = StepKind.Memory
    override fun subject(arguments: JsonObject, names: NameBook) =
        arguments["fact"]?.jsonPrimitive?.contentOrNull.orEmpty()

    override suspend fun execute(arguments: JsonObject): ToolResult = runCatching {
        val fact = arguments["fact"]?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.take(MAX_FACT_LENGTH)
        if (fact.isNullOrEmpty()) {
            ToolResult(forModel = "事实为空,没有记")
        } else if (save(fact)) {
            ToolResult(forModel = "已记住")
        } else {
            ToolResult(forModel = "这条已经记过了,不用重复记")
        }
    }.getOrElse {
        BiliLog.w("工具 $name 执行异常", it)
        ToolResult(forModel = "记的时候出错了:${it.message}")
    }
}
