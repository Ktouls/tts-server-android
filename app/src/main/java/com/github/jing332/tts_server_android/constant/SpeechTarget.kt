package com.github.jing332.tts_server_android.constant

import androidx.annotation.IntDef
import com.github.jing332.tts_server_android.App
import com.github.jing332.tts_server_android.R

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
                    // 修正：显式调用 App.context.getString
                    App.context.getString(R.string.bgm)
                }
                else -> ""
            }
        }
    }
}
