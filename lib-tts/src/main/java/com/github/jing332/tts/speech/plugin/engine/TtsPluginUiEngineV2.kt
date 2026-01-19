    fun eval(): Any? {
        var script = plugin.code
        // 🛡️ 针对 V3 (QuickJS) 插件的终极兼容性处理
        if (script.contains("\"use quickjs\"") || script.contains("'use quickjs'")) {
            
            script = script
                // 1. 【核心救命补丁】清洗模板字符串 (反引号)
                // 必须把 `...` 替换为空，否则 Rhino 解析必挂 -> 白屏
                .replace(Regex("`[\\s\\S]*?`"), "\"\"")
                
                // 2. 降级变量
                .replace(Regex("""\b(let|const)\b"""), "var")
                
                // 3. 移除异步关键字
                .replace(Regex("""\b(async|await)\b"""), "")
                
                // 4. 暴力清空 getAudio 体 (防止复杂语法残留)
                .replace(Regex("""getAudio\s*:\s*(function)?\s*\(.*?\)\s*(=>)?\s*\{([\s\S]*?)\}"""), "getAudio: function(){}")
                
                // 5. 箭头函数降级
                .replace(Regex("""\((.*?)\)\s*=>"""), "function($1)")
        }

        return context.evaluateString(scope, script, plugin.pluginId, 1, null)
    }
