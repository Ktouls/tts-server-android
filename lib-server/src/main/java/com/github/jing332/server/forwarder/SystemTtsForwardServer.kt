package com.github.jing332.server.forwarder

import android.util.Log
import com.github.jing332.server.BaseCallback
import com.github.jing332.server.CustomNetty
import com.github.jing332.server.Server
import com.github.jing332.server.installPlugins
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLQueryComponent
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.util.getOrFail
import java.io.File
import kotlinx.coroutines.withTimeoutOrNull // 引入超时控制

class SystemTtsForwardServer(val port: Int, val callback: Callback) : Server {
    private val ktor by lazy {
        // 使用 CustomNetty 启动服务器
        embeddedServer(CustomNetty, port = port) {
            installPlugins()
            intercept(ApplicationCallPipeline.Call) {
                // 仅调试用
                // val method = call.request.httpMethod.value
                // val uri = call.request.uri
                // Log.d("ForwardServer", "Request: $method $uri")
            }

            routing {
                staticResources("/", "forwarder")

                suspend fun RoutingContext.handleTts(params: TtsParams) {
                    try {
                        Log.i("ForwardServer", "开始处理 TTS 请求: ${params.text.take(10)}...")
                        
                        // 【核心修改】
                        // 我们在这里手动加一个 5 分钟的超长超时
                        // 如果 callback.tts (即脚本执行) 在5分钟内没返回，我们再放弃
                        // 这样就覆盖了任何默认的短超时
                        val file = withTimeoutOrNull(300000L) { // 5分钟
                             callback.tts(params)
                        }

                        if (file == null) {
                            Log.e("ForwardServer", "TTS 失败: 脚本返回 null 或超时")
                            call.respond(HttpStatusCode.InternalServerError, "TTS Failed or Timeout")
                        } else {
                            Log.i("ForwardServer", "TTS 成功, 文件大小: ${file.length()}")
                            call.respondOutputStream(
                                ContentType.parse("audio/x-wav"),
                                HttpStatusCode.OK,
                                contentLength = file.length()
                            ) {
                                file.inputStream().use { it.copyTo(this) }
                                file.delete()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("ForwardServer", "TTS处理异常: ${e.message}", e)
                        call.respond(HttpStatusCode.InternalServerError, "Server Error: ${e.message}")
                    }
                }

                get("api/tts") {
                    try {
                        val text = call.parameters.getOrFail("text")
                        val engine = call.parameters.getOrFail("engine")
                        val locale = call.parameters["locale"] ?: ""
                        val voice = call.parameters["voice"] ?: ""
                        val speed = (call.parameters["rate"] ?: call.parameters["speed"])?.toIntOrNull() ?: 50
                        val pitch = call.parameters["pitch"]?.toIntOrNull() ?: 100
                        handleTts(TtsParams(text, engine, locale, voice, speed, pitch))
                    } catch (e: Exception) {
                         call.respond(HttpStatusCode.BadRequest, "Params Error: ${e.message}")
                    }
                }

                post("api/tts") {
                    try {
                        val params = call.receive<TtsParams>()
                        handleTts(params)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, "Body Error: ${e.message}")
                    }
                }

                get("api/engines") { 
                     runCatching { call.respond(callback.engines()) }
                }
                get("api/voices") {
                    runCatching {
                        val engine = call.parameters.getOrFail("engine")
                        call.respond(callback.voices(engine))
                    }
                }
                get("api/legado") {
                    runCatching {
                        val api = call.parameters.getOrFail("api")
                        val name = call.parameters.getOrFail("name")
                        val engine = call.parameters.getOrFail("engine")
                        val voice = call.parameters["voice"] ?: ""
                        val pitch = call.parameters["pitch"] ?: "50"
                        call.respond(LegadoUtils.getLegadoJson(api, name, engine, voice, pitch))
                    }
                }
            }
        }
    }

    override fun start(wait: Boolean, onStarted: () -> Unit, onStopped: () -> Unit) {
        ktor.application.monitor.subscribe(ApplicationStarted) { onStarted() }
        ktor.application.monitor.subscribe(ApplicationStopped) { onStopped() }
        ktor.start(wait)
    }

    override fun stop() {
        ktor.stop(100, 500)
    }

    interface Callback : BaseCallback {
        suspend fun tts(params: TtsParams): File?
        suspend fun voices(engine: String): List<Voice>
        suspend fun engines(): List<Engine>
    }
}
