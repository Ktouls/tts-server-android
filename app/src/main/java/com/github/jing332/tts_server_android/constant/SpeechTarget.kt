package com.github.jing332.tts_server_android.constant

import androidx.annotation.IntDef
import com.github.jing332.tts_server_android.App // 确保导入了 App 类
import com.github.jing332.tts_server_android.R

@IntDef(
    SpeechTarget.ALL,
//    SpeechTarget.ASIDE,
//    SpeechTarget.DIALOGUE,
    SpeechTarget.BGM,
    SpeechTarget.TAG
)
@Retention(AnnotationRetention.SOURCE)
annotation class SpeechTarget {
    companion object {
        const val ALL = 0

        @Deprecated("已有自定TAG")
        const val ASIDE = 1 //旁白

        @Deprecated("已有自定TAG")
        const val DIALOGUE = 2 //对话

        const val BGM = 3 //背景音乐
        const val TAG = 4 // 自定义Tag

        fun toText(@SpeechTarget target: Int): String {
            return when (target) {
                BGM -> {
                    // 修正：显式使用 App.context 调用 getString
                    // 这样可以 100% 消除编译器对 Context 作用域的歧义
                    App.context.getString(R.string.bgm)
                }

                else -> ""
            }
        }
    }
}
