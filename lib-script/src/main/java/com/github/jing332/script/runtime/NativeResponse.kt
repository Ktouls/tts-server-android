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

        // 修改：移除异常抛出，防止脚本层读取错误响应时导致 APP 闪退
        private fun NativeResponse.checkResponse(force: Boolean): Response {
            val resp = rawResponse ?: throw IllegalStateException("rawResponse is null")
            // 原逻辑：if (force) return resp; if (resp.isSuccessful) return resp; else throw Exception(...)
            // 修改后：直接返回 resp。允许脚本读取 404/500/503 的响应体内容。
            // 脚本应通过 .ok 或 .status 属性判断请求是否成功。
            return resp
        }

        private fun NativeResponse.js_json(force: Boolean): Any = runScriptCatching {
            val resp = checkResponse(force)
            val str = resp.body?.string() ?: return@runScriptCatching ""
            // 如果返回的是空字符串（比如网络错误没内容），parseValue可能会报错，这里脚本层可能需要try-catch，但至少不会闪退
            if (str.isEmpty()) return@runScriptCatching Undefined.instance
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
