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

                callback.log(
                    level = Log.INFO,
                    "$method: ${uri.decodeURLQueryComponent()} \n remote: $remoteAddress \n"
                )
            }

            routing {
                staticResources("/", "forwarder")

                suspend fun RoutingContext.handleTts(params: TtsParams) {
                    // 🛠️ 关键修复：增加 runCatching 确保脚本错误不杀掉 Service 进程
                    runCatching {
                        val file = callback.tts(params)
                        if (file == null) {
                            call.respond(HttpStatusCode.InternalServerError, "Android TTS Engine Error")
                        } else {
                            call.respondOutputStream(
                                ContentType.parse("audio/x-wav"),
                                HttpStatusCode.OK,
                                contentLength = file.length()
                            ) {
                                file.inputStream().use { it.copyTo(this) }
                                file.delete()
                            }
                        }
                    }.onFailure { t ->
                        Log.e("ForwardServer", "TTS Synthesis Error", t)
                        call.respond(HttpStatusCode.InternalServerError, t.message ?: "Unknown Error")
                    }
                }

                get("api/tts") {
                    val text = call.parameters.getOrFail("text")
                    val engine = call.parameters.getOrFail("engine")
                    val locale = call.parameters["locale"] ?: ""
                    val voice = call.parameters["voice"] ?: ""
                    val speed = (call.parameters["rate"] ?: call.parameters["speed"])?.toIntOrNull() ?: 50
                    val pitch = call.parameters["pitch"]?.toIntOrNull() ?: 100
                    handleTts(TtsParams(text, engine, locale, voice, speed, pitch))
                }

                post("api/tts") {
                    val params = call.receive<TtsParams>()
                    handleTts(params)
                }

                get("api/engines") { call.respond(callback.engines()) }
                get("api/voices") {
                    val engine = call.parameters.getOrFail("engine")
                    call.respond(callback.voices(engine))
                }
                get("api/legado") {
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
