package com.github.jing332.tts_server_android.constant

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.github.jing332.tts_server_android.App
import com.github.jing332.tts_server_android.BuildConfig
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import java.util.Locale

@SuppressLint("SimpleDateFormat")
@Suppress("DEPRECATION")
object AppConst {
    val fileProviderAuthor = BuildConfig.APPLICATION_ID + ".fileprovider"
    
    val localBroadcast by lazy { LocalBroadcastManager.getInstance(App.context) }
    val externalFilesDir by lazy { checkNotNull(App.context.getExternalFilesDir("")) { "getExternalFilesDir() == null" } }
    val externalCacheDir by lazy { checkNotNull(App.context.externalCacheDir) { "externalCacheDir == null" } }

    var isSysTtsLogEnabled = true
    var isServerLogEnabled = false

    @OptIn(ExperimentalSerializationApi::class)
    val jsonBuilder by lazy {
        Json {
            allowStructuredMapKeys = true
            ignoreUnknownKeys = true
            prettyPrint = true
            isLenient = true
            explicitNulls = false 
        }
    }

    val isCnLocale: Boolean
        get() = App.context.resources.configuration.locale.language.endsWith("zh")

    val locale: Locale
        get() = App.context.resources.configuration.locale

    val appInfo: AppInfo by lazy {
        val appInfo = AppInfo()
        val context = App.context
        try {
            // 显式使用 context 引用方法，防止编译器迷路
            val info: PackageInfo = context.packageManager.getPackageInfo(
                context.packageName, 
                PackageManager.GET_ACTIVITIES
            )
            appInfo.versionName = info.versionName ?: ""
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                appInfo.versionCode = info.longVersionCode
            } else {
                appInfo.versionCode = info.versionCode.toLong()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        appInfo
    }

    data class AppInfo(
        var versionCode: Long = 0L,
        var versionName: String = "",
    )
}
