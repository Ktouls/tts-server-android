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

class SystemTtsForwardServer(val port: Int, val callback: Callback) : Server {
    private val ktor by lazy {
        embeddedServer(CustomNetty, port = port) {
            installPlugins()
            intercept(ApplicationCallPipeline.Call) {
                val method = call.request.httpMethod.value
                val uri = call.request.uri
                val remoteAddress = call.request.origin.remoteAddress

                // 减少日志刷屏，仅在调试时开启或保留 INFO
                // callback.log(Log.INFO, "$method: ${uri.decodeURLQueryComponent()} \n remote: $remoteAddress \n")
            }

            routing {
                staticResources("/", "forwarder")

                suspend fun RoutingContext.handleTts(params: TtsParams) {
                    try {
                        // 【核心修改】这里是调用脚本生成音频的地方
                        // 如果 GlobalHttp 抛出异常，这里必须接住，否则进程会崩
                        val file = callback.tts(params)
                        
                        if (file == null) {
                            Log.e("ForwardServer", "TTS Engine returned null file")
                            call.respond(HttpStatusCode.InternalServerError, "TTS Generation Failed: File is null")
                        } else {
                            val length = file.length()
                            call.respondOutputStream(
                                ContentType.parse("audio/x-wav"), // 默认 wav，也可以根据实际情况动态判断
                                HttpStatusCode.OK,
                                contentLength = length
                            ) {
                                file.inputStream().use { input ->
                                    input.copyTo(this)
                                }
                                file.delete() // 发送完立即删除，防止垃圾堆积
                            }
                        }
                    } catch (e: Exception) {
                        // 【防闪退绝杀】捕获所有异常（包括网络、脚本错误）
                        // 打印详细日志以便排查
                        Log.e("ForwardServer", "TTS Handle Error: ${e.message}", e)
                        
                        // 返回 500 错误给阅读APP，告诉它这次失败了
                        // 注意：如果这里不返回错误，阅读APP可能会一直空等直到超时
                        call.respond(HttpStatusCode.InternalServerError, "Server Error: ${e.message}")
                    }
                }

                get("api/tts") {
                    // 为了防止参数解析报错导致崩溃，这里也建议加上保护，或者依赖 Ktor 的自动处理
                    try {
                        val text = call.parameters.getOrFail("text")
                        val engine = call.parameters.getOrFail("engine")
                        val locale = call.parameters["locale"] ?: ""
                        val voice = call.parameters["voice"] ?: ""
                        val speed = (call.parameters["rate"] ?: call.parameters["speed"])?.toIntOrNull() ?: 50
                        val pitch = call.parameters["pitch"]?.toIntOrNull() ?: 100
                        handleTts(TtsParams(text, engine, locale, voice, speed, pitch))
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, "Invalid Parameters: ${e.message}")
                    }
                }

                post("api/tts") {
                    try {
                        val params = call.receive<TtsParams>()
                        handleTts(params)
                    } catch (e: Exception) {
                         call.respond(HttpStatusCode.BadRequest, "Invalid POST Body: ${e.message}")
                    }
                }

                get("api/engines") { 
                    runCatching { call.respond(callback.engines()) }
                        .onFailure { call.respond(HttpStatusCode.InternalServerError, it.message ?: "") }
                }
                get("api/voices") {
                    runCatching {
                        val engine = call.parameters.getOrFail("engine")
                        call.respond(callback.voices(engine))
                    }.onFailure { call.respond(HttpStatusCode.InternalServerError, it.message ?: "") }
                }
                get("api/legado") {
                    runCatching {
                        val api = call.parameters.getOrFail("api")
                        val name = call.parameters.getOrFail("name")
                        val engine = call.parameters.getOrFail("engine")
                        val voice = call.parameters["voice"] ?: ""
                        val pitch = call.parameters["pitch"] ?: "50"
                        call.respond(LegadoUtils.getLegadoJson(api, name, engine, voice, pitch))
                    }.onFailure { call.respond(HttpStatusCode.InternalServerError, it.message ?: "") }
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
