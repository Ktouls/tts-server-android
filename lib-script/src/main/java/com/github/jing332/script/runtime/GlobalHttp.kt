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

class GlobalHttp : ScriptableObject() {
    companion object {
        const val NAME = "http"
        private val TAG = "GlobalHttp"
        private val logger = KotlinLogging.logger(TAG)

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

        // 【关键逻辑】带重试的网络请求执行器
        // 如果断网，它会一直在这里循环等待，不会抛出异常，也不会返回错误
        // 阅读APP会因此处于“加载中”状态，而不会报错停止
        private fun executeWithRetry(url: String, block: () -> Response): Response {
            var retryCount = 0
            while (true) { // 无限重试，直到成功 (你可以加个最大限制，比如60次)
                try {
                    val response = block()
                    if (response.isSuccessful) {
                        if (retryCount > 0) Log.i(TAG, "网络已恢复，重试成功: $url")
                        return response
                    } else {
                        // 遇到 404/500 等服务器错误，选择直接返回还是重试？
                        // 这里我们假设非200也直接返回，交给脚本处理
                        return response 
                    }
                } catch (e: Exception) {
                    retryCount++
                    Log.w(TAG, "请求失败 (第${retryCount}次): ${e.message}。等待2秒后重试...")
                    try {
                        Thread.sleep(2000) // 等待2秒
                    } catch (e: InterruptedException) {
                        // 如果线程被强行中断（比如关闭APP），则退出
                        throw e 
                    }
                }
            }
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
                // 使用重试逻辑包裹 Net.get
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
                // 使用重试逻辑包裹 Net.post
                val resp = executeWithRetry(url.toString()) {
                    Net.post(url.toString()) {
                        headers?.forEach {
                            setHeader(it.key.toString(), it.value.toString())
                        }
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
