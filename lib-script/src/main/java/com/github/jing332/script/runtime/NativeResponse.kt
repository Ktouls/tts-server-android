package com.github.jing332.script.runtime

import com.github.jing332.script.PropertyGetter
import com.github.jing332.script.definePrototypeMethod
import com.github.jing332.script.definePrototypePropertys
import com.github.jing332.script.exception.runScriptCatching
import com.github.jing332.script.toNativeArrayBuffer
import okhttp3.Response
import org.mozilla.javascript.Context
import org.mozilla.javascript.LambdaConstructor
import org.mozilla.javascript.ScriptRuntime
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.json.JsonParser
import org.mozilla.javascript.typedarrays.NativeBuffer

class NativeResponse private constructor(val rawResponse: Response? = null) :
    ScriptableObject() {
    override fun getClassName(): String = CLASS_NAME

    companion object {
        const val CLASS_NAME = "Response"

        @JvmStatic
        val serialVersionUID: Long = 3110411773054879588L

        fun of(cx: Context, scope: Scriptable, resp: Response?): Scriptable {
            return cx.newObject(scope, CLASS_NAME, arrayOf(resp))
        }

        @JvmStatic
        fun init(cx: Context, scope: Scriptable, sealed: Boolean) {
            val constructor =
                LambdaConstructor(
                    scope,
                    CLASS_NAME,
                    1,
                    LambdaConstructor.CONSTRUCTOR_NEW
                ) { cx: Context, scope: Scriptable, args: Array<Any?> ->
                    NativeResponse(args.getOrNull(0) as? Response)
                }
            constructor.setPrototypePropertyAttributes(DONTENUM or READONLY or PERMANENT)

            constructor.definePrototypePropertys<NativeResponse>(
                cx, scope,
                listOf(
                    "status" to PropertyGetter { it.rawResponse?.code ?: 0 },
                    "statusText" to PropertyGetter { it.rawResponse?.message ?: "" },
                    "headers" to PropertyGetter {
                        it.rawResponse?.headers?.toMap() ?: Undefined.instance
                    },
                    "url" to PropertyGetter { it.rawResponse?.request?.url?.toString() ?: "" },
                    "redirected" to PropertyGetter { it.rawResponse?.isRedirect == true },
                    "ok" to PropertyGetter { it.rawResponse?.isSuccessful == true },
                )
            )

            constructor.definePrototypeMethod<NativeResponse>(
                scope, "json", 0, { cx, scope, thisObj, args ->
                    thisObj.js_json(ScriptRuntime.toBoolean(args.getOrNull(0)))
                }
            )
            constructor.definePrototypeMethod<NativeResponse>(
                scope,
                "text",
                0,
                { cx, scope, thisObj, args ->
                    thisObj.js_text(
                        ScriptRuntime.toBoolean(
                            args.getOrNull(0)
                        )
                    )
                }
            )
            constructor.definePrototypeMethod<NativeResponse>(
                scope,
                "bytes",
                0,
                { cx, scope, thisObj, args ->
                    thisObj.js_bytes(
                        cx, scope,
                        ScriptRuntime.toBoolean(args.getOrNull(0))
                    )
                }
            )

            defineProperty(scope, CLASS_NAME, constructor, DONTENUM)
            if (sealed) constructor.sealObject()
        }


        private fun js_constructor(
            cx: Context,
            scope: Scriptable,
            args: Array<out Any?>,
        ): NativeResponse {
            val resp = args.getOrNull(0) as? Response
            val obj = NativeResponse(resp)
            return obj
        }

        // 【关键逻辑】严厉检查模式。
        // 如果 GlobalHttp 返回了 503 (代表网络错误)，这里必须抛出异常。
        // 异常会被 SystemTtsService 捕获，从而触发重试，避免缓存错误文件。
        private fun NativeResponse.checkResponse(force: Boolean): Response {
            val resp = rawResponse ?: throw IllegalStateException("rawResponse is null")
            if (force) return resp
            if (resp.isSuccessful == true)
                return resp
            else
                throw Exception("Response failed: code=${resp.code}, message=${resp.message}")
        }

        private fun NativeResponse.js_json(force: Boolean): Any = runScriptCatching {
            val resp = checkResponse(force)
            val str = resp.body?.string() ?: return@runScriptCatching ""
            JsonParser(Context.getCurrentContext(), this).parseValue(str)
        }

        private fun NativeResponse.js_text(force: Boolean): Any = runScriptCatching {
            val resp = checkResponse(force)
            resp.body?.string() ?: ""
        }

        private fun NativeResponse.js_bytes(cx: Context, scope: Scriptable, force: Boolean): Any =
            runScriptCatching {
                val bytes = checkResponse(force).body?.bytes() ?: ByteArray(0)
                val buffer = bytes.toNativeArrayBuffer()

                NativeBuffer.of(cx, scope, buffer)
            }
    }
}
