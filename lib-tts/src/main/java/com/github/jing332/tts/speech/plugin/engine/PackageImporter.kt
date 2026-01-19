package com.github.jing332.tts.speech.plugin.engine

/**
 * 严谨版包导入器：扩展 Android 核心环境支持
 * 解决因缺少 android.text, android.graphics 等引用导致的 UI 渲染中断问题
 */
object PackageImporter {
    val default by lazy {
        line(
            listOf(
                "com.github.jing332.tts.speech.plugin.engine.type.ws",
                "com.github.jing332.tts.speech.plugin.engine.type.ui",
                "android.view",
                "android.widget",
                "android.content",   // 🛡️ 注入：支持 Context 及 Intent 相关操作
                "android.graphics",  // 🛡️ 注入：支持颜色、画布及布局参数
                "android.text",      // 🛡️ 注入：支持 InputType, Spannable 等文本增强
                "android.util",      // 🛡️ 注入：支持 Log, DisplayMetrics
                "android.os"         // 🛡️ 注入：支持 Bundle, Handler 等系统基础
            )
        )
    }

    private fun line(packages: List<String>): String {
        val s = packages.joinToString(separator = ";") { "importPackage($it)" }
        return "$s;"
    }
}
