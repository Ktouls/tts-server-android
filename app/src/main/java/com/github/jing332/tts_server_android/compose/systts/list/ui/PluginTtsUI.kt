package com.github.jing332.tts_server_android.compose.systts.list.ui

import android.util.Log
import android.widget.LinearLayout
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect // 新增
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key // 新增
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.drake.net.utils.withIO
import com.github.jing332.common.utils.toScale
import com.github.jing332.compose.widgets.AppSpinner
import com.github.jing332.compose.widgets.LabelSlider
import com.github.jing332.compose.widgets.LoadingContent
import com.github.jing332.compose.widgets.LoadingDialog
import com.github.jing332.database.entities.plugin.Plugin
import com.github.jing332.database.entities.systts.BasicAudioFormat
import com.github.jing332.database.entities.systts.SystemTtsV2
import com.github.jing332.database.entities.systts.TtsConfigurationDTO
import com.github.jing332.database.entities.systts.source.PluginTtsSource
import com.github.jing332.tts_server_android.R
import com.github.jing332.tts_server_android.compose.systts.AuditionDialog
import com.github.jing332.tts_server_android.compose.systts.list.ui.widgets.AuditionTextField
import com.github.jing332.tts_server_android.compose.systts.list.ui.widgets.BasicInfoEditScreen
import com.github.jing332.tts_server_android.compose.systts.list.ui.widgets.SaveActionHandler
import com.github.jing332.tts_server_android.ui.view.AppDialogs.displayErrorDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PluginTtsUI : IConfigUI() {
    companion object {
        const val TAG = "PluginTtsUI"
    }

    // ... ParamsEditScreen 保持不变 ...
    @Composable
    override fun ParamsEditScreen(
        modifier: Modifier,
        systemTts: SystemTtsV2,
        onSystemTtsChange: (SystemTtsV2) -> Unit,
    ) {
        val tts = (systemTts.config as TtsConfigurationDTO).source as PluginTtsSource
        Column(modifier) {
            val rateStr =
                stringResource(
                    id = R.string.label_speech_rate,
                    if (tts.speed == 0f) stringResource(id = R.string.follow) else tts.speed.toString()
                )
            LabelSlider(
                text = rateStr,
                value = tts.speed,
                onValueChange = {
                    onSystemTtsChange(systemTts.copySource(tts.copy(speed = it.toScale(2))))
                },
                valueRange = 0f..3f
            )

            val volumeStr =
                stringResource(
                    id = R.string.label_speech_volume,
                    if (tts.volume == 0f) stringResource(id = R.string.follow) else tts.volume.toString()
                )
            LabelSlider(
                text = volumeStr, value = tts.volume, onValueChange = {
                    onSystemTtsChange(
                        systemTts.copySource(
                            tts.copy(volume = it.toScale(2))
                        )
                    )
                }, valueRange = 0f..3f
            )

            val pitchStr = stringResource(
                id = R.string.label_speech_pitch,
                if (tts.pitch == 0f) stringResource(id = R.string.follow) else tts.pitch.toString()
            )
            LabelSlider(
                text = pitchStr, value = tts.pitch, onValueChange = {
                    onSystemTtsChange(
                        systemTts.copySource(
                            tts.copy(pitch = it.toScale(2))
                        )
                    )
                }, valueRange = 0f..3f
            )
        }
    }

    // ... FullEditScreen 保持不变 ...
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun FullEditScreen(
        modifier: Modifier,
        systemTts: SystemTtsV2,
        onSystemTtsChange: (SystemTtsV2) -> Unit,
        onSave: () -> Unit,
        onCancel: () -> Unit,
        content: @Composable () -> Unit,
    ) {
        DefaultFullEditScreen(
            modifier,
            title = stringResource(id = R.string.edit_plugin_tts),
            onCancel = onCancel,
            onSave = onSave,
        ) {
            content()
            EditContentScreen(systts = systemTts, onSysttsChange = onSystemTtsChange,)
        }
    }

    @Composable
    fun EditContentScreen(
        modifier: Modifier = Modifier,
        systts: SystemTtsV2,
        onSysttsChange: (SystemTtsV2) -> Unit,
        showBasicInfo: Boolean = true,
        plugin: Plugin? = null,
        vm: PluginTtsViewModel = viewModel(),
    ) {
        var displayName by remember { mutableStateOf("") }

        @Suppress("NAME_SHADOWING")
        val systts by rememberUpdatedState(newValue = systts)
        val tts by rememberUpdatedState(newValue = (systts.config as TtsConfigurationDTO).source as PluginTtsSource)
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        // 1. 初始化时加载插件列表
        LaunchedEffect(Unit) {
            vm.loadPluginList()
        }

        SaveActionHandler {
            val sampleRate = try {
                withIO { vm.engine.getSampleRate(tts.locale, tts.voice) ?: 16000 }
            } catch (e: Exception) {
                context.displayErrorDialog(
                    e,
                    context.getString(R.string.plugin_tts_get_sample_rate_failed)
                )
                null
            }

            val isNeedDecode = try {
                withIO { vm.engine.isNeedDecode(tts.locale, tts.voice) }
            } catch (e: Exception) {
                context.displayErrorDialog(
                    e,
                    context.getString(R.string.plugin_tts_get_need_decode_failed)
                )
                null
            }

            if (sampleRate != null && isNeedDecode != null) {
                onSysttsChange(
                    systts.copy(
                        displayName = if (systts.displayName.isNullOrBlank()) displayName else systts.displayName,
                        config = (systts.config as TtsConfigurationDTO).copy(
                            audioFormat = BasicAudioFormat(
                                sampleRate = sampleRate,
                                isNeedDecode = isNeedDecode
                            )
                        ),
                    )
                )

                true
            } else
                false
        }

        var showLoadingDialog by remember { mutableStateOf(false) }
        if (showLoadingDialog)
            LoadingDialog(onDismissRequest = { showLoadingDialog = false })

        var showAuditionDialog by remember { mutableStateOf(false) }
        @Suppress("UNCHECKED_CAST")
        if (showAuditionDialog)
            AuditionDialog(
                systts = systts,
                engine = if (plugin == null) null else vm.service()
            ) {
                showAuditionDialog = false
            }

        Column(modifier) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                if (showBasicInfo)
                    BasicInfoEditScreen(
                        Modifier.fillMaxWidth(),
                        systemTts = systts,
                        onSystemTtsChange = onSysttsChange
                    )

                AuditionTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    onAudition = {
                        showAuditionDialog = true
                    }
                )

                // 2. 新增: 插件选择器
                AppSpinner(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    labelText = stringResource(R.string.plugin), // 确保 R.string.plugin 存在，或者暂时用 "Plugin"
                    value = tts.pluginId,
                    values = vm.pluginList.map { it.pluginId },
                    entries = vm.pluginList.map { it.name },
                    onSelectedChange = { id, _ ->
                        // 切换插件时，重置 locale 和 voice，触发重载
                        onSysttsChange(
                            systts.copy(
                                config = (systts.config as TtsConfigurationDTO).copy(
                                    source = tts.copy(
                                        pluginId = id as String,
                                        locale = "",
                                        voice = ""
                                    )
                                )
                            )
                        )
                    }
                )

                // 3. 使用 key 包裹动态内容
                // 当 tts.pluginId 变化时，强制销毁并重新创建内部组件，从而重新触发 vm.load
                key(tts.pluginId) {
                    LoadingContent(isLoading = vm.isLoading) {
                        Column {
                            AppSpinner(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                labelText = stringResource(R.string.language),
                                value = tts.locale,
                                values = vm.locales.map { it.first },
                                entries = vm.locales.map { it.second },
                                onSelectedChange = { locale, _ ->
                                    Log.d("PluginTtsUI", "locale onSelectedChange: $locale")
                                    if (locale.toString().isBlank()) return@AppSpinner
                                    onSysttsChange(systts.copySource(tts.copy(locale = locale.toString())))
                                    runCatching {
                                        scope.launch(Dispatchers.IO) {
                                            vm.updateVoices(locale.toString())
                                        }
                                    }
                                },
                            )

                            AppSpinner(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                labelText = stringResource(R.string.label_voice),
                                value = tts.voice,
                                values = vm.voices.map { it.id },
                                entries = vm.voices.map { it.name },
                                icons = vm.voices.map { it.icon },
                                onSelectedChange = { voice, name ->
                                    val lastName = vm.voices.find { it.id == tts.voice }?.name ?: ""
                                    onSysttsChange(
                                        systts.copy(
                                            displayName =
                                            if (systts.displayName.isNullOrBlank() || lastName == systts.displayName) name
                                            else systts.displayName,
                                            config = (systts.config as TtsConfigurationDTO).copy(
                                                source = tts.copy(
                                                    voice = voice as String
                                                )
                                            )
                                        )
                                    )

                                    runCatching {
                                        vm.updateCustomUI(tts.locale, voice)
                                    }.onFailure {
                                        context.displayErrorDialog(it)
                                    }

                                    displayName = name
                                }
                            )

                            val scope = rememberCoroutineScope()
                            suspend fun load(linearLayout: LinearLayout) {
                                runCatching {
                                    vm.load(context, plugin, tts, linearLayout)
                                }.onFailure {
                                    it.printStackTrace()
                                    context.displayErrorDialog(it)
                                }
                            }

                            AndroidView(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateContentSize(),
                                factory = {
                                    LinearLayout(it).apply {
                                        orientation = LinearLayout.VERTICAL
                                        scope.launch { load(this@apply) }
                                    }
                                }
                            )
                        }
                    }
                }
            }

            ParamsEditScreen(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                systemTts = systts,
                onSystemTtsChange = onSysttsChange
            )
        }
    }
}
