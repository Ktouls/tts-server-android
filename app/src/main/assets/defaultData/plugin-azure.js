// 💡 说明 (Instructions):
// 1. 请点击保存后在 更多选项按钮(垂直三个点) -> 设置变量 中设置密钥和区域。
// 2. Please set the key and region in "More options" -> "Variables" after clicking save.

/**
 * 🚀 引擎引导 (Engine Directive):
 * - [V3 模式]: 首行保留 "use quickjs"。支持 ES6 (async/await, fetch 等)，执行效率更高。
 * - [V2 模式]: 若删除首行，系统将回退至旧版 Rhino 引擎，以兼容旧版 ES5 脚本。
 */
"use quickjs";

/**
 * ==========================================================
 * 第一部分：配置与全局变量 (Configuration & Globals)
 * ==========================================================
 */
let key = ttsrv.userVars['key'] || 'Default_KEY';
let region = ttsrv.userVars['region'] || 'eastus';

const format = "audio-24khz-48kbitrate-mono-mp3";
const sampleRate = 24000; 
const isNeedDecode = true; 

// 状态缓存
let voices = {};
let currentVoices = new Map();
let skillSpinner, styleSpinner, roleSpinner, seekStyle;

/**
 * ==========================================================
 * 第二部分：PluginJS (核心合成逻辑 - V3/ES6 标准)
 * ==========================================================
 */
let PluginJS = {
    name: "Azure (ES6 Full Edition)",
    id: "com.microsoft.azure.v3",
    author: "TTS Server",
    description: "全面还原业务逻辑并适配 V3 异步链路的 Azure 插件示例",
    version: 4,
    
    // 变量声明：保存后在“设置变量”中由用户填写
    vars: { 
        key: { label: "密钥 Key" },
        region: { label: "区域 Region", hint: "为空时使用默认'eastus'" },
    },

    onLoad: () => {
        checkKeyRegion();
    },

    /**
     * 获取音频：异步函数，完美对接重构后的动态超时机制
     */
    getAudio: async function (text, locale, voice, rate, volume, pitch) {
        // 1. 参数计算与预处理
        const adjRate = (rate * 2) - 100;
        const adjPitch = pitch - 50;

        let styleDegree = ttsrv.tts.data['styleDegree'];
        if (!styleDegree || Number(styleDegree) < 0.01) styleDegree = '1.0';

        const style = ttsrv.tts.data['style'] || 'general';
        const role = ttsrv.tts.data['role'] || 'default';

        // 2. 业务逻辑：文本转义与 SSML 模板构建
        let textSsml = '';
        const langSkill = ttsrv.tts.data['languageSkill'];
        if (!langSkill) {
            textSsml = escapeXml(text);
        } else {
            textSsml = `<lang xml:lang="${langSkill}">${escapeXml(text)}</lang>`;
        }

        // 使用 ES6 模板字符串，结构严谨且易于维护
        const ssml = `
        <speak xmlns="http://www.w3.org/2001/10/synthesis" xmlns:mstts="http://www.w3.org/2001/mstts" xmlns:emo="http://www.w3.org/2009/10/emotionml" version="1.0" xml:lang="zh-CN">
            <voice name="${voice}">
                <mstts:express-as style="${style}" styledegree="${styleDegree}" role="${role}">
                    <prosody rate="${adjRate}%" pitch="${adjPitch}%" volume="${volume}">${textSsml}</prosody>
                </mstts:express-as>
            </voice >
         </speak >`;

        // 3. 异步请求：利用 V3 引擎的 fetch 链路
        return await getAudioInternal(ssml, format);
    },
};

/**
 * ==========================================================
 * 第三部分：辅助工具函数 (Helper Functions)
 * ==========================================================
 */
function escapeXml(s) {
    return s.replace(/'/g, '&apos;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/&/g, '&amp;').replace(/\//g, '').replace(/\\/g, '');
}

function checkKeyRegion() {
    key = (key + '').trim();
    region = (region + '').trim();
    if (key === '' || region === '') {
        throw "请设置变量: 密钥Key与区域Region。 Please set the key and region.";
    }
}

async function getAudioInternal(ssml, format) {
    const ttsUrl = `https://${region}.tts.speech.microsoft.com/cognitiveservices/v1`;
    const headers = {
        'Ocp-Apim-Subscription-Key': key,
        "X-Microsoft-OutputFormat": format,
        "Content-Type": "application/ssml+xml",
    };

    // 🛡️ 调用重构后的 fetch，实现动态超时与 Base64 自动清洗
    const resp = await fetch(ttsUrl, {
        method: 'POST',
        headers: headers,
        body: ssml
    });

    if (!resp.ok) {
        if (resp.status === 401) throw "401 Unauthorized 未授权，请检查密钥与区域是否正确。";
        if (resp.status === 403) throw "403 Forbidden 被禁止，您的Azure账户可能已被禁用。";
        throw `音频获取失败: HTTP-${resp.status}`;
    }

    return await resp.body(); 
}

/**
 * ==========================================================
 * 第四部分：EditorJS (UI 与 联动逻辑 - Rhino 兼容)
 * ==========================================================
 */
let EditorJS = {
    getAudioSampleRate: (locale, voice) => sampleRate,
    isNeedDecode: (locale, voice) => isNeedDecode,

    getLocales: function() {
        const locales = [];
        voices.forEach((v) => {
            const loc = v["Locale"];
            if (!locales.includes(loc)) locales.push(loc);
        });
        return locales;
    },

    getVoices: function(locale) {
        currentVoices = new Map();
        voices.forEach((v) => {
            if (v['Locale'] === locale) currentVoices.set(v['ShortName'], v);
        });

        const mm = {};
        for (let [k, v] of currentVoices.entries()) {
            mm[k] = new java.lang.String(v['LocalName'] + ' (' + k + ')');
        }
        return mm;
    },

    onLoadData: function() {
        let jsonStr = '';
        if (ttsrv.fileExist('voices.json')) {
            jsonStr = ttsrv.readTxtFile('voices.json');
        } else {
            checkKeyRegion();
            const url = `https://${region}.tts.speech.microsoft.com/cognitiveservices/voices/list`;
            jsonStr = ttsrv.httpGetString(url, { "Ocp-Apim-Subscription-Key": key });
            ttsrv.writeTxtFile('voices.json', jsonStr);
        }
        voices = JSON.parse(jsonStr);
    },

    onLoadUI: function(ctx, linerLayout) {
        const layout = new org.android.widget.LinearLayout(ctx);
        layout.setOrientation(0); 
        const params = new org.android.widget.LinearLayout.LayoutParams(0, -2, 1);

        skillSpinner = JSpinner(ctx, "语言技能 (language skill)");
        linerLayout.addView(skillSpinner);
        ttsrv.setMargins(skillSpinner, 2, 4, 0, 0);
        skillSpinner.setOnItemSelected((_, __, item) => {
            ttsrv.tts.data['languageSkill'] = item.value + '';
        });

        styleSpinner = JSpinner(ctx, "风格 (style)");
        styleSpinner.layoutParams = params;
        layout.addView(styleSpinner);
        ttsrv.setMargins(styleSpinner, 2, 4, 0, 0);
        styleSpinner.setOnItemSelected((_, pos, item) => {
            ttsrv.tts.data['style'] = item.value;
            seekStyle.visibility = (pos === 0 || !item.value) ? 8 : 0; 
        });

        roleSpinner = JSpinner(ctx, "角色 (role)");
        roleSpinner.layoutParams = params;
        layout.addView(roleSpinner);
        ttsrv.setMargins(roleSpinner, 0, 4, 2, 0);
        roleSpinner.setOnItemSelected((_, __, item) => {
            ttsrv.tts.data['role'] = item.value;
        });
        linerLayout.addView(layout);

        seekStyle = JSeekBar(ctx, "风格强度 (Style degree)：");
        linerLayout.addView(seekStyle);
        ttsrv.setMargins(seekStyle, 0, 4, 0, -4);
        seekStyle.setFloatType(2); 
        seekStyle.max = 200;

        let styleDegree = Number(ttsrv.tts.data['styleDegree']);
        if (!styleDegree || isNaN(styleDegree)) styleDegree = 1.0;
        seekStyle.value = new java.lang.Float(styleDegree);

        seekStyle.setOnChangeListener({
            onStopTrackingTouch: (seek) => {
                ttsrv.tts.data['styleDegree'] = Number(seek.value).toFixed(2);
            },
        });
    },

    onVoiceChanged: function(locale, voiceCode) {
        const vic = currentVoices.get(voiceCode);
        if (!vic) return;

        // 联动刷新语言技能
        const locale2Items = [Item("默认 (default)", "")];
        let locale2Pos = 0;
        (vic['SecondaryLocaleList'] || []).forEach((v, i) => {
            const loc = java.util.Locale.forLanguageTag(v);
            locale2Items.push(Item(loc.getDisplayName(loc), v));
            if (v === ttsrv.tts.data['languageSkill'] + '') locale2Pos = i + 1;
        });
        skillSpinner.items = locale2Items;
        skillSpinner.selectedPosition = locale2Pos;
        skillSpinner.visibility = (locale2Items.length === 1) ? 8 : 0;

        // 联动刷新风格列表
        const styleItems = [Item("默认 (general)", "")];
        let stylePos = 0;
        const styles = vic['StyleList'];
        if (styles) {
            styles.forEach((v, i) => {
                styleItems.push(Item(getString(v), v));
                if (v === ttsrv.tts.data['style'] + '') stylePos = i + 1;
            });
        } else {
            seekStyle.visibility = 8;
        }
        styleSpinner.items = styleItems;
        styleSpinner.selectedPosition = stylePos;

        // 联动刷新角色列表
        const roleItems = [Item("默认 (default)", "")];
        let rolePos = 0;
        const roles = vic['RolePlayList'];
        if (roles) {
            roles.forEach((v, i) => {
                roleItems.push(Item(getString(v), v));
                if (v === ttsrv.tts.data['role'] + '') rolePos = i + 1;
            });
        }
        roleSpinner.items = roleItems;
        roleSpinner.selectedPosition = rolePos;
    }
};

/**
 * ==========================================================
 * 第五部分：本地化字典 (1:1 完整保留)
 * ==========================================================
 */
const cnLocales = {
    "narrator": "旁白", "girl": "女孩", "boy": "男孩",
    "youngadultfemale": "年轻女性", "youngadultmale": "年轻男性",
    "olderadultfemale": "年长女性", "olderadultmale": "年长男性",
    "seniorfemale": "年老女性", "seniormale": "年老男性",
    "advertisement_upbeat": "广告推销", "affectionate": "亲切",
    "angry": "生气", "assistant": "数字助理", "calm": "平静",
    "chat": "闲聊", "cheerful": "愉快", "customerservice": "客户服务",
    "depressed": "沮丧", "disgruntled": "不满", "documentary-narration": "纪录片",
    "embarrassed": "尴尬", "empathetic": "同情", "envious": "嫉妒",
    "excited": "兴奋", "fearful": "恐惧", "friendly": "友好",
    "gentle": "温柔", "hopeful": "希望", "lyrical": "抒情",
    "narration-professional": "专业", "narration-relaxed": "轻松",
    "newscast": "新闻", "newscast-casual": "新闻-休闲",
    "newscast-formal": "新闻-正式", "poetry-reading": "诗歌朗诵",
    "sad": "悲伤", "serious": "严肃", "shouting": "喊叫",
    "sports_commentary": "体育", "sports_commentary_excited": "体育-兴奋",
    "whispering": "耳语", "terrified": "恐惧", "unfriendly": "不友好",
};

const isZhLocale = java.util.Locale.getDefault().getLanguage() === 'zh';
function getString(key) {
    if (isZhLocale) return cnLocales[key.toLowerCase()] || key;
    return key;
}
