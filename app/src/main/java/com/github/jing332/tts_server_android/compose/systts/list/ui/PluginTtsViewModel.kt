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
        return PluginTtsProvider(getApplication<Application>() as Context, engine.plugin) as TextToSpeechProvider<TextToSpeechSource>
    }

    private fun initEngine(plugin: Plugin?, source: PluginTtsSource) {
        if (this::engine.isInitialized) {
            if (plugin == null && engine.plugin.pluginId == source.pluginId) return
            if (plugin != null && engine.plugin.pluginId == plugin.pluginId) return
        }

        val context = getApplication<Application>() as Context
        val targetPlugin = plugin ?: getPluginFromDB(source.pluginId)

        // 🛡️ 防御性获取：确保从 Manager 获取的实例是 Ui 引擎
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

    /**
     * 🛠️ 深度加固的加载逻辑
     */
    suspend fun load(
        context: Context,
        plugin: Plugin?,
        source: PluginTtsSource,
        linearLayout: LinearLayout,
    ) = withIO {
        withMain { isLoading = true }
        try {
            initEngine(plugin, source)

            // 1. 数据预加载：即使 UI 挂了，数据也必须先尝试加载
            runCatching { engine.onLoadData() }.onFailure { logger.error(it) { "onLoadData 执行失败" } }

            // 2. UI 渲染逻辑：使用 runCatching 隔离异常，防止其阻断后续列表刷新
            withMain {
                linearLayout.removeAllViews() 
                runCatching { engine.onLoadUI(context, linearLayout) }.onFailure {
                    logger.error(it) { "onLoadUI 渲染失败，尝试继续加载数据" }
                }
            }

            // 3. 强制触发数据更新
            updateLocales()
            updateVoices(source.locale)

        } catch (t: Throwable) {
            logger.error(t) { "初始化引擎失败" }
            throw t
        } finally {
            withMain { isLoading = false }
        }
    }

    private suspend fun updateLocales() {
        val list = engine.getLocales().toList()
        logger.info { "已加载语言列表: ${list.size} 个项" }
        withMain {
            locales.clear()
            locales.addAll(list)
        }
    }

    suspend fun updateVoices(locale: String) {
        if (locale.isBlank()) return 
        val list = engine.getVoices(locale).toList()
        logger.info { "已加载音频列表 ($locale): ${list.size} 个项" }
        withMain {
            voices.clear()
            voices.addAll(list)
        }
    }

    fun updateCustomUI(locale: String, voice: String) {
        try {
            engine.onVoiceChanged(locale, voice)
        } catch (_: NoSuchMethodException) {
        } catch (e: Exception) {
            logger.error(e) { "更新自定义 UI 失败" }
        }
    }
}
