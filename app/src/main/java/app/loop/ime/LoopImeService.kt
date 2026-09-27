package app.loop.ime

import android.content.*
import android.inputmethodservice.InputMethodService
import android.os.*
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import java.util.UUID
import java.util.ArrayDeque

class LoopImeService : InputMethodService() {
    private lateinit var keyboard: KeyboardView
    private lateinit var editor: SafeEditor
    private lateinit var rime: RimeEngine
    private lateinit var speech: SpeechController
    private lateinit var prefs: Prefs
    private lateinit var nineAi: NineKeyAiSession
    private var state=RimeState()
    private var candidateRevision=0L
    private var fieldEpoch=0L
    private var chinese=true
    private var nineKey=true
    private var restricted=false
    private var passwordField=false
    private var cloudBlocked=false
    private var visible=false
    private var voice=false
    private var stoppingVoice=false
    private var speechDone=false
    private var holdRequested=false
    private var voiceHeld=false
    private var speechStarted=false
    private var voiceGeneration=0L
    private var speechTerms=emptyList<String>()
    private var cloudSpeechTerms=emptyList<String>()
    private var speechTermsRevision=-1L
    private var lastOrientation=android.content.res.Configuration.ORIENTATION_UNDEFINED
    private var partial=""
    private var voicePrevious=""
    private var panelRevision=0L
    private var personal=emptyList<Term>()
    private var personalHasMore=false
    private data class RankKey(val raw: String,val nine: Boolean,val context: String,val historyRevision: Long,val learn: Boolean,val privateMode: Boolean,val restricted: Boolean,val minute: Long)
    private var rankKey: RankKey?=null
    private var rankSource: List<Term>?=null
    private var rankedPersonal=emptyList<Term>()
    private var suggestions=emptyList<String>()
    private var correctionSuggestion: String?=null
    private val keys=ArrayDeque<String>()
    private var busy=false
    private var call: AiCall?=null
    private var aiVersion=0L
    internal var completeText: (String,List<String>,(Result<AiResult>)->Unit)->AiCall = { text,terms,callback -> AiClient(this).complete(text,terms,callback) }
    private var latestText=""
    private var latestTime=0L
    private val recentWrites=ArrayDeque<Pair<Int,Long>>()
    private var latestId=""
    private var undo: Triple<String,String,Long>?=null
    private val history=InputHistory { DraftWriter.get(this).offer(it) }
    private var symbolsMode=false
    private var voiceRecovery=""
    private val storeChanged: ()->Unit = { if(::prefs.isInitialized) { refreshTerms();refreshSpeechTerms() } }
    private var clipLast=""
    private var observedClip=""
    private var observedClipTime=0L
    private var activePanel=""
    private var transientField=false
    private val quickClips=SuggestionBuffer()
    private val clipExpiry=Runnable { quickClips.values() }
    private val otpChanged: ()->Unit = { if(visible && ::keyboard.isInitialized) { if(activePanel=="quick")showPanel("quick") else renderCandidates() } }
    private val suggestionExpiry=object: Runnable { override fun run() { if(visible) { renderCandidates();LoopApp.main.postDelayed(this,15000) } } }
    private val voiceQueue=ArrayDeque<Segment>()
    private data class Segment(val raw: String, var text: String, var ready: Boolean=false, var call: AiCall?=null)
    private val screen=object: BroadcastReceiver() { override fun onReceive(c: Context,i: Intent) { if(i.action==Intent.ACTION_SCREEN_OFF) { visible=false;LoopApp.keyboardVisible=false;quickClips.clear();OtpInbox.clear();stopForNavigation() } } }
    private val clipboardListener=ClipboardManager.OnPrimaryClipChangedListener { if(visible && !restricted && ::prefs.isInitialized && !prefs.privateMode) { captureClip();renderCandidates() } }

    override fun onCreate() {
        setTheme(R.style.LoopImeTheme)
        super.onCreate()
        window.window?.let(ImeAppearance::apply)
        editor=SafeEditor { currentInputConnection };rime=RimeEngine(this)
        if(LoopApp.unlocked(this))prefs=Prefs(this)
        nineAi=NineKeyAiSession({ query,callback -> AiClient(this).nineKey(query,callback) },::nineKeyAiAllowed,::renderCandidates,status={ if(visible && ::keyboard.isInitialized)keyboard.aiStatus(it) })
        speech=SpeechController(this,::onSpeech)
        StoreEvents.add(storeChanged);OtpInbox.listeners.add(otpChanged)
        if(LoopApp.unlocked(this))DraftWriter.get(this).recover()
        lastOrientation=resources.configuration.orientation
        registerReceiver(screen,IntentFilter(Intent.ACTION_SCREEN_OFF),Context.RECEIVER_NOT_EXPORTED)
        getSystemService(ClipboardManager::class.java).addPrimaryClipChangedListener(clipboardListener)
    }
    override fun onCreateInputView(): View {
        if(::keyboard.isInitialized)keyboard.cancelSpaceGesture()
        keyboard=KeyboardView(this,::enqueue)
        if(LoopApp.unlocked(this)) {
            prefs=Prefs(this);nineKey=prefs.flag("chinese_t9",true);rime.layout(nineKey);keyboard.setNineKey(nineKey);keyboard.setHeightPreset(prefs.keyboardHeight)
            keyboard.status("正在准备拼音字典…")
            rime.prepare { error -> if(::keyboard.isInitialized && !voice) keyboard.status(error ?: "简体拼音已就绪 · 长按空格说话") }
        } else keyboard.status("解锁后启用中文、语音和个人词库")
        return keyboard
    }
    override fun onEvaluateFullscreenMode()=false
    override fun onWindowShown() { super.onWindowShown();window.window?.let(ImeAppearance::apply);if(::keyboard.isInitialized)keyboard.requestApplyInsets() }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        val rotated=lastOrientation!=android.content.res.Configuration.ORIENTATION_UNDEFINED && newConfig.orientation!=lastOrientation
        lastOrientation=newConfig.orientation
        if(rotated && voice)cancelVoice(true)
        super.onConfigurationChanged(newConfig)
        if(::keyboard.isInitialized) { keyboard.requestLayout();keyboard.requestApplyInsets() }
    }
    override fun onStartInput(info: EditorInfo, restarting: Boolean) {
        super.onStartInput(info,restarting)
        // Android has already replaced currentInputConnection here: abandon old ownership before cleanup.
        editor.start(minOf(info.initialSelStart,info.initialSelEnd))
        stopForNavigation();activePanel="";transientField=false;fieldEpoch++;keys.clear();busy=false;state=RimeState();rime.clear()
        val klass=info.inputType and InputType.TYPE_MASK_CLASS
        val variation=info.inputType and InputType.TYPE_MASK_VARIATION
        passwordField=(klass==InputType.TYPE_CLASS_TEXT && variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (klass==InputType.TYPE_CLASS_NUMBER && variation==InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        restricted=!LoopApp.unlocked(this) || (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)!=0 || passwordField || (::prefs.isInitialized && prefs.localApp(info.packageName.orEmpty()))
        if(LoopApp.unlocked(this)) { prefs=Prefs(this);nineKey=prefs.flag("chinese_t9",true);rime.layout(nineKey) }
        if(LoopApp.unlocked(this) && !rime.ready)rime.prepare { error -> if(::keyboard.isInitialized)keyboard.status(error ?: "拼音已就绪") }
        cloudBlocked=restricted;chinese=klass==InputType.TYPE_CLASS_TEXT && variation !in setOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,InputType.TYPE_TEXT_VARIATION_URI) && !passwordField
        latestText="";recentWrites.clear();undo=null;history.clear();symbolsMode=false;refreshTerms();refreshSpeechTerms()
    }
    override fun onStartInputView(info: EditorInfo,restarting: Boolean) {
        super.onStartInputView(info,restarting);visible=true;LoopApp.keyboardVisible=true
        keyboard.setHeightPreset(if(LoopApp.unlocked(this))prefs.keyboardHeight else KeyboardHeight.HIGH)
        keyboard.setNineKey(nineKey)
        symbolsMode=(info.inputType and InputType.TYPE_MASK_CLASS) in setOf(InputType.TYPE_CLASS_NUMBER,InputType.TYPE_CLASS_PHONE,InputType.TYPE_CLASS_DATETIME)
        keyboard.setInputKind(when(info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_PHONE->"phone";InputType.TYPE_CLASS_NUMBER->if(info.inputType and InputType.TYPE_NUMBER_FLAG_DECIMAL!=0)"decimal" else "number";InputType.TYPE_CLASS_DATETIME->"date"
            else->when(info.inputType and InputType.TYPE_MASK_VARIATION) { InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS->"email";InputType.TYPE_TEXT_VARIATION_URI->"url";else->"text" }
        })
        keyboard.setHand(if(::prefs.isInitialized)prefs.text("one_hand","off") else "off")
        keyboard.setCursorGesture(::prefs.isInitialized && prefs.flag("cursor_gesture",true))
        keyboard.setMode(chinese,symbolsMode)
        keyboard.setEnter(EnterKey.forEditor(info).label)
        keyboard.status(if(restricted)"此输入框不记录、不学习、不调用 AI" else if(prefs.privateMode)"隐私模式 · 本地输入" else if(chinese)"简体${if(nineKey)"九宫格" else "全键盘"} · 长按空格说话" else "英文 · 长按空格说话")
        if(!restricted && !prefs.privateMode)captureClip()
        if(!restricted && !prefs.privateMode)OtpInbox.recentSms(this)
        LoopApp.main.removeCallbacks(suggestionExpiry);LoopApp.main.postDelayed(suggestionExpiry,15000)
        renderCandidates()
        scheduleNineKeyAi()
        if(!restricted && voiceRecovery.isNotEmpty())keyboard.status("有尚未插入的语音 · 点击处理",::showVoiceRecovery)
        else if(!restricted && LoopApp.unlocked(this) && prefs.text("memory_last_error").isNotEmpty())keyboard.status(prefs.text("memory_last_error"))
    }
    override fun onFinishInputView(finishingInput: Boolean) { visible=false;LoopApp.keyboardVisible=false;stopForNavigation();activePanel="";transientField=false;fieldEpoch++;keys.clear();busy=false;state=RimeState();rime.clear();super.onFinishInputView(finishingInput) }
    override fun onFinishInput() { stopForNavigation();fieldEpoch++;rime.clear();super.onFinishInput() }
    override fun onUpdateSelection(oldSelStart: Int,oldSelEnd: Int,newSelStart: Int,newSelEnd: Int,candidatesStart: Int,candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart,oldSelEnd,newSelStart,newSelEnd,candidatesStart,candidatesEnd)
        if(editor.selection(newSelStart,newSelEnd,candidatesStart,candidatesEnd)) {
            stopForNavigation();fieldEpoch++;keys.clear();busy=false;rime.clear();state=RimeState();latestText="";renderCandidates()
        }
    }
    private fun enqueue(key: String) {
        panelRevision++
        // Release is an immediate stop signal, never delayed behind Rime or dictionary work.
        if(key=="voice_hold_end") { holdRequested=false;if(voice && voiceHeld)finishVoiceCapture();return }
        if(!key.startsWith("t9cand:") && key!="more_candidates")nineAi.cancel()
        if(key=="voice_hold_start")holdRequested=true
        if(keys.size>=48) { keyboard.status("输入处理繁忙，请稍候");return }
        keys.add(key);drain()
    }
    private fun drain() {
        if(busy || keys.isEmpty())return
        busy=true;val key=keys.removeFirst();val epoch=fieldEpoch
        handle(key) { if(epoch==fieldEpoch) { busy=false;drain() } }
    }
    private fun handle(key: String,done: ()->Unit) {
        if(key=="more_candidates") {
            if(!state.hasMore) { if(personalHasMore)loadMorePersonal(done) else { keyboard.candidatePaging(false);done() };return }
            val epoch=fieldEpoch;val revision=candidateRevision
            keyboard.candidatePaging(true,true)
            rime.event(60,5) { next ->
                if(epoch==fieldEpoch && revision==candidateRevision) {
                    if(next.error==null && next.raw==state.raw)state=next
                    renderCandidates();if(personalHasMore)loadMorePersonal(done) else done()
                }
            }
            return
        }
        if(key=="voice_hold_start") { if(holdRequested)startVoice(true,done) else done();return }
        if(key=="mic") { if(voice) { finishVoiceCapture();done() } else startVoice(false,done);return }
        if(key=="cancel_voice") { cancelVoice(true);done();return }
        if(key in setOf("settings","ai_settings","speech_settings","offline_model_settings","phrase_settings","quick_settings","keyboard_settings")) { stopForNavigation();startActivity(Intent(this,SettingsActivity::class.java).putExtra("page",when(key) { "ai_settings"->"api";"speech_settings"->"speech";"offline_model_settings"->"offline_model";"phrase_settings"->"phrases";"quick_settings"->"quick";"keyboard_settings"->"keyboard";else->"home" }).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));done();return }
        if(key=="hide") { requestHideSelf(0);done();return }
        if(key=="app_privacy") {
            val pkg=currentInputEditorInfo?.packageName.orEmpty()
            if(LoopApp.unlocked(this) && pkg.isNotBlank()) {
                stopForNavigation();prefs.setLocalApp(pkg,!prefs.localApp(pkg))
                currentInputEditorInfo?.let { onStartInput(it,true);onStartInputView(it,true) };showPanel("tools")
            }
            done();return
        }
        if(key=="privacy") { if(LoopApp.unlocked(this)) { stopForNavigation();prefs.set("private",!prefs.privateMode);quickClips.clear();OtpInbox.clear();refreshTerms();showPanel("tools") };done();return }
        if(voice) { done();return }
        if(key in setOf("clipboard","memory","emoji","punctuation","tools","height","layouts","edit","hand","phrases","quick")) { showPanel(key);done();return }
        if(key.startsWith("quick:")) { val insert={ insertQuick(key.substringAfter(':'));done() };if(state.raw.isNotEmpty())commitRime(insert) else insert();return }
        if(key=="quick_dismiss") { quickClips.values().forEach { quickClips.consume(it.id) };OtpInbox.buffer.values().forEach { OtpInbox.buffer.consume(it.id) };renderCandidates();done();return }
        if(key.startsWith("hand:")) { val value=key.substringAfter(':');prefs.set("one_hand",value);keyboard.setHand(value);showPanel("hand");done();return }
        if(key.startsWith("layout:")) { val nine=key.substringAfter(':')=="nine";if(nine==nineKey) { showPanel("layouts");done() } else handle("layout") { showPanel("layouts");done() };return }
        if(key.startsWith("symbol:")) { val value=key.substringAfter(':');val panel=activePanel;val insert={ commit(value);showPanel(panel);done() };if(state.raw.isNotEmpty())commitRime(insert) else insert();return }
        if(key in setOf("select_all","copy","cut","paste","undo_edit")) {
            val act={ cancelAi();flushDraft();latestText=""
                when(key) {
                    "undo_edit" -> { if(editor.undoLast())recordEdit("undo") else keyboard.status("没有可安全撤销的操作") }
                    "paste" -> { val text=captureClip();if(text!=null)insertLocal(text) else keyboard.status("没有可读取的普通剪贴板文本") }
                    else -> {
                        if(key!="select_all" && restricted)keyboard.status("隐私字段不复制或剪切内容")
                        else {
                            val ic=currentInputConnection
                            if(key=="cut") {
                                if(!ic?.getSelectedText(0).isNullOrEmpty() && ic?.performContextMenuAction(android.R.id.copy)==true && editor.delete())recordEdit("cut")
                                else keyboard.status("请选择可剪切的文本")
                            } else if(ic?.performContextMenuAction(if(key=="select_all")android.R.id.selectAll else android.R.id.copy)!=true)keyboard.status("输入框未接收操作")
                        }
                    }
                };showPanel("edit");done()
            };if(state.raw.isNotEmpty())commitRime(act) else act();return
        }
        if(key.startsWith("height:")) {
            val preset=KeyboardHeight.from(key.substringAfter(':'))
            if(LoopApp.unlocked(this))prefs.keyboardHeight=preset
            keyboard.setHeightPreset(preset);showPanel("height");done();return
        }
        if(key=="panel_close") { activePanel="";keyboard.closePanel();renderCandidates();done();return }
        if(key=="undo") { applyUndo();if(activePanel=="edit")showPanel("edit");done();return }
        if(key=="retype") {
            cancelAi()
            if(state.raw.isNotEmpty())rimeEvent(0,2,done) else done()
            return
        }
        if(key=="left" || key=="right") { val move={ cancelAi();flushDraft();latestText="";editor.navigate(key=="left");renderCandidates();done() };if(state.raw.isNotEmpty())commitRime(move) else move();return }
        if(key=="symbols" || key=="numbers") {
            cancelAi();latestText=""
            val change={ symbolsMode=!symbolsMode;keyboard.setMode(chinese,symbolsMode);renderCandidates();done() }
            if(state.raw.isNotEmpty())rimeEvent(0,2,change) else change()
            return
        }
        if(key=="language") { val action={ cancelAi();latestText="";symbolsMode=false;chinese=!chinese;keyboard.setMode(chinese,symbolsMode);renderCandidates();done() };if(state.raw.isNotEmpty())commitRime(action) else action();return }
        if(key=="layout") {
            val action={
                nineKey=!nineKey;if(LoopApp.unlocked(this))prefs.set("chinese_t9",nineKey)
                keyboard.setNineKey(nineKey);val epoch=fieldEpoch
                rime.layout(nineKey) { next -> if(epoch==fieldEpoch) { state=next;candidateRevision++;refreshTerms();keyboard.status("简体${if(nineKey)"九宫格" else "全键盘"} · 长按空格说话");done() } }
            }
            if(state.raw.isNotEmpty())commitRime(action) else action();return
        }
        if(key=="separator") { if(state.raw.isNotEmpty() && rime.ready)rimeEvent('\''.code,0,done) else done();return }
        if(key.startsWith("literal:")) {
            val literal=key.substringAfter(':')
            if(literal.length!=1 || literal[0].isISOControl()) { done();return }
            val insert={ commit(literal);renderCandidates();done() }
            if(state.raw.isNotEmpty())commitRime(insert) else insert()
            return
        }
        if(key.startsWith("t9:")) {
            val digit=key.substringAfter(':').singleOrNull()
            if(chinese && nineKey && !symbolsMode && digit!=null && digit in '2'..'9') {
                if(rime.ready) { cancelAi();rimeEvent(digit.code,0,done) } else { keyboard.status("拼音字典正在准备，请稍候");done() }
            } else done();return
        }
        if(key.startsWith("cand:")) {
            val parts=key.split(':');val chosen=parts.getOrNull(1)?.toIntOrNull() ?: -1
            if(chosen>=0 && parts.getOrNull(2)?.toLongOrNull()==candidateRevision)rimeEvent(chosen,1,done) else done();return }
        if(key.startsWith("personal:")) {
            val text=key.substringAfter(':')
            val term=personalCandidates().firstOrNull { it.text==text }
            val inputCode=CandidateRanking.inputCode(state.raw,nineKey)
            if(state.raw.isNotEmpty() && term!=null) {
                if(!term.cloud)cloudBlocked=true
                rimeEvent(0,2) { if(editor.commit(text)) { record(text,"choice");learnChoice(text,term.pinyin,inputCode) } else keyboard.status("输入框未接收词条，请重新选择");done() }
            } else done();return
        }
        if(key.startsWith("t9cand:")) {
            val parts=key.split(':')
            val choice=if(parts.getOrNull(3)?.toLongOrNull()==candidateRevision)
                nineAi.choose(parts.getOrNull(1)?.toIntOrNull() ?: -1,parts.getOrNull(2)?.toLongOrNull() ?: -1) else null
            val inputCode=CandidateRanking.inputCode(state.raw,nineKey)
            if(choice!=null)rimeEvent(0,2) {
                if(editor.commit(choice.text)) { record(choice.text,"choice");learnChoice(choice.text,choice.pinyin,inputCode) }
                renderCandidates();done()
            } else done()
            return
        }
        if(key=="delete") {
            cancelAi();undo=null
            if(state.raw.isNotEmpty())rimeEvent(0xff08,0,done)
            else { if(editor.delete())recordEdit("manual");latestText="";renderCandidates();done() };return
        }
        if(key=="space" && state.raw.isNotEmpty()) { chooseFirst(done);return }
        if(key=="enter" && state.raw.isNotEmpty()) { commitRime(done);return }
        if(key=="enter") {
            cancelAi();flushDraft();val action=EnterKey.forEditor(currentInputEditorInfo).action
            if(action!=null)editor.action(action) else commit("\n")
            done();return
        }
        val text=when(key) { "space"->" ";"comma"->if(chinese)"，" else ",";"period"->if(chinese)"。" else ".";else->key }
        if(text.length==1 && (text[0] in 'a'..'z' || text=="'") && chinese && !symbolsMode && rime.ready) { cancelAi();rimeEvent(text[0].code,0,done);return }
        if(state.raw.isNotEmpty()) { commitRime { commit(text);done() };return }
        commit(text);done()
    }
    private fun rimeEvent(key: Int,kind: Int,done: ()->Unit) {
        nineAi.cancel()
        val epoch=fieldEpoch;val previous=state;val inputCode=CandidateRanking.inputCode(previous.raw,nineKey)
        rime.event(key,kind) { next ->
            if(epoch!=fieldEpoch)return@event
            if(next.error!=null) { state=RimeState();candidateRevision++;keyboard.status(next.error);done();return@event }
            state=next;candidateRevision++
            val committed=next.editorCommit(chinese && nineKey)
            if(committed.isNotEmpty() && editor.commit(committed)) {
                record(committed,if(kind==1 || kind==3)"choice" else "manual")
                if(kind==1 || kind==3)learnChoice(committed,if(previous.candidates.firstOrNull()==committed)previous.reading else "",if(next.raw.isEmpty())inputCode else "")
            }
            val composition=next.editorComposition(chinese && nineKey)
            if(composition.isNotEmpty())editor.setComposition(composition,"rime")
            else if(editor.owner=="rime")editor.cancelComposition()
            renderCandidates();refreshTerms();done();scheduleNineKeyAi()
        }
    }
    private fun chooseFirst(done: ()->Unit) {
        val p=personalCandidates().firstOrNull { CandidateRanking.exact(it,state.raw,nineKey) || state.candidates.isEmpty() }
        if(p!=null)handle("personal:${p.text}",done) else if(state.candidates.isNotEmpty())rimeEvent(0,1,done) else commitRime(done)
    }
    private fun commitRime(done: ()->Unit) {
        val first=personalCandidates().firstOrNull { CandidateRanking.exact(it,state.raw,nineKey) }
        if(first!=null)handle("personal:${first.text}",done) else rimeEvent(0,if(chinese && nineKey && state.candidates.isEmpty())2 else 3,done)
    }
    private fun commit(text: String,source: String="manual"): Boolean {
        cancelAi();val ok=editor.commit(text);if(ok)record(text,source)
        else keyboard.status("输入框暂未接收文字，请重试")
        renderCandidates()
        return ok
    }
    private fun recordEdit(source: String) {
        if(transientField || restricted || !LoopApp.unlocked(this) || prefs.privateMode)return
        history.apply(editor.lastEdit,source,prefs.memory,prefs.cloud && !cloudBlocked && prefs.flag("memory_cloud"))
    }
    private fun record(text: String,source: String) {
        if(text.isEmpty())return
        if(transientField || restricted || !LoopApp.unlocked(this) || prefs.privateMode) { latestText="";return }
        recordEdit(source)
        latestText=history.context(editor.cursor);latestTime=SystemClock.uptimeMillis();latestId=history.lastId
        recentWrites.add(text.length to latestTime)
        while(recentWrites.isNotEmpty() && (latestTime-recentWrites.first.second>3000 || recentWrites.size>120))recentWrites.removeFirst()
        scheduleAi()
        refreshTerms()
    }
    private fun flushDraft() {
        history.separate()
        if(LoopApp.unlocked(this) && ::prefs.isInitialized)LearnJob.schedule(this)
    }
    private fun learnChoice(text: String,pinyin: String="",inputCode: String="") {
        if(transientField || restricted || prefs.privateMode || !prefs.learning)return
        history.learn(text,pinyin,prefs.cloud && !cloudBlocked,inputCode)
    }
    private fun refreshTerms() {
        personalHasMore=false
        if(!LoopApp.unlocked(this) || restricted || prefs.privateMode) { personal=emptyList();renderCandidates();return }
        val epoch=fieldEpoch;val raw=state.raw;val nine=nineKey;val context=latestText
        if(raw.isEmpty() && context.isBlank()) { personal=emptyList();renderCandidates();return }
        LoopApp.background(this,{ val store=PersonalStore.get(this)
            val list=if(raw.isEmpty())store.continuationTerms(context) else store.rankedTerms(raw.replace("'",""),context,nineKey=nine)
            LoopApp.main.post { if(epoch==fieldEpoch && state.raw==raw && context==latestText && nine==nineKey && !restricted && !prefs.privateMode) { personal=list.take(64);personalHasMore=raw.isNotEmpty() && list.size>64;renderCandidates() } }
        })
    }
    private fun loadMorePersonal(done: ()->Unit) {
        val epoch=fieldEpoch;val raw=state.raw;val nine=nineKey;val offset=personal.size;val context=latestText
        if(restricted || prefs.privateMode) { personalHasMore=false;keyboard.candidatePaging(state.hasMore);done();return }
        keyboard.candidatePaging(true,true)
        var list=emptyList<Term>()
        LoopApp.background(this,{
            list=PersonalStore.get(this).rankedTerms(raw.replace("'",""),context,nineKey=nine,offset=offset)
        }) { error ->
            if(epoch==fieldEpoch) {
                if(state.raw==raw && nine==nineKey && latestText==context) {
                    if(error!=null)keyboard.status("个人词库加载失败，请重试")
                    else if(!restricted && !prefs.privateMode && personal.size==offset) { personal=(personal+list.take(64)).distinctBy { it.text };personalHasMore=list.size>64 }
                    renderCandidates()
                }
                done()
            }
        }
    }
    private fun refreshSpeechTerms() {
        speechTerms=emptyList();cloudSpeechTerms=emptyList();speechTermsRevision=-1
        if(!LoopApp.unlocked(this) || restricted || prefs.privateMode)return
        val epoch=fieldEpoch
        val revision=StoreEvents.revision
        LoopApp.background(this,{
            val store=PersonalStore.get(this)
            val words=store.terms(limit=64).map { it.text }
            val cloudWords=store.cloudSpeechHints()
            LoopApp.main.post { if(epoch==fieldEpoch && revision==StoreEvents.revision && !restricted && !prefs.privateMode) { speechTerms=words;cloudSpeechTerms=cloudWords;speechTermsRevision=revision } }
        }) // Optional hints: storage failure must never prevent microphone startup.
    }
    private fun personalCandidates(): List<Term> {
        if(state.raw.isEmpty() || state.selStart!=0 || state.caret!=state.raw.length)return emptyList()
        val key=RankKey(state.raw,nineKey,latestText,history.revision,prefs.learning,prefs.privateMode,restricted,System.currentTimeMillis()/60000)
        if(rankKey==key && rankSource===personal)return rankedPersonal
        val matches=personal.filter { CandidateRanking.exact(it,state.raw,nineKey) || NineKey.matchesPrefix(it.pinyin,state.raw,nineKey) }.associateBy { it.text }.toMutableMap()
        if(!restricted && !prefs.privateMode && prefs.learning) {
            val origins=history.rankingOrigins()
            // Replace pending origins, never add the journal and its persisted copy together.
            matches.replaceAll { _,t ->
                val old=t.evidence.filter { it.origin in origins };val pending=history.rankingEvidence(t.text)
                val evidence=t.evidence.filterNot { it.origin in origins }+pending
                if(old.isEmpty() && pending.isEmpty())t else t.copy(score=(t.score-old.sumOf { it.count }+pending.sumOf { it.count }).coerceAtLeast(1),
                    lastUsed=evidence.maxOfOrNull { it.time } ?: 0,evidence=evidence)
            }
            history.recentChoices().forEach { choice ->
                if(choice.inputCode==CandidateRanking.inputCode(state.raw,nineKey) || (choice.pinyin.isNotBlank() && NineKey.matchesPrefix(choice.pinyin.replace(" ",""),state.raw,nineKey))) {
                    val old=matches[choice.text]
                    val evidence=old?.evidence ?: history.rankingEvidence(choice.text)
                    matches[choice.text]=Term(choice.text,old?.pinyin ?: choice.pinyin.replace(" ",""),old?.score ?: (1+evidence.sumOf { it.count }),choice.cloud && (old?.cloud ?: true),"choice",maxOf(choice.lastUsed,old?.lastUsed ?: 0),choice.inputCode,evidence)
                }
            }
        }
        rankedPersonal=CandidateRanking.sort(matches.values.toList(),context=latestText)
        rankKey=key;rankSource=personal
        return rankedPersonal
    }
    private fun renderCandidates() {
        if(!::keyboard.isInitialized)return
        if(voice || keyboard.panelOpen)return
        val list=mutableListOf<Pair<String,()->Unit>>()
        val cloud=mutableListOf<Pair<String,()->Unit>>()
        val revision=candidateRevision
        if(state.raw.isNotEmpty() && !symbolsMode) {
            val matches=personalCandidates()
            fun addPersonal(t: Term) { list+=(t.text to { if(revision==candidateRevision)enqueue("personal:${t.text}") }) }
            matches.filter { CandidateRanking.exact(it,state.raw,nineKey) }.forEach(::addPersonal)
            state.candidates.firstOrNull()?.let { text -> list+=(text to { enqueue("cand:0:$revision") }) }
            matches.filterNot { CandidateRanking.exact(it,state.raw,nineKey) }.forEach(::addPersonal)
            state.candidates.drop(1).forEachIndexed { index,text -> list+=(text to { enqueue("cand:${index+1}:$revision") }) }
            val aiRevision=nineAi.revision
            nineAi.candidates.forEachIndexed { index,candidate ->
                cloud+=candidate.text to { enqueue("t9cand:$index:$aiRevision:$revision") }
            }
            keyboard.composition(state.keyboardComposition(nineKey))
        } else {
            keyboard.endComposition()
            if(!symbolsMode && !restricted && !prefs.privateMode && latestText.isNotBlank()) {
                val context=latestText;val editorRevision=editor.revision
                correctionSuggestion?.let { text -> cloud+=("改为 $text" to { acceptCorrection(text) }) }
                suggestions.forEach { text -> cloud+=(text.trim() to {
                    if(!busy && !voice && state.raw.isEmpty() && context==latestText && editorRevision==editor.revision && text in suggestions)commit(text,"prediction")
                }) }
                if(prefs.flag("predict",true))personal.forEach { t ->
                    PredictionText.localSuffix(context,t.text)?.let { suffix -> list+=(suffix.trim() to {
                        if(!busy && !voice && state.raw.isEmpty() && context==latestText && editorRevision==editor.revision) {
                            if(!t.cloud)cloudBlocked=true
                            commit(suffix,"prediction")
                        }
                    }) }
                }
            }
        }
        if(state.raw.isEmpty() && visible && !restricted && !prefs.privateMode) {
            val epoch=fieldEpoch
            availableQuick().take(3).asReversed().forEach { item -> list.add(0,"${item.label} ${item.text.take(24)}" to { if(epoch==fieldEpoch)enqueue("quick:${item.id}") }) }
            if(availableQuick().isNotEmpty())list+=("忽略建议" to { if(epoch==fieldEpoch)enqueue("quick_dismiss") })
        }
        keyboard.candidatePaging(!symbolsMode && state.raw.isNotEmpty() && (state.hasMore || personalHasMore))
        keyboard.setCandidates(list.distinctBy { it.first })
        keyboard.setPredictions(cloud)
    }
    private fun nineKeyAiAllowed(query: NineKeyQuery): Boolean =
        visible && !voice && chinese && nineKey && !symbolsMode && !restricted && !cloudBlocked &&
            LoopApp.unlocked(this) && ::prefs.isInitialized && prefs.cloud && prefs.flag("ai_t9",true) &&
            state.selStart==0 && state.caret==state.raw.length && state.raw==query.raw && latestText.takeLast(120)==query.context
    private fun scheduleNineKeyAi() {
        if(busy || keys.isNotEmpty())return
        val query=NineKeyQuery(state.raw,latestText.takeLast(120))
        if(nineKeyAiAllowed(query))nineAi.update(query)
    }
    private val aiTask=Runnable { requestAi() }
    private fun cancelAi() { aiVersion++;call?.cancel();call=null;LoopApp.main.removeCallbacks(aiTask);suggestions=emptyList();correctionSuggestion=null;if(::nineAi.isInitialized)nineAi.cancel() }
    private fun scheduleAi() {
        if(restricted || cloudBlocked || !prefs.cloud || latestText.isBlank() || (prefs.correctionMode==CorrectionMode.OFF && !prefs.flag("predict",true)))return
        LoopApp.main.removeCallbacks(aiTask);LoopApp.main.postDelayed(aiTask,650)
    }
    private fun requestAi() {
        if(!visible || voice || busy || symbolsMode || state.raw.isNotEmpty() || editor.owner.isNotEmpty() || restricted || cloudBlocked || !prefs.cloud)return
        val text=latestText;val revision=editor.revision;val generation=editor.generation;val version=++aiVersion
        val started=SystemClock.uptimeMillis()
        call=completeText(text,personal.filter { it.cloud }.map { it.text }) { result ->
            if(!visible || symbolsMode || !prefs.cloud || version!=aiVersion || revision!=editor.revision || generation!=editor.generation || state.raw.isNotEmpty())return@completeText
            result.onSuccess { answer ->
                val now=SystemClock.uptimeMillis()
                val editable=recentWrites.filter { now-it.second<=3000 }.sumOf { it.first }.coerceAtMost(40)
                val inRecentTail=editable>0 && answer.corrected.startsWith(text.dropLast(editable.coerceAtMost(text.length)))
                if(SystemClock.uptimeMillis()-started<=3000 && prefs.correctionMode==CorrectionMode.CONSERVATIVE && answer.corrected!=text && inRecentTail && TextRules.safeCorrection(text,answer.corrected,personal.map { it.text })) {
                    if(editor.patch(text,answer.corrected,revision,generation)) {
                        undo=Triple(text,answer.corrected,editor.revision);updateRecordedCorrection(text,answer.corrected);latestText=answer.corrected;recentWrites.clear();keyboard.status("AI 已纠错 · 点击撤销",::applyUndo)
                    } else correctionSuggestion=answer.corrected
                } else if(prefs.correctionMode!=CorrectionMode.OFF && answer.corrected!=text && answer.corrected.length in 1..200)correctionSuggestion=answer.corrected
                if(prefs.flag("predict",true))suggestions=PredictionText.continuations(latestText,answer.predictions)
                renderCandidates()
            }.onFailure { keyboard.aiStatus("AI："+AiProtocol.failure(it).take(80)) }
        }
    }
    private fun acceptCorrection(text: String) {
        if(correctionSuggestion!=text || busy || voice || state.raw.isNotEmpty())return
        val original=latestText
        if(editor.patch(original,text,editor.revision,editor.generation)) { undo=Triple(original,text,editor.revision);updateRecordedCorrection(original,text);latestText=text;learnChoice(text) }
        correctionSuggestion=null;renderCandidates()
    }
    private fun updateRecordedCorrection(old: String,new: String) {
        recordEdit("corrected")
    }
    private fun applyUndo() {
        val u=undo ?: return
        if(editor.patch(u.second,u.first,u.third,editor.generation)) { updateRecordedCorrection(u.second,u.first);latestText=u.first;keyboard.status("已撤销本次 AI 修改") }
        undo=null;cancelAi();renderCandidates()
    }
    private fun startVoice(held: Boolean, done: ()->Unit) {
        if(voice || !visible || (held && !holdRequested)) { done();return }
        if(passwordField || !LoopApp.unlocked(this)) { keyboard.status("此输入框不启用语音");done();return }
        if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startActivity(Intent(this,SettingsActivity::class.java).putExtra("microphone",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));done();return
        }
        if(state.raw.isNotEmpty()) { commitRime { startVoice(held,done) };return }
        cancelAi();flushDraft();latestText="";voice=true;voiceHeld=held;speechStarted=false;stoppingVoice=false;speechDone=false;partial="";voicePrevious="";voiceQueue.clear();keyboard.voice(true)
        keyboard.status(if(held)"正在准备语音 · 松开空格结束" else "正在准备语音…")
        voiceGeneration++
        val localOnly=prefs.privateMode || restricted
        val cached=speechTermsRevision==StoreEvents.revision
        val words=if(localOnly || !cached)emptyList() else speechTerms
        // Revalidate cloud permission on the configuration worker even when local hints are cached.
        val vocabulary:(()->Pair<List<String>,List<String>>)?=if(localOnly)null else ({
            val store=PersonalStore.get(this)
            store.terms(limit=64).map { it.text } to if(prefs.flag("speech_cloud_terms",true))store.cloudSpeechHints() else emptyList()
        })
        speechStarted=true
        speech.start(words,allowCloud=!localOnly,cloudWords=emptyList(),vocabulary=vocabulary)
        done()
    }
    private fun finishVoiceCapture() {
        if(!voice || stoppingVoice)return
        stoppingVoice=true
        if(!speechStarted) { cancelVoice(true);keyboard.status("语音已结束");return }
        keyboard.voice(false);keyboard.status("正在完成尾句…");speech.finish()
    }
    private fun onSpeech(kind: Int,text: String) {
        if(!voice)return
        when(kind) {
            SpeechWire.READY -> if(!stoppingVoice)keyboard.status(text+if(voiceHeld)" · 松开空格结束" else "")
            SpeechWire.PARTIAL -> { partial=text;drawTranscript();if(!stoppingVoice)keyboard.status("正在听 · ${speech.recognitionLabel} · ${speech.inputRouteLabel}"+if(voiceHeld)" · 松开结束" else "") }
            SpeechWire.FINAL -> queueSegment(text)
            SpeechWire.DONE -> { partial="";stoppingVoice=true;speechDone=true;drainVoice();finishVoiceIfReady() }
            SpeechWire.ERROR,SpeechWire.MODEL_REQUIRED -> {
                voiceQueue.forEach { it.ready=true;it.call?.cancel() };partial="";drainVoice();cancelVoice(true)
                if(voiceRecovery.isEmpty())renderCandidates()
                if(kind==SpeechWire.MODEL_REQUIRED)keyboard.status(text) { enqueue("offline_model_settings") } else keyboard.status(text)
                prefs.set("speech_last_error",text)
            }
        }
    }
    private fun queueSegment(raw: String) {
        partial=""
        if(raw.isBlank())return
        val text=if(prefs.flag("punctuation",true) && raw.lastOrNull()?.let { it !in "。！？.!?，," }==true)raw+if(raw.any { it.code in 0x3400..0x9fff })"。" else "." else raw
        val s=Segment(text,text);voiceQueue.add(s)
        if(voiceQueue.size>2) { voiceQueue.first.ready=true;voiceQueue.first.call?.cancel() }
        if(prefs.cloud && !cloudBlocked && prefs.correctionMode==CorrectionMode.CONSERVATIVE) {
            val epoch=fieldEpoch
            s.call=AiClient(this).complete(text,personal.filter { it.cloud }.map { it.text }) { result ->
                if(!voice || epoch!=fieldEpoch || !voiceQueue.contains(s) || s.ready)return@complete
                result.onSuccess { if(TextRules.safeCorrection(text,it.corrected,personal.map { t -> t.text }))s.text=it.corrected }
                s.ready=true;drainVoice()
            }
            LoopApp.main.postDelayed({ if(voiceQueue.contains(s) && !s.ready) { s.ready=true;s.call?.cancel();drainVoice() } },800)
        } else s.ready=true
        drawTranscript();drainVoice()
    }
    private fun transcript()=SpeechText.join(voicePrevious,voiceQueue.map { it.text }+partial)
    private fun drawTranscript() { val text=transcript();if(text.isNotEmpty())editor.setComposition(text,"voice") else if(editor.owner=="voice")editor.cancelComposition() }
    private fun drainVoice() {
        while(voice && voiceQueue.isNotEmpty() && voiceQueue.first.ready) {
            val s=voiceQueue.first;s.call?.cancel()
            val head=SpeechText.append(voicePrevious,s.text)
            val tail=SpeechText.join(head,voiceQueue.drop(1).map { it.text }+partial)
            if(!editor.sealVoice(head,tail)) {
                voiceRecovery=transcript()
                cancelVoice(true)
                showVoiceRecovery()
                return
            }
            voiceQueue.removeFirst();voicePrevious=head;record(head,"voice")
        }
        finishVoiceIfReady()
    }
    private fun showVoiceRecovery() {
        val text=voiceRecovery
        if(text.isEmpty())return
        val epoch=fieldEpoch
        keyboard.panel("语音暂未插入 · 已保留文字",listOf(
            "重试插入" to { if(epoch==fieldEpoch && commit(text,"voice")) { voiceRecovery="";keyboard.setMode(chinese,symbolsMode) } },
            "复制文字" to { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Loop 语音",text));keyboard.status("语音文字已复制") },
            "放弃这段文字" to { voiceRecovery="";keyboard.setMode(chinese,symbolsMode) }
        ))
    }
    private fun finishVoiceIfReady() {
        if(voice && speechDone && voiceQueue.isEmpty() && partial.isEmpty()) {
            voice=false;voiceHeld=false;speechStarted=false;voiceGeneration++;stoppingVoice=false;editor.finishComposition();keyboard.voice(false);keyboard.status("语音已结束");flushDraft();renderCandidates()
        }
    }
    private fun cancelVoice(removeTail: Boolean) {
        voiceGeneration++;voiceHeld=false;speechStarted=false
        speech.cancel();voiceQueue.forEach { it.call?.cancel() };voiceQueue.clear();partial=""
        if(voice && removeTail && editor.owner=="voice")editor.cancelComposition()
        voice=false;stoppingVoice=false;if(::keyboard.isInitialized)keyboard.voice(false);flushDraft()
    }
    private fun stopForNavigation() {
        holdRequested=false;voiceGeneration++;voiceHeld=false;speechStarted=false
        if(::speech.isInitialized)speech.cancel()
        voiceQueue.forEach { it.call?.cancel() };voiceQueue.clear();partial=""
        if(voice && editor.owner=="voice")editor.cancelComposition()
        voice=false;stoppingVoice=false;cancelAi();if(::nineAi.isInitialized)nineAi.cancel(clearCache=true);if(::prefs.isInitialized)flushDraft()
        if(::keyboard.isInitialized) { keyboard.dismissPreview();keyboard.cancelSpaceGesture();keyboard.voice(false) }
    }
    private fun availableQuick(): List<QuickSuggestion> {
        if(!::prefs.isInitialized || restricted || prefs.privateMode || !visible)return emptyList()
        val sms=prefs.flag("otp_sms") && checkSelfPermission(android.Manifest.permission.READ_SMS)==android.content.pm.PackageManager.PERMISSION_GRANTED
        val notifications=prefs.flag("otp_notifications") && androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        return (quickClips.values().filter { if(it.sensitive)prefs.flag("otp_clip",true) else prefs.flag("quick_clip",true) }+
            OtpInbox.buffer.values().filter { if(it.source=="短信")sms else notifications }).sortedByDescending { it.time }
    }
    private fun insertQuick(id: String) {
        if(state.raw.isNotEmpty() || !visible || availableQuick().none { it.id==id })return
        val value=quickClips.values().firstOrNull { it.id==id } ?: OtpInbox.buffer.values().firstOrNull { it.id==id } ?: return
        if(insertLocal(value.text)) { quickClips.consume(id);OtpInbox.buffer.consume(id);activePanel="";keyboard.closePanel();renderCandidates() }
    }
    private fun insertLocal(text: String): Boolean {
        cancelAi();flushDraft();history.clear();cloudBlocked=true;transientField=true;latestText="";undo=null
        val ok=editor.commit(text);if(!ok)keyboard.status("输入框未接收内容，请重试")
        return ok
    }
    private fun captureClip(): String? {
        if(!visible || restricted || !::prefs.isInitialized || prefs.privateMode)return null
        val cm=getSystemService(ClipboardManager::class.java)
        val description=cm.primaryClipDescription ?: run { quickClips.removeSource("剪贴板");return null }
        if(description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE)==true) { quickClips.removeSource("剪贴板");return null }
        val text=cm.primaryClip?.getItemAt(0)?.text?.toString()?.takeIf { it.isNotBlank() && it.length<=20000 } ?: run { quickClips.removeSource("剪贴板");return null }
        val fingerprint=OtpInbox.event("剪贴板",text,0)
        if(fingerprint!=observedClip) { observedClip=fingerprint;observedClipTime=System.currentTimeMillis() }
        val time=description.timestamp.takeIf { it>0 } ?: observedClipTime
        if(prefs.flag("quick_clip",true) || prefs.flag("otp_clip",true))quickClips.offer(OtpInbox.event("剪贴板",text,time),text,"剪贴板",time)
        LoopApp.main.removeCallbacks(clipExpiry);LoopApp.main.postDelayed(clipExpiry,(time+SuggestionBuffer.TTL+1-System.currentTimeMillis()).coerceIn(1,SuggestionBuffer.TTL+1))
        if(text!=clipLast && prefs.flag("clipboard") && !QuickText.sensitive(text)) { clipLast=text;LoopApp.background(this,{ PersonalStore.get(this).addClip(text) }) }
        return text
    }
    override fun onKeyDown(keyCode: Int,event: android.view.KeyEvent): Boolean {
        if(keyCode==android.view.KeyEvent.KEYCODE_BACK && ::keyboard.isInitialized && keyboard.panelOpen) { enqueue(if(activePanel=="tools")"panel_close" else "tools");return true }
        return super.onKeyDown(keyCode,event)
    }
    private fun showPanel(kind: String) {
        cancelAi();activePanel=kind
        val unlocked=LoopApp.unlocked(this)
        val private=restricted || !unlocked || prefs.privateMode
        fun act(label: String,code: String,enabled: Boolean=true,selected: Boolean=false)=PanelAction(label,code,enabled=enabled,selected=selected)
        when(kind) {
            "tools" -> { val app=currentInputEditorInfo?.packageName.orEmpty()
                keyboard.tools(ToolCatalog.actions(if(unlocked)prefs.text("tool_order") else ""),listOf(
                    act(if(unlocked && prefs.privateMode)"全局隐私 · 开启" else "全局隐私 · 关闭","privacy",unlocked,unlocked && prefs.privateMode),
                    act(if(restricted && (!unlocked || passwordField || currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)!=0))"字段强制隐私" else if(unlocked && prefs.localApp(app))"此应用 · 隐私" else "此应用 · 跟随全局","app_privacy",unlocked && !passwordField && currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)==0 && app.isNotBlank(),unlocked && prefs.localApp(app)))) }
            "height" -> keyboard.actionPanel("键盘高度",KeyboardHeight.entries.map { act(it.label+if(unlocked && prefs.keyboardHeight==it)" · 当前" else "","height:${it.value}",unlocked,unlocked && prefs.keyboardHeight==it) })
            "layouts" -> keyboard.actionPanel("键盘与快捷入口",listOf(act("九宫格","layout:nine",unlocked,nineKey),act("26 键","layout:full",unlocked,!nineKey),act("单手模式","hand"),act("常用短语","phrases"),act("快捷建议","quick"),act("工具排序 / 手势","keyboard_settings")))
            "hand" -> keyboard.actionPanel("单手模式",listOf("left" to "靠左","off" to "完整宽度","right" to "靠右").map { (value,label) -> act(label,"hand:$value",unlocked,unlocked && prefs.text("one_hand","off")==value) })
            "edit" -> keyboard.actionPanel("文本编辑",listOf(act("← 光标","left"),act("光标 →","right"),act("全选","select_all"),act("复制","copy",!private),act("剪切","cut",!private),act("粘贴","paste",!private),act("撤销输入","undo_edit",editor.canUndo),act("撤销 AI","undo",undo!=null),act("常用短语","phrases",!private)))
            "emoji","punctuation" -> { val marks=if(kind=="emoji")listOf("😀","😄","😊","😂","🥰","👍","🙌","🎉","❤️","✨","🌿","🙏","，","。","？","！","、","：","；","……","“","”") else listOf("，","。","？","！","、","：","；","……","“","”","（","）","《","》","—","·")
                keyboard.actionPanel(if(kind=="emoji")"表情与符号" else "常用标点",marks.map { act(it,"symbol:$it") },6) }
            "quick" -> { captureClip();val epoch=fieldEpoch
                keyboard.cards("快捷建议",availableQuick().map { item -> PanelCard("${item.label} · ${item.source} · ${((System.currentTimeMillis()-item.time)/1000).coerceAtLeast(0)} 秒前",item.text,{ if(epoch==fieldEpoch)enqueue("quick:${item.id}") }) },if(private)"隐私字段不自动读取消息或剪贴板" else "暂无新建议；可复制短信后提取验证码",extra=listOf(act("验证码与快捷建议设置","quick_settings"))) }
            else -> {
                if(private) { keyboard.cards("隐私保护",emptyList(),"当前输入框不读取记忆、短语或剪贴板");return }
                val epoch=fieldEpoch;val revision=panelRevision
                fun active()=epoch==fieldEpoch && revision==panelRevision && visible && activePanel==kind && !restricted && !prefs.privateMode
                fun insert(value: String) { if(!active())return;val finish={ if(insertLocal(value)) { activePanel="";keyboard.closePanel();renderCandidates() } };if(state.raw.isNotEmpty())commitRime(finish) else finish() }
                val title=when(kind) { "phrases"->"常用短语";"clipboard"->"剪贴板";else->"输入记忆" }
                val current=if(kind=="clipboard")captureClip() else null
                keyboard.cards(title,listOfNotNull(current?.let { PanelCard("当前剪贴板",it,{ insert(it) }) }),"正在读取…")
                var cards=listOfNotNull(current?.let { PanelCard("当前剪贴板",it,{ insert(it) }) })
                LoopApp.background(this,{
                    val store=PersonalStore.get(this)
                    cards=when(kind) {
                        "phrases" -> store.phrases().map { phrase -> PanelCard(phrase.group,phrase.text,{ insert(phrase.text) }) }
                        "clipboard" -> (listOfNotNull(current?.let { PanelCard("当前剪贴板",it,{ insert(it) }) })+if(prefs.flag("clipboard"))store.clips().filterNot { QuickText.sensitive(it.text) }.map { clip -> PanelCard(if(clip.pinned)"已置顶" else "历史记录",clip.text,{ insert(clip.text) },listOf((if(clip.pinned)"取消置顶" else "置顶") to { if(active())LoopApp.background(this,{ store.clipAction(clip.id,!clip.pinned) }) { if(active())showPanel(kind) } },"删除" to { if(active())LoopApp.background(this,{ store.clipAction(clip.id,null) }) { if(active())showPanel(kind) } })) } else emptyList()).distinctBy { it.text }
                        else -> store.memories(limit=30).filterNot { QuickText.sensitive(it.text) }.map { PanelCard("最近记忆",it.text,{ insert(it.text) }) }
                    }
                }) { error -> if(active())keyboard.cards(title,cards,if(error==null)"暂无内容" else "读取失败，请在数据维护中检查；原数据保留",extra=if(kind=="phrases")listOf(act("添加 / 管理短语","phrase_settings")) else emptyList()) }
            }
        }
    }
    override fun onDestroy() {
        LoopApp.keyboardVisible=false
        StoreEvents.remove(storeChanged);OtpInbox.listeners.remove(otpChanged);LoopApp.main.removeCallbacks(suggestionExpiry);LoopApp.main.removeCallbacks(clipExpiry);quickClips.clear()
        stopForNavigation();speech.destroy();unregisterReceiver(screen)
        getSystemService(ClipboardManager::class.java).removePrimaryClipChangedListener(clipboardListener);super.onDestroy()
    }
}
