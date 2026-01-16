package com.github.jing332.tts_server_android.constant

import android.content.Context
import androidx.annotation.IntDef
import com.github.jing332.tts_server_android.R
import com.github.jing332.tts_server_android.app

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
                    // 修正：强转为 Context 调用
                    (app as Context).getString(R.string.bgm)
                }
                else -> ""
            }
        }
    }
}
