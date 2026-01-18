package com.github.jing332.script.runtime

import android.util.Log
import com.drake.net.Net
import com.github.jing332.script.ensureArgumentsLength
import com.github.jing332.script.exception.runScriptCatching
import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.io.File
import java.util.concurrent.TimeUnit

class GlobalHttp : ScriptableObject() {
    companion object {
        const val NAME = "http"
        private val TAG = "GlobalHttp"
        private val logger = KotlinLogging.logger(TAG)

        private const val MAX_RETRY_COUNTS = 150
        private const val RETRY_INTERVAL_MS = 2000L

        @JvmStatic
        fun init(cx: Context, scope: Scriptable, sealed: Boolean) {
            val obj = GlobalHttp()
            obj.prototype = getObjectPrototype(scope)
            obj.parentScope = scope

            obj.defineProperty(scope, "get", 2, ::get, DONTENUM, DONTENUM or READONLY)
            obj.defineProperty(scope, "post", 3, ::post, DONTENUM, DONTENUM or READONLY)

            defineProperty(scope, NAME, obj, DONTENUM or READONLY)
            if (sealed) obj.sealObject()
        }

        private fun returnErrorResponse(url: String, msg: String): Response {
            Log.e(TAG, "请求终止或重试耗尽: $msg")
            return Response.Builder()
                .request(Request.Builder().url(url).build())
                .protocol(Protocol.HTTP_1_1)
                .code(503)
                .message(msg)
                .body(msg.toResponseBody(null))
                .build()
        }

        private fun executeWithRetry(url: String, block: () -> Response): Response {
            var currentRetry = 0
            var lastError: Exception? = null

            // 🛠️ 关键修复：增加 !Thread.currentThread().isInterrupted 检查
            // 确保旧任务在网络异常重试期间，如果收到取消指令，能立刻释放 Mutex 锁
            while (currentRetry < MAX_RETRY_COUNTS && !Thread.currentThread().isInterrupted) {
                try {
                    val resp = block()
                    if (resp.isSuccessful) {
                        if (currentRetry > 0) Log.i(TAG, "重试成功 ($currentRetry): $url")
                        return resp
                    } else {
                        throw RuntimeException("HTTP Code ${resp.code}")
                    }
                } catch (e: Exception) {
                    // 检测是否是因为任务被取消导致的异常
                    if (e is InterruptedException || Thread.currentThread().isInterrupted) {
                        return returnErrorResponse(url, "Interrupted")
                    }

                    lastError = e
                    currentRetry++
                    
                    if (currentRetry % 5 == 1) { 
                        Log.w(TAG, "网络异常 ($currentRetry/$MAX_RETRY_COUNTS): ${e.message}. 正在重试...")
                    }

                    try {
                        // 在休眠前再次检查中断状态
                        if (Thread.currentThread().isInterrupted) return returnErrorResponse(url, "Interrupted")
                        Thread.sleep(RETRY_INTERVAL_MS)
                    } catch (interrupted: InterruptedException) {
                        return returnErrorResponse(url, "Interrupted")
                    }
                }
            }
            return returnErrorResponse(url, "Stopped or Max Retries: ${lastError?.message}")
        }

        @Suppress("UNCHECKED_CAST")
        @JvmStatic
        private fun get(
            cx: Context,
            scope: Scriptable,
            thisObj: Scriptable,
            args: Array<Any>,
        ): Any = ensureArgumentsLength(args, 1..2) {
            val url = args[0] as CharSequence
            val headers = args.getOrNull(1) as? Map<CharSequence, CharSequence>

            runScriptCatching {
                val resp = executeWithRetry(url.toString()) {
                    Net.get(url.toString()) {
                        headers?.forEach { setHeader(it.key.toString(), it.value.toString()) }
                    }.execute<Response>()
                }
                NativeResponse.of(cx, scope, resp)
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun postMultipart(
            type: String,
            form: Map<CharSequence, Any>,
        ): MultipartBody.Builder {
            val multipartBody = MultipartBody.Builder()
            multipartBody.setType(type.toMediaType())

            form.forEach { entry ->
                when (entry.value) {
                    is Map<*, *> -> {
                        val filePartMap = entry.value as Map<CharSequence, Any>
                        val fileName = filePartMap["fileName"] as? CharSequence
                        val body = filePartMap["body"]
                        val contentType = filePartMap["contentType"] as? CharSequence
                        val mediaType = contentType?.toString()?.toMediaType()
                        val requestBody = when (body) {
                            is File -> body.asRequestBody(mediaType)
                            is ByteArray -> body.toRequestBody(mediaType)
                            is String -> body.toRequestBody(mediaType)
                            else -> body.toString().toRequestBody()
                        }
                        multipartBody.addFormDataPart(entry.key.toString(), fileName.toString(), requestBody)
                    }
                    else -> multipartBody.addFormDataPart(entry.key.toString(), entry.value as String)
                }
            }
            return multipartBody
        }

        @Suppress("UNCHECKED_CAST")
        @JvmStatic
        private fun post(
            cx: Context,
            scope: Scriptable,
            thisObj: Scriptable,
            args: Array<Any>,
        ): Any = ensureArgumentsLength(args, 1..3) {
            val url = args[0] as CharSequence
            val body = args.getOrNull(1)
            val headers = args.getOrNull(2) as? Map<CharSequence, CharSequence>
            val contentType = headers?.get("Content-Type")?.toString()?.toMediaType()

            runScriptCatching {
                val resp = executeWithRetry(url.toString()) {
                    Net.post(url.toString()) {
                        headers?.forEach { setHeader(it.key.toString(), it.value.toString()) }

                        if (body is CharSequence)
                            this.body = body.toString().toRequestBody(contentType)
                        else if (body is Map<*, *>)
                            this.body = postMultipart("multipart/form-data", body as Map<CharSequence, Any>).build()
                    }.execute()
                }
                NativeResponse.of(cx, scope, resp)
            }
        }
    }
    override fun getClassName(): String = "Http"
}
