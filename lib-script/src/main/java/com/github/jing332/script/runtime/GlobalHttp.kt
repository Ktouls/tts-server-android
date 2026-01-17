package com.github.jing332.script.runtime

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

        // 新增：构建一个表示错误的响应对象（503 Service Unavailable）
        // 作用：拦截子线程的崩溃，将其转化为一个可被上层处理的 Response 对象
        private fun returnErrorResponse(url: String, e: Exception): Response {
            val msg = e.message ?: "Unknown Error"
            return Response.Builder()
                .request(Request.Builder().url(url).build())
                .protocol(Protocol.HTTP_1_1)
                .code(503) 
                .message(msg)
                .body(msg.toResponseBody(null))
                .build()
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
                // 【严谨修改】增加 try-catch 保护
                // 目的：捕获 Net 库在子线程抛出的致命异常，防止 APP 直接闪退
                val resp = try {
                    Net.get(url.toString()) {
                        headers?.forEach {
                            setHeader(it.key.toString(), it.value.toString())
                        }
                    }.execute<Response>()
                } catch (e: Exception) {
                    logger.error(e) { "Get request failed: $url" }
                    returnErrorResponse(url.toString(), e)
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
                    // 文件表单
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

                    // 常规表单
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
            logger.debug {
                "POST $url, $body, $headers"
            }

            runScriptCatching {
                // 【严谨修改】增加 try-catch 保护
                val resp: Response = try {
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
                } catch (e: Exception) {
                    logger.error(e) { "Post request failed: $url" }
                    returnErrorResponse(url.toString(), e)
                }
                NativeResponse.of(cx, scope, resp)
            }
        }
    }

    override fun getClassName(): String = "Http"
}
