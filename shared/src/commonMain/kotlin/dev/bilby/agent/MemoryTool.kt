package dev.bilby.agent

import dev.bilby.BiliLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 记忆是一句事实,不是一篇文章。 */
private const val MAX_FACT_LENGTH = 200

/**
 * 长期记忆的写入口。它不在 [createBiliTools] 里 —— 那一份是"在 B 站找内容"的动作集,
 * 这一条动的是本地库,给谁都能跑(OpenAI 协议的工具调用而已)。
 *
 * 记什么由模型自己判断,什么时候值得记在 description 里写明;**写多少、回多长由代码管**:
 * 超长截断、重复免写,这两条放在这里是因为它们是规矩,不是请求。
 *
 * @param save 落库动作(AppContainer 接 AgentSessionRepository.addMemory)。返回 false
 *   表示同样的内容已经存在,模型收到的是"记过了"而不是又一次成功。
 */
fun saveMemoryTool(save: suspend (String) -> Boolean): Tool = object : Tool {
    override val name = "save_memory"
    override val description =
        "把一条关于用户的、以后的对话里还用得上的事实存进长期记忆(偏好、口味、明确说过的习惯)。" +
            "只在用户流露出稳定偏好或明确要求记住时用;问题本身、检索到的视频都不值得记。"
    override val parameters = Json.parseToJsonElement(
        """
        {
          "type": "object",
          "properties": {
            "fact": {"type": "string", "description": "要记住的事实,一句话,陈述句"}
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
