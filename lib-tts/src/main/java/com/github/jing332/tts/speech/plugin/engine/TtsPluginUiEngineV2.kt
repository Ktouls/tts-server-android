    fun eval(): Any? {
        var script = plugin.code
        // 🛡️ 针对 V3 (QuickJS) 插件的兼容性处理
        if (script.contains("\"use quickjs\"") || script.contains("'use quickjs'")) {
            logger.info { "Detected V3 plugin, applying strict sanitization for Rhino..." }
            
            script = script
                // 1. 降级变量声明
                .replace(Regex("""\b(let|const)\b"""), "var")
                // 2. 移除 async/await 关键字
                .replace(Regex("""\b(async|await)\b"""), "")
                // 3. 【关键】暴力清空 getAudio 函数体
                // 防止 Rhino 解析 ES6 的箭头函数或 fetch 语法导致崩溃
                // 匹配 getAudio: ... { ... } 的结构
                .replace(Regex("""getAudio\s*:\s*(function)?\s*\(.*?\)\s*(=>)?\s*\{([\s\S]*?)\}"""), "getAudio: function(){ return null; }")
                // 4. 简单的箭头函数降级 (针对非 getAudio 部分)
                .replace(Regex("""\((.*?)\)\s*=>"""), "function($1)")
        }

        // ... 后续代码保持不变 (context.evaluateString ...)
        return context.evaluateString(scope, script, plugin.pluginId, 1, null)
    }
