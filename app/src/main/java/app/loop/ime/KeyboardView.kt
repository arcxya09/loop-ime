package app.loop.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.*
import android.widget.*

class KeyboardView(c: Context,private val key: (String)->Unit) : LinearLayout(c) {
    private val green=UiPalette.green(c)
    private val surface=UiPalette.surface(c)
    private val keySurface=UiPalette.card(c)
    private val secondary=UiPalette.secondary(c)
    private val mint=UiPalette.mint(c)
    private val ink=UiPalette.ink(c)
    private val muted=UiPalette.muted(c)
    private val status=TextView(c)
    private val preedit=TextView(c)
    private val notice=TextView(c)
    private val toolbarButtons=mutableListOf<View>()
    private val cloudRow=LinearLayout(c)
    private val cloudCandidates=LinearLayout(c)
    private var predictionValues=emptyList<Pair<String,()->Unit>>()
    private var deferredPredictions: List<Pair<String,()->Unit>>?=null
    private lateinit var aiButton: KeyboardIcon
    private lateinit var toolsButton: KeyboardIcon
    private var aiMessage=""
    private val clearAiMessage=Runnable {
        aiMessage="";updateToolsHint()
    }
    private val candidates=LinearLayout(c)
    private var touchingCandidates=false
    private var deferredCandidates: Pair<List<Pair<String,()->Unit>>,Set<String>>?=null
    private val candidateScroll=object: HorizontalScrollView(c) {
        private var downX=0f
        private var downY=0f
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if(event.actionMasked==MotionEvent.ACTION_DOWN) { touchingCandidates=true;downX=event.x;downY=event.y }
            if(event.actionMasked==MotionEvent.ACTION_MOVE && event.y-downY>dp(24) && event.y-downY>kotlin.math.abs(event.x-downX)) {
                val cancel=MotionEvent.obtain(event).apply { action=MotionEvent.ACTION_CANCEL }
                try { super.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
                touchingCandidates=false;flushDeferredCandidates();setExpanded(true);return true
            }
            return try { super.dispatchTouchEvent(event) } finally {
                if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL) {
                    touchingCandidates=false
                    post { flushDeferredCandidates() }
                }
            }
        }
    }
    private val body=LinearLayout(c)
    private val panelHeader=LinearLayout(c)
    private lateinit var toolbar: LinearLayout
    var panelOpen=false;private set
    private var hand="off"
    private var inputKind="text"
    private var previewDialog: android.app.AlertDialog?=null
    fun dismissPreview() { previewDialog?.dismiss();previewDialog=null }
    private var gestureEnabled=true
    private val content=FrameLayout(c)
    private val expandedWords=CandidateFlowLayout(c)
    private val expandedScroll=object: ScrollView(c) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if(event.actionMasked==MotionEvent.ACTION_DOWN)touchingCandidates=true
            return try { super.dispatchTouchEvent(event) } finally {
                if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL) {
                    touchingCandidates=false
                    post { flushDeferredCandidates() }
                }
            }
        }
    }
    private lateinit var expandButton: KeyboardIcon
    private var expanded=false
    private var candidateValues=emptyList<Pair<String,()->Unit>>()
    private var candidateAiTexts=emptySet<String>()
    private var hasMore=false
    private var loadingMore=false
    private lateinit var micButton: KeyboardIcon
    private lateinit var clipButton: KeyboardIcon
    private lateinit var separator: TextView
    private var chinese=true
    private var nineKey=true
    private var heightPreset=KeyboardHeight.HIGH
    private var symbols=false
    private var shift=false
    private var isVoice=false
    private var enterLabel="换行"
    private var statusText="长按空格说话 · 松开结束"
    private var statusAction: (()->Unit)?=null
    private var hasCandidates=false
    private var composing=false
    private var compositionText=""
    private var showNotice=false
    private val clearNotice=Runnable { showNotice=false;updateStrip() }
    private var spaceKey: HoldSpaceKey?=null
    private var pendingRender=false
    private var discardMultiTouch=false
    init {
        orientation=VERTICAL;layoutDirection=View.LAYOUT_DIRECTION_LTR
        setBackgroundColor(surface);setPadding(dp(6),dp(4),dp(6),dp(6));isFocusable=false
        toolbar=LinearLayout(c).apply { gravity=Gravity.CENTER_VERTICAL;tag="keyboard_toolbar" }
        fun tool(code: String,description: String,action: ()->Unit): KeyboardIcon {
            val v=icon(code,description,Color.TRANSPARENT,action)
            toolbarButtons+=v
            toolbar.addView(v,LayoutParams(dp(42),dp(42)));return v
        }
        toolsButton=tool("tools","Loop 工具与设置") { key("tools") }
        toolsButton.setOnLongClickListener {
            if(showNotice) { showStatus();true } else if(aiMessage.isNotEmpty()) { Toast.makeText(context,aiMessage,Toast.LENGTH_LONG).show();true } else false
        }
        val strip=FrameLayout(c).apply { tag="candidate_strip" }
        strip.setOnLongClickListener { candidateMenu(strip);true }
        labelStyle(status,11,9);status.setTextColor(muted);status.gravity=Gravity.CENTER_VERTICAL
        status.setPadding(dp(5),0,dp(5),0);status.tag="keyboard_status"
        status.setOnClickListener { showStatus() }
        candidates.orientation=HORIZONTAL;candidateScroll.isHorizontalScrollBarEnabled=false
        candidateScroll.addView(candidates,ViewGroup.LayoutParams(-2,-1))
        strip.addView(status,FrameLayout.LayoutParams(-1,-1))
        labelStyle(preedit,11,10);preedit.setTextColor(green);preedit.gravity=Gravity.CENTER_VERTICAL
        preedit.setPadding(dp(5),0,dp(5),0);preedit.tag="keyboard_preedit";preedit.visibility=GONE
        preedit.ellipsize=TextUtils.TruncateAt.START
        preedit.setOnClickListener { Toast.makeText(context,compositionText,Toast.LENGTH_LONG).show() }
        preedit.setOnLongClickListener { candidateMenu(preedit);true }
        strip.addView(preedit,FrameLayout.LayoutParams(-1,dp(16),Gravity.TOP))
        strip.addView(candidateScroll,FrameLayout.LayoutParams(-1,dp(28),Gravity.BOTTOM))
        toolbar.addView(strip,LayoutParams(0,-1,1f))
        expandButton=tool("expand_candidates","展开全部候选词") { setExpanded(!expanded) }
        separator=action("分词","separator",10,Color.TRANSPARENT).apply { setTextColor(muted);contentDescription="拼音分词" }
        toolbarButtons+=separator
        toolbar.addView(separator,LayoutParams(dp(34),dp(42)))
        aiButton=tool("ai_settings","AI 设置") { key("ai_settings") }
        clipButton=tool("clipboard","剪贴板") { key(if(isVoice)"cancel_voice" else "clipboard") }
        micButton=tool("mic","语音输入") { key("mic") }
        tool("hide","收起键盘") { key("hide") }
        addView(toolbar,LayoutParams(-1,dp(44)))
        panelHeader.gravity=Gravity.CENTER_VERTICAL;panelHeader.tag="panel_header";panelHeader.visibility=GONE
        addView(panelHeader,LayoutParams(-1,dp(44)))
        cloudRow.tag="cloud_predictions";cloudRow.gravity=Gravity.CENTER_VERTICAL;cloudRow.visibility=GONE
        cloudRow.addView(TextView(c).apply { text="AI";setTextColor(muted);labelStyle(this,11);gravity=Gravity.CENTER },LayoutParams(dp(28),-1))
        cloudRow.addView(cloudCandidates,LayoutParams(-2,-1))
        notice.tag="keyboard_notice";notice.setTextColor(muted);labelStyle(notice,11);notice.setPadding(dp(8),0,dp(8),0)
        notice.gravity=Gravity.CENTER_VERTICAL;notice.visibility=GONE
        notice.setOnClickListener { showStatus() }
        strip.addView(notice,FrameLayout.LayoutParams(-1,dp(16),Gravity.TOP))
        body.orientation=VERTICAL;body.tag="keyboard_body";content.addView(body,FrameLayout.LayoutParams(-1,-1))
        expandedScroll.tag="expanded_candidates";expandedScroll.visibility=GONE;expandedScroll.isFillViewport=true
        expandedScroll.addView(expandedWords,ViewGroup.LayoutParams(-1,-2));content.addView(expandedScroll,FrameLayout.LayoutParams(-1,-1))
        expandedScroll.setOnScrollChangeListener { _,_,y,_,_ ->
            if(expanded && y>0 && expandedWords.height-y-expandedScroll.height<dp(60))requestMore()
        }
        addView(content,LayoutParams(-1,dp(heightPreset.padDp+4)))
        render();voice(false)
        setOnApplyWindowInsetsListener { _,insets ->
            // Android draws the switcher. This is only its safe area, with no app footer or globe.
            val safe=insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.captionBar() or WindowInsets.Type.displayCutout() or WindowInsets.Type.mandatorySystemGestures())
            setPadding(dp(6)+safe.left,dp(4),dp(6)+safe.right,dp(6)+safe.bottom);insets
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow();requestApplyInsets() }
    override fun onDetachedFromWindow() { dismissPreview();removeCallbacks(clearNotice);removeCallbacks(clearAiMessage);showNotice=false;clearAiMessage.run();super.onDetachedFromWindow() }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN)discardMultiTouch=false
        if(event.actionMasked==MotionEvent.ACTION_POINTER_DOWN && spaceKey?.tracking==true) { discardMultiTouch=true;cancelSpaceGesture() }
        if(discardMultiTouch) {
            if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL)discardMultiTouch=false
            return true
        }
        return super.dispatchTouchEvent(event)
    }
    private fun dp(x: Int)=(x*resources.displayMetrics.density).toInt()
    private fun bg(color: Int,radius: Int=9)=GradientDrawable().apply { setColor(color);cornerRadius=dp(radius).toFloat() }
    private fun keyBg(color: Int,radius: Int=9)=StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed),bg(if(UiPalette.dark(context))mint else 0xffd1e2d9.toInt(),radius))
        addState(intArrayOf(),bg(color,radius))
    }
    private fun labelStyle(v: TextView,maxSize: Int,minSize: Int=maxSize-3) {
        v.setTextSize(TypedValue.COMPLEX_UNIT_DIP,maxSize.toFloat());v.includeFontPadding=false
        v.setSingleLine(true);v.setHorizontallyScrolling(false);v.ellipsize=TextUtils.TruncateAt.END
        v.setAutoSizeTextTypeUniformWithConfiguration(minSize.coerceAtLeast(8),maxSize,1,TypedValue.COMPLEX_UNIT_DIP)
        v.typeface=Typeface.create("sans-serif",Typeface.NORMAL)
    }
    private fun button(label: String,size: Int=17,color: Int=keySurface,action: ()->Unit): TextView = TextView(context).apply {
        text=label;contentDescription=label;labelStyle(this,size);gravity=Gravity.CENTER;setTextColor(ink);background=keyBg(color)
        setPadding(dp(4),0,dp(4),0);isClickable=true;isFocusable=false
        setOnClickListener { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);action() }
    }
    private fun icon(code: String,description: String,color: Int=secondary,action: ()->Unit)=KeyboardIcon(context,code).apply {
        tag=code;contentDescription=description;tint=ink;background=keyBg(color)
        setOnClickListener { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);action() }
    }
    private fun action(label: String,code: String,size: Int=17,color: Int=keySurface): TextView = button(label,size,color) { press(code) }.apply { tag=code }
    private fun alternate(label: String,code: String,other: String,size: Int=17): TextView = AlternateKey(context,other) { press("literal:$other") }.apply {
        text=label;tag=code;contentDescription="$label，长按输入 $other";labelStyle(this,size)
        gravity=Gravity.CENTER;setTextColor(ink);background=keyBg(keySurface);setPadding(dp(4),dp(2),dp(4),0);isFocusable=false
        setOnClickListener { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);press(code) }
    }
    private fun candidateMenu(anchor: View) {
        PopupMenu(context,anchor).apply {
            menu.add(if(expanded)"返回键盘" else "展开候选").setOnMenuItemClickListener { setExpanded(!expanded);true }
            if(composing && chinese)menu.add("拼音分词").setOnMenuItemClickListener { key("separator");true }
            if(showNotice)menu.add(statusText).setOnMenuItemClickListener { showStatus();true }
            if(aiMessage.isNotEmpty())menu.add(aiMessage).setOnMenuItemClickListener { Toast.makeText(context,aiMessage,Toast.LENGTH_LONG).show();true }
            menu.add("Loop 工具").setOnMenuItemClickListener { key("tools");true }
            menu.add("收起键盘").setOnMenuItemClickListener { key("hide");true }
            show()
        }
    }
    private fun deleteKey(): View=icon("delete","删除") { press("delete") }.apply {
        val repeat=object: Runnable { override fun run() { key("delete");postDelayed(this,65) } }
        setOnTouchListener { _,e -> when(e.actionMasked) { MotionEvent.ACTION_DOWN->postDelayed(repeat,380);MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->removeCallbacks(repeat) };false }
        addOnAttachStateChangeListener(object: OnAttachStateChangeListener { override fun onViewAttachedToWindow(v: View) {};override fun onViewDetachedFromWindow(v: View) { removeCallbacks(repeat) } })
    }
    private fun space(): HoldSpaceKey=HoldSpaceKey(context,
        { active -> key(if(active)"voice_hold_start" else "voice_hold_end") },
        { if(pendingRender)render() }, { key("mic") }, { left -> if(gestureEnabled)key(if(left)"left" else "right") }, { gestureEnabled }).apply {
        text="按住说话";labelStyle(this,11,9);gravity=Gravity.CENTER;setTextColor(muted);background=keyBg(keySurface);isFocusable=false
        setPadding(dp(4),0,dp(4),0)
        setOnClickListener { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);if(isVoice)key("mic") else press("space") }
        spaceKey=this
    }
    private fun modeName()=if(symbols)"数字 / 符号" else if(!chinese)"英文" else if(nineKey)"九宫格" else "全键盘"
    private fun isIdleHint(s: String)=s.startsWith("简体九宫格 ·") || s.startsWith("简体全键盘 ·") ||
        s.startsWith("简体拼音已就绪") || s=="拼音已就绪" || s=="英文 · 长按空格说话" || s.startsWith("长按空格说话")
    private fun updateStrip() {
        toolbar.visibility=if(panelOpen)GONE else VISIBLE
        panelHeader.visibility=if(panelOpen)VISIBLE else GONE
        status.text=if(showNotice)statusText else modeName()
        status.contentDescription=statusText
        val showCandidates=hasCandidates && !isVoice
        val showPreedit=composing && compositionText.isNotBlank() && chinese && !symbols && !isVoice
        preedit.text=compositionText;preedit.contentDescription="拼音：$compositionText"
        preedit.visibility=if(showPreedit)VISIBLE else GONE
        val statusParams=status.layoutParams as FrameLayout.LayoutParams
        val statusHeight=if(showPreedit)dp(28) else -1
        if(statusParams.height!=statusHeight) { statusParams.height=statusHeight;statusParams.gravity=Gravity.BOTTOM;status.layoutParams=statusParams }
        if(showPreedit && !showCandidates)status.text="暂无候选词"
        status.visibility=if(showCandidates)View.GONE else View.VISIBLE
        candidateScroll.visibility=if(showCandidates)View.VISIBLE else View.GONE
        expandButton.visibility=if(showCandidates)VISIBLE else GONE
        notice.text=statusText;notice.contentDescription=statusText
        notice.visibility=if(showNotice && showCandidates && !showPreedit)VISIBLE else GONE
        separator.visibility=if(showCandidates && chinese && !symbols)View.VISIBLE else View.GONE
        aiButton.visibility=if(showCandidates || isVoice)View.GONE else View.VISIBLE
        clipButton.visibility=if(showCandidates)View.GONE else View.VISIBLE
        // The entire toolbar width belongs to candidates; gestures/menu retain secondary actions.
        toolbarButtons.forEach { it.visibility=if(showCandidates)GONE else VISIBLE }
        if(!showCandidates) { expandButton.visibility=GONE;separator.visibility=GONE;aiButton.visibility=if(isVoice)GONE else VISIBLE }
        updateToolsHint()
    }
    private fun updateToolsHint() {
        val message=if(showNotice)statusText else aiMessage
        toolsButton.tint=if(message.isEmpty())ink else 0xffa06520.toInt()
        toolsButton.contentDescription=if(message.isEmpty())"Loop 工具与设置" else "Loop 工具与设置；$message；长按查看或操作"
        toolsButton.invalidate()
    }
    private fun showStatus() {
        val action=statusAction
        if(showNotice && action!=null)action() else Toast.makeText(context,if(showNotice)statusText else modeName(),Toast.LENGTH_LONG).show()
    }
    /** All transient feedback stays inside the fixed toolbar, including actionable notices. */
    fun aiStatus(s: String) {
        aiMessage=s;updateToolsHint();removeCallbacks(clearAiMessage);postDelayed(clearAiMessage,8000)
    }
    fun status(s: String,action: (()->Unit)?=null) {
        statusText=s;statusAction=action;removeCallbacks(clearNotice)
        showNotice=s.isNotBlank() && !isIdleHint(s)
        updateStrip();if(showNotice && action==null)postDelayed(clearNotice,3500)
    }
    fun composition(preedit: String) {
        composing=preedit.isNotBlank();compositionText=preedit;updateStrip()
    }
    fun endComposition() { composing=false;compositionText="";updateStrip() }
    fun setMode(cn: Boolean,sym: Boolean=false) { composing=false;compositionText="";chinese=cn;symbols=sym;render() }
    fun setNineKey(nine: Boolean) { nineKey=nine;render() }
    fun setHeightPreset(preset: KeyboardHeight) { if(heightPreset!=preset) { heightPreset=preset;render() } }
    fun setEnter(s: String) { enterLabel=s;render() }
    fun cancelSpaceGesture() { spaceKey?.cancelGesture() }
    fun voice(active: Boolean) {
        isVoice=active
        if(active) { composing=false;compositionText="" }
        // Keep the same keyboard, space view, and height throughout both recording gestures.
        micButton.glyph=if(active)"stop" else "mic";micButton.contentDescription=if(active)"结束语音" else "语音输入"
        micButton.tint=if(active)0xffffffff.toInt() else green;micButton.background=keyBg(if(active)green else mint,21)
        clipButton.glyph=if(active)"cancel" else "clipboard";clipButton.contentDescription=if(active)"取消语音尾句" else "剪贴板";clipButton.invalidate()
        if(spaceKey?.holding!=true)spaceKey?.text=if(active)"结束语音" else "按住说话"
        spaceKey?.setTextColor(if(active)green else muted)
        clearCandidates()
    }
    private fun flushDeferredCandidates() {
        if(touchingCandidates)return
        deferredCandidates?.let { deferredCandidates=null;setCandidates(it.first,it.second) }
        deferredPredictions?.let { deferredPredictions=null;setPredictions(it) }
    }
    private fun clearCandidates() {
        touchingCandidates=false;deferredCandidates=null;deferredPredictions=null
        predictionValues=emptyList();setCandidates(emptyList())
    }
    fun setPredictions(values: List<Pair<String,()->Unit>>) {
        if(touchingCandidates) { deferredPredictions=values.toList();return }
        deferredPredictions=null;predictionValues=values.toList();renderCandidates()
    }
    fun candidatePaging(more: Boolean,loading: Boolean=false) {
        if(hasMore==more && loadingMore==loading)return
        hasMore=more;loadingMore=loading
        if(expanded && !touchingCandidates)renderExpanded()
    }
    private fun requestMore() { if(hasMore && !loadingMore) { loadingMore=true;key("more_candidates") } }
    private fun setExpanded(value: Boolean) {
        expanded=value && hasCandidates && !isVoice
        if(expanded)expandedScroll.layoutParams.height=dp(heightPreset.padDp+4)
        body.visibility=if(expanded)INVISIBLE else VISIBLE;expandedScroll.visibility=if(expanded)VISIBLE else GONE
        expandButton.glyph=if(expanded)"collapse_candidates" else "expand_candidates";expandButton.invalidate()
        expandButton.contentDescription=if(expanded)"收起候选词" else "展开全部候选词"
        if(expanded) { renderExpanded();expandedScroll.scrollTo(0,0) }
    }
    private fun renderExpanded() {
        expandedWords.removeAllViews()
        expandedWords.addView(button("返回键盘",13,secondary) { setExpanded(false) },ViewGroup.LayoutParams(-2,dp(44)))
        candidateValues.forEachIndexed { i,(text,choose) -> expandedWords.addView(button(text,17,if(i==0)mint else Color.TRANSPARENT) {
            setExpanded(false);choose()
        }.apply { setPadding(dp(14),0,dp(14),0);minimumWidth=dp(56);setTextColor(if(text in candidateAiTexts)green else ink) },ViewGroup.LayoutParams(-2,dp(44))) }
        predictionValues.forEach { (text,choose) -> expandedWords.addView(button(text,17,Color.TRANSPARENT) {
            setExpanded(false);choose()
        }.apply { setPadding(dp(14),0,dp(14),0);minimumWidth=dp(56);setTextColor(green);contentDescription="$text，AI 候选" },ViewGroup.LayoutParams(-2,dp(44))) }
        if(hasMore)expandedWords.addView(button(if(loadingMore)"正在加载…" else "更多候选词",13,secondary) { requestMore() },ViewGroup.LayoutParams(-2,dp(44)))
    }
    fun setCandidates(values: List<Pair<String,()->Unit>>,aiTexts: Set<String> = emptySet()) {
        // A late cloud answer must not replace or move the word currently under a finger.
        if(touchingCandidates) { deferredCandidates=values.toList() to aiTexts.toSet();return }
        deferredCandidates=null
        val previous=candidateValues.map { it.first }
        candidateValues=values.toList();candidateAiTexts=aiTexts.toSet()
        renderCandidates()
        if(previous!=values.map { it.first } && previous!=values.take(previous.size).map { it.first }) {
            candidateScroll.scrollTo(0,0);expandedScroll.scrollTo(0,0)
        }
    }
    private fun renderCandidates() {
        val values=candidateValues;val aiTexts=candidateAiTexts
        candidates.removeAllViews();hasCandidates=values.isNotEmpty() || predictionValues.isNotEmpty()
        values.forEachIndexed { i,(text,choose) ->
            candidates.addView(button(text,16,if(i==0)mint else Color.TRANSPARENT,choose).apply {
                setTextColor(if(i==0 || text in aiTexts)green else ink);setPadding(dp(12),0,dp(12),0)
                if(text in aiTexts)contentDescription="$text，AI 候选"
                setOnLongClickListener { candidateMenu(this);true }
            },LayoutParams(-2,-1).apply { rightMargin=dp(4) })
        }
        cloudCandidates.removeAllViews()
        predictionValues.forEach { (text,choose) -> cloudCandidates.addView(button(text,15,Color.TRANSPARENT,choose).apply {
            setTextColor(green);setPadding(dp(12),0,dp(12),0);contentDescription="$text，AI 候选"
            setOnLongClickListener { candidateMenu(this);true }
        },LayoutParams(-2,-1)) }
        cloudRow.visibility=if(predictionValues.isNotEmpty())VISIBLE else GONE
        candidates.addView(cloudRow,LayoutParams(-2,-1))
        if(!hasCandidates)setExpanded(false) else if(expanded)renderExpanded()
        updateStrip()
    }
    private fun render() {
        if(spaceKey?.tracking==true) { pendingRender=true;return }
        pendingRender=false;spaceKey=null;body.removeAllViews()
        panelOpen=false;dismissPreview()
        applyHand()
        content.layoutParams=LayoutParams(-1,dp(heightPreset.padDp+4))
        if(symbols && inputKind in setOf("number","phone","decimal","date"))renderNumeric() else if(chinese && nineKey && !symbols)renderNineKey() else renderFullKeys()
        if(expanded)setExpanded(false)
        updateStrip()
    }
    private fun renderNineKey() {
        val pad=LinearLayout(context).apply { orientation=HORIZONTAL;tag="nine_key_pad" }
        val height=dp(heightPreset.padDp);val gap=dp(5);val rowHeight=(height-gap*3)/4
        val left=LinearLayout(context).apply { orientation=VERTICAL;tag="punctuation_column" }
        val punctuation=LinearLayout(context).apply { orientation=VERTICAL;background=bg(secondary) }
        listOf("，","。","？","！").forEachIndexed { index,mark ->
            punctuation.addView(action(mark,mark,18,Color.TRANSPARENT),LayoutParams(-1,0,1f))
            if(index<3)punctuation.addView(View(context).apply { setBackgroundColor(0xffcfd5dd.toInt()) },LayoutParams(-1,1).apply { leftMargin=dp(9);rightMargin=dp(9) })
        }
        left.addView(punctuation,LayoutParams(-1,rowHeight*3+gap*2).apply { bottomMargin=gap })
        left.addView(action("符号","symbols",14,secondary),LayoutParams(-1,height-rowHeight*3-gap*3))
        pad.addView(left,LayoutParams(0,height,0.78f).apply { rightMargin=gap })
        val center=LinearLayout(context).apply { orientation=VERTICAL;tag="letter_grid" }
        val labels=listOf("@#","ABC","DEF","GHI","JKL","MNO","PQRS","TUV","WXYZ")
        repeat(3) { rowIndex ->
            val row=LinearLayout(context)
            repeat(3) { col ->
                val n=rowIndex*3+col+1;val code=if(n==1)"punctuation" else "t9:$n"
                val v=alternate(labels[n-1],code,n.toString(),if(n>=7)17 else 18)
                row.addView(v,LayoutParams(0,-1,1f).apply { if(col<2)rightMargin=gap })
            }
            center.addView(row,LayoutParams(-1,rowHeight).apply { bottomMargin=gap })
        }
        val bottom=LinearLayout(context)
        bottom.addView(alternate("123","numbers","0",16),LayoutParams(0,-1,0.7f).apply { rightMargin=gap })
        bottom.addView(space(),LayoutParams(0,-1,1.9f).apply { rightMargin=gap })
        bottom.addView(action("中/英","language",12),LayoutParams(0,-1,0.85f))
        center.addView(bottom,LayoutParams(-1,height-rowHeight*3-gap*3))
        pad.addView(center,LayoutParams(0,height,3f).apply { rightMargin=gap })
        val right=LinearLayout(context).apply { orientation=VERTICAL;tag="action_column" }
        right.addView(deleteKey(),LayoutParams(-1,rowHeight).apply { bottomMargin=gap })
        right.addView(action("重输","retype",14,secondary),LayoutParams(-1,rowHeight).apply { bottomMargin=gap })
        right.addView(action(enterLabel,"enter",15,mint).apply { setTextColor(green) },LayoutParams(-1,height-rowHeight*2-gap*2))
        pad.addView(right,LayoutParams(0,height,0.88f));body.addView(pad,LayoutParams(-1,height).apply { topMargin=dp(4) })
    }
    private fun renderFullKeys() {
        if(symbols) {
            row("1234567890".map { it.toString() to it.toString() })
            row(listOf("@","#","¥","%","&","*","(",")","-","+").map { it to it })
            row(listOf("。","，","？","！","：","；","/","\"","⌫").map { it to if(it=="⌫")"delete" else it })
        } else {
            row("qwertyuiop".map { (if(shift)it.uppercaseChar() else it).toString() to (if(shift)it.uppercaseChar() else it).toString() })
            row("asdfghjkl".map { (if(shift)it.uppercaseChar() else it).toString() to (if(shift)it.uppercaseChar() else it).toString() },margin=dp(10))
            row(listOf("⇧" to "shift")+"zxcvbnm".map { (if(shift)it.uppercaseChar() else it).toString() to (if(shift)it.uppercaseChar() else it).toString() }+listOf("⌫" to "delete"))
        }
        val bottom=LinearLayout(context)
        for((label,code) in listOf((if(symbols)"ABC" else "123") to "symbols",(if(inputKind=="email")"@" else if(inputKind=="url")"/" else if(chinese)"，" else ",") to (if(inputKind=="email")"literal:@" else if(inputKind=="url")"literal:/" else "comma"),"空格" to "space",(if(chinese)"。" else ".") to "period",(if(chinese)"中/英" else "英/中") to "language",enterLabel to "enter")) {
            val v=if(code=="space")space() else action(label,code,12,if(code=="enter")mint else keySurface)
            bottom.addView(v,LayoutParams(0,-1,if(code=="space")3.5f else 1.2f).apply { setMargins(dp(2),0,dp(2),0) })
        };addFullRow(bottom)
    }
    private fun row(keys: List<Pair<String,String>>,margin: Int=0) {
        val row=LinearLayout(context).apply { setPadding(margin,0,margin,0) }
        keys.forEach { (label,code) ->
            val index="qwertyuiopasdfghjklzxcvbnm".indexOf(code.lowercase())
            val other=if(!chinese && !symbols && code.length==1 && index>=0)"1234567890@#$%&*()-!\"':;?/"[index].toString() else null
            val v=if(code=="delete")deleteKey() else if(other!=null)alternate(label,code,other) else action(label,code)
            row.addView(v,LayoutParams(0,-1,1f).apply { setMargins(dp(2),0,dp(2),0) })
        };addFullRow(row)
    }
    private fun addFullRow(row: View) {
        val index=body.childCount;val height=dp(heightPreset.padDp);val gap=dp(5);val regular=(height-gap*3)/4
        body.addView(row,LayoutParams(-1,if(index==3)height-regular*3-gap*3 else regular).apply { topMargin=if(index==0)dp(4) else gap })
    }
    private fun press(code: String) {
        if(isVoice && code !in setOf("space","mic"))return
        if(code=="shift") { shift=!shift;render();return }
        key(code)
    }
    fun setInputKind(kind: String) { inputKind=kind;render() }
    fun setHand(value: String) { hand=value.takeIf { it in setOf("left","right") } ?: "off";applyHand() }
    fun setCursorGesture(enabled: Boolean) { gestureEnabled=enabled }
    private fun applyHand() {
        val width=resources.displayMetrics.widthPixels
        val inset=if(!panelOpen && hand!="off")minOf((width*.18f).toInt(),dp(84)) else 0
        body.setPadding(if(hand=="right")inset else 0,0,if(hand=="left")inset else 0,0)
    }
    private fun renderNumeric() {
        val decimal=if(inputKind=="phone")"+" else if(inputKind=="date")"/" else "."
        listOf(listOf("1","2","3","delete"),listOf("4","5","6","-"),listOf("7","8","9",decimal),listOf("symbols","0","space","enter")).forEach { codes ->
            val row=LinearLayout(context)
            codes.forEach { code -> val v=when(code) { "delete"->deleteKey();"space"->action("空格",code,13);"enter"->action(enterLabel,code,13,mint);"symbols"->action("ABC",code,13);else->action(code,code) }
                row.addView(v,LayoutParams(0,-1,1f).apply { setMargins(dp(2),0,dp(2),0) }) }
            addFullRow(row)
        }
    }
    fun closePanel() { render() }
    private fun beginPanel(title: String,back: String) {
        dismissPreview()
        cancelSpaceGesture();spaceKey=null;setExpanded(false);panelOpen=true;body.removeAllViews();applyHand()
        content.layoutParams=LayoutParams(-1,dp(heightPreset.padDp+4));panelHeader.removeAllViews()
        panelHeader.addView(button("‹",26,Color.TRANSPARENT) { key(back) }.apply { contentDescription=if(back=="panel_close")"返回键盘" else "返回工具面板" },LayoutParams(dp(48),-1))
        panelHeader.addView(TextView(context).apply { text=title;textSize=14f;setTextColor(ink);gravity=Gravity.CENTER_VERTICAL;setSingleLine();ellipsize=TextUtils.TruncateAt.END },LayoutParams(0,-1,1f))
        if(back!="panel_close")panelHeader.addView(button("⌨",20,Color.TRANSPARENT) { key("panel_close") }.apply { contentDescription="返回键盘" },LayoutParams(dp(48),-1))
        else panelHeader.addView(icon("settings","全部设置",Color.TRANSPARENT) { key("settings") },LayoutParams(dp(48),-1))
        updateStrip()
    }
    fun tools(actions: List<PanelAction>,privacy: List<PanelAction>) {
        beginPanel("Loop 工具","panel_close")
        val large=resources.configuration.fontScale>1.15f || resources.configuration.screenWidthDp<340
        val columns=if(large)3 else 4
        val list=LinearLayout(context).apply { orientation=VERTICAL }
        val scroll=ScrollView(context).apply { isFillViewport=true;addView(list) }
        body.addView(scroll,LayoutParams(-1,0,1f))
        val tileHeight=if(large)dp(68) else (dp(heightPreset.padDp+4)-dp(64))/2
        actions.chunked(columns).forEach { items ->
            val row=LinearLayout(context)
            items.forEach { a ->
                val tile=LinearLayout(context).apply { orientation=VERTICAL;gravity=Gravity.CENTER;background=keyBg(keySurface,12);tag="tool:"+a.code;contentDescription=a.label;isClickable=true;isFocusable=true;isEnabled=a.enabled
                    setOnClickListener { key(a.code) }
                    addView(KeyboardIcon(context,a.icon).apply { tint=green;isClickable=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO },LayoutParams(dp(24),dp(24)))
                    addView(TextView(context).apply { text=a.label;textSize=11f;setTextColor(ink);gravity=Gravity.CENTER;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO },LayoutParams(-1,-2)) }
                row.addView(tile,LayoutParams(0,-1,1f).apply { setMargins(dp(2),dp(2),dp(2),dp(2)) })
            }
            repeat(columns-items.size) { row.addView(Space(context),LayoutParams(0,1,1f)) }
            list.addView(row,LayoutParams(-1,tileHeight+dp(4)))
        }
        val footer=LinearLayout(context)
        privacy.forEach { a -> footer.addView(panelButton(a),LayoutParams(0,dp(48),1f).apply { setMargins(dp(2),dp(4),dp(2),dp(4)) }) }
        body.addView(footer,LayoutParams(-1,dp(56)))
    }
    private fun panelButton(a: PanelAction): TextView=button(a.label,14,if(a.selected)mint else keySurface) { key(a.code) }.apply {
        tag="panel:"+a.code;isEnabled=a.enabled;alpha=if(a.enabled)1f else .45f;isFocusable=true
        setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE);textSize=13f;setSingleLine(false);maxLines=3
        contentDescription=a.label+if(a.selected)"，已选中" else "";isSelected=a.selected
    }
    fun actionPanel(title: String,actions: List<PanelAction>,columns: Int=3,back: String="tools") {
        beginPanel(title,back)
        val list=LinearLayout(context).apply { orientation=VERTICAL }
        val actual=if(resources.configuration.fontScale>1.3f)minOf(2,columns) else columns
        actions.chunked(actual).forEach { items ->
            val row=LinearLayout(context)
            items.forEach { a -> row.addView(panelButton(a),LayoutParams(0,dp(if(resources.configuration.fontScale>1.3f)72 else 52),1f).apply { setMargins(dp(2),dp(2),dp(2),dp(2)) }) }
            repeat(actual-items.size) { row.addView(Space(context),LayoutParams(0,1,1f)) };list.addView(row)
        }
        body.addView(ScrollView(context).apply { addView(list) },LayoutParams(-1,-1))
    }
    fun cards(title: String,cards: List<PanelCard>,empty: String="暂无内容",back: String="tools",extra: List<PanelAction> = emptyList()) {
        beginPanel(title,back)
        val list=LinearLayout(context).apply { orientation=VERTICAL }
        extra.forEach { list.addView(panelButton(it),LayoutParams(-1,dp(48)).apply { bottomMargin=dp(4) }) }
        if(cards.isEmpty())list.addView(TextView(context).apply { text=empty;textSize=14f;setTextColor(muted);setPadding(dp(12),dp(12),dp(12),dp(12)) })
        cards.forEach { card ->
            val box=LinearLayout(context).apply { orientation=VERTICAL;background=bg(keySurface,12);setPadding(dp(10),dp(4),dp(10),dp(4)) }
            box.addView(TextView(context).apply { text=card.title;textSize=11f;setTextColor(muted) })
            box.addView(button(card.text,15) { key("preview_unused") }.apply {
                setOnClickListener { dismissPreview();val dialog=android.app.AlertDialog.Builder(context).setTitle(card.title).setMessage(card.text).setPositiveButton("插入") { _,_->card.insert() }.setNegativeButton("关闭",null).create()
                    previewDialog=dialog;dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG);dialog.window?.attributes=dialog.window?.attributes?.apply { token=windowToken };dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE);dialog.show() }
                setSingleLine(false);maxLines=2;setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE);textSize=15f;gravity=Gravity.CENTER_VERTICAL
                contentDescription="预览："+card.text
            },LayoutParams(-1,dp(56)))
            val actions=LinearLayout(context)
            (listOf("插入" to card.insert)+card.actions).forEach { (label,action) -> actions.addView(button(label,13,secondary,action),LayoutParams(0,dp(48),1f).apply { setMargins(dp(2),dp(2),dp(2),dp(2)) }) }
            box.addView(actions);list.addView(box,LayoutParams(-1,-2).apply { bottomMargin=dp(6) })
        }
        body.addView(ScrollView(context).apply { addView(list) },LayoutParams(-1,-1))
    }
    fun panel(title: String,entries: List<Pair<String,()->Unit>>) {
        beginPanel(title,"panel_close")
        val list=LinearLayout(context).apply { orientation=VERTICAL }
        entries.forEach { (label,choose) -> list.addView(button(label,14,keySurface,choose),LayoutParams(-1,dp(52)).apply { topMargin=dp(4) }) }
        body.addView(ScrollView(context).apply { addView(list) },LayoutParams(-1,-1))
    }
}
