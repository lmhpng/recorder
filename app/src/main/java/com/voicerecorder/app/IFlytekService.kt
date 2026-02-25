package com.voicerecorder.app

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object IFlytekService {

    private const val APP_ID = "c445a5c3"
    private const val API_KEY = "daf89fdc869aea167373f5bf59d6c88d"
    private const val API_SECRET = "MjgwZTkyNzRhMzcwNTFjOGFkZGZlODZl"

    private const val UPLOAD_URL = "https://raasr.xfyun.cn/v2/api/upload"
    private const val RESULT_URL = "https://raasr.xfyun.cn/v2/api/getResult"

    // 生成签名
    private fun buildSigna(ts: Long): String {
        val baseStr = APP_ID + ts.toString()
        val md5 = MessageDigest.getInstance("MD5")
            .digest(baseStr.toByteArray()).joinToString("") { "%02x".format(it) }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(API_SECRET.toByteArray(), "HmacSHA1"))
        val hmac = mac.doFinal(md5.toByteArray())
        return Base64.encodeToString(hmac, Base64.NO_WRAP)
    }

    // 上传录音文件，返回 orderId
    private suspend fun uploadFile(audioFile: File): String = withContext(Dispatchers.IO) {
        val ts = System.currentTimeMillis() / 1000
        val signa = buildSigna(ts)
        val audioBytes = audioFile.readBytes()
        val audioBase64 = Base64.encodeToString(audioBytes, Base64.NO_WRAP)

        val params = JSONObject().apply {
            put("appId", APP_ID)
            put("signa", signa)
            put("ts", ts)
            put("fileBase64", audioBase64)
            put("language", "zh_cn")
            put("pd", "court") // 通用领域
        }

        val conn = URL(UPLOAD_URL).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        conn.connectTimeout = 30000
        conn.readTimeout = 30000

        conn.outputStream.use { it.write(params.toString().toByteArray()) }

        val response = conn.inputStream.bufferedReader().readText()
        Log.d("IFlytek", "Upload response: $response")

        val json = JSONObject(response)
        if (json.optInt("code") != 0) {
            throw Exception("上传失败: ${json.optString("descInfo", json.optString("desc", "未知错误"))}")
        }
        json.getString("orderId")
    }

    // 轮询获取转写结果
    private suspend fun getResult(orderId: String): String = withContext(Dispatchers.IO) {
        val ts = System.currentTimeMillis() / 1000
        val signa = buildSigna(ts)

        val params = JSONObject().apply {
            put("appId", APP_ID)
            put("signa", signa)
            put("ts", ts)
            put("orderId", orderId)
            put("resultType", "transfer")
        }

        val conn = URL(RESULT_URL).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000

        conn.outputStream.use { it.write(params.toString().toByteArray()) }

        val response = conn.inputStream.bufferedReader().readText()
        Log.d("IFlytek", "Result response: $response")
        response
    }

    // 主入口：上传并等待结果，返回转写文字
    suspend fun transcribeAudio(audioFile: File): String = withContext(Dispatchers.IO) {
        // 1. 上传文件
        val orderId = uploadFile(audioFile)
        Log.d("IFlytek", "orderId: $orderId")

        // 2. 轮询结果（最多等60秒）
        repeat(20) { attempt ->
            delay(3000) // 每3秒查询一次
            val resultJson = getResult(orderId)
            val json = JSONObject(resultJson)
            val code = json.optInt("code")

            if (code != 0) {
                throw Exception("获取结果失败: ${json.optString("descInfo", "错误码$code")}")
            }

            val content = json.optJSONObject("content")
            val orderState = content?.optInt("orderState") ?: 0

            Log.d("IFlytek", "attempt=$attempt orderState=$orderState")

            when (orderState) {
                4 -> { // 转写完成
                    val lattice = content.optJSONArray("lattice")
                    if (lattice != null && lattice.length() > 0) {
                        val sb = StringBuilder()
                        for (i in 0 until lattice.length()) {
                            val item = lattice.getJSONObject(i)
                            val jsonStr = item.optString("json_1best", "")
                            if (jsonStr.isNotEmpty()) {
                                val innerJson = JSONObject(jsonStr)
                                val st = innerJson.optJSONObject("st")
                                val rt = st?.optJSONArray("rt")
                                if (rt != null) {
                                    for (j in 0 until rt.length()) {
                                        val ws = rt.getJSONObject(j).optJSONArray("ws")
                                        if (ws != null) {
                                            for (k in 0 until ws.length()) {
                                                val cw = ws.getJSONObject(k).optJSONArray("cw")
                                                if (cw != null && cw.length() > 0) {
                                                    sb.append(cw.getJSONObject(0).optString("w", ""))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        return@withContext sb.toString().trim().ifEmpty { "（识别结果为空，请检查录音内容）" }
                    }
                    return@withContext "（识别结果为空）"
                }
                5 -> throw Exception("转写失败，请重试")
                else -> { /* 继续等待 */ }
            }
        }
        throw Exception("转写超时，请检查网络连接")
    }
}
