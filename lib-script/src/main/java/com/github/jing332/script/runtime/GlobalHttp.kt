package com.github.jing332.script.runtime

import com.drake.net.Net
import com.github.jing332.script.ensureArgumentsLength
import com.github.jing332.script.exception.runScriptCatching
import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
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

            // 【严谨恢复】直接请求，不拦截异常，让 SystemTtsService 去处理
            runScriptCatching {
                val resp = Net.get(url.toString()) {
                    headers?.forEach {
                        setHeader(it.key.toString(), it.value.toString())
                    }
                }.execute<Response>()
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

            // 【严谨恢复】直接请求，不拦截异常
            runScriptCatching {
                val resp: Response = Net.post(url.toString()) {
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
                NativeResponse.of(cx, scope, resp)
            }
        }
    }

    override fun getClassName(): String = "Http"
}
