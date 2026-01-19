/**
 * 💡 引擎引导 (Engine Directive):
 * 1. [V3/QuickJS]: 默认启用。支持 ES6 (箭头函数、解构赋值、模板字符串)、async/await、Promise 及 fetch。
 * 2. [V2/Rhino]: 若删除首行，系统将回退至旧版引擎。
 */
"use quickjs";

// --- 全局变量与配置 (Global Configuration) ---
const CONFIG = {
    FORMAT: "audio-24khz-48kbitrate-mono-mp3",
    SAMPLE_RATE: 24000,
    NEED_DECODE: true
};

// 使用 ES6 箭头函数处理 XML 转义
const escapeXml = (str) => str.replace(/[<>&"']/g, (c) => {
    switch (c) {
        case '<': return '&lt;';
        case '>': return '&gt;';
        case '&': return '&amp;';
        case '"': return '&quot;';
        case "'": return '&apos;';
        default: return c;
    }
});

/**
 * 核心逻辑对象 (Core Plugin Logic)
 */
let PluginJS = {
    name: "Azure (Modern ES6)",
    id: "com.microsoft.azure.v3",
    author: "TTS Server",
    version: 4,

    // 声明变量声明 (Variable Declarations)
    vars: {
        key: { label: "密钥 (Subscription Key)", hint: "在 Azure 门户获取" },
        region: { label: "区域 (Region)", hint: "默认: eastus" }
    },

    // 插件加载初始化
    onLoad: () => {
        const key = ttsrv.userVars['key']?.trim();
        if (!key) throw "请先在 '设置变量' 中配置 API 密钥 (Key is required)";
    },

    /**
     * 音频合成入口
     * 使用 async 异步函数提升执行效率
     */
    getAudio: async (text, locale, voice, rate, volume, pitch) => {
        // 1. 参数预处理 (Parameters Preprocessing)
        const adjRate = (rate * 2) - 100;
        const adjPitch = pitch - 50;
        const { style = 'general', role = 'default', styleDegree = '1.0', languageSkill = "" } = ttsrv.tts.data;

        // 2. 构建 SSML (Template Literals)
        const textContent = languageSkill 
            ? `<lang xml:lang="${languageSkill}">${escapeXml(text)}</lang>`
            : escapeXml(text);

        const ssml = `
            <speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xmlns:mstts="http://www.w3.org/2001/mstts" xml:lang="${locale}">
                <voice name="${voice}">
                    <mstts:express-as style="${style}" styledegree="${styleDegree}" role="${role}">
                        <prosody rate="${adjRate}%" pitch="${adjPitch}%" volume="${volume}">${textContent}</prosody>
                    </mstts:express-as>
                </voice>
            </speak>`;

        // 3. 执行异步网络请求 (Async Fetch)
        const region = ttsrv.userVars['region']?.trim() || 'eastus';
        const url = `https://${region}.tts.speech.microsoft.com/cognitiveservices/v1`;

        try {
            const response = await fetch(url, {
                method: 'POST',
                headers: {
                    'Ocp-Apim-Subscription-Key': ttsrv.userVars['key'],
                    'X-Microsoft-OutputFormat': CONFIG.FORMAT,
                    'Content-Type': 'application/ssml+xml'
                },
                body: ssml
            });

            if (!response.ok) {
                const errorText = await response.text();
                throw `HTTP ${response.status}: ${errorText}`;
            }

            // 返回 Base64 或 InputStream (V3 引擎会自动处理适配)
            return await response.text(); 
        } catch (e) {
            throw `网络请求失败 (Fetch Error): ${e.message || e}`;
        }
    }
};

/**
 * 编辑器扩展对象 (UI & Metadata logic)
 * 运行在 Rhino 环境中，以支持 Android 原生 View 操作
 */
let EditorJS = {
    getAudioSampleRate: (locale, voice) => CONFIG.SAMPLE_RATE,
    isNeedDecode: (locale, voice) => CONFIG.NEED_DECODE,

    // 获取支持的语言 (ES6 Object.keys / filter / map)
    getLocales: () => {
        const locales = new Set();
        (EditorJS.cachedVoices || []).forEach(v => locales.add(v.Locale));
        return Array.from(locales);
    },

    // 获取音色列表
    getVoices: (locale) => {
        const voiceMap = {};
        (EditorJS.cachedVoices || [])
            .filter(v => v.Locale === locale)
            .forEach(v => {
                voiceMap[v.ShortName] = `${v.LocalName} (${v.ShortName})`;
            });
        return voiceMap;
    },

    // 异步加载数据 (Data Bootstrapping)
    onLoadData: () => {
        if (ttsrv.fileExist('voices_v3.json')) {
            EditorJS.cachedVoices = JSON.parse(ttsrv.readTxtFile('voices_v3.json'));
        } else {
            const key = ttsrv.userVars['key']?.trim();
            const region = ttsrv.userVars['region']?.trim() || 'eastus';
            if (!key) return;

            const url = `https://${region}.tts.speech.microsoft.com/cognitiveservices/voices/list`;
            const jsonStr = ttsrv.httpGetString(url, { 'Ocp-Apim-Subscription-Key': key });
            
            EditorJS.cachedVoices = JSON.parse(jsonStr);
            ttsrv.writeTxtFile('voices_v3.json', jsonStr);
        }
    },

    // 渲染原生 UI (UI Construction)
    onLoadUI: (ctx, container) => {
        const layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        
        // 使用注入的 V3 组件 (JSpinner 等)
        const styleSpinner = JSpinner(ctx, "风格 (Style)");
        const roleSpinner = JSpinner(ctx, "角色 (Role)");
        
        container.addView(styleSpinner);
        container.addView(roleSpinner);

        // 设置监听器 (Arrow Functions)
        styleSpinner.setOnItemSelected((_, __, item) => {
            ttsrv.tts.data['style'] = item.value;
        });
        
        roleSpinner.setOnItemSelected((_, __, item) => {
            ttsrv.tts.data['role'] = item.value;
        });
    }
};
