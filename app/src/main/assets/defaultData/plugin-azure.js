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

const CONFIG = {
    format: "audio-24khz-48kbitrate-mono-mp3",
    sampleRate: 24000, 
    isNeedDecode: true 
};

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

        const ssml = `
        <speak xmlns="http://www.w3.org/2001/10/synthesis" xmlns:mstts="http://www.w3.org/2001/mstts" version="1.0" xml:lang="zh-CN">
            <voice name="${voice}">
                <mstts:express-as style="${style}" styledegree="${styleDegree}" role="${role}">
                    <prosody rate="${adjRate}%" pitch="${adjPitch}%" volume="${volume}">${textSsml}</prosody>
                </mstts:express-as>
            </voice >
         </speak >`;

        // 3. 异步请求：利用 V3 引擎的 fetch 链路
        return await getAudioInternal(ssml, CONFIG.format);
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

    // 调用重构后的 fetch，对接动态超时与 Base64 自动清洗
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
 * 第四部分：EditorJS (UI 与 联动逻辑 - Rhino 深度加固)
 * ==========================================================
 */
let EditorJS = {
    cachedVoices: [],
    currentVoices: {}, 
    skillSpinner: null, styleSpinner: null, roleSpinner: null, seekStyle: null,
    VIEW_VISIBLE: 0,
    VIEW_GONE: 8,

    getAudioSampleRate: (locale, voice) => CONFIG.sampleRate,
    isNeedDecode: (locale, voice) => CONFIG.isNeedDecode,

    getLocales: function() {
        var locales = [];
        var list = this.cachedVoices || [];
        for (var i = 0; i < list.length; i++) {
            var loc = list[i]["Locale"];
            if (locales.indexOf(loc) === -1) locales.push(loc);
        }
        return locales;
    },

    getVoices: function(locale) {
        this.currentVoices = {}; 
        var mm = {};
        var list = this.cachedVoices || [];
        for (var i = 0; i < list.length; i++) {
            var v = list[i];
            if (v['Locale'] === locale) {
                this.currentVoices[v['ShortName']] = v;
                mm[v['ShortName']] = new java.lang.String(v['LocalName'] + ' (' + v['ShortName'] + ')');
            }
        }
        return mm;
    },

    onLoadData: function() {
        var cachePath = 'voices_v3.json';
        var jsonStr = '';
        if (ttsrv.fileExist(cachePath)) {
            jsonStr = ttsrv.readTxtFile(cachePath);
        } else {
            checkKeyRegion();
            var url = "https://" + region + ".tts.speech.microsoft.com/cognitiveservices/voices/list";
            jsonStr = ttsrv.httpGetString(url, { "Ocp-Apim-Subscription-Key": key });
            ttsrv.writeTxtFile(cachePath, jsonStr);
        }
        this.cachedVoices = JSON.parse(jsonStr);
    },

    onLoadUI: function(ctx, linerLayout) {
        var LinearLayout = org.android.widget.LinearLayout;
        var layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.HORIZONTAL); 
        var params = new LinearLayout.LayoutParams(0, -2, 1);

        this.skillSpinner = JSpinner(ctx, "语言技能 (language skill)");
        linerLayout.addView(this.skillSpinner);
        ttsrv.setMargins(this.skillSpinner, 2, 4, 0, 0);
        this.skillSpinner.setOnItemSelected(function(_, __, item) {
            ttsrv.tts.data['languageSkill'] = String(item.value || '');
        });

        this.styleSpinner = JSpinner(ctx, "风格 (style)");
        this.styleSpinner.layoutParams = params;
        layout.addView(this.styleSpinner);
        ttsrv.setMargins(this.styleSpinner, 2, 4, 0, 0);
        
        var self = this; 
        this.styleSpinner.setOnItemSelected(function(_, pos, item) {
            ttsrv.tts.data['style'] = String(item.value || '');
            self.seekStyle.setVisibility((pos === 0 || !item.value) ? self.VIEW_GONE : self.VIEW_VISIBLE);
        });

        this.roleSpinner = JSpinner(ctx, "角色 (role)");
        this.roleSpinner.layoutParams = params;
        layout.addView(this.roleSpinner);
        ttsrv.setMargins(this.roleSpinner, 0, 4, 2, 0);
        this.roleSpinner.setOnItemSelected(function(_, __, item) {
            ttsrv.tts.data['role'] = String(item.value || '');
        });
        linerLayout.addView(layout);

        this.seekStyle = JSeekBar(ctx, "风格强度 (Style degree)：");
        linerLayout.addView(this.seekStyle);
        ttsrv.setMargins(this.seekStyle, 0, 4, 0, -4);
        this.seekStyle.setFloatType(2); 
        this.seekStyle.setMax(200);

        var styleDegree = parseFloat(ttsrv.tts.data['styleDegree'] || 1.0);
        this.seekStyle.setValue(new java.lang.Float(styleDegree));

        this.seekStyle.setOnChangeListener({
            onStopTrackingTouch: function(seek) {
                ttsrv.tts.data['styleDegree'] = Number(seek.getValue()).toFixed(2);
            }
        });
    },

    onVoiceChanged: function(locale, voiceCode) {
        var vic = this.currentVoices[voiceCode];
        if (!vic) return;

        var locale2Items = [Item("默认 (default)", "")];
        var list2 = vic['SecondaryLocaleList'] || [];
        var locale2Pos = 0;
        for (var i = 0; i < list2.length; i++) {
            var v = list2[i];
            var loc = java.util.Locale.forLanguageTag(v);
            locale2Items.push(Item(loc.getDisplayName(loc), v));
            if (v === String(ttsrv.tts.data['languageSkill'])) locale2Pos = i + 1;
        }
        this.skillSpinner.setItems(locale2Items);
        this.skillSpinner.setSelectedPosition(locale2Pos);
        this.skillSpinner.setVisibility(locale2Items.length === 1 ? this.VIEW_GONE : this.VIEW_VISIBLE);

        var styleItems = [Item("默认 (general)", "")];
        var stylePos = 0;
        var styles = vic['StyleList'] || [];
        for (var j = 0; j < styles.length; j++) {
            var s = styles[j];
            styleItems.push(Item(getString(s), s));
            if (s === String(ttsrv.tts.data['style'])) stylePos = j + 1;
        }
        this.styleSpinner.setItems(styleItems);
        this.styleSpinner.setSelectedPosition(stylePos);
        this.seekStyle.setVisibility(styleItems.length === 1 ? this.VIEW_GONE : this.VIEW_VISIBLE);

        var roleItems = [Item("默认 (default)", "")];
        var rolePos = 0;
        var roles = vic['RolePlayList'] || [];
        for (var k = 0; k < roles.length; k++) {
            var r = roles[k];
            roleItems.push(Item(getString(r), r));
            if (r === String(ttsrv.tts.data['role'])) rolePos = k + 1;
        }
        this.roleSpinner.setItems(roleItems);
        this.roleSpinner.setSelectedPosition(rolePos);
    }
};

/**
 * ==========================================================
 * 第五部分：本地化字典
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
