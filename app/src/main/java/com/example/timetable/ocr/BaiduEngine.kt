package com.example.timetable.ocr

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 云端 OCR：百度通用文字识别（标准版，含位置信息）。
 * 需要用户在"设置"中填写百度智能云的 API Key 与 Secret Key（免费额度可用）。
 * 若未配置 Key，recognize 会抛出 OcrConfigException。
 */
class BaiduEngine(
    private val apiKey: String,
    private val secretKey: String
) : OcrEngine {

    override val label: String = "云端识别（百度）"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var cachedToken: String? = null

    override suspend fun recognize(bitmap: Bitmap): OcrResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || secretKey.isBlank()) {
            throw OcrConfigException("未配置百度 OCR Key，请在设置页填写 API Key 与 Secret Key")
        }
        val token = cachedToken ?: fetchToken().also { cachedToken = it }
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, baos)
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

        val body = FormBody.Builder()
            .add("image", b64)
            .add("location", "true")
            .build()
        val req = Request.Builder()
            .url("https://aip.baidubce.com/rest/2.0/ocr/v1/general_basic?access_token=$token")
            .post(body)
            .build()

        val resp = client.newCall(req).execute()
        val text = resp.body?.string().orEmpty()
        val json = JSONObject(text)
        val err = json.optInt("error_code", 0)
        if (err != 0) {
            if (err == 110) cachedToken = null // token 过期，下次重取
            throw OcrRemoteException("百度 OCR 错误 $err: ${json.optString("error_msg")}")
        }

        val lines = mutableListOf<OcrLine>()
        val arr = json.getJSONArray("words_result")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val loc = o.optJSONObject("location")
            if (loc != null) {
                lines.add(
                    OcrLine(
                        text = o.getString("words"),
                        x = loc.optInt("left", 0),
                        y = loc.optInt("top", 0),
                        w = loc.optInt("width", 0),
                        h = loc.optInt("height", 0)
                    )
                )
            } else {
                lines.add(OcrLine(o.getString("words"), 0, 0, 0, 0))
            }
        }
        OcrResult(lines, bitmap.width, bitmap.height)
    }

    private fun fetchToken(): String {
        val url = "https://aip.baidubce.com/oauth/2.0/token" +
            "?grant_type=client_credentials&client_id=$apiKey&client_secret=$secretKey"
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            val json = JSONObject(resp.body?.string().orEmpty())
            val token = json.optString("access_token", "")
            if (token.isEmpty()) {
                throw OcrRemoteException("获取百度 token 失败: ${json.optString("error_description")}")
            }
            return token
        }
    }
}
