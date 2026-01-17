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

        // 最大重试次数 (例如 60次 * 2秒 = 120秒等待时间)
        private const val MAX_RETRY_COUNTS = 60
        // 重试间隔 (毫秒)
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

        // 构造一个防崩溃的错误响应（仅当重试耗尽时使用）
        private fun returnErrorResponse(url: String, msg: String): Response {
            Log.e(TAG, "重试耗尽，返回错误信息: $msg")
            return Response.Builder()
                .request(Request.Builder().url(url).build())
                .protocol(Protocol.HTTP_1_1)
                .code(503)
                .message(msg)
                .body(msg.toResponseBody(null))
                .build()
        }

        // 【核心逻辑】执行带重试的网络请求
        private fun executeWithRetry(url: String, block: () -> Response): Response {
            var currentRetry = 0
            var lastError: Exception? = null

            while (currentRetry < MAX_RETRY_COUNTS) {
                try {
                    // 尝试执行请求
                    val resp = block()
                    if (resp.isSuccessful) {
                        if (currentRetry > 0) Log.i(TAG, "网络恢复，请求成功: $url")
                        return resp
                    } else {
                        // 如果是 503/500/404 等服务器错误，也可以选择重试，或者直接返回
                        // 这里假设非 200 也是一种需要重试的“失败”（视情况而定，保守起见直接返回）
                        return resp
                    }
                } catch (e: Exception) {
                    lastError = e
                    currentRetry++
                    Log.w(TAG, "请求失败 ($currentRetry/$MAX_RETRY_COUNTS): ${e.message}. 等待 ${RETRY_INTERVAL_MS}ms 后重试...")
                    
                    try {
                        Thread.sleep(RETRY_INTERVAL_MS)
                    } catch (interrupted: InterruptedException) {
                        return returnErrorResponse(url, "Interrupted")
                    }
                }
            }

            // 重试全部失败，为了防止APP崩溃，返回一个错误的Response
            return returnErrorResponse(url, "Max retry reached: ${lastError?.message}")
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
                // 使用重试机制包裹
                val resp = executeWithRetry(url.toString()) {
                    Net.get(url.toString()) {
                        headers?.forEach {
                            setHeader(it.key.toString(), it.value.toString())
                        }
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

                        multipartBody.addFormDataPart(
                            entry.key.toString(),
                            fileName.toString(),
                            requestBody
                        )
                    }
                    else -> multipartBody.addFormDataPart(
                        entry.key.toString(),
                        entry.value as String
                    )
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
            logger.debug { "POST $url, $body, $headers" }

            runScriptCatching {
                // 使用重试机制包裹
                val resp = executeWithRetry(url.toString()) {
                    Net.post(url.toString()) {
                        headers?.forEach {
                            setHeader(it.key.toString(), it.value.toString())
                        }
                        if (body is CharSequence)
                            this.body = body.toString().toRequestBody(contentType)
                        else if (body is Map<*, *>)
                            this.body = postMultipart(
                                "multipart/form-data",
                                body as Map<CharSequence, Any>
                            ).build()

                    }.execute()
                }
                NativeResponse.of(cx, scope, resp)
            }
        }
    }
    override fun getClassName(): String = "Http"
}
