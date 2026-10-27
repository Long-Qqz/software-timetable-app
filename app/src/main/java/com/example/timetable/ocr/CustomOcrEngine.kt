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
 * 自定义云端 OCR：对接任意「POST 图片 base64 -> JSON 文本结果」的通用识别 API
 * （腾讯云、阿里云、讯飞、火山引擎等，只要返回带文字及坐标的 JSON 均可接入）。
 *
 * 配置（设置页）：
 *  - url：接口地址
 *  - token：Token / API Key（自动加 Bearer 头；也可直接写完整 Authorization 值）
 *  - imageField：请求体中的图片字段名（默认 image，内容为 base64）
 *  - extraHeaders：附加请求头 JSON，如 {"X-Api-Key":"xxx","Content-Type":"application/json"}
 *
 * 响应解析兼容常见格式：百度 words_result[].words + location，
 * 以及通用 words / text / content 数组、对象数组中的坐标字段。
 * 识别完成后统一交给 LayoutAnalyzer 做课表专用版面分析。
 */
class CustomOcrEngine(
    private val url: String,
    private val token: String,
    private val imageField: String,
    private val extraHeaders: String
) : OcrEngine {

    override val label: String = "云端识别（自定义）"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun recognize(bitmap: Bitmap): OcrResult = withContext(Dispatchers.IO) {
        if (url.isBlank()) {
            throw OcrConfigException("未配置自定义 OCR 接口地址，请到设置页填写")
        }
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, baos)
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

        val bodyJson = JSONObject().put(imageField.ifBlank { "image" }, b64).toString()
        val builder = Request.Builder().url(url)
            .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))

        if (token.isNotBlank()) {
            val auth = if (token.startsWith("Bearer ") || token.contains(" ")) token else "Bearer $token"
            builder.header("Authorization", auth)
        }
        extraHeaders.takeIf { it.isNotBlank() }?.let { raw ->
            try {
                val h = JSONObject(raw)
                val keys = h.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = h.optString(k)
                    if (v.isNotBlank()) builder.header(k, v)
                }
            } catch (_: Exception) {
                // 附加头格式错误忽略，不影响主流程
            }
        }

        val resp = client.newCall(builder.build()).execute()
        val text = resp.body?.string().orEmpty()
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            throw OcrRemoteException("自定义 OCR 响应不是有效 JSON（HTTP ${resp.code}）")
        }

        // 接口自带错误码则抛出
        val err = json.optString("error", "").ifBlank { json.optString("err_msg") }
        if (err.isNotBlank()) throw OcrRemoteException("自定义 OCR 错误: $err")

        val lines = extractLines(json)
        if (lines.isEmpty()) {
            throw OcrRemoteException("自定义 OCR 未识别到文本，请检查接口地址 / Token / 图片字段名 / 返回格式")
        }
        OcrResult(lines, bitmap.width, bitmap.height)
    }

    /** 兼容解析：百度格式优先，其次通用文本数组 */
    private fun extractLines(root: JSONObject): List<OcrLine> {
        val out = mutableListOf<OcrLine>()

        // 1) 百度兼容：words_result[].words + location
        val wr = root.optJSONArray("words_result")
        if (wr != null) {
            for (i in 0 until wr.length()) {
                val o = wr.optJSONObject(i) ?: continue
                val w = o.optString("words")
                if (w.isBlank()) continue
                val loc = o.optJSONObject("location")
                if (loc != null) {
                    out.add(
                        OcrLine(
                            w,
                            loc.optInt("left", 0), loc.optInt("top", 0),
                            loc.optInt("width", 0), loc.optInt("height", 0)
                        )
                    )
                } else {
                    out.add(OcrLine(w, 0, 0, 0, 0))
                }
            }
            if (out.isNotEmpty()) return out
        }

        // 2) 通用格式：深度优先收集所有字符串 + 尽力解析坐标
        collectAll(root, out)
        return out
    }

    private fun collectAll(node: Any?, out: MutableList<OcrLine>) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                var text: String? = null
                var x = 0; var y = 0; var w = 0; var h = 0
                var hasBox = false
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = node.opt(k)
                    when {
                        k.equals("words", true) || k.equals("text", true) ||
                            k.equals("content", true) || k.equals("value", true) || k.equals("name", true) -> {
                            if (v is String && (v as String).isNotBlank() && text == null) text = v as String
                        }
                        k.equals("left", true) -> { x = (v as? Number)?.toInt() ?: 0; hasBox = true }
                        k.equals("top", true) || k.equals("x", true) -> { y = (v as? Number)?.toInt() ?: 0; hasBox = true }
                        k.equals("width", true) || k.equals("w", true) -> { w = (v as? Number)?.toInt() ?: 0; hasBox = true }
                        k.equals("height", true) || k.equals("h", true) -> { h = (v as? Number)?.toInt() ?: 0; hasBox = true }
                        else -> collectAll(v, out)
                    }
                }
                if (text != null) {
                    if (hasBox) out.add(OcrLine(text, x, y, w, h)) else out.add(OcrLine(text, 0, 0, 0, 0))
                }
            }
            is JSONArray -> for (i in 0 until node.length()) collectAll(node.opt(i), out)
            is String -> if (node.isNotBlank()) out.add(OcrLine(node, 0, 0, 0, 0))
            else -> {}
        }
    }
}
