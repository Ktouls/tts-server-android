package com.github.jing332.tts_server_android.compose.systts.plugin

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.github.jing332.common.utils.sizeToReadable
import com.github.jing332.database.entities.plugin.Plugin
import com.github.jing332.database.entities.systts.source.PluginTtsSource

import com.github.jing332.script.runtime.console.Console
import com.github.jing332.tts.speech.plugin.engine.TtsPluginUiEngineV2
import com.github.jing332.tts_server_android.app
import com.github.jing332.tts_server_android.conf.PluginConfig
import com.github.jing332.tts_server_android.conf.SysTtsConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PluginEditorViewModel(app: Application) : AndroidViewModel(app) {
    companion object {
        private const val TAG = "PluginEditViewModel"
        
        // 🛠️ 现代化示例模板：支持 V3 引擎，兼容 V2 逻辑
        private val DEFAULT_SAMPLE_CODE = """
            // 💡 引擎说明：
            // 1. 首行保留 "use quickjs" 将启用 QuickJS (V3) 引擎，支持 ES6+ (async/await, fetch 等)。
            // 2. 若删除首行，将回退至 Rhino (V2) 引擎以兼容旧版插件逻辑。
            "use quickjs";

            let PluginJS = {
                name: "示例插件 (V3)",
                id: "com.example.v3",
                author: "ktouls",
                version: 1,
                
                // 声明用户变量（在更多选项->设置变量中配置）
                vars: {
                    apiKey: { label: "API Key", hint: "填入你的密钥" }
                },

                // 音频获取逻辑
                getAudio: async (text, locale, voice, rate, volume, pitch) => {
                    // 🛡️ V3 引擎可直接使用 fetch (由 nativeBridge 代理，支持动态超时)
                    // const resp = await fetch("https://api.example.com/tts", { method: "GET" });
                    // return await resp.text(); 
                    
                    console.log("正在合成: " + text);
                    return ""; // 返回音频 URL 或 Base64 字符串
                }
            };

            let EditorJS = {
                getLocales: () => ["zh-CN", "en-US"],
                getVoices: (locale) => {
                    return locale === "zh-CN" ? { "xiaoxiao": "晓晓" } : { "jenny": "Jenny" };
                }
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
        // 🛠️ 逻辑修正：如果传入的 defaultCode 为空，使用我们定义的现代化 V3 模板
        plugin.apply { 
            if (code.isEmpty()) {
                code = if (defaultCode.isBlank()) DEFAULT_SAMPLE_CODE else defaultCode
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
        // 🛡️ 注入注入：读取全局超时设置
        val timeout = SysTtsConfig.requestTimeout
        mEngine = mEngine?.also { it.plugin = plugin }
            ?: TtsPluginUiEngineV2(app as Context, plugin, timeout).also { it.console = console }
        mEngine?.eval()
    }

    fun updateCode(code: String) {
        updatePlugin(plugin.copy(code = code))
    }

    private var mDebugJob: Job? = null
    fun debug(code: String) {
        console.info("START\n==========")
        mDebugJob = viewModelScope.launch(Dispatchers.IO) {
            val plugin = try {
                updateCode(code)
                engine.plugin
            } catch (e: Exception) {
                writeErrorLog(e)
                console.info("\n" + "==========\nEND")
                return@launch
            }
            console.debug(plugin.toString().replace(", ", "\n"))
            console.debug("")
            
            kotlin.runCatching {
                val sampleRate = engine.getSampleRate(pluginSource.locale, pluginSource.voice)
                console.debug("Sample rate: ${'$'}sampleRate")
            }.onFailure { writeErrorLog(it) }

            runCatching {
                val isNeedDecode = engine.isNeedDecode(pluginSource.locale, pluginSource.voice)
                console.debug("Need decode: ${'$'}isNeedDecode")
            }.onFailure { writeErrorLog(it) }

            kotlin.runCatching {
                engine.onLoad()
                val stream = engine.getAudio(
                    text = PluginConfig.textParam.value,
                    locale = pluginSource.locale,
                    voice = pluginSource.voice
                )
                val bytes = stream.readBytes()
                console.info("Audio size: ${'$'}{bytes.size.toLong().sizeToReadable()}")
            }.onFailure { writeErrorLog(it) }
            console.info("\n" + "==========\nEND")
        }
    }

    fun stopDebug() {
        runCatching {
            mDebugJob?.cancel()
            engine.onStop()
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
