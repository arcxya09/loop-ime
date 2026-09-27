package app.loop.ime

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream

class SettingsActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var prefs: Prefs
    private var page="home"
    private var backupPassword: CharArray?=null
    private var pendingBackup: Pair<Int,Uri>?=null
    private var backupPrompt: AlertDialog?=null
    private val workDialogs=mutableSetOf<ProgressDialog>()
    private val green=Color.rgb(23,107,80)
    private var memoryOffset=0
    private var memoryQuery=""
    private var memoryPage=0L
    private var screenRevision=0L
    private var apiTest: AiCall?=null
    private var speechTest: CloudAsrStream?=null
    private var modelUiRefresh: (()->Unit)?=null
    private var modelUiVisible=false
    private val modelUiTick=object: Runnable {
        override fun run() {
            if(!modelUiVisible || page!="offline_model" || isFinishing || isDestroyed)return
            modelUiRefresh?.invoke();LoopApp.main.postDelayed(this,300)
        }
    }
    private fun dp(x: Int)=(x*resources.displayMetrics.density).toInt()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { DiagnosticLog.attach(this) }
        window.setDecorFitsSystemWindows(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        prefs=Prefs(this)
        savedInstanceState?.getString("pending_backup_uri")?.let { pendingBackup=savedInstanceState.getInt("pending_backup_code") to Uri.parse(it) }
        when(savedInstanceState?.getString("page") ?: intent.getStringExtra("page")) { "api"->apiPage();"custom_api"->customApiPage();"speech"->speechPage();"offline_model"->offlineModelPage();"backup"->backupPage();"connection_backup"->connectionBackupPage();"data"->dataPage();"diagnostics"->diagnosticsPage();"database"->databasePage();else->home() }
        if(intent.getBooleanExtra("microphone",false))requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),10)
    }
    override fun onResume() { super.onResume();RotationLock(this).recover();modelUiVisible=true;LoopApp.main.removeCallbacks(modelUiTick);LoopApp.main.post(modelUiTick);if(pendingBackup!=null)askBackupPassword() }
    override fun onStop() { modelUiVisible=false;LoopApp.main.removeCallbacks(modelUiTick);OfflineModels.pause();super.onStop() }
    override fun onSaveInstanceState(out: Bundle) { out.putString("page",page);pendingBackup?.let { out.putInt("pending_backup_code",it.first);out.putString("pending_backup_uri",it.second.toString()) };super.onSaveInstanceState(out) }
    override fun onDestroy() { backupPassword?.fill('\u0000');backupPassword=null;backupPrompt?.dismiss();backupPrompt=null;workDialogs.forEach { it.dismiss() };workDialogs.clear();screenRevision++;apiTest?.cancel();speechTest?.cancel();modelUiRefresh=null;LoopApp.main.removeCallbacks(modelUiTick);OfflineModels.pause();super.onDestroy() }
    private fun layout(title: String, subtitle: String) {
        screenRevision++;apiTest?.cancel();apiTest=null;speechTest?.cancel();speechTest=null
        modelUiRefresh=null;LoopApp.main.removeCallbacks(modelUiTick);if(page!="offline_model")OfflineModels.pause()
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(18),dp(22),dp(32));setBackgroundColor(Color.rgb(245,245,239)) }
        val scroll=ScrollView(this).apply { isFillViewport=true;addView(root) };setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { view,ins -> val b=ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime());view.setPadding(b.left,b.top,b.right,b.bottom);ins }
        scroll.requestApplyInsets()
        if(page!="home")button("← 返回") { home() }
        label(title,30f,true);label(subtitle,14f);space(14)
    }
    private fun space(n: Int=10) { root.addView(Space(this),LinearLayout.LayoutParams(1,dp(n))) }
    private fun label(s: String,size: Float=15f,bold: Boolean=false): TextView = TextView(this).apply {
        text=s;textSize=size;setTextColor(if(bold)green else Color.rgb(73,87,76));setLineSpacing(dp(3).toFloat(),1f);if(bold)setTypeface(typeface,Typeface.BOLD)
        setTextIsSelectable(true)
        root.addView(this,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(7) })
    }
    private fun button(s: String,action: ()->Unit): Button = Button(this).apply {
        text=s;isAllCaps=false;textSize=15f;setTextColor(green)
        background=GradientDrawable().apply { setColor(Color.WHITE);cornerRadius=dp(14).toFloat() }
        setPadding(dp(15),dp(9),dp(15),dp(9));setOnClickListener { action() }
        root.addView(this,LinearLayout.LayoutParams(-1,dp(53)).apply { topMargin=dp(6);bottomMargin=dp(3) })
    }
    private fun field(title: String,value: String="",password: Boolean=false,multiline: Boolean=false): EditText {
        label(title,13f)
        return EditText(this).apply {
            setText(value);textSize=16f;setTextColor(Color.rgb(32,44,37));setPadding(dp(12),dp(10),dp(12),dp(10))
            background=GradientDrawable().apply { setColor(Color.WHITE);cornerRadius=dp(10).toFloat() }
            inputType=InputType.TYPE_CLASS_TEXT or if(password)InputType.TYPE_TEXT_VARIATION_PASSWORD else if(multiline)InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_TEXT_VARIATION_NORMAL
            if(password)isSaveEnabled=false
            imeOptions=EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            if(!multiline)setSingleLine(true) else minLines=3
            root.addView(this,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
        }
    }
    private fun toggle(title: String,key: String,default: Boolean=false,explain: String?=null): Switch {
        val view=Switch(this).apply { text=title;textSize=16f;setTextColor(green);isChecked=prefs.flag(key,default);setPadding(0,dp(10),0,dp(10)) }
        view.setOnCheckedChangeListener { _,on ->
            if(on && explain!=null) {
                AlertDialog.Builder(this).setTitle(title).setMessage(explain).setPositiveButton("开启") { _,_->prefs.set(key,true) }.setNegativeButton("暂不开启") { _,_->view.isChecked=false }.setOnCancelListener { view.isChecked=false }.show()
            } else prefs.set(key,on)
        };root.addView(view,LinearLayout.LayoutParams(-1,-2));return view
    }
    private fun copyText(text: String) {
        try {
            val clip=ClipData.newPlainText("Loop",text)
            clip.description.extras=android.os.PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE,true) }
            getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            Toast.makeText(this,"已复制",Toast.LENGTH_SHORT).show()
        } catch(e: Exception) { DiagnosticLog.failure(DiagnosticLog.Area.SETTINGS,e);Toast.makeText(this,"复制失败，请导出日志文件",Toast.LENGTH_LONG).show() }
    }
    private fun message(s: String) {
        if(isFinishing || isDestroyed)return
        AlertDialog.Builder(this).setMessage(s).setPositiveButton("知道了",null)
            .setNeutralButton("复制") { _,_->copyText(s) }.show()
            .findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
    }
    private fun work(status: String, files: Boolean=false, diagnostics: Boolean=false, action: ()->String) {
        val dialog=ProgressDialog(this).apply { setMessage(status);setCancelable(false);show() }
        workDialogs.add(dialog)
        val task=Runnable {
            val result=runCatching(action).onFailure { DiagnosticLog.failure(DiagnosticLog.Area.SETTINGS,it) }
            runOnUiThread { workDialogs.remove(dialog);if(!isDestroyed) { dialog.dismiss();message(result.getOrElse { "操作失败：${it.javaClass.simpleName}：${it.message.orEmpty().take(120)}" }) } }
        }
        try { (if(diagnostics)DiagnosticLog.reports else if(files)LoopApp.files else LoopApp.io).execute(task) }
        catch(e: java.util.concurrent.RejectedExecutionException) { DiagnosticLog.failure(DiagnosticLog.Area.SETTINGS,e);workDialogs.remove(dialog);dialog.dismiss();message("操作队列繁忙，请稍后重试") }
    }
    private fun home() {
        page="home";layout("∞ Loop","让输入自然发生。\n实时语音 · 智能纠错 · 持续学习")
        val imm=getSystemService(InputMethodManager::class.java)
        val enabled=imm.enabledInputMethodList.any { it.packageName==packageName }
        label(if(enabled)"输入法已启用" else "先完成两步设置",18f,true)
        button(if(enabled)"1. 管理已启用的输入法" else "1. 启用 Loop 输入法") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        button("2. 选择 Loop 输入法") { imm.showInputMethodPicker() }
        space();label("你的输入空间",18f,true)
        button("AI 连接与实时纠错") { apiPage() }
        button("语音输入：百炼云端与离线模型") { speechPage() }
        button("键盘高度：${prefs.keyboardHeight.label}") {
            AlertDialog.Builder(this).setTitle("键盘高度")
                .setSingleChoiceItems(arrayOf("高 · 原有高度","中","低"),prefs.keyboardHeight.ordinal) { dialog,index ->
                    prefs.keyboardHeight=KeyboardHeight.entries[index];dialog.dismiss();home()
                }.setNegativeButton("取消",null).show()
        }
        button("输入记忆与词库") { dataPage() }
        button("剪贴板管理") { clipboardPage() }
        button("备份与恢复") { backupPage() }
        button("数据库检查与恢复") { databasePage() }
        button("诊断日志：复制 / 导出") { diagnosticsPage() }
        space();label("试一下 Loop",18f,true)
        field("在这里试试拼音、语音或连续输入",multiline=true).apply { hint="你好，Loop。";id=R.id.test_input;imeOptions=EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING }
        label("此试写框为隐私字段，语音仅本地识别，不进入记忆、不调用云端。测试百炼语音和文本 AI 请使用普通文本框。",12f)
        button("关于、开源许可与当前版本") { about() }
        label("${packageManager.getPackageInfo(packageName,0).versionName} · Android 17+",12f)
    }
    private fun apiPage() {
        page="api";layout("DeepSeek Flash","只需填写 DeepSeek 开放平台的 API Key。")
        val client=AiClient(this)
        val loaded=runCatching { client.deepSeekProfile() }
        loaded.exceptionOrNull()?.let { DiagnosticLog.failure(DiagnosticLog.Area.SETTINGS,it) }
        val profile=loaded.getOrDefault(AiProtocol.deepSeek())
        val active=runCatching { client.profile() }.getOrDefault(profile)
        if(loaded.isFailure)label("已保存的 Key 暂时无法读取。请重新粘贴 Key 并保存，或导入 AI 配置备份；记忆和词库会保留。",14f)
        if(!AiProtocol.isDeepSeek(active))label("当前使用自定义接口，保存下方 Key 后切换到 DeepSeek Flash。",13f)
        val key=field("API Key",profile.key,true).apply {
            id=R.id.api_key;hint=if(profile.key.isNotBlank())"Key 已保存；留空仍使用已保存 Key" else "粘贴 DeepSeek API Key"
            isSaveEnabled=false;importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO
            imeOptions=EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_DONE
        }
        val status=label(if(profile.key.isNotBlank())"Key 已加密保存，覆盖安装后继续使用。" else "地址和模型已设置好。填写后请保存。",13f).apply { id=R.id.api_test_status }
        button("仅保存 Key") {
            val value=key.text.toString()
            work("正在保存 Key…") { client.saveDeepSeek(value);"Key 已加密保存，重启与覆盖安装会继续保留。" }
        }
        lateinit var test: Button
        test=button("保存 Key 并测试连接") {
            val value=key.text.toString()
            saveAndTestApi(status,test) { client.saveDeepSeek(value) }
        }.apply { id=R.id.api_test_button }
        key.setOnEditorActionListener { _,action,_ -> if(action==EditorInfo.IME_ACTION_DONE) { test.performClick();true } else false }
        apiToggles()
        button("检查 AI 九宫格候选流程") {
            DiagnosticLog.event(DiagnosticLog.Area.CANDIDATE_TEST,DiagnosticLog.Step.POLICY,"cloud_enabled" to if(prefs.cloud)1L else 0L,"t9_enabled" to if(prefs.flag("ai_t9",true))1L else 0L)
            if(!prefs.cloud) { message("请先开启云端 AI，再检查候选流程。");return@button }
            if(!prefs.flag("ai_t9",true)) { message("请先开启 AI 九宫格候选，再检查候选流程。");return@button }
            val revision=screenRevision;apiTest?.cancel();status.text="正在用“你好”测试候选流程…"
            apiTest=AiClient(this).nineKey(NineKeyQuery("64426","")) { result ->
                if(!isDestroyed && revision==screenRevision)status.text=result.fold(
                    { words -> if(words.isEmpty())"连接已完成，模型未返回通过拼音校验的候选。" else "候选流程成功："+words.joinToString("、") { it.text } },
                    { "候选流程失败："+AiProtocol.failure(it) })
            }
        }
        button("查看 / 导出诊断日志") { diagnosticsPage() }
        button("数据库检查与恢复") { databasePage() }
        space();button("重装恢复：备份 / 导入 AI 配置") { connectionBackupPage() }
        space();button("高级设置：自定义 API") { customApiPage() }
    }
    private fun saveAndTestApi(status: TextView,trigger: Button,save: ()->AiProfile) {
        if(!trigger.isEnabled)return
        val revision=screenRevision
        trigger.isEnabled=false;status.text="正在加密保存 Key…";apiTest?.cancel()
        var saved: AiProfile?=null
        LoopApp.background(this,{ saved=save() }) { error ->
            if(isFinishing || isDestroyed || revision!=screenRevision)return@background
            if(error!=null) { status.text="保存失败：$error";trigger.isEnabled=true;return@background }
            status.text="Key 已保存，正在验证…"
            apiTest=AiClient(this).test(checkNotNull(saved),{ result ->
                if(!isFinishing && !isDestroyed && revision==screenRevision) { status.text=result;trigger.isEnabled=true }
            },{ stage -> if(!isFinishing && !isDestroyed && revision==screenRevision)status.text=stage })
        }
    }
    private fun customApiPage() {
        page="custom_api";layout("自定义 AI 接口","在这里配置文本 AI；语音识别的服务与 Key 在语音设置中单独管理。")
        val client=AiClient(this)
        val loaded=runCatching { client.profile() };val profile=loaded.getOrDefault(AiProtocol.deepSeek())
        if(loaded.isFailure)label("已保存的配置暂时无法读取。可重新填写并保存 API 配置，或导入配置备份；记忆和词库会保留。",14f)
        val current=prefs.text("profile",AiProtocol.DEFAULT_PROFILE)
        val names=(prefs.text("profiles",AiProtocol.DEFAULT_PROFILE).split(',')+current).distinct()
        button("当前配置：$current · 切换") { AlertDialog.Builder(this).setItems(names.toTypedArray()) { _,i -> prefs.set("profile",names[i]);customApiPage() }.show() }
        val name=field("配置名",current)
        val url=field("完整 Chat Completions HTTPS 地址",profile.url).apply { hint="https://你的服务/v1/chat/completions";inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        val model=field("模型名称",profile.model)
        val key=field("API Key",profile.key,true)
        val headers=field("附加请求头 JSON（可留空）",profile.headers,multiline=true)
        val status=label("保存并测试当前填写的地址、模型和 Key。",13f)
        lateinit var test: Button
        test=button("保存配置并测试连接") {
            val value=AiProfile(url.text.toString(),model.text.toString(),key.text.toString(),headers.text.toString())
            val n=name.text.toString()
            saveAndTestApi(status,test) { client.save(n,value);client.profile() }
        }
        button("返回 DeepSeek Flash 设置") { apiPage() }
        apiToggles()
    }
    private fun apiToggles() {
        toggle("启用云端文本 AI","cloud",explain="开启后，普通输入框中由 Loop 输入的最近最多 200 字，以及启用 AI 九宫格候选时的当前按键编码和获准使用的词库提示，会发送到当前已保存的 API。默认使用 DeepSeek Flash；服务商可能存储文本。密码字段和隐私模式始终排除。")
        toggle("AI 九宫格候选","ai_t9",true,explain="复用上方 API 和 Key。停顿输入后，结合九宫格拼音与最近语境补充简体中文词语；点选才上屏。需要开启云端文本 AI，会产生 API 用量；断网时继续本地候选。通讯录和仅本地词条不作为云端提示。")
        val correction=button("纠错模式：${prefs.correctionMode.label}") {}
        correction.setOnClickListener {
            AlertDialog.Builder(this).setTitle("选择纠错模式").setSingleChoiceItems(CorrectionMode.entries.map { it.label }.toTypedArray(),prefs.correctionMode.ordinal) { dialog,index ->
                prefs.correctionMode=CorrectionMode.entries[index];correction.text="纠错模式：${prefs.correctionMode.label}";dialog.dismiss()
            }.show()
        }
        toggle("AI 预测下一小段","predict",true)
        label("纠错会保护数字、单位、否定词和已知专有词；不确定的改动显示为候选。AI 返回过慢或输入位置改变时放弃修改。可在键盘左上角 Loop 工具中选择“撤销 AI 修改”恢复。",13f)
    }
    private fun speechPage() {
        page="speech";layout("说话，即输入","百炼云端优先，离线模型按需下载。")
        label("长按空格触发语音，出现“正在听”即可说话。连接云端或加载本地模型期间也会缓存开头音频，无需等待识别服务就绪；松开立即停止录音，已录内容继续识别。短按空格仍选择候选或输入空格。也可点击顶部麦克风连续说话，再点停止图标结束；× 可取消尚未提交的尾句。",14f)
        cloudSpeechSettings()
        space();label("离线识别",20f,true)
        button("离线模型：下载与管理") { offlineModelPage() }
        prefs.text("speech_last_error").takeIf { it.isNotBlank() }?.let { label("最近一次语音错误：$it",13f) }
        label("本地模式：16 kHz 单声道 · 词库热词\n停顿约 0.65 秒后分段，连续长句每 10 秒整理。识别速度和准确率取决于设备、口音和录音环境。",14f)
        button(if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)"麦克风权限已允许" else "允许麦克风") { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),10) }
        toggle("停顿处自动添加句末标点","punctuation",true)
        toggle("语音期间锁定屏幕方向","rotation")
        button("授予临时锁定方向所需的系统设置权限") { startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,Uri.parse("package:$packageName"))) }
        label("停止语音后恢复原来的自动旋转设置。系统或当前应用可能覆盖方向请求；无法授权时仍可录音。",13f)
    }
    private fun offlineModelPage() {
        page="offline_model";layout("离线语音模型","只在需要本地识别时下载。")
        offlineModelControls()
        space();label("自定义模型",18f,true)
        button("导入兼容的离线模型 ZIP") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE),24) }
        label("高级导入：ZIP 根目录需有 encoder.onnx、decoder.onnx、joiner.onnx、tokens.txt、bpe.vocab 和 manifest.json。支持 streaming Zipformer transducer，中英 cjkchar+bpe；manifest.json 必须列出每个文件的 SHA-256。",12f)
        button("返回语音设置") { speechPage() }
    }
    private fun offlineModelControls() {
        val model=OfflineModels.pack(this)
        val selected=label("",16f,true)
        label("中英双语离线包 · 约 190 MiB。使用云端语音无需下载；下载后可在断网、隐私模式中使用本地识别。",14f)
        val status=label("",13f).apply { id=R.id.offline_model_status }
        val progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=1000;id=R.id.offline_model_progress }
        root.addView(progress,LinearLayout.LayoutParams(-1,dp(12)))
        val download=button("下载离线模型") { OfflineModels.download(this);modelUiRefresh?.invoke() }.apply { id=R.id.offline_model_download }
        val pause=button("暂停下载") { OfflineModels.pause();modelUiRefresh?.invoke() }
        val useRecommended=button("使用已下载的推荐模型") { prefs.set("custom_model","");modelUiRefresh?.invoke() }
        val remove=button("删除已下载模型") { OfflineModels.remove(this);modelUiRefresh?.invoke() }
        val importedSize=label("正在统计导入模型空间…",12f)
        LoopApp.background(this,{ val bytes=ModelStorage(noBackupFilesDir,prefs.text("custom_model")).bytes();runOnUiThread { importedSize.text="导入模型占用：${bytes/(1024*1024)} MiB" } })
        button("清理未使用的导入模型") { work("清理旧模型…",files=true) {
            val count=ModelStorage(noBackupFilesDir,prefs.text("custom_model")).clearUnused()
            "已清理 $count 个旧模型，当前选择的模型保留。"
        } }
        label("下载期间请保持本页打开；离开页面会暂停，回来可继续。来自模型作者的 Hugging Face 仓库，无需 Key；逐文件校验后启用，下载中断不影响云端语音。",12f)
        modelUiRefresh={
            val state=OfflineModels.status;val installed=model.installed();val cached=model.cachedBytes()
            selected.text=when {
                prefs.text("custom_model").isNotBlank() -> "当前使用：已导入的自定义模型"
                installed -> "当前使用：中英双语离线模型"
                else -> "离线模型尚未下载"
            }
            status.text=if(state.busy)"${state.message} · ${state.bytes*100/model.total}%" else if(state.message.isNotBlank())state.message else if(installed)"已下载并校验，可离线识别" else if(cached>0)"已下载 ${cached*100/model.total}%，可以继续" else "只用云端语音时，可跳过下载"
            progress.visibility=if(state.busy || cached>0)View.VISIBLE else View.GONE
            progress.progress=((if(state.busy)state.bytes else cached)*1000/model.total).toInt()
            download.isEnabled=!state.busy && !installed;download.text=if(installed)"离线模型已下载" else if(cached>0)"继续下载离线模型" else "下载离线模型 · 190 MiB"
            pause.visibility=if(state.busy)View.VISIBLE else View.GONE
            useRecommended.visibility=if(prefs.text("custom_model").isNotBlank())View.VISIBLE else View.GONE
            useRecommended.isEnabled=installed && !state.busy
            remove.visibility=if(installed || cached>0)View.VISIBLE else View.GONE;remove.isEnabled=!state.busy
            remove.text=if(installed)"删除已下载模型" else "清除下载缓存"
        }
        modelUiRefresh?.invoke();if(modelUiVisible)LoopApp.main.post(modelUiTick)
    }
    private fun cloudSpeechSettings() {
        lateinit var cloudToggle: Switch
        val settings=CloudSpeechSettings(this)
        val region=settings.region()
        val loaded=runCatching { settings.profile(region) }
        val profile=loaded.getOrNull()
        label("阿里云百炼",20f,true)
        label(CloudAsrProfile.MODEL,12f)
        label("保存 Key 后优先使用云端。录音实时发送到所选地域的百炼接口；本机不保存音频。已下载离线模型时，断网自动转为本地识别；尚未下载时会停止并提示下载入口。网络恢复后，下次语音重新优先使用云端。",14f)
        label("密码框禁用语音；隐私模式及应用标记的隐私输入框始终使用本地识别。Key 无效、额度不足会直接提示。",12f)
        button("地域："+if(region=="singapore")"新加坡" else "北京（默认）") {
            AlertDialog.Builder(this).setTitle("选择百炼 Key 所属地域").setItems(arrayOf("北京","新加坡")) { _,i ->
                prefs.set("speech_region",CloudAsrProfile.REGIONS[i]);speechPage()
            }.show()
        }
        val key=field("百炼 API Key",profile?.key ?: "",password=true).apply {
            id=R.id.speech_api_key;isSaveEnabled=false;importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO
            hint="填写所选地域的百炼 Key，与 DeepSeek Key 分开"
        }
        val status=label(when {
            loaded.isFailure -> "已保存的百炼 Key 暂时无法读取，可以重新填写保存。"
            profile!=null -> "此地域 Key 已加密保存，覆盖安装后继续保留；留空不会删除。"
            else -> "填写百炼 Key 后保存；下载离线模型后，也可不使用 Key 进行本地语音输入。"
        },13f).apply { id=R.id.speech_api_status }
        button("保存 Key 并启用云端优先") {
            val value=key.text.toString()
            work("正在加密保存百炼 Key…") { settings.save(value,region);runOnUiThread { cloudToggle.isChecked=true;status.text="百炼 Key 已加密保存，云端优先已启用。" };"已保存并启用百炼云端优先。回到普通文本框即可开始语音。" }
        }
        lateinit var test: Button
        test=button("保存并测试百炼连接") {
            if(!test.isEnabled)return@button
            val value=key.text.toString();val revision=screenRevision
            test.isEnabled=false;status.text="正在加密保存 Key…";speechTest?.cancel()
            fun valid()=!isFinishing && !isDestroyed && revision==screenRevision
            try { CloudSpeechSettings.io.execute {
                val saved=runCatching { settings.save(value,region) }
                runOnUiThread {
                    if(!valid())return@runOnUiThread
                    saved.fold({ p ->
                        cloudToggle.isChecked=true;status.text="Key 已保存，正在连接百炼模型…"
                        val listener=object: CloudAsrListener {
                            override fun ready() {
                                if(!valid()) { speechTest?.cancel();return }
                                status.text="模型任务已启动，正在完成测试…"
                                if(speechTest?.audio(ShortArray(8000))==true)speechTest?.finish()
                                else failed(AsrFailure(AsrFailureKind.NETWORK,"测试音频发送失败"))
                            }
                            override fun partial(text: String) {}
                            override fun final(text: String,endMs: Long) {}
                            override fun done() { if(valid()) { status.text="连接成功：百炼流式模型任务已通过。";test.isEnabled=true;speechTest=null } }
                            override fun failed(error: AsrFailure) { if(valid()) { status.text="Key 已保存；测试失败：${error.message}";test.isEnabled=true;speechTest?.cancel();speechTest=null } }
                        }
                        speechTest=BailianAsr(p,emptyList(),listener);speechTest!!.start()
                    },{ status.text="保存失败，请解锁手机并检查所填 Key 后重试。";test.isEnabled=true })
                }
            } } catch(_: java.util.concurrent.RejectedExecutionException) { status.text="语音设置繁忙，请稍后重试。";test.isEnabled=true }
        }.apply { id=R.id.speech_api_test }
        cloudToggle=toggle("优先使用百炼云端识别","speech_cloud")
        toggle("云端使用已允许上传的个人词条","speech_cloud_terms",true)
        label("通讯录和标记为仅本地的词条不作为云端热词上传；本地识别仍使用完整词库。连接测试仅发送半秒合成静音，不使用麦克风或输入历史。",12f)
    }
    private fun dataPage() {
        page="data";layout("记忆与词库","保存你的表达，积累你常用的名字与术语。")
        toggle("保存完整输入记忆","memory",explain="保存 Loop 在普通输入框中提交的文本片段，长期保存在本机加密数据库。它不读取过去聊天、其他输入法或密码字段，也不保存录音和按键轨迹。可检索、逐条删除、全部清空及加密导出。")
        toggle("持续学习词库","learning",true)
        toggle("允许新记忆参与云端关键词整理","memory_cloud",explain="仅开启后新保存且当时已允许云端 AI 的普通记忆可参与云端整理。通讯录和手工词条默认仅在本地使用。")
        toggle("在设备空闲时用 AI 提取关键词","cloud_learning",explain="空闲且电量充足时，分批把允许云端处理的记忆片段发给已配置 API。只接受原文中实际存在的关键词加入词库。")
        toggle("隐私模式","private")
        button("搜索与管理输入记忆") { memoryOffset=0;memoryQuery="";memoryPage() }
        button("个人词库：搜索、添加、遗忘") { termPage() }
        button("导入通讯录姓名（仅本地）") {
            AlertDialog.Builder(this).setTitle("导入通讯录姓名").setMessage("读取联系人显示姓名并生成拼音，用于手动输入候选和本地语音热词。不导入电话、邮箱或照片；不会发送给 AI API。")
                .setPositiveButton("导入") { _,_->if(checkSelfPermission(Manifest.permission.READ_CONTACTS)==PackageManager.PERMISSION_GRANTED)importContacts() else requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS),11) }.setNegativeButton("取消",null).show()
        }
        button("删除通讯录导入及其词库贡献") { confirm("删除已导入的联系人词条？其他来源仍在使用的词条保留。") { work("正在清理…") { PersonalStore.get(this).deleteContacts();"已删除通讯录来源。" } } }
        button("数据库检查与恢复") { databasePage() }
        button("查看 / 导出诊断日志") { diagnosticsPage() }
        button("安排记忆关键词整理") {
            LearnJob.schedule(this)
            // Run through JobScheduler with the same persisted policy; idle jobs remain deferred by Android.
            message("已安排后台整理。键盘收起、手机解锁且电量足够时运行。手动选择的词条会立即在本地学习。")
        }
    }
    private fun confirm(s: String,action: ()->Unit) { AlertDialog.Builder(this).setMessage(s).setPositiveButton("确定") { _,_->action() }.setNegativeButton("取消",null).show() }
    private fun memoryPage() {
        page="memories";layout("输入记忆","加密存储 · 按片段保存 · 删除会撤回该片段的词库贡献")
        val query=field("包含文字",memoryQuery)
        button("搜索") { memoryQuery=query.text.toString();memoryOffset=0;memoryPage() }
        button("清空全部输入记忆") { confirm("清空全部输入记忆及其衍生词条？此操作无法撤销。") { work("清空中…") { DraftWriter.get(this).discard().get();PersonalStore.get(this).deleteAllMemories();"记忆已清空。" } } }
        val status=label("加载中…",13f);val token=++memoryPage
        LoopApp.background(this,{
            val s=PersonalStore.get(this);val rows=s.memories(memoryQuery,memoryOffset);val count=s.count("memories")
            runOnUiThread {
                if(page!="memories" || token!=memoryPage)return@runOnUiThread
                status.text="共 $count 条 · 当前第 ${memoryOffset/60+1} 页"
                rows.forEach { m ->
                    label(SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(Date(m.time))+" · "+m.source+" · "+if(m.cloud)"可云端整理" else "仅本地",11f)
                    button(m.text.take(100)) { AlertDialog.Builder(this).setTitle("输入片段").setMessage(m.text).setPositiveButton("删除并遗忘此片段") { _,_->LoopApp.background(this,{DraftWriter.get(this).discard(m.id).get();s.deleteMemory(m.id)}) { memoryPage() } }.setNegativeButton("关闭",null).show() }
                }
                if(memoryOffset>0)button("上一页") { memoryOffset-=60;memoryPage() }
                if(rows.size==60)button("下一页") { memoryOffset+=60;memoryPage() }
            }
        }) { err -> if(err!=null)status.text="读取失败：$err" }
    }
    private fun termPage(q: String="",offset: Int=0) {
        page="terms";layout("个人词库","同一套词条，为拼音候选和本地语音热词提供帮助。")
        val word=field("词条");val py=field("拼音（可留空自动生成；多音字建议手动修改）")
        button("添加 / 更新词条") { val w=word.text.toString();val p=py.text.toString();work("保存词条…") { require(TextRules.cleanTerm(w)!=null) { "词条需 2–32 字" };PersonalStore.get(this).addTerm(w,p,explicit=true);"词条已保存，仅本地使用。" } }
        val revision=screenRevision
        val query=field("搜索汉字或拼音",q)
        button("搜索词库") { termPage(query.text.toString()) }
        LoopApp.background(this,{
            val rows=PersonalStore.get(this).terms(q,61,offset=offset)
            runOnUiThread { if(!isDestroyed && page=="terms" && revision==screenRevision) {
                label("第 ${offset/60+1} 页 · 点击词条修改多音字读音",13f)
                rows.take(60).forEach { t -> button("${t.text}  ·  ${t.pinyin}  ·  权重 ${t.score}") {
                    val pronunciation=EditText(this).apply { setText(t.pinyin);hint="完整拼音，例如 chongqing";setSingleLine() }
                    AlertDialog.Builder(this).setTitle(t.text).setMessage("来源：${t.source}\n词频权重：${t.score}\n保存读音后，词条仅在本地使用。遗忘会撤回所有来源的贡献。").setView(pronunciation)
                        .setPositiveButton("保存读音") { _,_->val value=pronunciation.text.toString();LoopApp.background(this,{ PersonalStore.get(this).addTerm(t.text,value,explicit=true) }) { error -> if(error==null)termPage(q,offset) else message(error) } }
                        .setNeutralButton("遗忘") { _,_->LoopApp.background(this,{PersonalStore.get(this).forgetTerm(t.text)}) { termPage(q,offset) } }.setNegativeButton("关闭",null).show()
                } }
                if(offset>0)button("上一页") { termPage(q,maxOf(0,offset-60)) }
                if(rows.size>60)button("下一页") { termPage(q,offset+60) }
            } }
        })
    }
    private fun importContacts() {
        work("正在导入姓名…") { ContactsImporter(this).run() }
    }
    private fun databasePage() {
        page="database";layout("数据库检查与恢复","检查旧数据库，保留已有文件和 Key。")
        label("候选检查和通讯录导入都需要个人词库。数据库无法打开时，AI 九宫格仍可仅根据当前按键生成候选，不发送上下文或个人词条；记忆保存、词库学习和通讯录导入需先恢复数据库。",14f)
        button("检查并尝试恢复旧库") {
            work("正在检查旧库副本…",files=true) { PersonalStore.repair(this) }
        }.apply { id=R.id.database_repair }
        label("先使用上方检查。兼容恢复只处理能正确解密并通过完整性校验的副本，成功后切换到新的加密数据库。密钥不匹配或文件损坏时，旧库保持原样。",14f)
        button("保留旧库并启用新库") {
            AlertDialog.Builder(this).setTitle("启用新的个人数据库？")
                .setMessage("新库开始时没有旧记忆、词库或剪贴板记录，需要重新导入通讯录。旧文件和旧密钥仍保存在本机，但旧词库暂不参与输入或隐私词条匹配。API Key 和设置保留；以后可从可用的加密备份合并恢复。")
                .setPositiveButton("保留旧库，启用新库") { _,_->work("正在创建并校验新库…",files=true) { PersonalStore.startSeparate(this) } }
                .setNegativeButton("取消",null).show()
        }.apply { id=R.id.database_separate }
        button("从加密备份恢复记忆与词库") { backupPage() }
        button("查看 / 导出诊断日志") { diagnosticsPage() }
    }
    private fun diagnosticsPage() {
        page="diagnostics";layout("诊断日志","生成并导出日志，用于排查问题。")
        label("请先重现一次“候选流程失败”或“导入通讯录失败”，再刷新或导出。日志自动记录运行阶段和异常，不记录 Key、输入内容、联系人姓名或音频。仅保存在手机上，容量上限约 512 KiB，报告包含最近 7 天的记录。",14f)
        val revision=screenRevision
        lateinit var content: TextView
        button("复制最近日志") {
            val trigger=content
            try { DiagnosticLog.reports.execute {
                val result=runCatching { DiagnosticLog.report(applicationContext) }
                runOnUiThread { if(!isDestroyed && revision==screenRevision)result.fold({ trigger.text=it;copyText(it) },{ message("日志生成失败：${it.javaClass.simpleName}") }) }
            } } catch(_: java.util.concurrent.RejectedExecutionException) { message("日志任务繁忙，请稍后重试") }
        }.apply { id=R.id.diagnostics_copy }
        button("导出日志文件（TXT）") {
            try {
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(Intent.EXTRA_TITLE,"Loop-diagnostics-${SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(Date())}.txt"),40)
            } catch(e: ActivityNotFoundException) { DiagnosticLog.failure(DiagnosticLog.Area.LOG_EXPORT,e);message("系统文件选择器不可用，请使用“复制最近日志”。") }
        }.apply { id=R.id.diagnostics_export }
        button("刷新日志") { diagnosticsPage() }
        button("清空日志") { work("正在清空诊断日志…",diagnostics=true) {
            DiagnosticLog.clear(applicationContext)
            runOnUiThread { if(!isDestroyed && revision==screenRevision)content.text="诊断日志已清空。请重现问题后刷新。" }
            "已清空诊断日志。新发生的操作会继续记录。"
        } }
        content=label("正在生成日志…",12f).apply { id=R.id.diagnostics_content;typeface=Typeface.MONOSPACE;isSaveEnabled=false }
        try { DiagnosticLog.reports.execute {
            val result=runCatching { DiagnosticLog.report(applicationContext) }
            runOnUiThread { if(!isDestroyed && revision==screenRevision)content.text=result.getOrElse { "日志生成失败：${it.javaClass.simpleName}" } }
        } } catch(_: java.util.concurrent.RejectedExecutionException) { content.text="日志任务繁忙，请稍后刷新。" }
    }
    private fun clipboardPage() {
        page="clipboard";layout("剪贴板","仅键盘可见时记录普通文本；未固定内容保留 7 天，最多 200 条。")
        toggle("启用剪贴板历史","clipboard",explain="仅在 Loop 键盘可见、普通输入框和非隐私模式下保存剪贴板文本。系统标记为敏感的剪贴板不保存；不会上传。")
        button("清空剪贴板历史") { confirm("删除全部剪贴板历史（包括固定内容）？") { work("清空中…") { PersonalStore.get(this).clearClips();"已清空。" } } }
        val revision=screenRevision
        LoopApp.background(this,{
            val clips=PersonalStore.get(this).clips()
            runOnUiThread { if(!isDestroyed && page=="clipboard" && revision==screenRevision)clips.forEach { clip -> button((if(clip.pinned)"★ " else "")+clip.text.take(70)) { AlertDialog.Builder(this).setMessage(clip.text)
                .setPositiveButton(if(clip.pinned)"取消固定" else "固定") { _,_->LoopApp.background(this,{PersonalStore.get(this).clipAction(clip.id,!clip.pinned)}) { clipboardPage() } }
                .setNeutralButton("删除") { _,_->LoopApp.background(this,{PersonalStore.get(this).clipAction(clip.id,null)}) { clipboardPage() } }.setNegativeButton("关闭",null).show() } } }
        })
    }
    private fun backupPage() {
        page="backup";layout("备份与恢复","使用独立密码加密。包含记忆、词库、来源关系与剪贴板，不包含 API Key。")
        label("请妥善保存密码。卸载应用会删除本地加密密钥和数据，只有备份密码可以恢复导出的备份。",14f)
        val password=field("备份密码（至少 8 位）",password=true)
        label("可先预览新增、合并与跳过的记录数量。恢复按片段更新时间选取较新文本；词频跟随选中的片段版本，云端权限取更严格的一方。",13f)
        button("预览备份合并结果") { if(password.text.length<8)message("请输入原备份密码") else { backupPassword=password.text.toString().toCharArray();startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE),22) } }
        button("导出加密备份") { if(password.text.length<8)message("密码至少 8 位") else { backupPassword=password.text.toString().toCharArray();startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE,"Loop-${SimpleDateFormat("yyyyMMdd",Locale.US).format(Date())}.loopbackup").addCategory(Intent.CATEGORY_OPENABLE),20) } }
        button("合并恢复备份") { if(password.text.length<8)message("请输入原备份密码") else { backupPassword=password.text.toString().toCharArray();startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE),21) } }
        button("单独备份 / 恢复 AI Key") { connectionBackupPage() }
    }
    private fun connectionBackupPage() {
        page="connection_backup";layout("连接配置备份","包含全部文本 AI、百炼语音 Key 和输入设置，使用独立密码加密。")
        label("直接覆盖安装会保留配置。先卸载会清除应用数据，请提前将备份保存到下载目录或你自己的文件夹，重装后选择该文件恢复。",14f)
        val password=field("配置备份密码（至少 8 位）",password=true).apply { isSaveEnabled=false }
        button("导出全部连接配置（含 Key）") {
            if(password.text.length<8)message("备份密码至少 8 位") else {
                backupPassword=password.text.toString().toCharArray()
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"Loop-AI-Config.loopkey"),30)
            }
        }
        button("导入 AI 配置备份") {
            if(password.text.length<8)message("请输入备份时使用的密码") else {
                backupPassword=password.text.toString().toCharArray()
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE),31)
            }
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int,permissions: Array<out String>,grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        val granted=grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED
        if(requestCode==11)DiagnosticLog.event(DiagnosticLog.Area.CONTACTS_PERMISSION,DiagnosticLog.Step.PERMISSION,"permission_granted" to if(granted)1L else 0L)
        if(requestCode==11 && granted)importContacts() else message(if(granted)"权限已允许，回到键盘即可开始语音。" else "未获授权，相关功能保持关闭。")
    }
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==40) {
            val uri=data?.data
            if(resultCode!=RESULT_OK || uri==null) { DiagnosticLog.event(DiagnosticLog.Area.LOG_EXPORT,DiagnosticLog.Step.CANCELLED);return }
            work("正在生成诊断日志…",diagnostics=true) {
                val trace=DiagnosticLog.begin(DiagnosticLog.Area.LOG_EXPORT)
                try {
                    val report=DiagnosticLog.report(applicationContext,full=true)
                    (contentResolver.openOutputStream(uri,"wt") ?: error("系统未允许写入所选文件")).bufferedWriter(Charsets.UTF_8).use { it.write(report) }
                    trace.success();"诊断日志已保存，可将这个 TXT 文件发给开发者分析。"
                } catch(t: Throwable) { trace.failure(t);throw t }
            };return
        }
        if(resultCode!=RESULT_OK || data?.data==null) { backupPassword?.fill('\u0000');backupPassword=null;return }
        val uri=data.data!!
        if(data.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION!=0)runCatching {
            val read=data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0
            val write=data.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION!=0
            when {
                read && write -> contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                read -> contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
                write -> contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        if(requestCode==24) { importModel(uri);return }
        if(requestCode !in setOf(20,21,22,30,31))return
        val pass=backupPassword
        backupPassword=null
        if(pass==null) { pendingBackup=requestCode to uri;askBackupPassword();return }
        runBackup(requestCode,uri,pass)
    }
    private fun askBackupPassword() {
        val operation=pendingBackup ?: return
        if(backupPrompt?.isShowing==true || isFinishing || isDestroyed)return
        val password=EditText(this).apply { inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;isSaveEnabled=false;hint="备份密码（至少 8 位）" }
        backupPrompt=AlertDialog.Builder(this).setTitle("继续备份操作").setMessage("请重新输入备份密码，继续处理已选择的文件。").setView(password)
            .setPositiveButton("继续",null).setNegativeButton("取消") { _,_->pendingBackup=null;message("已取消本次备份操作。") }
            .setOnCancelListener { pendingBackup=null }.create().also { dialog ->
                dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    if(password.text.length<8)password.error="密码至少 8 位" else {
                        val pass=password.text.toString().toCharArray();password.text.clear();pendingBackup=null;dialog.dismiss();runBackup(operation.first,operation.second,pass)
                    }
                } };dialog.show()
            }
    }
    private fun runBackup(requestCode: Int,uri: Uri,pass: CharArray) {
        if(requestCode==30 || requestCode==31) {
            work(if(requestCode==30)"正在加密导出连接配置…" else "正在验证配置备份…",files=true) {
                try {
                    if(requestCode==30) {
                        exportBackup(uri) { ConnectionBackup.exportAll(this,it,pass) }
                        "全部文本 AI、百炼语音 Key 与输入设置已加密导出。"
                    } else contentResolver.openInputStream(uri)!!.use { ConnectionBackup.restoreAll(this,it,pass) }
                } finally { pass.fill('\u0000') }
            };return
        }
        work(if(requestCode==20)"正在生成加密备份…" else "验证并恢复备份…",files=true) {
            try {
                DraftWriter.get(this).settle()
                val s=PersonalStore.get(this)
                if(requestCode==20)exportBackup(uri) { Backup.export(s,it,pass) }
                else return@work contentResolver.openInputStream(uri)!!.use {
                    val result=Backup.restore(s,it,pass,preview=requestCode==22)
                    if(requestCode==22)"预览：$result。本机数据未修改。正式恢复时会重新检查当时的数据版本。"
                    else "备份已验证。$result。API 连接仍使用本机设置。"
                }
                "备份已导出。"
            } finally { pass.fill('\u0000') }
        }
    }
    private fun exportBackup(uri: Uri,write: (java.io.OutputStream)->Unit) {
        val encrypted=File.createTempFile("loop-export-",".encrypted",cacheDir)
        try {
            encrypted.outputStream().use(write)
            contentResolver.openOutputStream(uri,"wt")!!.use { target -> encrypted.inputStream().use { it.copyTo(target) } }
        } finally { encrypted.delete() }
    }
    private fun importModel(uri: Uri) {
        work("验证并导入模型…",files=true) {
            val staging=File(noBackupFilesDir,"model-import-${System.nanoTime()}").apply { mkdirs() }
            val required=setOf("encoder.onnx","decoder.onnx","joiner.onnx","tokens.txt","bpe.vocab","manifest.json")
            try {
                var total=0L;val seen=mutableSetOf<String>()
                ZipInputStream(contentResolver.openInputStream(uri)).use { zip ->
                    while(true) { val entry=zip.nextEntry ?: break
                        require(entry.name in required && !entry.isDirectory && seen.add(entry.name)) { "模型包结构不兼容" }
                        val limit=when(entry.name) { "manifest.json"->10000L;"tokens.txt","bpe.vocab"->20L*1024*1024;else->600L*1024*1024 }
                        var entryBytes=0L
                        File(staging,entry.name).outputStream().use { out -> val buf=ByteArray(65536);while(true) { val n=zip.read(buf);if(n<0)break;total+=n;entryBytes+=n;require(total<=800L*1024*1024 && entryBytes<=limit) { "模型文件大小超限" };out.write(buf,0,n) } }
                    }
                }
                require(seen==required) { "模型文件不完整" }
                val manifest=org.json.JSONObject(File(staging,"manifest.json").readText().also { require(it.length<10000) })
                for(name in required-"manifest.json") {
                    val digest=MessageDigest.getInstance("SHA-256");File(staging,name).inputStream().use { input -> val b=ByteArray(65536);while(true) { val n=input.read(b);if(n<0)break;digest.update(b,0,n) } }
                    val hash=digest.digest().joinToString("") { "%02x".format(it) };require(hash==manifest.getString(name).lowercase()) { "$name 校验失败" }
                }
                prefs.set("custom_model",staging.path)
                runOnUiThread { modelUiRefresh?.invoke() }
                "模型文件校验通过。请收起并重新打开键盘；若模型接口不兼容，可下载并选择推荐模型。"
            } catch(t: Throwable) { staging.deleteRecursively();throw t }
        }
    }
    private fun about() {
        page="about";layout("Loop 输入法","${packageManager.getPackageInfo(packageName,0).versionName} · Android 17 / API 37+ · arm64-v8a / x86_64")
        label("本版本提供可安装的输入法、离线拼音、百炼云端与本地中英流式语音、自定义文本 API、记忆词库、通讯录导入、剪贴板和密码备份。手机上的语音准确率、延迟、续航与不同应用兼容性仍需实际验证。",15f)
        label("GPL-3.0-or-later 开源。使用 Rime / Trime 原生引擎、sherpa-onnx、ONNX Runtime、SQLCipher、Kotlin 与 AndroidX。模型为 Apache-2.0。完整许可和构建来源见随源码提供的 THIRD_PARTY_NOTICES.md。",14f)
        button("查看内置开源声明") { message(assets.open("NOTICE.txt").bufferedReader().use { it.readText() }) }
    }
}
