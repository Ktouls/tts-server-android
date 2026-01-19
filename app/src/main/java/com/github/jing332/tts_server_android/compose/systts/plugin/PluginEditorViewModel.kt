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
        
        // 默认 V3 示例模板 (当传入代码为空时使用)
        private val DEFAULT_V3_TEMPLATE = """
            // 💡 引擎引导: 首行保留 "use quickjs" 启用 V3 引擎 (支持 ES6/async/await)
            "use quickjs";

            let PluginJS = {
                name: "新插件示例",
                id: "com.example.v3",
                author: "User",
                version: 1,
                vars: {},
                getAudio: async (text, locale, voice, rate, volume, pitch) => {
                    console.log("正在合成: " + text);
                    return ""; // 返回音频链接或 Base64
                }
            };
            
            let EditorJS = {
                getLocales: () => ["zh-CN"],
                getVoices: (locale) => ({ "voice1": "默认音色" })
            };
        """.trimIndent()
    }

    private var mEngine: TtsPluginUiEngineV2? = null
    
    // 获取当前 V2 引擎 (用于 UI 渲染)
    val engine: TtsPluginUiEngineV2
        get() = mEngine ?: throw IllegalStateException("Engine is null")
        
    val pluginSource: PluginTtsSource
        get() = engine.source
        
    val plugin: Plugin
        get() = engine.plugin

    private val _updateCodeLiveData = MutableLiveData<String>()
    val codeLiveData: LiveData<String> get() = _updateCodeLiveData
    val console: Console = Console()

    // 初始化：加载插件代码
    fun init(plugin: Plugin, defaultCode: String) {
        plugin.apply { 
            // 如果代码为空，优先使用传入的 defaultCode；如果还为空，使用我们的 V3 模板
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

    // 更新插件并刷新 V2 引擎 (用于 UI 预览)
    fun updatePlugin(plugin: Plugin) {
        // 注入系统超时配置，防止 V2 引擎加载时卡死
        val timeout = SysTtsConfig.requestTimeout
        mEngine = mEngine?.also { it.plugin = plugin }
            ?: TtsPluginUiEngineV2(getApplication(), plugin, timeout).also { it.console = console }
            
        // 这里会调用 V2 引擎的 eval()。
        // 请确保 TtsPluginUiEngineV2.kt 已经加了“正则净化”，否则 V3 代码会导致这里报错。
        try {
            mEngine?.eval()
        } catch (e: Exception) {
            // 忽略 V2 解析 ES6 的错误，避免编辑器崩溃，只记录日志
            console.error("V2 引擎预览加载警告 (可忽略): ${e.message}")
        }
    }

    fun updateCode(code: String) {
        updatePlugin(plugin.copy(code = code))
    }

    private var mDebugJob: Job? = null

    // 核心调试入口
    fun debug(code: String) {
        console.info("START DEBUG\n==========")
        
        mDebugJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. 先更新代码 (这会触发 V2 引擎解析 EditorJS 以获取采样率等信息)
                updateCode(code)
                val currentPlugin = engine.plugin
                
                // 2. 智能分流：检测是否启用了 V3 引擎
                val isV3 = code.contains("\"use quickjs\"") || code.contains("'use quickjs'")
                
                if (isV3) {
                    console.info("🚀 正在使用 V3 (QuickJS) 引擎进行调试...")
                    debugV3(code) // 进入 V3 专用调试通道
                } else {
                    console.info("🛠️ 正在使用 V2 (Rhino) 引擎进行调试...")
                    debugV2() // 进入 V2 传统调试通道
                }

            } catch (e: Exception) {
                writeErrorLog(e)
            } finally {
                 console.info("\n" + "==========\nEND")
            }
        }
    }

    // 停止调试
    fun stopDebug() {
        runCatching {
            mDebugJob?.cancel()
            engine.onStop()
        }
    }

    // ========== V2 调试逻辑 (旧版) ==========
    private suspend fun debugV2() {
        kotlin.runCatching {
            val sampleRate = engine.getSampleRate(pluginSource.locale, pluginSource.voice)
            console.debug("采样率 (Sample Rate): $sampleRate")
        }.onFailure { writeErrorLog(it) }

        kotlin.runCatching {
            val isNeedDecode = engine.isNeedDecode(pluginSource.locale, pluginSource.voice)
            console.debug("需要解码 (Need Decode): $isNeedDecode")
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

    // ========== V3 调试逻辑 (新版 QuickJS) ==========
    private suspend fun debugV3(code: String) {
        // 动态创建一个 V3 引擎实例
        val v3Engine = TtsPluginEngineV3(getApplication())
        try {
            // 1. 加载代码
            v3Engine.eval(code)
            
            // 2. 打印基础信息
            console.debug("插件名称: ${v3Engine.pluginId}")
            
            // 3. 执行合成 (使用系统参数)
            // 注意：V3 引擎会自动处理 fetch 网络请求的超时
            val stream = v3Engine.getAudio(
                text = PluginConfig.textParam.value,
                locale = pluginSource.locale,
                voice = pluginSource.voice,
                rate = 50, // 默认 50
                volume = 50,
                pitch = 50
            )

            if (stream == null) {
                console.error("错误: 引擎返回了空音频流 (Stream is null)")
                return
            }

            // 4. 读取数据并检测错误暗号
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
            // 打印堆栈以便排查脚本错误
            e.stackTrace.take(3).forEach { console.error("\t at $it") }
        } finally {
            // 必须销毁引擎以释放内存
            v3Engine.destroy()
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
