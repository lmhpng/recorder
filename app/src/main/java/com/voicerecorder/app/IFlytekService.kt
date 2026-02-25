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
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object IFlytekService {

    private const val APP_ID = "c445a5c3"
    private const val API_SECRET = "MjgwZTkyNzRhMzcwNTFjOGFkZGZlODZl"

    private const val UPLOAD_URL = "https://raasr.xfyun.cn/v2/api/upload"
    private const val RESULT_URL = "https://raasr.xfyun.cn/v2/api/getResult"

    // 生成签名：MD5(appId + ts) 后用 APISecret 做 HmacSHA1
    private fun buildSigna(ts: Long): String {
        val baseStr = APP_ID + ts.toString()
        val md5Bytes = MessageDigest.getInstance("MD5").digest(baseStr.toByteArray(Charsets.UTF_8))
        val md5Hex = md5Bytes.joinToString("") { "%02x".format(it) }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(API_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val hmacBytes = mac.doFinal(md5Hex.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(hmacBytes, Base64.NO_WRAP)
    }

    // 构建表单参数字符串
    private fun buildFormParams(vararg pairs: Pair<String, String>): ByteArray {
        return pairs.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }.toByteArray(Charsets.UTF_8)
    }

    // 上传录音文件，返回 orderId
    private suspend fun uploadFile(audioFile: File): String = withContext(Dispatchers.IO) {
        val ts = System.currentTimeMillis() / 1000
        val signa = buildSigna(ts)
        val audioBase64 = Base64.encodeToString(audioFile.readBytes(), Base64.NO_WRAP)

        val body = buildFormParams(
            "appId" to APP_ID,
            "signa" to signa,
            "ts" to ts.toString(),
            "fileBase64" to audioBase64,
            "language" to "zh_cn",
            "pd" to "general"
        )

        val conn = URL(UPLOAD_URL).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        conn.connectTimeout = 60000
        conn.readTimeout = 60000
        conn.outputStream.use { it.write(body) }

        val response = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        Log.d("IFlytek", "Upload: $response")

        val json = JSONObject(response)
        val code = json.optInt("code", -1)
        if (code != 0) {
            val desc = json.optString("descInfo", json.optString("desc", "code=$code"))
            throw Exception("上传失败：$desc")
        }
        json.getString("orderId")
    }

    // 查询转写结果
    private suspend fun queryResult(orderId: String): JSONObject = withContext(Dispatchers.IO) {
        val ts = System.currentTimeMillis() / 1000
        val signa = buildSigna(ts)

        val body = buildFormParams(
            "appId" to APP_ID,
            "signa" to signa,
            "ts" to ts.toString(),
            "orderId" to orderId,
            "resultType" to "transfer"
        )

        val conn = URL(RESULT_URL).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.outputStream.use { it.write(body) }

        val response = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        Log.d("IFlytek", "Result: $response")
        JSONObject(response)
    }

    // 从识别结果中解析出文字
    private fun parseTranscript(content: JSONObject): String {
        val sb = StringBuilder()
        val lattice = content.optJSONArray("lattice") ?: return ""
        for (i in 0 until lattice.length()) {
            val jsonStr = lattice.getJSONObject(i).optString("json_1best", "")
            if (jsonStr.isEmpty()) continue
            val st = JSONObject(jsonStr).optJSONObject("st") ?: continue
            val rt = st.optJSONArray("rt") ?: continue
            for (j in 0 until rt.length()) {
                val ws = rt.getJSONObject(j).optJSONArray("ws") ?: continue
                for (k in 0 until ws.length()) {
                    val cw = ws.getJSONObject(k).optJSONArray("cw") ?: continue
                    if (cw.length() > 0) sb.append(cw.getJSONObject(0).optString("w", ""))
                }
            }
        }
        return sb.toString().trim()
    }

    // 主入口
    suspend fun transcribeAudio(audioFile: File): String = withContext(Dispatchers.IO) {
        val orderId = uploadFile(audioFile)
        Log.d("IFlytek", "orderId=$orderId")

        // 轮询，最多等 60 秒
        repeat(20) { attempt ->
            delay(3000)
            val json = queryResult(orderId)
            val code = json.optInt("code", -1)
            if (code != 0) {
                throw Exception("查询失败：${json.optString("descInfo", "code=$code")}")
            }
            val content = json.optJSONObject("content")
            val state = content?.optInt("orderState", 0) ?: 0
            Log.d("IFlytek", "attempt=$attempt state=$state")

            when (state) {
                4 -> {
                    val text = parseTranscript(content!!)
                    return@withContext text.ifEmpty { "（识别结果为空，请检查录音是否有声音）" }
                }
                5 -> throw Exception("转写失败，请重试")
                else -> { /* 继续等待 */ }
            }
        }
        throw Exception("识别超时，请检查网络后重试")
    }
}
