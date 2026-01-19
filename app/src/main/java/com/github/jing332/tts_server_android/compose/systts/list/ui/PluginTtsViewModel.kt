package com.github.jing332.tts_server_android.compose.systts.list.ui

import android.app.Application
import android.content.Context
import android.widget.LinearLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drake.net.utils.withIO
import com.drake.net.utils.withMain
import com.github.jing332.database.dbm
import com.github.jing332.database.entities.plugin.Plugin
import com.github.jing332.database.entities.systts.source.PluginTtsSource
import com.github.jing332.database.entities.systts.source.TextToSpeechSource
import com.github.jing332.tts.speech.TextToSpeechProvider
import com.github.jing332.tts.speech.plugin.PluginTtsProvider
import com.github.jing332.tts.speech.plugin.engine.TtsPluginEngineManager
import com.github.jing332.tts.speech.plugin.engine.TtsPluginUiEngineV2
import com.github.jing332.tts_server_android.JsConsoleManager
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PluginTtsViewModel(app: Application) : AndroidViewModel(app) {
    companion object {
        private val logger = KotlinLogging.logger { PluginTtsViewModel::class.java.name }
    }

    lateinit var engine: TtsPluginUiEngineV2
    val pluginList = mutableStateListOf<Plugin>()

    fun loadPluginList() {
        viewModelScope.launch(Dispatchers.IO) {
            val plugins = dbm.pluginDao.allEnabled
            withMain {
                pluginList.clear()
                pluginList.addAll(plugins)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun service(): TextToSpeechProvider<TextToSpeechSource> {
        // 修正：将 engine 赋值逻辑与 Provider 重构后的逻辑对齐
        return PluginTtsProvider(getApplication<Application>() as Context, engine.plugin).also {
            // 注意：重构后的 Provider 内部通过 EngineManager 获取实例，
            // 这里的 engine 赋值主要用于 UI 状态同步
        } as TextToSpeechProvider<TextToSpeechSource>
    }

    private fun initEngine(plugin: Plugin?, source: PluginTtsSource) {
        if (this::engine.isInitialized) {
            if (plugin == null && engine.plugin.pluginId == source.pluginId) return
            if (plugin != null && engine.plugin.pluginId == plugin.pluginId) return
        }

        // 修正：使用更新后的 TtsPluginEngineManager 路径及类型处理
        val context = getApplication<Application>() as Context
        val targetPlugin = plugin ?: getPluginFromDB(source.pluginId)
        
        // 严谨逻辑：确保从 Manager 获取的是具有 UI 渲染能力的 TtsPluginUiEngineV2
        val rawEngine = TtsPluginEngineManager.get(context, targetPlugin)
        engine = if (rawEngine is TtsPluginUiEngineV2) {
            rawEngine
        } else {
            TtsPluginUiEngineV2(context, targetPlugin).apply { eval() }
        }

        engine.console = JsConsoleManager.ui
        engine.source = source
    }

    private fun getPluginFromDB(id: String) =
        dbm.pluginDao.getEnabled(pluginId = id)
            ?: throw IllegalStateException("Plugin $id not found from database")

    var isLoading by mutableStateOf(false)
    val locales = mutableStateListOf<Pair<String, String>>()
    val voices = mutableStateListOf<TtsPluginUiEngineV2.Voice>()

    suspend fun load(
        context: Context,
        plugin: Plugin?,
        source: PluginTtsSource,
        linearLayout: LinearLayout,
    ) =
        withIO {
            withMain { isLoading = true }
            try {
                initEngine(plugin, source)
                engine.onLoadData()

                withMain {
                    linearLayout.removeAllViews() 
                    engine.onLoadUI(context, linearLayout)
                }

                updateLocales()
                updateVoices(source.locale)
            } catch (t: Throwable) {
                logger.error(t) { "加载插件 UI 失败" }
                throw t
            } finally {
                withMain { isLoading = false }
            }
        }

    private suspend fun updateLocales() {
        val list = engine.getLocales().toList()
        withMain {
            locales.clear()
            locales.addAll(list)
        }
    }

    suspend fun updateVoices(locale: String) {
        if (locale.isBlank()) return 
        val list = engine.getVoices(locale).toList()
        withMain {
            voices.clear()
            voices.addAll(list)
        }
    }

    fun updateCustomUI(locale: String, voice: String) {
        try {
            engine.onVoiceChanged(locale, voice)
        } catch (_: NoSuchMethodException) {
        }
    }
}
