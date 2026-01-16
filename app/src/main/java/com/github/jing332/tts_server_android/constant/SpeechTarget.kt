package com.github.jing332.tts_server_android.constant

import androidx.annotation.IntDef
import com.github.jing332.tts_server_android.R
import com.github.jing332.tts_server_android.app // 确保导入全局 app

@IntDef(SpeechTarget.ALL, SpeechTarget.BGM, SpeechTarget.TAG)
@Retention(AnnotationRetention.SOURCE)
annotation class SpeechTarget {
    companion object {
        const val ALL = 0
        const val BGM = 3
        const val TAG = 4

        fun toText(@SpeechTarget target: Int): String {
            return when (target) {
                BGM -> {
                    // 修正：改用全局 app.getString，确保 100% 识别 Context 方法
                    app.getString(R.string.bgm)
                }
                else -> ""
            }
        }
    }
}
