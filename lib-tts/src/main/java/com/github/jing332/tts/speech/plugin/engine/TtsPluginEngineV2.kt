    // ... 前面的代码不变 ...

    fun eval() {
        var scriptCode = plugin.code
        
        if (scriptCode.contains("\"use quickjs\"") || scriptCode.contains("'use quickjs'")) {
            scriptCode = scriptCode
                .replace(Regex("`[\\s\\S]*?`"), "\"\"")
                .replace(Regex("""\b(let|const)\b"""), "var")
                .replace(Regex("""\b(async|await)\b"""), "")
                // 🛠️ 修复点：正确处理箭头函数，加上 { return ... }
                // 匹配: item => item.locale  -->  function(item) { return item.locale; }
                .replace(Regex("""(\w+)\s*=>\s*([^,;)}\n]+)"""), "function($1){ return $2; }")
                // 处理带括号的 (a,b) => ... (简单的处理)
                .replace(Regex("""\((.*?)\)\s*=>"""), "function($1)")
                // 屏蔽 getAudio
                .replace(Regex("""getAudio\s*:\s*function\s*\(.*?\)\s*\{"""), "getAudio: function(){ return null; //")
        }

        try {
            execute(scriptCode)
            // ... 后续代码不变 ...
