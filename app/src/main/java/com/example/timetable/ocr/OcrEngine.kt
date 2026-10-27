package com.example.timetable.ocr

import android.graphics.Bitmap

/** OCR 引擎统一接口：所有云端/离线引擎实现它，识别结果统一进入课表版面分析 */
interface OcrEngine {
    suspend fun recognize(bitmap: Bitmap): OcrResult
    val label: String
}

/** OCR 未配置或配置错误（设置页未填 Key/接口地址等） */
class OcrConfigException(message: String) : Exception(message)

/** OCR 远端错误（网络异常、接口返回错误码、响应格式不符等） */
class OcrRemoteException(message: String) : Exception(message)
