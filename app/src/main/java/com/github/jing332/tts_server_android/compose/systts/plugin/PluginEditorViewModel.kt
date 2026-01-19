package com.github.jing332.tts_server_android.compose.systts.plugin

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.github.jing332.common.utils.sizeToReadable
import com.github.jing332.database.entities.plugin.Plugin
import com.github.jing332.database.entities.systts.source.PluginTtsSource
import com.github.jing332.script.runtime.console.Console
import com.github.jing332.tts.speech.plugin.engine.TtsPluginEngineV3
import com.github.jing332.tts.speech.plugin.engine.TtsPluginUiEngineV2
import com.github.jing332.tts_server_android.conf.PluginConfig
import com.github.jing332.tts_server_android.conf.SysTtsConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets

class PluginEditorViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "PluginEditViewModel"
        
        private val DEFAULT_V3_TEMPLATE = """
            // 💡 引擎引导: 首行保留 "use quickjs" 启用 V3 引擎 (支持 ES6/async/await)
            "use quickjs";

            // 全局配置 (Global Config)
            const CONFIG = {
                sampleRate: 24000,
                needDecode: true
            };

            let PluginJS = {
                name: "新插件示例",
                id: "com.example.v3",
                author: "User",
                version: 1,
                vars: {},
                
                // 核心合成逻辑
                getAudio: async (text, locale, voice, rate, volume, pitch) => {
                    console.log("正在合成: " + text);
                    // TODO: 在此实现 fetch 请求
                    return null; 
                }
            };
            
            let EditorJS = {
                getAudioSampleRate: () => CONFIG.sampleRate,
                isNeedDecode: () => CONFIG.needDecode,
                getLocales: () => ["zh-CN"],
                getVoices: (locale) => ({ "voice1": "默认音色" })
            };
        """.trimIndent()
    }

    private var mEngine: TtsPluginUiEngineV2? = null
    
    val engine: TtsPluginUiEngineV2
        get() = mEngine ?: throw IllegalStateException("Engine is null")
        
    val pluginSource: PluginTtsSource
        get() = engine.source
        
    val plugin: Plugin
        get() = engine.plugin

    private val _updateCodeLiveData = MutableLiveData<String>()
    val codeLiveData: LiveData<String> get() = _updateCodeLiveData
    val console: Console = Console()

    fun init(plugin: Plugin, defaultCode: String) {
        plugin.apply { 
            if (code.isEmpty()) {
                code = if (defaultCode.isNotBlank()) defaultCode else DEFAULT_V3_TEMPLATE
            }
        }
        updatePlugin(plugin)
        updateSource(PluginTtsSource())
        _updateCodeLiveData.postValue(plugin.code)
    }

    fun updateSource(source: PluginTtsSource) {
        engine.source = source
    }

    fun updatePlugin(plugin: Plugin) {
        val timeout = SysTtsConfig.requestTimeout
        
        // 实例化 TtsPluginUiEngineV2 (使用 Long 类型的 timeout)
        mEngine = mEngine?.also { it.plugin = plugin }
            ?: TtsPluginUiEngineV2(getApplication(), plugin, timeout.toLong()).also { it.console = console }
            
        try {
            mEngine?.eval()
        } catch (e: Exception) {
            console.error("V2 引擎预览加载警告 (可忽略): ${e.message}")
        }
    }

    fun updateCode(code: String) {
        updatePlugin(plugin.copy(code = code))
    }

    private var mDebugJob: Job? = null

    fun debug(code: String) {
        console.info("START DEBUG\n==========")
        
        mDebugJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                updateCode(code)
                // 智能分流
                val isV3 = code.contains("\"use quickjs\"") || code.contains("'use quickjs'")
                
                if (isV3) {
                    console.info("🚀 正在使用 V3 (QuickJS) 引擎进行调试...")
                    debugV3(code) 
                } else {
                    console.info("🛠️ 正在使用 V2 (Rhino) 引擎进行调试...")
                    debugV2() 
                }

            } catch (e: Exception) {
                writeErrorLog(e)
            } finally {
                 console.info("\n" + "==========\nEND")
            }
        }
    }

    fun stopDebug() {
        runCatching {
            mDebugJob?.cancel()
            engine.onStop()
        }
    }

    private suspend fun debugV2() {
        kotlin.runCatching {
            val sampleRate = engine.getSampleRate(pluginSource.locale, pluginSource.voice)
            console.debug("采样率: $sampleRate")
        }.onFailure { writeErrorLog(it) }

        kotlin.runCatching {
            val isNeedDecode = engine.isNeedDecode(pluginSource.locale, pluginSource.voice)
            console.debug("需要解码: $isNeedDecode")
        }.onFailure { writeErrorLog(it) }

        kotlin.runCatching {
            engine.onLoad()
            val stream = engine.getAudio(
                text = PluginConfig.textParam.value,
                locale = pluginSource.locale,
                voice = pluginSource.voice
            )
            val bytes = stream.readBytes()
            console.info("合成成功，数据大小: ${bytes.size.toLong().sizeToReadable()}")
        }.onFailure { writeErrorLog(it) }
    }

    private suspend fun debugV3(code: String) {
        val tempPlugin = plugin.copy(code = code)
        
        val v3Engine = TtsPluginEngineV3(
            context = getApplication(), 
            plugin = tempPlugin, 
            requestTimeout = SysTtsConfig.requestTimeout.toLong()
        )
        
        try {
            console.debug("插件ID: ${tempPlugin.pluginId}")
            
            val stream = v3Engine.getAudio(
                text = PluginConfig.textParam.value,
                locale = pluginSource.locale,
                voice = pluginSource.voice,
                rate = 50f, 
                volume = 50f,
                pitch = 50f
            )

            if (stream == null) {
                console.error("错误: 引擎返回了空音频流 (Stream is null)")
                return
            }

            val bytes = stream.readBytes()
            
            if (bytes.size < 512) {
                val str = String(bytes, StandardCharsets.UTF_8)
                if (str.startsWith("TTS_NET_ERR:")) {
                     throw Exception("网络超时或错误: " + str.removePrefix("TTS_NET_ERR:"))
                }
            }

            console.info("✅ V3 合成成功!")
            console.info("音频大小: ${bytes.size.toLong().sizeToReadable()}")

        } catch (e: Exception) {
            console.error("V3 调试出错: ${e.message}")
            e.stackTrace.take(3).forEach { console.error("\t at $it") }
        } finally {
            // 🛠️ 关键修正：改用 onStop()，这是本项目统一的销毁方法名
            runCatching { v3Engine.onStop() }
        }
    }

    private fun writeErrorLog(t: Throwable) {
        console.error(t.message)
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { mEngine?.onStop() }
    }
}
