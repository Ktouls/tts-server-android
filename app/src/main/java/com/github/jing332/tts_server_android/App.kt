package com.github.jing332.tts_server_android

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startActivity
import androidx.core.graphics.drawable.toBitmap
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.asImage
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.request.crossfade
import com.github.jing332.compose.widgets.AsyncCircleImageSettings
import com.github.jing332.database.entities.systts.SystemTtsV2
import com.github.jing332.tts_server_android.App.Companion.context
import com.github.jing332.tts_server_android.conf.SystemTtsForwarderConfig
import com.github.jing332.tts_server_android.constant.AppConst
import com.github.jing332.tts_server_android.model.hanlp.HanlpManager
import com.github.jing332.tts_server_android.service.forwarder.ForwarderServiceManager.switchSysTtsForwarder
import com.github.jing332.tts_server_android.service.forwarder.system.SysTtsForwarderService
import com.petterp.floatingx.FloatingX
import com.petterp.floatingx.compose.enableComposeSupport
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlin.properties.Delegates
import kotlin.text.lowercase


val app: App
    inline get() = App.instance

@Suppress("DEPRECATION")
class App : Application() {
    companion object {
        const val TAG = "App"
        var instance: App by Delegates.notNull()
        val context: Context by lazy { instance }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base.apply { AppLocale.setLocale(base) })
    }

    @SuppressLint("SdCardPath")
    @OptIn(DelicateCoroutinesApi::class, DelicateCoilApi::class)
    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashHandler(this)

        SystemTtsV2.Converters.json = AppConst.jsonBuilder

        AsyncCircleImageSettings.interceptor = AsyncImageInterceptor

        SingletonImageLoader.setUnsafe(
            ImageLoader
                .Builder(context)
                .crossfade(true)
                .build()
        )


        GlobalScope.launch {
            HanlpManager.initDir(
                context.getExternalFilesDir("hanlp")?.absolutePath
                    ?: "/data/data/$packageName/files/hanlp"
            )

            // 👇👇👇 新增：智能自启逻辑 👇👇👇
            // 如果配置为“自动启动”且服务当前没运行，则启动它
            if (SystemTtsForwarderConfig.isAutoStart.value && !SysTtsForwarderService.isRunning) {
                switchSysTtsForwarder()
            }
        }
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    fun restart() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)!!
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        //杀掉以前进程
        Process.killProcess(Process.myPid());
    }
}
