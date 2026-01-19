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
// 🛡️ 这里是 app 模块，import SysTtsConfig 是合法的，不会报错
import com.github.jing332.tts_server_android.conf.SysTtsConfig 
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
        val timeout = SysTtsConfig.requestTimeout // 🛡️ 从配置中读取
        return PluginTtsProvider(getApplication<Application>() as Context, engine.plugin, timeout) as TextToSpeechProvider<TextToSpeechSource>
    }

    private fun initEngine(plugin: Plugin?, source: PluginTtsSource) {
        if (this::engine.isInitialized) {
            if (plugin == null && engine.plugin.pluginId == source.pluginId) return
            if (plugin != null && engine.plugin.pluginId == plugin.pluginId) return
        }

        val context = getApplication<Application>() as Context
        val targetPlugin = plugin ?: getPluginFromDB(source.pluginId)

        // 🛡️ 最优解：由 ViewModel 读取配置并注入到底层 Manager
        val timeout = SysTtsConfig.requestTimeout
        val rawEngine = TtsPluginEngineManager.get(context, targetPlugin, timeout)
        
        engine = if (rawEngine is TtsPluginUiEngineV2) {
            rawEngine
        } else {
            TtsPluginUiEngineV2(context, targetPlugin, timeout).apply { eval() }
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
    ) = withIO {
        withMain { isLoading = true }
        try {
            initEngine(plugin, source)
            runCatching { engine.onLoadData() }.onFailure { logger.error(it) { "onLoadData 执行失败" } }

            withMain {
                linearLayout.removeAllViews() 
                runCatching { engine.onLoadUI(context, linearLayout) }.onFailure {
                    logger.error(it) { "onLoadUI 渲染失败，尝试继续加载数据" }
                }
            }
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
        } catch (e: Exception) {
            logger.error(e) { "更新自定义 UI 失败" }
        }
    }
}
