@file:Suppress("DEPRECATION")

package com.github.jing332.tts_server_android.service.systts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.graphics.Color
import android.media.AudioFormat
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.ServiceCompat.stopForeground
import androidx.core.content.ContextCompat
import com.github.jing332.common.utils.StringUtils
import com.github.jing332.common.utils.limitLength
import com.github.jing332.common.utils.longToast
import com.github.jing332.common.utils.registerGlobalReceiver
import com.github.jing332.common.utils.runOnUI
import com.github.jing332.common.utils.sizeToReadable
import com.github.jing332.common.utils.startForegroundCompat
import com.github.jing332.common.utils.toHtmlBold
import com.github.jing332.common.utils.toHtmlSmall
import com.github.jing332.database.dbm
import com.github.jing332.database.entities.systts.AudioParams
import com.github.jing332.database.entities.systts.SystemTtsV2
import com.github.jing332.database.entities.systts.TtsConfigurationDTO
import com.github.jing332.tts.ConfigType
import com.github.jing332.tts.MixSynthesizer
import com.github.jing332.tts.SynthesizerConfig
import com.github.jing332.tts.error.StreamProcessorError
import com.github.jing332.tts.error.SynthesisError
import com.github.jing332.tts.error.TextProcessorError
import com.github.jing332.tts.synthesizer.RequestPayload
import com.github.jing332.tts.synthesizer.SystemParams
import com.github.jing332.tts.synthesizer.event.ErrorEvent
import com.github.jing332.tts.synthesizer.event.Event
import com.github.jing332.tts.synthesizer.event.IEventDispatcher
import com.github.jing332.tts.synthesizer.event.NormalEvent
import com.github.jing332.tts_server_android.R
import com.github.jing332.tts_server_android.compose.MainActivity
import com.github.jing332.tts_server_android.conf.SysTtsConfig
import com.github.jing332.tts_server_android.constant.AppConst
import com.github.jing332.tts_server_android.constant.SystemNotificationConst
import com.github.jing332.tts_server_android.service.systts.help.TextProcessor
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.jvm.Throws
import kotlin.system.exitProcess


@Suppress("DEPRECATION")
class SystemTtsService : TextToSpeechService(), IEventDispatcher {
    companion object {
        const val TAG = "SystemTtsService"
        private val logger = KotlinLogging.logger(TAG)

        const val ACTION_UPDATE_CONFIG = "tts.update_config"
        const val ACTION_UPDATE_REPLACER = "tts.update_replacer"

        const val ACTION_NOTIFY_CANCEL = "tts.notification.cancel"
        const val ACTION_NOTIFY_KILL_PROCESS = "tts.notification.exit"
        const val NOTIFICATION_CHAN_ID = "system_tts_service"

        const val DEFAULT_VOICE_NAME = "DEFAULT_默认"
        const val PARAM_BGM_ENABLED = "bgm_enabled"

        /**
         * 更新配置
         */
        fun notifyUpdateConfig(isOnlyReplacer: Boolean = false) {
            if (isOnlyReplacer)
                AppConst.localBroadcast.sendBroadcast(Intent(ACTION_UPDATE_REPLACER))
            else
                AppConst.localBroadcast.sendBroadcast(Intent(ACTION_UPDATE_CONFIG))
        }
    }

    private val mCurrentLanguage: MutableList<String> = mutableListOf("zho", "CHN", "")


    private val mTextProcessor = TextProcessor()
    private var mTtsManager: MixSynthesizer? = null


    private val mNotificationReceiver: NotificationReceiver by lazy { NotificationReceiver() }
    private val mLocalReceiver: LocalReceiver by lazy { LocalReceiver() }

    private lateinit var mScope: CoroutineScope


    // WIFI 锁
    private val mWifiLock by lazy {
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tts-server:wifi_lock")
    }

    // 唤醒锁
    private var mWakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        updateNotification(getString(R.string.systts_service), "")
        mScope = CoroutineScope(Dispatchers.IO)

        registerGlobalReceiver(
            listOf(ACTION_NOTIFY_KILL_PROCESS, ACTION_NOTIFY_CANCEL), mNotificationReceiver
        )

        AppConst.localBroadcast.registerReceiver(
            mLocalReceiver,
            IntentFilter(ACTION_UPDATE_CONFIG).apply {
                addAction(ACTION_UPDATE_REPLACER)
            }
        )

        if (SysTtsConfig.isWakeLockEnabled)
            mWakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                "tts-server:wake_lock"
            )

        mWakeLock?.acquire(60 * 20 * 100)
        mWifiLock.acquire()


        initManager()
    }

    fun initManager() {
        logger.debug { "initialize or load configruation" }
        mScope.launch {
            mTtsManager = mTtsManager ?: MixSynthesizer.global.apply {
                context.androidContext = appCtx
                context.event = this@SystemTtsService
                context.cfg = SynthesizerConfig(
                    // 修正点：去掉 .value，直接转换为 Long
                    requestTimeout = { SysTtsConfig.requestTimeout.toLong() },
                    maxRetryTimes = { SysTtsConfig.maxRetryCount },
                    streamPlayEnabled = { SysTtsConfig.isStreamPlayModeEnabled },
                    silenceSkipEnabled = { SysTtsConfig.isSkipSilentAudio },
                    bgmShuffleEnabled = { SysTtsConfig.isBgmShuffleEnabled },
                    bgmVolume = { SysTtsConfig.bgmVolume },
                    audioParams = {
                        AudioParams(
                            speed = SysTtsConfig.audioParamsSpeed,
                            volume = SysTtsConfig.audioParamsVolume,
                            pitch = SysTtsConfig.audioParamsPitch
                        )
                    }
                )
                textProcessor = mTextProcessor
            }

            mTtsManager!!.init()
        }
    }

    fun loadReplacer() {
        mTextProcessor.loadReplacer()
    }

    override fun onDestroy() {
        logger.debug { "service destroy" }
        super.onDestroy()

        mScope.launch(Dispatchers.Main) {
            mTtsManager?.destroy()
            mTtsManager = null
            logger.debug { "destoryed" }
        }
        unregisterReceiver(mNotificationReceiver)
        AppConst.localBroadcast.unregisterReceiver(mLocalReceiver)

        mWakeLock?.release()
        mWifiLock.release()

        stopForeground(/* removeNotification = */ true)
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        return if (Locale.SIMPLIFIED_CHINESE.isO3Language == lang || Locale.US.isO3Language == lang) {
            if (Locale.SIMPLIFIED_CHINESE.isO3Country == country || Locale.US.isO3Country == country) TextToSpeech.LANG_COUNTRY_AVAILABLE else TextToSpeech.LANG_AVAILABLE
        } else TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun onGetLanguage(): Array<String> {
        return mCurrentLanguage.toTypedArray()
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val result = onIsLanguageAvailable(lang, country, variant)
        mCurrentLanguage.clear()
        mCurrentLanguage.addAll(
            mutableListOf(
                lang.toString(),
                country.toString(),
                variant.toString()
            )
        )

        return result
    }

    override fun onGetDefaultVoiceNameFor(
        lang: String?,
        country: String?,
        variant: String?,
    ): String {
        return DEFAULT_VOICE_NAME
    }


    override fun onGetVoices(): MutableList<Voice> {
        val list =
            mutableListOf(Voice(DEFAULT_VOICE_NAME, Locale.getDefault(), 0, 0, true, emptySet()))

        dbm.systemTtsV2.getAllGroupWithTts().forEach { groups ->
            groups.list.forEach { it ->
                if (it.config is TtsConfigurationDTO) {
                    val tts = (it.config as TtsConfigurationDTO).source

                    list.add(
                        Voice(
                            /* name = */ "${it.displayName}_${it.id}",
                            /* locale = */ Locale.forLanguageTag(tts.locale),
                            /* quality = */ 0,
                            /* latency = */ 0,
                            /* requiresNetworkConnection = */true,
                            /* features = */mutableSetOf<String>().apply {
                                add(it.order.toString())
                                add(it.id.toString())
                            }
                        )
                    )
                }

            }
        }

        return list
    }

    override fun onIsValidVoiceName(voiceName: String?): Int {
        val isDefault = voiceName == DEFAULT_VOICE_NAME
        if (isDefault) return TextToSpeech.SUCCESS

        val index =
            dbm.systemTtsV2.all.indexOfFirst { "${it.displayName}_${it.id}" == voiceName }

        return if (index == -1) TextToSpeech.ERROR else TextToSpeech.SUCCESS
    }

    override fun onStop() {
        if (synthesizerJob?.isActive == true) {
            synthesizerJob?.cancel()
        }
        synthesizerJob = null
        updateNotification(getString(R.string.systts_state_idle), "")
    }

    private lateinit var mCurrentText: String
    private var synthesizerJob: Job? = null
    private var mNotificationJob: Job? = null

    // 🛠️ 记住上一个 callback，解决系统队列死锁
    private var lastTtsCallback: android.speech.tts.SynthesisCallback? = null


    private fun getConfigIdFromVoiceName(voiceName: String): Result<Long?, Unit> {
        if (voiceName.isNotBlank()) {
            val voiceSplitList = voiceName.split("_")
            if (voiceSplitList.isEmpty()) {
                return Err(Unit)
            } else {
                voiceSplitList.getOrNull(voiceSplitList.size - 1)?.let { idStr ->
                    return Ok(idStr.toLongOrNull())
                }
            }
        }
        return Ok(null)
    }

    override fun onSynthesizeText(
        request: SynthesisRequest,
        callback: android.speech.tts.SynthesisCallback,
    ) {
        val text = request.charSequenceText.toString().trim()
        if (text.isBlank()) {
            logger.debug { "Skip empty text request" }
            callback.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.done()
            return
        }

        // 🛠️ 强力破窗：新请求冒头时强制注销旧任务，解决阅读APP重新点击播放没反应
        onStop()
        lastTtsCallback?.runCatching { error(TextToSpeech.ERROR_SYNTHESIS); done() }
        lastTtsCallback = callback

        mNotificationJob?.cancel()
        reNewWakeLock()
        startForegroundService()
        mCurrentText = text
        updateNotification(getString(R.string.systts_state_synthesizing), text)

        val enabledBgm = request.params.getBoolean(PARAM_BGM_ENABLED, true)
        mTtsManager?.context?.cfg?.bgmEnabled = { enabledBgm }

        runBlocking {
            var cfgId: Long? = getConfigIdFromVoiceName(request.voiceName ?: "").onFailure {
                longToast(R.string.voice_name_bad_format)
                callback.error(TextToSpeech.ERROR_INVALID_REQUEST)
                callback.done()
                return@runBlocking
            }.value

            val exceptionHandler = CoroutineExceptionHandler { _, e ->
                Log.e(TAG, "Synthesize Crash Caught: ${e.message}", e)
                callback.error(TextToSpeech.ERROR_SYNTHESIS)
                callback.done()
            }

            synthesizerJob = mScope.launch(exceptionHandler) {
                var isAudioOutputted = false
                try {
                    // 🛠️ 增加 125 秒总保护
                    withTimeoutOrNull(125000L) {
                        mTtsManager?.synthesize(
                            params = SystemParams(text = request.charSequenceText.toString()),
                            forceConfigId = cfgId,
                            callback = object :
                                com.github.jing332.tts.synthesizer.SynthesisCallback {
                                override fun onSynthesizeStart(sampleRate: Int) {
                                    callback.start(
                                        /* sampleRateInHz = */ sampleRate,
                                        /* audioFormat = */ AudioFormat.ENCODING_PCM_16BIT,
                                        /* channelCount = */ 1
                                    )
                                }

                                override fun onSynthesizeAvailable(audio: ByteArray) {
                                    isAudioOutputted = true
                                    writeToCallBack(callback, audio)
                                }

                            }
                        )
                    }?.onSuccess {
                        // 如果插件“跳过”了重试且没给音频，向系统报错
                        if (!isAudioOutputted) {
                            callback.error(TextToSpeech.ERROR_NETWORK_TIMEOUT)
                        }
                    }?.onFailure {
                        handleSynthesisError(it, callback)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Synthesize Interrupted: ${e.message}")
                    callback.error(TextToSpeech.ERROR_SYNTHESIS)
                } finally {
                    // 🛠️ 结案铁律：确保必须调用 done()，防止队列挂起
                    callback.done()
                    if (lastTtsCallback == callback) lastTtsCallback = null
                }
            }

            synthesizerJob?.join()
        }


        mNotificationJob = mScope.launch {
            delay(5000)
            stopForeground(true)
            mNotificationDisplayed = false
        }
    }

    private suspend fun handleSynthesisError(err: SynthesisError, callback: android.speech.tts.SynthesisCallback) {
        when (err) {
            SynthesisError.ConfigEmpty -> {
                callback.error(TextToSpeech.ERROR_SYNTHESIS)
            }

            is SynthesisError.TextHandle -> {
                callback.error(TextToSpeech.ERROR_SYNTHESIS)
                awaitCancellation()
            }

            is SynthesisError.PresetMissing -> {
                logE(R.string.tts_config_not_exist)
                longToast(R.string.tts_config_not_exist)
                callback.error(TextToSpeech.ERROR_INVALID_REQUEST)
            }
        }
    }

    // 【核心修改】对暗号 + 强制抛出异常
    private fun writeToCallBack(
        callback: android.speech.tts.SynthesisCallback,
        pcmData: ByteArray,
    ) {
        try {
            if (pcmData.size < 512) { 
                val str = String(pcmData, StandardCharsets.UTF_8)
                if (str.startsWith("TTS_NET_ERR:")) {
                    logE("捕获网络超时暗号: $str")
                    callback.error(TextToSpeech.ERROR_NETWORK_TIMEOUT)
                    // 跳转到 finally 强制结案
                    throw RuntimeException("Network Error Stop")
                }
            }

            val maxBufferSize: Int = callback.maxBufferSize
            var offset = 0
            while (offset < pcmData.size && synthesizerJob?.isActive == true) {
                val bytesToWrite = maxBufferSize.coerceAtMost(pcmData.size - offset)
                val ret = callback.audioAvailable(pcmData, offset, bytesToWrite)
                if (ret == TextToSpeech.ERROR) {
                    throw RuntimeException("SynthesisCallback.audioAvailable ERROR")
                }
                offset += bytesToWrite
            }
        } catch (e: Exception) {
            throw e 
        }
    }

    private fun reNewWakeLock() {
        if (mWakeLock != null && mWakeLock?.isHeld == false) {
            mWakeLock?.acquire(60 * 20 * 1000)
        }
    }

    private var mNotificationBuilder: Notification.Builder? = null

    // 通知是否显示中
    private var mNotificationDisplayed = false

    /* 启动前台服务通知 */
    private fun startForegroundService() {
        if (SysTtsConfig.isForegroundServiceEnabled && !mNotificationDisplayed) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val chan = NotificationChannel(
                    NOTIFICATION_CHAN_ID,
                    getString(R.string.systts_service),
                    NotificationManager.IMPORTANCE_NONE
                )
                chan.lightColor = Color.CYAN
                chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE

                notificationManager.createNotificationChannel(chan)
            }
            val notifi = getNotification()

            startForegroundCompat(SystemNotificationConst.ID_SYSTEM_TTS, notifi)
            mNotificationDisplayed = true
        }
    }

    /* 更新通知 */
    private fun updateNotification(title: String, content: String? = null) {
        if (SysTtsConfig.isForegroundServiceEnabled)
            runOnUI {
                mNotificationBuilder?.let { builder ->
                    content?.let {
                        val bigTextStyle =
                            Notification.BigTextStyle().bigText(it).setSummaryText("TTS")
                        builder.style = bigTextStyle
                        builder.setContentText(it)
                    }

                    builder.setContentTitle(title)
                    startForegroundCompat(
                        SystemNotificationConst.ID_SYSTEM_TTS,
                        builder.build()
                    )
                }
            }
    }

    /* 获取通知 */
    @Suppress("DEPRECATION")
    private fun getNotification(): Notification {
        val notification: Notification
        /*Android 12(S)+ 必须指定PendingIntent.FLAG_*/
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
        /*点击通知跳转*/
        val pendingIntent =
            PendingIntent.getActivity(
                this, 1, Intent(
                    this,
                    MainActivity::class.java
                ).apply { /*putExtra(KEY_FRAGMENT_INDEX, INDEX_SYS_TTS)*/ }, pendingIntentFlags
            )

        val killProcessPendingIntent = PendingIntent.getBroadcast(
            this, 0, Intent(
                ACTION_NOTIFY_KILL_PROCESS
            ), pendingIntentFlags
        )
        val cancelPendingIntent =
            PendingIntent.getBroadcast(
                this,
                0,
                Intent(ACTION_NOTIFY_CANCEL),
                pendingIntentFlags
            )

        mNotificationBuilder = Notification.Builder(applicationContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mNotificationBuilder?.setChannelId(NOTIFICATION_CHAN_ID)
        }
        notification = mNotificationBuilder!!
            .setSmallIcon(R.mipmap.ic_app_notification)
            .setContentIntent(pendingIntent)
            .setColor(ContextCompat.getColor(this, R.color.md_theme_light_primary))
            .addAction(0, getString(R.string.kill_process), killProcessPendingIntent)
            .addAction(0, getString(R.string.cancel), cancelPendingIntent)
            .build()

        return notification
    }

    @Suppress("DEPRECATION")
    inner class NotificationReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_NOTIFY_KILL_PROCESS -> { // 通知按钮{结束进程}
                    stopForeground(true)
                    exitProcess(0)
                }

                ACTION_NOTIFY_CANCEL -> { // 通知按钮{取消}
                    if (mTtsManager?.isSynthesizing == true)
                        onStop() /* 取消当前播放 */
                    else /* 无播放，关闭通知 */ {
                        stopForeground(true)
                        mNotificationDisplayed = false
                    }
                }
            }
        }
    }

    inner class LocalReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_UPDATE_CONFIG -> initManager()
                ACTION_UPDATE_REPLACER -> loadReplacer()
            }
        }
    }

    private fun logD(msg: String) = logger.debug(msg)
    private fun logI(msg: String) = logger.info(msg)
    private fun logW(msg: String) = logger.warn(msg)
    private fun logE(msg: String, throwable: Throwable? = null) {
        updateNotification("⚠️ Error", msg)
        Log.e(TAG, msg, throwable)

        logger.error(msg)
    }

    @Throws(Resources.NotFoundException::class)
    private fun logE(@StringRes strId: Int, throwable: Throwable? = null) {
        logE(getString(strId, throwable), throwable)
    }

    override fun dispatch(event: Event) {
        when (event) {
            is ErrorEvent -> errorEvent(event)
            is NormalEvent -> normalEvent(event)
            else -> {
                logE("Unknown event: $event")
            }
        }
    }

    private fun RequestPayload.text(): String {
        val tag = config.tag
        val standbyTag = config.standbyConfig?.tag
        val standbyInfo = if (standbyTag is SystemTtsV2) {
            "<br>${getString(R.string.systts_standby)} " + standbyTag.displayName
        } else ""

        val config = if (tag is SystemTtsV2) {
            tag.displayName + ", ${config.source.voice}, ${config.speechInfo.tagName}" + standbyInfo.toHtmlSmall()
        } else ""
        return text.toHtmlBold() + "<br>" + config
    }

    private fun normalEvent(e: NormalEvent) {
        when (e) {
            is NormalEvent.Request ->
                if (e.retries > 0)
                    logW(getString(R.string.systts_log_start_retry, e.retries))
                else
                    logI(
                        getString(
                            R.string.systts_log_request_audio,
                            e.request.text()
                        )
                    )


            is NormalEvent.DirectPlay -> logI(
                getString(
                    R.string.systts_log_direct_play,
                    e.request.text()
                )
            )

            is NormalEvent.ReadAllFromStream -> {
                if (e.size > 0)
                    logI(
                        getString(
                            R.string.systts_log_success,
                            e.size.sizeToReadable(),
                            "${e.costTime}ms"
                        ) + "<br> ${e.request.text()}"
                    )
            }

            is NormalEvent.HandleStream ->
                logI(
                    getString(
                        R.string.loading_audio_stream,
                        e.request.text.limitLength(10)
                    )
                )

            is NormalEvent.StandbyTts -> logI(
                getString(
                    R.string.use_standby_tts, e.request.text()
                )
            )

            NormalEvent.RequestCountEnded -> logW(getString(R.string.reach_retry_limit))
            is NormalEvent.BgmCurrentPlaying -> {
                val name = e.source.path.split("/").lastOrNull() ?: e.source.path
                logI(getString(R.string.current_playing_bgm, "${e.source.volume}, ${name}"))
            }
        }
    }

    private fun errorEvent(e: ErrorEvent) {
        when (e) {
            is ErrorEvent.TextProcessor -> handleTextProcessorError(e.error)
            is ErrorEvent.Request -> logE(R.string.systts_log_failed, e.cause)
            is ErrorEvent.RequestTimeout -> logW("超时：120秒")
            ErrorEvent.ConfigEmpty -> {
                logE(R.string.config_empty_error)
            }

            is ErrorEvent.BgmLoading -> {
                logE(R.string.config_load_error, e.cause)
            }

            is ErrorEvent.Repository -> {
                logE(R.string.config_load_error, e.cause)
            }

            is ErrorEvent.DirectPlay -> logE(getString(R.string.systts_log_direct_play, e.cause))
            is ErrorEvent.ResultProcessor -> e.error.let { processor ->
                when (processor) {
                    is StreamProcessorError.AudioDecoding -> logE(
                        getString(
                            R.string.audio_decoding_error,
                            processor.error.toString() + "<br>" + e.request.text()
                        )
                    )

                    is StreamProcessorError.AudioSource -> logE(
                        getString(
                            R.string.audio_source_error,
                            processor.error.toString() + "<br>" + e.request.text()
                        )
                    )

                    is StreamProcessorError.HandleError -> logE(
                        getString(
                            R.string.stream_handle_error,
                            processor.error.toString() + "<br>" + e.request.text()
                        )
                    )
                }
            }
        }
    }

    fun ConfigType.toLocaleString() = when (this) {
        ConfigType.SINGLE_VOICE -> getString(R.string.single_voice)
        ConfigType.TAG -> getString(R.string.tag)
    }

    private fun handleTextProcessorError(err: TextProcessorError) {
        when (err) {
            is TextProcessorError.HandleText -> logE(
                R.string.systts_log_text_handle_failed,
                err.error
            )

            is TextProcessorError.MissingConfig -> {
                val str = getString(R.string.missing_config, err.type.toLocaleString())
                longToast(str)
                logE(str)
            }

            is TextProcessorError.MissingRule -> {
                getString(
                    R.string.missing_speech_rule,
                    err.id.ifBlank { getString(R.string.none) }
                ).let {
                    logE(it)
                    longToast(StringUtils.WARNING_EMOJI + " " + it)
                }

            }

            TextProcessorError.Initialization -> logE(getString(R.string.text_processor_init_failed))
        }
    }

}
