package app.loop.ime

import android.app.Application
import android.os.Looper
import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.time.Duration
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[37],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class NineKeyImeTest {
    @Test fun toolsAndSubpanelsPreserveUncommittedPinyinAndReturnCandidates()=Fixture().use { f ->
        f.type()
        for(panel in listOf("tools","height","layouts","emoji","edit")) {
            f.press(panel);f.drain();assertTrue(f.keyboard.panelOpen)
            assertEquals("",f.text.toString());assertEquals("64426",ReflectionHelpers.getField<RimeState>(f.service,"state").raw)
        }
        f.press("panel_close");f.drain();assertFalse(f.keyboard.panelOpen)
        assertEquals("ni hao",f.keyboard.findViewWithTag<TextView>("keyboard_preedit").text.toString())
        f.press("space");f.drain();assertEquals("你",f.text.toString())
        f.press("emoji");f.press("symbol:😀");f.drain();assertEquals("你😀",f.text.toString());assertTrue(f.keyboard.panelOpen)
    }
    @Test fun otpQuickFillIsConsumedAndNeverEntersHistoryOrFollowingCloudContext()=Fixture().use { f ->
        val writes=mutableListOf<DraftSnapshot>();ReflectionHelpers.setField(f.service,"history",InputHistory(writes::add))
        ReflectionHelpers.setField(f.service,"restricted",false);Prefs(f.service).set("clipboard",true)
        val clips=ReflectionHelpers.getField<SuggestionBuffer>(f.service,"quickClips")
        clips.offer("test","验证码 001234","剪贴板",System.currentTimeMillis())
        val id=clips.values().single().id
        f.press("quick:$id");f.drain();assertEquals("001234",f.text.toString());assertTrue(writes.isEmpty())
        assertTrue(clips.values().isEmpty());assertTrue(ReflectionHelpers.getField(f.service,"cloudBlocked"))
        f.press("quick:$id");f.press("下文");f.drain();assertEquals("001234下文",f.text.toString());assertTrue(writes.isEmpty())
        ReflectionHelpers.setField(f.service,"restricted",true)
    }
    @Test fun quickPanelKeepsPreviewViewsUntilSuggestionsActuallyChange()=Fixture().use { f ->
        ReflectionHelpers.setField(f.service,"restricted",false)
        val clips=ReflectionHelpers.getField<SuggestionBuffer>(f.service,"quickClips")
        clips.offer("refresh-test","验证码 001234","测试来源",System.currentTimeMillis())
        f.press("quick");f.drain()
        val preview=all(f.keyboard).filterIsInstance<TextView>().single { it.text=="001234" }
        ReflectionHelpers.getField<Runnable>(f.service,"suggestionExpiry").run()
        assertSame(preview,all(f.keyboard).filterIsInstance<TextView>().single { it.text=="001234" })
        clips.consume(clips.values().single().id)
        ReflectionHelpers.getField<Runnable>(f.service,"suggestionExpiry").run()
        assertFalse(all(f.keyboard).filterIsInstance<TextView>().any { it.text=="001234" })
        ReflectionHelpers.setField(f.service,"restricted",true)
    }
    @Test fun contextualFirstCandidateIsSharedByDisplaySpaceAndEnterWithoutDoubleCountingJournal()=Fixture().use { f ->
        val history=InputHistory {};ReflectionHelpers.setField(f.service,"history",history)
        ReflectionHelpers.setField(f.service,"restricted",false);Prefs(f.service).set("learning",true)
        f.press("开始");f.drain();f.type()
        val now=System.currentTimeMillis()
        val words=listOf(Term("事实","nihao",12,false,"choice",now,evidence=listOf(RankingEvidence("old",11,now,mapOf("这是" to 11)))),
            Term("实施","nihao",4,false,"choice",now,evidence=listOf(RankingEvidence("context",3,now,mapOf("开始" to 3)))))
        ReflectionHelpers.setField(f.service,"personal",words);ReflectionHelpers.callInstanceMethod<Unit>(f.service,"renderCandidates")
        val strip=f.keyboard.findViewWithTag<View>("candidate_strip")
        assertEquals("实施",all(strip).filterIsInstance<TextView>().first { it.text in listOf("实施","事实") }.text)
        f.press("space");f.drain();assertEquals("开始实施",f.text.toString())
        val pending=history.rankingEvidence("实施")
        assertEquals(mapOf("开始" to 1),pending.single().contexts)
        f.press("。");f.press("开始");f.drain();f.type()
        ReflectionHelpers.setField(f.service,"personal",listOf(words[0],words[1].copy(score=5,evidence=words[1].evidence+pending)))
        val ranked=ReflectionHelpers.callInstanceMethod<List<Term>>(f.service,"personalCandidates")
        assertEquals(5,ranked.single { it.text=="实施" }.score)
        assertEquals(2,ranked.single { it.text=="实施" }.evidence.size)
        f.press("enter");f.drain();assertEquals("开始实施。开始实施",f.text.toString())
        ReflectionHelpers.setField(f.service,"restricted",true)
    }
    @Test fun partialRimeCommitsDoNotBindTheWholeInputCodeToTheFirstWord()=Fixture().use { f ->
        ReflectionHelpers.setField(f.service,"history",InputHistory {})
        ReflectionHelpers.setField(f.service,"restricted",false);Prefs(f.service).set("learning",true)
        f.type();f.remainingAfterChoice="426"
        val revision=ReflectionHelpers.getField<Long>(f.service,"candidateRevision")
        f.press("cand:0:$revision");f.drain()
        assertEquals("",ReflectionHelpers.getField<InputHistory>(f.service,"history").recentChoices().single().inputCode)
        ReflectionHelpers.setField(f.service,"restricted",true)
    }
    @Test fun choosingAWordImmediatelyPromotesItAndSpaceAndEnterUseTheDisplayedFirstWord()=Fixture().use { f ->
        val writes=mutableListOf<DraftSnapshot>();ReflectionHelpers.setField(f.service,"history",InputHistory(writes::add))
        ReflectionHelpers.setField(f.service,"restricted",false);Prefs(f.service).set("learning",true)
        f.type()
        val revision=ReflectionHelpers.getField<Long>(f.service,"candidateRevision")
        f.press("cand:1:$revision");f.drain();assertEquals("你好",f.text.toString())
        // No database write has run: the in-memory learning state must already determine rank.
        ReflectionHelpers.setField(f.service,"personal",emptyList<Term>())
        f.type()
        fun words()=all(f.keyboard.findViewWithTag("candidate_strip")).filterIsInstance<TextView>().map { it.text.toString() }.filter { it in setOf("你","你好") }
        assertEquals("你好",words().first());f.press("space");f.drain();assertEquals("你好你好",f.text.toString())
        f.type();assertEquals("你好",words().first());f.press("enter");f.drain();assertEquals("你好你好你好",f.text.toString())
        assertEquals(3,writes.last().choices.single().count)
        ReflectionHelpers.setField(f.service,"restricted",true)
    }
    @Test fun learningDisabledPrivateAndRejectedCommitsNeverBoostCandidates()=Fixture().use { f ->
        val writes=mutableListOf<DraftSnapshot>();ReflectionHelpers.setField(f.service,"history",InputHistory(writes::add))
        val prefs=Prefs(f.service)
        for(mode in 0..3) {
            prefs.set("learning",mode!=0);prefs.set("private",mode==1);ReflectionHelpers.setField(f.service,"restricted",mode==2);f.acceptCommit=mode!=3
            f.type();val revision=ReflectionHelpers.getField<Long>(f.service,"candidateRevision")
            f.press("cand:1:$revision");f.drain()
            assertTrue(ReflectionHelpers.getField<InputHistory>(f.service,"history").recentChoices().isEmpty())
        }
        ReflectionHelpers.setField(f.service,"restricted",true)
    }
    @Test fun suggestionModeDoesNotRewriteAndOffModeSuppressesCorrectionCandidates()=Fixture().use { f ->
        val prefs=Prefs(f.service);prefs.set("cloud",true);prefs.correctionMode=CorrectionMode.SUGGEST
        ReflectionHelpers.setField(f.service,"restricted",false)
        var answer: ((Result<AiResult>)->Unit)?=null
        f.service.completeText={ _,_,cb -> answer=cb;AiCall() }
        f.press("hello worle");f.drain();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(660))
        answer!!(Result.success(AiResult("hello world",emptyList())))
        assertEquals("hello worle",f.text.toString())
        assertEquals("hello world",ReflectionHelpers.getField<String>(f.service,"correctionSuggestion"))
        prefs.correctionMode=CorrectionMode.OFF
        f.press("!");f.drain();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(660))
        answer!!(Result.success(AiResult("hello world!",emptyList())))
        assertNull(ReflectionHelpers.getField(f.service,"correctionSuggestion"))
        assertEquals("hello worle!",f.text.toString())
        ReflectionHelpers.setField(f.service,"restricted",true)
    }
    @Test fun rejectedPagingSubmissionReleasesTheKeyQueue()=Fixture().use { f ->
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        LoopApp.io.execute { entered.countDown();release.await(15,TimeUnit.SECONDS) }
        assertTrue(entered.await(3,TimeUnit.SECONDS))
        try {
            repeat(256) { LoopApp.io.execute {} }
            ReflectionHelpers.setField(f.service,"restricted",false)
            ReflectionHelpers.setField(f.service,"personalHasMore",true)
            f.press("more_candidates");f.drain()
            ReflectionHelpers.setField(f.service,"restricted",true)
            f.press("1");f.drain();assertEquals("1",f.text.toString())
        } finally { LoopApp.io.queue.clear();release.countDown();ReflectionHelpers.setField(f.service,"restricted",true) }
    }
    @Test fun currentClipboardPastesWithHistoryDisabledAndBlocksCloudContext()=Fixture().use { f ->
        Prefs(f.service).set("clipboard",false)
        ReflectionHelpers.setField(f.service,"restricted",false)
        f.service.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("test","当前剪贴板"))
        f.press("clipboard");f.drain()
        all(f.keyboard).filterIsInstance<TextView>().single { it.text=="插入" }.performClick()
        assertEquals("当前剪贴板",f.text.toString());assertTrue(ReflectionHelpers.getField(f.service,"cloudBlocked"))
        ReflectionHelpers.setField(f.service,"restricted",true)
    }
    @Test fun englishVoiceFinalsAndPartialsKeepSegmentBoundaries()=Fixture().use { f ->
        Prefs(f.service).set("private",true);Prefs(f.service).set("punctuation",false)
        ReflectionHelpers.setField(f.service,"voice",true)
        fun emit(kind: Int,text: String)=ReflectionHelpers.callInstanceMethod<Unit>(f.service,"onSpeech",ClassParameter.from(Int::class.javaPrimitiveType,kind),ClassParameter.from(String::class.java,text))
        emit(SpeechWire.FINAL,"hello");emit(SpeechWire.PARTIAL,"wor");assertEquals("hello wor",f.text.toString())
        emit(SpeechWire.FINAL,"world");emit(SpeechWire.DONE,"");assertEquals("hello world",f.text.toString())
    }
    @Test fun speechCompletionAndFailuresRestorePersonalCandidatesWithoutGrowingTheKeyboard()=Fixture().use { f ->
        val prefs=Prefs(f.service);prefs.set("cloud",false);prefs.set("private",false);prefs.set("learning",false)
        ReflectionHelpers.setField(f.service,"restricted",false)
        ReflectionHelpers.setField(f.service,"personal",listOf(Term("个人词","gerenci",1,false,"manual")))
        fun height(): Int {
            f.keyboard.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
            f.keyboard.layout(0,0,f.keyboard.measuredWidth,f.keyboard.measuredHeight);return f.keyboard.height
        }
        fun emit(kind: Int,text: String)=ReflectionHelpers.callInstanceMethod<Unit>(f.service,"onSpeech",ClassParameter.from(Int::class.javaPrimitiveType,kind),ClassParameter.from(String::class.java,text))
        val before=height()
        for(kind in listOf(SpeechWire.DONE,SpeechWire.ERROR,SpeechWire.MODEL_REQUIRED)) {
            ReflectionHelpers.setField(f.service,"latestText","个人")
            ReflectionHelpers.setField(f.service,"voice",true);ReflectionHelpers.setField(f.service,"speechDone",false);f.keyboard.voice(true)
            emit(SpeechWire.READY,"正在听");assertEquals(before,height())
            emit(kind,if(kind==SpeechWire.MODEL_REQUIRED)"请下载离线模型" else "识别失败")
            assertEquals(before,height());assertFalse(ReflectionHelpers.getField(f.service,"voice"))
            assertTrue("Missing continuation after speech event $kind",all(f.keyboard.findViewWithTag("candidate_strip")).filterIsInstance<TextView>().any { it.text=="词" })
        }
        ReflectionHelpers.setField(f.service,"restricted",true)
    }
    private fun all(v: View): List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList())
    private class Fixture: AutoCloseable {
        val life=Robolectric.buildService(LoopImeService::class.java).create()
        val service=life.get()
        val text=SpannableStringBuilder().apply { Selection.setSelection(this,0) }
        var acceptCommit=true
        val connection=object: BaseInputConnection(View(service),true) {
            override fun getEditable(): Editable=text
            override fun commitText(value: CharSequence?,position: Int)=acceptCommit && super.commitText(value,position)
        }
        val editor=SafeEditor { connection }.apply { start(0) }
        val keyboard=KeyboardView(service,::press)
        private var raw=""
        var candidateCount=2
        var remainingAfterChoice=""
        private var candidateLimit=30
        private fun localWords()=if(candidateCount==2)listOf("你","你好") else (0 until candidateCount).map { "候选词$it" }
        val engine=RimeEngine(service) { key,kind ->
            var commit=""
            if(kind!=5)candidateLimit=30
            when(kind) {
                2 -> raw=""
                1,3 -> { commit=localWords()[key];raw=remainingAfterChoice }
                5 -> candidateLimit+=60
                else -> if(key==0xff08)raw=raw.dropLast(1) else raw+=key.toChar()
            }
            JSONObject().put("raw",raw).put("preedit",raw).put("caret",raw.length).put("selStart",0)
                .put("reading",when(raw) { "64426"->"ni hao";"6442"->"ni ha";""->"";else->"ni" })
                .put("commit",commit).put("candidates",JSONArray(if(raw.isEmpty())emptyList() else localWords().take(candidateLimit)))
                .put("hasMore",raw.isNotEmpty() && candidateCount>candidateLimit).toString().toByteArray()
        }
        init {
            Prefs(service).set("learning",false)
            ReflectionHelpers.setField(service,"restricted",true)
            ReflectionHelpers.setField(service,"visible",true)
            ReflectionHelpers.setField(service,"editor",editor)
            ReflectionHelpers.setField(service,"keyboard",keyboard)
            ReflectionHelpers.setField(engine,"ready",true)
            ReflectionHelpers.setField(service,"rime",engine)
        }
        fun press(key: String) { ReflectionHelpers.callInstanceMethod<Unit>(service,"enqueue",ClassParameter.from(String::class.java,key)) }
        fun drain() {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
            while(ReflectionHelpers.getField<Boolean>(service,"busy") && System.nanoTime()<deadline) { shadowOf(Looper.getMainLooper()).idle();Thread.yield() }
            assertFalse("Rime key queue did not finish",ReflectionHelpers.getField(service,"busy"))
        }
        fun type() { "64426".forEach { press("t9:$it") };drain() }
        fun ai(): NineKeyAiSession {
            val session=NineKeyAiSession({ _,cb -> cb(Result.success(listOf(NineKeyCandidate("你好","nihao"))));AiCall() },{ true },{
                ReflectionHelpers.callInstanceMethod<Unit>(service,"renderCandidates")
            })
            ReflectionHelpers.setField(service,"nineAi",session)
            session.update(NineKeyQuery("64426",""));shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(320));return session
        }
        override fun close() { life.destroy() }
    }
    @Test fun paginationKeepsTheCompositionAndSelectsTheActualIndexPastThirty()=Fixture().use { f ->
        f.candidateCount=125;f.type()
        val before=ReflectionHelpers.getField<Long>(f.service,"candidateRevision")
        f.press("more_candidates");f.drain();f.press("more_candidates");f.drain()
        val state=ReflectionHelpers.getField<RimeState>(f.service,"state")
        assertEquals(125,state.candidates.size);assertEquals("64426",state.raw);assertFalse(state.hasMore)
        assertEquals(before,ReflectionHelpers.getField<Long>(f.service,"candidateRevision"));assertEquals("",f.text.toString())
        all(f.keyboard).filterIsInstance<TextView>().single { it.text=="候选词124" }.performClick();f.drain()
        assertEquals("候选词124",f.text.toString())
    }
    @Test fun contactPrefixAndMoreThanThreeExactTermsRemainSelectable()=Fixture().use { f ->
        f.type()
        val words=(0 until 8).map { Term("姓名$it","nihao",10-it,false,"contacts") }+Term("扩展姓名","nihaoma",2,false,"contacts")
        ReflectionHelpers.setField(f.service,"personal",words)
        ReflectionHelpers.callInstanceMethod<Unit>(f.service,"renderCandidates")
        val strip=f.keyboard.findViewWithTag<View>("candidate_strip")
        assertTrue(all(strip).filterIsInstance<TextView>().any { it.text=="姓名7" })
        all(strip).filterIsInstance<TextView>().single { it.text=="扩展姓名" }.performClick();f.drain()
        assertEquals("扩展姓名",f.text.toString())
    }
    @Test fun typingShowsTheCombinationOnlyInTheKeyboardWhileNumberModeStillTypesDigits()=Fixture().use { f ->
        f.type();assertEquals("",f.text.toString());assertEquals("",f.editor.owner)
        assertEquals("ni hao",f.keyboard.findViewWithTag<TextView>("keyboard_preedit").text.toString())
        f.press("delete");f.drain();assertEquals("",f.text.toString())
        assertEquals("ni ha",f.keyboard.findViewWithTag<TextView>("keyboard_preedit").text.toString())
        f.press("retype");f.drain();assertEquals("",f.text.toString())
        assertEquals(View.GONE,f.keyboard.findViewWithTag<View>("keyboard_preedit").visibility)
        f.press("6");f.drain();assertEquals("6",f.text.toString())
    }
    @Test fun aiResultDoesNotWriteUntilTappedAndClearsTheWholeRimeBufferExactlyOnce()=Fixture().use { f ->
        f.type();f.ai();assertEquals("",f.text.toString())
        val word=all(f.keyboard).filterIsInstance<TextView>().single { it.contentDescription=="你好，AI 候选" }
        word.performClick();f.drain();assertEquals("你好",f.text.toString())
        assertEquals("",ReflectionHelpers.getField<RimeState>(f.service,"state").raw)
        word.performClick();f.drain();assertEquals("你好",f.text.toString())
    }
    @Test fun spaceKeepsLocalFirstChoiceAndStaleAiButtonCannotCommitAfterAnotherKey()=Fixture().use { f ->
        f.type();f.ai();val old=all(f.keyboard).filterIsInstance<TextView>().single { it.contentDescription=="你好，AI 候选" }
        f.press("space");f.drain();assertEquals("你",f.text.toString())
        old.performClick();f.drain();assertEquals("你",f.text.toString())
        f.type();f.ai();val other=all(f.keyboard).filterIsInstance<TextView>().single { it.contentDescription=="你好，AI 候选" }
        f.press("delete");other.performClick();f.drain();assertEquals("你",f.text.toString())
    }
    @Test fun actualImePolicyRejectsRestrictedPrivatePastedVoiceAndChangedContexts()=Fixture().use { f ->
        f.type();val prefs=Prefs(f.service);prefs.set("cloud",true)
        ReflectionHelpers.setField(f.service,"restricted",false)
        fun allowed()=ReflectionHelpers.callInstanceMethod<Boolean>(f.service,"nineKeyAiAllowed",ClassParameter.from(NineKeyQuery::class.java,NineKeyQuery("64426","")))
        assertTrue(allowed())
        for(field in listOf("restricted","cloudBlocked","voice")) {
            ReflectionHelpers.setField(f.service,field,true);assertFalse(allowed());ReflectionHelpers.setField(f.service,field,false)
        }
        prefs.set("private",true);assertFalse(allowed());prefs.set("private",false)
        prefs.set("ai_t9",false);assertFalse(allowed());prefs.set("ai_t9",true)
        ReflectionHelpers.setField(f.service,"latestText","新位置");assertFalse(allowed())
        ReflectionHelpers.setField(f.service,"restricted",true)
    }
    @Test fun qwertyPinyinAndVoiceCompositionStayAvailableButRawNineKeyFallbackCannotLeak() {
        assertEquals("ni hao",RimeState(raw="nihao",preedit="ni hao").editorComposition(false))
        assertEquals("",RimeState(raw="64426",preedit="64 426").editorComposition(true))
        assertEquals("",RimeState(commit="64426").editorCommit(true))
        assertEquals("你好",RimeState(commit="你好").editorCommit(true))
    }
    @Test fun numberPageCancelsPendingChineseAndCannotSubmitItsOldCandidate()=Fixture().use { f ->
        f.type();f.press("numbers");f.drain()
        assertEquals("",f.text.toString());assertTrue(ReflectionHelpers.getField(f.service,"symbolsMode"))
        assertEquals("",ReflectionHelpers.getField<RimeState>(f.service,"state").raw)
        f.press("1");f.drain();assertEquals("1",f.text.toString())
        f.press("symbols");f.drain();assertFalse(ReflectionHelpers.getField(f.service,"symbolsMode"))
    }
    @Test fun rejectedPersonalWordDoesNotLearnFromAPreviousSuccessfulEdit()=Fixture().use { f ->
        f.type()
        val prefs=Prefs(f.service);prefs.set("learning",true)
        ReflectionHelpers.setField(f.service,"restricted",false)
        val history=ReflectionHelpers.getField<InputHistory>(f.service,"history")
        history.apply(TextEdit(0,0,"小明"),"manual",false,false)
        ReflectionHelpers.setField(f.service,"personal",listOf(Term("小明","nihao",2,false,"manual")))
        f.acceptCommit=false;f.press("personal:小明");f.drain()
        assertEquals("",f.text.toString())
        val chunks=ReflectionHelpers.getField<List<Any>>(history,"chunks")
        assertTrue(ReflectionHelpers.getField<DraftSnapshot>(chunks.single(),"snapshot").choices.isEmpty())
        assertTrue(all(f.keyboard).filterIsInstance<TextView>().any { it.text.contains("未接收") })
        ReflectionHelpers.setField(f.service,"restricted",true)
    }
    @Test fun rejectedVoiceTextCanBeExplicitlyRetriedWithoutDuplicatingIt()=Fixture().use { f ->
        Prefs(f.service).set("private",true)
        f.acceptCommit=false;ReflectionHelpers.setField(f.service,"voice",true)
        ReflectionHelpers.callInstanceMethod<Unit>(f.service,"onSpeech",ClassParameter.from(Int::class.javaPrimitiveType,SpeechWire.FINAL),ClassParameter.from(String::class.java,"你好"))
        assertEquals("你好。",ReflectionHelpers.getField<String>(f.service,"voiceRecovery"))
        assertEquals("",f.text.toString())
        f.acceptCommit=true
        all(f.keyboard).filterIsInstance<TextView>().single { it.text=="重试插入" }.performClick()
        assertEquals("你好。",f.text.toString());assertEquals("",ReflectionHelpers.getField<String>(f.service,"voiceRecovery"))
        ReflectionHelpers.callInstanceMethod<Unit>(f.service,"onSpeech",ClassParameter.from(Int::class.javaPrimitiveType,SpeechWire.DONE),ClassParameter.from(String::class.java,""))
        assertEquals("你好。",f.text.toString())
    }
    @Test fun fourSecondPredictionStillAppearsWhenTheEditorHasNotChanged()=Fixture().use { f ->
        val prefs=Prefs(f.service);prefs.set("cloud",true)
        ReflectionHelpers.setField(f.service,"restricted",false)
        var answer: ((Result<AiResult>)->Unit)?=null
        f.service.completeText={ _,_,cb -> answer=cb;AiCall() }
        f.press("你好");f.drain();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(660))
        assertNotNull(answer);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4000))
        answer!!(Result.success(AiResult("你好",listOf("世界"))))
        assertTrue(all(f.keyboard).filterIsInstance<TextView>().any { it.text.contains("世界") })
        assertEquals("你好",f.text.toString())
        f.service.onFinishInputView(true)
        answer!!(Result.success(AiResult("您好",listOf("过期结果"))))
        assertEquals("你好",f.text.toString())
        ReflectionHelpers.setField(f.service,"restricted",true)
    }
    @Test fun idleWordsNeverAppearAndLocalPredictionsInsertOnlyTheSuffix()=StoreFixture().use { store ->
        store.install()
        Fixture().use { f ->
            ReflectionHelpers.setField(f.service,"restricted",false)
            val terms=listOf(Term("好的","haode",100,false,"manual"),Term("你好世界","nihaoshijie",1,false,"manual"))
            ReflectionHelpers.setField(f.service,"personal",terms)
            fun render()=ReflectionHelpers.callInstanceMethod<Unit>(f.service,"renderCandidates")
            render();assertFalse(all(f.keyboard.findViewWithTag("candidate_strip")).filterIsInstance<TextView>().any { it.text=="好的" })
            f.press("你好");f.drain();ReflectionHelpers.setField(f.service,"personal",terms);render()
            all(f.keyboard.findViewWithTag("candidate_strip")).filterIsInstance<TextView>().single { it.text=="世界" }.performClick()
            assertEquals("你好世界",f.text.toString());assertTrue(ReflectionHelpers.getField(f.service,"cloudBlocked"))
            ReflectionHelpers.setField(f.service,"restricted",true)
        }
    }
    @Test fun cloudEchoesAreRemovedAndStalePredictionClicksCannotInsert()=StoreFixture().use { store ->
        store.install()
        Fixture().use { f ->
            Prefs(f.service).set("cloud",true);ReflectionHelpers.setField(f.service,"restricted",false)
            var answer: ((Result<AiResult>)->Unit)?=null
            f.service.completeText={ _,_,cb -> answer=cb;AiCall() }
            f.press("今天天气");f.drain();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(660))
            answer!!(Result.success(AiResult("今天天气",listOf("今天天气","今天天气很好","天气很好"))))
            val values=ReflectionHelpers.getField<List<String>>(f.service,"suggestions");assertEquals(listOf("很好"),values)
            val word=all(f.keyboard.findViewWithTag("candidate_strip")).filterIsInstance<TextView>().single { it.text=="很好" }
            word.performClick();assertEquals("今天天气很好",f.text.toString())
            word.performClick();assertEquals("今天天气很好",f.text.toString())
            f.press("delete");f.drain();answer!!(Result.success(AiResult("今天天气",listOf("旧结果"))))
            assertTrue(ReflectionHelpers.getField<List<String>>(f.service,"suggestions").isEmpty())
            ReflectionHelpers.setField(f.service,"restricted",true)
        }
    }
    @Test fun alternateCharactersCommitCompositionAndNeverEnterThePinyinEngine()=Fixture().use { f ->
        f.type();f.press("literal:3");f.drain();assertEquals("你3",f.text.toString())
        assertEquals("",ReflectionHelpers.getField<RimeState>(f.service,"state").raw)
        f.press("literal:'");f.drain();assertEquals("你3'",f.text.toString())
        f.press("language");f.press("literal:@");f.drain();assertEquals("你3'@",f.text.toString())
    }
    @Test fun aiCandidateSwitchUsesExistingTextAiConsentAndPersistsWhenDisabled() {
        val c=org.robolectric.RuntimeEnvironment.getApplication()
        val life=Robolectric.buildActivity(SettingsActivity::class.java,android.content.Intent(c,SettingsActivity::class.java).putExtra("page","api")).setup()
        try {
            val toggle=all(life.get().window.decorView).filterIsInstance<android.widget.Switch>().single { it.text=="AI 九宫格候选" }
            assertTrue(toggle.isChecked);assertFalse(Prefs(c).cloud)
            toggle.performClick();assertFalse(Prefs(c).flag("ai_t9",true))
            assertEquals(1,all(life.get().window.decorView).filterIsInstance<android.widget.EditText>().size)
        } finally { life.pause().stop().destroy() }
    }
}
