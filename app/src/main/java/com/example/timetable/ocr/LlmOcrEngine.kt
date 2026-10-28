package com.example.timetable.ocr

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 大模型识图引擎：接入任意主流多模态大模型（GPT-4o、通义千问 Qwen-VL、
 * 智谱 GLM-4V、Kimi、豆包等），通过「内置课表调教提示词」让模型直接输出
 * 结构化课表 JSON，无需版面坐标分析。
 *
 * 协议支持：
 *  - OpenAI 兼容（/chat/completions，绝大多数平台：OpenAI、DashScope、智谱、Moonshot、火山方舟…）
 *  - Gemini（:generateContent）
 *
 * 配置（设置页）：平台类型 / Base URL / API Key / 模型名 / 调教提示词（默认内置）。
 */
class LlmOcrEngine(
    private val platform: String,   // openai / gemini
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val prompt: String
) : OcrEngine {

    override val label: String = "大模型识别（自定义）"

    /** 内置课表调教提示词（可被设置页覆盖） */
    companion object {
        const val DEFAULT_PROMPT: String = "你是一名专业课程表识别助手。请识别图片中的课程表，" +
            "并严格按 JSON 数组输出，不要输出任何其他文字、注释或 markdown 代码块。\n" +
            "输出格式（每门课一个对象，必须逐门列出，不要合并）：\n" +
            "[{\"name\":\"课程名\",\"day\":1,\"start\":1,\"end\":4,\"weeks\":\"1-16,6,14-17\",\"loc\":\"B2-410\",\"teacher\":\"金刚波\",\"classes\":\"1班\",\"major\":\"软件开发\"}]\n" +
            "规则：\n" +
            "1. day：1=星期一，2=星期二，3=星期三，4=星期四，5=星期五\n" +
            "2. start/end：该课在当天占用的完整节次区间。合并单元格（如\"1-4节\"）必须写完整：start=1、end=4，绝不能只写区间中的一部分\n" +
            "3. weeks：只写周次数字，用英文逗号分隔，如 1-16、1-4,6-14、4-6双、9-13单、18；单双周用\"单\"或\"双\"标注（如 4-6双、9-13单）；不要写\"周\"字。**严禁把节次区间写进 weeks**（如\"6-9\"\"6-8\"\"6-7\"\"10-13\"这类若与节次重复则是错误，weeks 里只能出现真正的周次）。周次必须按图片原文逐字转写（如\"1-4周,6-14周,17-18周\"→\"1-4,6-14,17-18\"），不要改写、不要合并、不要补全；数字区间严禁逆序（\"14-6\"是错误写法）\n" +
            "4. 若表格里课程文本形如\"1-4节15-16周\"，应解析为 weeks=\"15-16\"、start=1、end=4\n" +
            "5. loc：只保留教室编号（统一格式如 B2-410、B5-102，即 楼栋-楼层-房间号），不要带实验室名称等后缀\n" +
            "6. teacher：教师姓名必须用图片中的全名（如 金刚波、向丽丽、赵煦华、沈伊诺、钟保辉），严禁省略或改名，识别不到填空字符串\n" +
            "7. classes：教学班组成，图片中\"教学班组成\"含多个班时（如\"24软件工程技术（本科）1班;24软件工程技术（本科）2班\"）必须写全\"1、2班\"，严禁只取其中一个班号；识别不到用 JSON null（不是字符串\"null\"）\n" +
            "8. major：只识别课程格内\"专业方向:\"字段的值（如 软件开发、大数据技术；大数据技术请填 大数据）；公共课/无该字段必须用 JSON null（不是字符串\"null\"，不是\"无\"）。严禁根据教室、实验室名称（如\"智能硬件与物联网实验室\"）猜测专业方向\n" +
            "9. 同一门课若在一天内有多个不连续的时间段，拆成多个对象（节次、周次各自对应）\n" +
            "10. 只输出图片中真实存在的课程，不要编造"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun recognize(bitmap: Bitmap): OcrResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            throw OcrConfigException("未配置 API Key，请到设置页填写")
        }
        if (model.isBlank()) {
            throw OcrConfigException("未填写模型名，请到设置页填写（如 gpt-4o-mini、qwen-vl-plus、glm-4v-flash）")
        }
        val p = prompt.ifBlank { DEFAULT_PROMPT }
        val b64 = encodeImage(bitmap)

        val replyText = if (platform == "gemini") {
            callGemini(baseUrl, apiKey, model, p, b64)
        } else {
            callOpenAI(baseUrl, apiKey, model, p, b64)
        }

        val json = extractJson(replyText)
        val arr = if (json.startsWith("[")) JSONArray(json) else JSONObject(json).optJSONArray("courses")
            ?: throw OcrRemoteException("大模型返回中未找到课程 JSON 数组")

        val out = mutableListOf<OcrLine>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            // 直接把结构化对象序列化为一行特殊文本，由调用方走 parseCourseFromJson
            out.add(OcrLine(o.toString(), 0, 0, 0, 0))
        }
        OcrResult(out, bitmap.width, bitmap.height)
    }

    /** 压缩图片 → base64 */
    private fun encodeImage(bitmap: Bitmap): String {
        var bmp = bitmap
        val maxSide = 1568
        val w = bmp.width; val h = bmp.height
        val scale = maxSide.toFloat() / maxOf(w, h)
        if (scale < 1f) {
            bmp = Bitmap.createScaledBitmap(bmp, (w * scale).toInt(), (h * scale).toInt(), true)
        }
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, baos)
        if (baos.size() > 8 * 1024 * 1024) { // 过大则再降质
            baos.reset()
            bmp.compress(Bitmap.CompressFormat.JPEG, 55, baos)
        }
        if (bmp != bitmap) bmp.recycle()
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    private fun callOpenAI(base: String, key: String, model: String, prompt: String, b64: String): String {
        // Base URL 需填到版本层（如 .../v1、.../v4），直接拼 /chat/completions
        val url = "${base.trimEnd('/')}/chat/completions"

        val imagePart = JSONObject()
            .put("type", "image_url")
            .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
        val textPart = JSONObject()
            .put("type", "text")
            .put("text", "请识别这张课表并输出 JSON")
        val userContent = JSONArray()
            .put(imagePart)
            .put(textPart)
        val sysMsg = JSONObject()
            .put("role", "system")
            .put("content", prompt)
        val userMsg = JSONObject()
            .put("role", "user")
            .put("content", userContent)
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0)
            .put("messages", JSONArray().put(sysMsg).put(userMsg))
            .toString()

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val resp = client.newCall(req).execute()
        val text = resp.body?.string().orEmpty()
        val json = try { JSONObject(text) } catch (_: Exception) {
            throw OcrRemoteException("大模型响应不是有效 JSON（HTTP ${resp.code}）")
        }
        json.optJSONObject("error")?.let { e ->
            throw OcrRemoteException("大模型接口错误：${e.optString("message", "未知")}")
        }
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            throw OcrRemoteException("大模型响应缺少 choices")
        }
        return choices.getJSONObject(0).optJSONObject("message")?.optString("content") ?: ""
    }

    private fun callGemini(base: String, key: String, model: String, prompt: String, b64: String): String {
        val url = "${base.trimEnd('/')}/v1beta/models/$model:generateContent?key=$key"

        val textPart = JSONObject().put("text", "$prompt\n请识别这张课表并输出 JSON")
        val imagePart = JSONObject()
            .put("inline_data", JSONObject()
                .put("mime_type", "image/jpeg")
                .put("data", b64))
        val userPart = JSONObject()
            .put("role", "user")
            .put("parts", JSONArray().put(textPart).put(imagePart))
        val body = JSONObject()
            .put("contents", JSONArray().put(userPart))
            .put("generationConfig", JSONObject().put("temperature", 0))
            .toString()

        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val resp = client.newCall(req).execute()
        val text = resp.body?.string().orEmpty()
        val json = try { JSONObject(text) } catch (_: Exception) {
            throw OcrRemoteException("Gemini 响应不是有效 JSON（HTTP ${resp.code}）")
        }
        val cands = json.optJSONArray("candidates")
        if (cands == null || cands.length() == 0) {
            throw OcrRemoteException("Gemini 响应缺少 candidates：${json.optString("error", text.take(200))}")
        }
        val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            parts.getJSONObject(i).optString("text")?.let { sb.append(it) }
        }
        return sb.toString()
    }

    /** 从模型输出中提取 JSON 数组（剥掉 markdown 围栏与前后杂文字） */
    private fun extractJson(text: String): String {
        var s = text.trim()
        val fence = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(s)
        if (fence != null) s = fence.groupValues[1].trim()
        val start = s.indexOf('[')
        val last = s.lastIndexOf(']')
        if (start >= 0 && last > start) return s.substring(start, last + 1)
        val os = s.indexOf('{')
        val oe = s.lastIndexOf('}')
        if (os >= 0 && oe > os) return s.substring(os, oe + 1)
        throw OcrRemoteException("大模型未返回可解析的 JSON：${s.take(200)}")
    }
}
