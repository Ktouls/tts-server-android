"use quickjs";

/**
 * 📘 V3 插件开发标准教学模板 (Azure)
 * -----------------------------------------------------------------------
 * 核心架构说明 (必读):
 * 1. [合成层] PluginJS:
 * - 运行引擎: QuickJS (新一代引擎)
 * - 语法权限: ✅ ES6+ (const, let, 模板字符串) | ❌ 禁止 async/await
 * - 网络请求: 使用同步 fetch()
 *
 * 2. [UI 层] EditorJS:
 * - 运行引擎: Rhino (老旧引擎, 负责渲染界面)
 * - 语法权限: ✅ 严格 ES5 (var, function)
 * - 禁忌事项: 🚫 严禁使用箭头函数 (=>)，否则界面会白屏!
 * -----------------------------------------------------------------------
 */

// ============================================================
// 1. 全局配置与工具函数 (Global Scope)
// ============================================================

// ✅ [ES6] 使用 const 定义常量
const CONFIG = {
    defaultUrl: "https://eastus.tts.speech.microsoft.com/cognitiveservices/v1",
    defaultFormat: "audio-24khz-48kbitrate-mono-mp3",
    sampleRate: 24000
};

// ✅ [ES6] 风格本地化映射表
const cnLocales = {
    "narrator": "旁白", "girl": "女孩", "boy": "男孩",
    "youngadultfemale": "年轻女性", "youngadultmale": "年轻男性",
    "olderadultfemale": "年长女性", "olderadultmale": "年长男性",
    "seniorfemale": "年老女性", "seniormale": "年老男性",
    "advertisement_upbeat": "广告推销", "affectionate": "亲切",
    "angry": "生气", "assistant": "数字助理", "calm": "平静",
    "chat": "闲聊", "cheerful": "愉快", "customerservice": "客户服务",
    "depressed": "沮丧", "disgruntled": "不满", "documentary-narration": "纪录片",
    "embarrassed": "尴尬", "empathetic": "同情", "excited": "兴奋",
    "fearful": "恐惧", "friendly": "友好", "gentle": "温柔",
    "hopeful": "希望", "lyrical": "抒情", "narration-professional": "专业",
    "narration-relaxed": "轻松", "newscast": "新闻", "newscast-casual": "新闻-休闲",
    "newscast-formal": "新闻-正式", "poetry-reading": "诗歌朗诵",
    "sad": "悲伤", "serious": "严肃", "shouting": "喊叫",
    "whispering": "耳语", "terrified": "惊恐", "unfriendly": "冷漠"
};

// ✅ [ES6] XML 转义工具函数 (箭头函数在 QuickJS 中是安全的)
const escapeXml = (s) => {
    if (!s) return "";
    return s.toString()
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&apos;');
};

// ⚠️ [ES5] 这个函数会被 UI 层调用，为了安全，建议用 function 关键字
function getLocalString(key) {
    // 判断是否为中文环境
    var isZh = java.util.Locale.getDefault().getLanguage() == 'zh';
    if (isZh) {
        return cnLocales[key.toLowerCase()] || key;
    }
    return key;
}

// ============================================================
// 2. 合成逻辑层 (PluginJS - QuickJS Engine)
// ============================================================
let PluginJS = {
    name: "Azure TTS",
    id: "com.microsoft.azure.v3.tutorial",
    author: "TTS Server",
    version: 1,
    description: "基于 V3 同步架构重构的标准示例",
    // 变量声明：用户在设置界面填写的参数
    vars: {
        key: { label: "密钥 (Key)", hint: "Azure Subscription Key" },
        region: { label: "区域 (Region)", hint: "默认 eastus" }
    },

    // ⚡️ 核心合成函数：必须同步执行 (无 async)
    getAudio: function(text, locale, voice, speed, volume, pitch) {
        // 1. 获取用户配置
        const key = ttsrv.userVars['key'];
        const region = ttsrv.userVars['region'] || 'eastus';

        if (!key) throw "❌ 请先在插件设置中配置密钥 (Key)";

        console.log(`[V3] 开始合成: ${voice} | ${text.substring(0, 10)}...`);

        // 2. 处理参数转换 (Azure 的参数范围不同)
        const rateParam = (speed * 2) - 100; // 转换语速
        const pitchParam = pitch - 50;       // 转换音高

        // 3. 获取 UI 传递的高级参数 (风格、角色等)
        // 使用 ES6 解构赋值和默认值
        let { styleDegree = '1.0', style = 'general', role = 'default', languageSkill } = ttsrv.tts.data;

        // 4. 构建 SSML (使用模板字符串，清晰易读)
        let textPart = languageSkill 
            ? `<lang xml:lang="${languageSkill}">${escapeXml(text)}</lang>` 
            : escapeXml(text);

        const ssml = `
        <speak xmlns="http://www.w3.org/2001/10/synthesis" xmlns:mstts="http://www.w3.org/2001/mstts" xmlns:emo="http://www.w3.org/2009/10/emotionml" version="1.0" xml:lang="${locale}">
            <voice name="${voice}">
                <mstts:express-as style="${style}" styledegree="${styleDegree}" role="${role}">
                    <prosody rate="${rateParam}%" pitch="${pitchParam}%" volume="${volume}">${textPart}</prosody>
                </mstts:express-as>
            </voice>
        </speak>`.trim();

        // 5. 发起同步网络请求
        const url = `https://${region}.tts.speech.microsoft.com/cognitiveservices/v1`;
        
        // ⚡️ 使用同步 fetch
        const resp = fetch(url, {
            method: "POST",
            headers: {
                "Ocp-Apim-Subscription-Key": key,
                "Content-Type": "application/ssml+xml",
                "X-Microsoft-OutputFormat": CONFIG.defaultFormat,
                "User-Agent": "TTS_Server_Android/V3"
            },
            body: ssml // SSML 必须作为字符串发送
        });

        // 6. 处理响应
        // 注意：我们改造后的 fetch 默认返回 text/json。
        // 对于 Azure 这种返回二进制流的接口，如果 V3 引擎底层支持不足，
        // 这里返回的字符串实际上是 ISO-8859-1 编码的二进制数据。
        // *但在本教学模板中，我们展示标准的处理逻辑*
        
        // 如果是 JSON 错误信息
        if (resp.status && resp.status !== 200) {
             throw `HTTP Error: ${resp.status}`;
        }
        
        // 如果引擎返回的是封装对象，获取 body 字符串
        let resultData = "";
        if (typeof resp.text === 'function') {
            resultData = resp.text(); 
        } else if (resp.body && typeof resp.body.string === 'function') {
            resultData = resp.body.string();
        } else {
            resultData = resp; // 假设直接返回了字符串
        }

        // ⚠️ 重要：Azure 返回的是 Raw MP3 Bytes。
        // 如果我们的 V3 引擎 fetch 实现将 Bytes 强转为了 String，
        // 这里需要将其“透传”回去，或者底层应该直接返回 Base64。
        // *此处假设底层已经做了适配，直接返回数据*
        return resultData;
    }
};

// ============================================================
// 3. UI 交互层 (EditorJS - Rhino Engine)
// ⚠️ 警告: 此处代码运行在旧版引擎，必须使用严格 ES5 写法!
// ============================================================

// 全局变量 (UI 组件引用)
var skillSpinner, styleSpinner, roleSpinner, seekStyle;
var voicesCache = {};     // 缓存所有声音数据
var currentVoices = {};   // 当前语言筛选后的数据

var EditorJS = {
    getAudioSampleRate: function() { return CONFIG.sampleRate; },
    isNeedDecode: function() { return true; },

    // 1. 加载数据 (支持缓存)
    onLoadData: function() {
        var jsonStr = "";
        // 检查本地缓存
        if (ttsrv.fileExist('azure_voices.json')) {
            jsonStr = ttsrv.readTxtFile('azure_voices.json');
        } else {
            // 获取用户配置的 Key/Region
            var key = ttsrv.userVars['key'];
            var region = ttsrv.userVars['region'] || 'eastus';
            if (!key) return; // 没 Key 就不加载

            var url = "https://" + region + ".tts.speech.microsoft.com/cognitiveservices/voices/list";
            var header = { "Ocp-Apim-Subscription-Key": key };
            
            // ⚠️ 注意: 这里使用 ttsrv.httpGetString (这是 Rhino 环境特有的 API)
            jsonStr = ttsrv.httpGetString(url, header);
            ttsrv.writeTxtFile('azure_voices.json', jsonStr);
        }
        
        if (jsonStr) {
            voicesCache = JSON.parse(jsonStr);
        }
    },

    // 2. 获取语言列表 (ES5 map/filter)
    getLocales: function() {
        var locs = [];
        if (Array.isArray(voicesCache)) {
            voicesCache.forEach(function(v) {
                if (locs.indexOf(v.Locale) === -1) {
                    locs.push(v.Locale);
                }
            });
        }
        return locs;
    },

    // 3. 获取声音列表
    getVoices: function(locale) {
        currentVoices = {}; // 重置当前缓存
        var res = {};
        
        if (Array.isArray(voicesCache)) {
            voicesCache.forEach(function(v) {
                if (v.Locale === locale) {
                    currentVoices[v.ShortName] = v; // 存入缓存供 UI 联动使用
                    res[v.ShortName] = v.LocalName + " (" + v.ShortName + ")";
                }
            });
        }
        return res;
    },

    // 4. 构建高级 UI (下拉框、滑动条)
    onLoadUI: function(ctx, linearLayout) {
        try {
            // 创建水平布局容器
            var layout = new LinearLayout(ctx);
            layout.orientation = 0; // HORIZONTAL
            var params = new LinearLayout.LayoutParams(0, -2, 1.0); // Weight=1

            // --- 语言技能 Spinner ---
            skillSpinner = JSpinner(ctx, "语言技能 (Language Skill)");
            linearLayout.addView(skillSpinner);
            ttsrv.setMargins(skillSpinner, 0, 10, 0, 0);
            
            skillSpinner.setOnItemSelected(function(spinner, pos, item) {
                ttsrv.tts.data['languageSkill'] = item.value;
            });

            // --- 风格 Spinner ---
            styleSpinner = JSpinner(ctx, "风格 (Style)");
            styleSpinner.layoutParams = params;
            layout.addView(styleSpinner);
            
            styleSpinner.setOnItemSelected(function(spinner, pos, item) {
                ttsrv.tts.data['style'] = item.value;
                // 只有选择了风格，才显示强度滑块
                if (seekStyle) {
                    seekStyle.visibility = (pos === 0 || !item.value) ? 8 : 0; // GONE / VISIBLE
                }
            });

            // --- 角色 Spinner ---
            roleSpinner = JSpinner(ctx, "角色 (Role)");
            roleSpinner.layoutParams = params;
            layout.addView(roleSpinner);
            
            roleSpinner.setOnItemSelected(function(spinner, pos, item) {
                ttsrv.tts.data['role'] = item.value;
            });

            // 添加水平布局到主界面
            linearLayout.addView(layout);

            // --- 风格强度 SeekBar ---
            seekStyle = JSeekBar(ctx, "风格强度 (Style Degree)");
            linearLayout.addView(seekStyle);
            seekStyle.setFloatType(2); // 2位小数
            seekStyle.max = 200;       // 0.01 - 2.00
            
            // 恢复上次的值
            var savedDegree = ttsrv.tts.data['styleDegree'];
            seekStyle.value = new java.lang.Float(savedDegree || 1.0);

            seekStyle.setOnChangeListener({
                onStopTrackingTouch: function(seek) {
                    ttsrv.tts.data['styleDegree'] = Number(seek.value).toFixed(2);
                }
            });

        } catch (e) {
            console.error("UI加载错误: " + e);
        }
    },

    // 5. 声音切换联动 (核心逻辑)
    onVoiceChanged: function(locale, voiceCode) {
        try {
            var vic = currentVoices[voiceCode];
            if (!vic) return;

            // 辅助函数：生成 Spinner 的 Item 列表
            var makeItems = function(list, defaultName) {
                var items = [Item(defaultName, "")];
                if (list) {
                    list.forEach(function(val) {
                        items.push(Item(getLocalString(val), val));
                    });
                }
                return items;
            };

            // 更新 语言技能
            var skillList = vic['SecondaryLocaleList']; // Azure返回的字段
            var skillItems = [];
            skillItems.push(Item("默认 (Default)", ""));
            if (skillList) {
                skillList.forEach(function(code) {
                    // 使用 Java Locale 获取语言名称
                    var loc = java.util.Locale.forLanguageTag(code);
                    skillItems.push(Item(loc.getDisplayName(loc), code));
                });
            }
            if (skillSpinner) {
                skillSpinner.items = skillItems;
                skillSpinner.visibility = (skillItems.length <= 1) ? 8 : 0;
            }

            // 更新 风格
            if (styleSpinner) {
                styleSpinner.items = makeItems(vic['StyleList'], "默认 (General)");
                // 如果没有风格，隐藏强度条
                if (!vic['StyleList'] || vic['StyleList'].length === 0) {
                    if (seekStyle) seekStyle.visibility = 8;
                }
            }

            // 更新 角色
            if (roleSpinner) {
                roleSpinner.items = makeItems(vic['RolePlayList'], "默认 (Default)");
            }

        } catch (e) {
            console.error("联动更新错误: " + e);
        }
    }
};
/*
 * =========================================================================
 * 🤖 AI 辅助开发指南 (复制以下内容发送给 AI 即可自动检查代码)
 * =========================================================================
 *
 * # Role
 * 你是一位 Android "TTS Server" 应用的插件代码审查专家。你的目标是确保插件代码符合该应用特殊的 **V3 同步混合架构**，并修复所有可能导致崩溃或功能失效的错误。
 *
 * # 核心校验规则 (Critical Rules)
 * 请逐行检查用户提供的代码，必须满足以下所有约束：
 *
 * ## 1. 全局约束
 * - **文件头**：第一行必须是 `"use quickjs";`。
 *
 * ## 2. 合成层 (`PluginJS` 对象)
 * - **运行环境**：QuickJS 引擎。
 * - **执行模式**：**强制同步 (Synchronous)**。
 * - ❌ **致命错误**：
 * - 严禁使用 `async` 关键字修饰 `getAudio`。
 * - 严禁使用 `await`。
 * - 严禁使用 `Promise` 或回调地狱。
 * - ✅ **正确写法**：
 * - 使用宿主提供的**同步 fetch**：
 * let resp = fetch(url, options); 
 * let json = resp.json(); // 直接获取结果
 * return json.data;
 *
 * ## 3. UI 层 (`EditorJS` 对象)
 * - **运行环境**：Rhino 引擎 (旧版 Java JS 引擎)。
 * - ❌ **致命错误**：
 * - **严禁使用箭头函数 (`=>`)**。一旦出现，应用加载插件时会直接白屏崩溃。
 * - 避免使用 ES6 的 `let`/`const` (建议在 EditorJS 内部使用 `var`)。
 * - ✅ **正确写法**：
 * - 所有的回调必须写成 `function(arg) { ... }`。
 *
 * # 任务流程
 * 1. **分析**：检查代码是否违反上述规则（尤其是 UI 层的箭头函数和合成层的 async）。
 * 2. **判定**：
 * - 如果代码完全符合规范，回复：“✅ **代码通过，符合 V3 同步架构规范。**”
 * - 如果有错误，回复：“❌ **发现潜在问题，已自动修复。**” 并列出修改点。
 * 3. **输出**：提供修复后的完整代码块。
 */
