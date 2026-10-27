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
            "输出格式（每门课一个对象）：\n" +
            "[{\"name\":\"课程名\",\"day\":1,\"start\":1,\"end\":2,\"weeks\":\"1-16\",\"loc\":\"A104\",\"teacher\":\"张三\",\"classes\":\"1班\",\"major\":\"软件工程\"}]\n" +
            "规则：\n" +
            "1. day：1=星期一，2=星期二，3=星期三，4=星期四，5=星期五\n" +
            "2. start/end：该课在当天占用的起始节次和结束节次（数字，如第1-2节 start=1 end=2）\n" +
            "3. weeks：周次字符串，格式如 1-16、1-4,6-14、4-6双、9-13单；识别不到写 1-18\n" +
            "4. loc：只保留教室编号（如 A104、5教203），不要带实验室名称等后缀\n" +
            "5. teacher：教师姓名，识别不到填空字符串\n" +
            "6. classes：教学班组成，如 1班、1、2班，识别不到填 null\n" +
            "7. major：课程标注的专业方向（如 软件工程、大数据）就填，公共课填 null\n" +
            "8. 跨多个节次的课合并成一个对象；同一天同一节次只保留一门课\n" +
            "9. 只输出图片中真实存在的课程，不要编造"
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
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", prompt))
                .put(JSONObject().put("role", "user").put("content", JSONArray()
                    .put(JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
                    .put(JSONObject().put("type", "text").put("text", "请识别这张课表并输出 JSON"))))
            .toString()

        val req = Request.Builder()
            .url("$url/chat/completions")
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
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("parts", JSONArray()
                    .put(JSONObject().put("text", "$prompt\n请识别这张课表并输出 JSON"))
                    .put(JSONObject().put("inline_data", JSONObject()
                        .put("mime_type", "image/jpeg")
                        .put("data", b64))))))
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
