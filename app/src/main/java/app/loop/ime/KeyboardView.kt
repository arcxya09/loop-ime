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
    private val green=0xff286952.toInt()
    private val surface=ImeAppearance.SURFACE
    private val secondary=0xffe0e5ea.toInt()
    private val mint=0xffdfece5.toInt()
    private val ink=0xff343d48.toInt()
    private val muted=0xff7b8590.toInt()
    private val status=TextView(c)
    private val notice=TextView(c)
    private val cloudRow=LinearLayout(c)
    private val cloudCandidates=LinearLayout(c)
    private var touchingCloud=false
    private var deferredCloud: List<Pair<String,()->Unit>>?=null
    private val cloudScroll=object: HorizontalScrollView(c) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if(event.actionMasked==MotionEvent.ACTION_DOWN)touchingCloud=true
            return try { super.dispatchTouchEvent(event) } finally {
                if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL) {
                    touchingCloud=false
                    post { if(!touchingCloud)deferredCloud?.let { deferredCloud=null;setPredictions(it) } }
                }
            }
        }
    }
    private lateinit var aiButton: KeyboardIcon
    private lateinit var toolsButton: KeyboardIcon
    private var aiMessage=""
    private val clearAiMessage=Runnable {
        aiMessage="";toolsButton.tint=ink;toolsButton.contentDescription="Loop 工具与设置";toolsButton.invalidate()
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
                touchingCandidates=false;setExpanded(true);return true
            }
            return try { super.dispatchTouchEvent(event) } finally {
                if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL) {
                    touchingCandidates=false
                    post { if(!touchingCandidates)deferredCandidates?.let { deferredCandidates=null;setCandidates(it.first,it.second) } }
                }
            }
        }
    }
    private val body=LinearLayout(c)
    private val content=FrameLayout(c)
    private val expandedWords=CandidateFlowLayout(c)
    private val expandedScroll=object: ScrollView(c) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if(event.actionMasked==MotionEvent.ACTION_DOWN)touchingCandidates=true
            return try { super.dispatchTouchEvent(event) } finally {
                if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL) {
                    touchingCandidates=false
                    post { if(!touchingCandidates)deferredCandidates?.let { deferredCandidates=null;setCandidates(it.first,it.second) } }
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
    private var showNotice=false
    private val clearNotice=Runnable { showNotice=false;updateStrip() }
    private var spaceKey: HoldSpaceKey?=null
    private var pendingRender=false
    private var discardMultiTouch=false
    init {
        orientation=VERTICAL;layoutDirection=View.LAYOUT_DIRECTION_LTR
        setBackgroundColor(surface);setPadding(dp(6),dp(4),dp(6),dp(6));isFocusable=false
        val toolbar=LinearLayout(c).apply { gravity=Gravity.CENTER_VERTICAL;tag="keyboard_toolbar" }
        fun tool(code: String,description: String,action: ()->Unit): KeyboardIcon {
            val v=icon(code,description,Color.TRANSPARENT,action)
            toolbar.addView(v,LayoutParams(dp(42),dp(42)));return v
        }
        toolsButton=tool("tools","Loop 工具与设置") { key("tools") }
        toolsButton.setOnLongClickListener {
            if(aiMessage.isEmpty())false else { Toast.makeText(context,aiMessage,Toast.LENGTH_LONG).show();true }
        }
        val strip=FrameLayout(c).apply { tag="candidate_strip" }
        labelStyle(status,11,9);status.setTextColor(muted);status.gravity=Gravity.CENTER_VERTICAL
        status.setPadding(dp(5),0,dp(5),0);status.tag="keyboard_status"
        status.setOnClickListener { statusAction?.invoke() ?: Toast.makeText(context,statusText,Toast.LENGTH_LONG).show() }
        candidates.orientation=HORIZONTAL;candidateScroll.isHorizontalScrollBarEnabled=false
        candidateScroll.addView(candidates,ViewGroup.LayoutParams(-2,-1))
        strip.addView(status,FrameLayout.LayoutParams(-1,-1))
        strip.addView(candidateScroll,FrameLayout.LayoutParams(-1,dp(34),Gravity.CENTER_VERTICAL))
        toolbar.addView(strip,LayoutParams(0,-1,1f))
        expandButton=tool("expand_candidates","展开全部候选词") { setExpanded(!expanded) }
        separator=action("分词","separator",10,Color.TRANSPARENT).apply { setTextColor(muted);contentDescription="拼音分词" }
        toolbar.addView(separator,LayoutParams(dp(34),dp(42)))
        aiButton=tool("ai_settings","AI 设置") { key("ai_settings") }
        clipButton=tool("clipboard","剪贴板") { key(if(isVoice)"cancel_voice" else "clipboard") }
        micButton=tool("mic","语音输入") { key("mic") }
        tool("hide","收起键盘") { key("hide") }
        addView(toolbar,LayoutParams(-1,dp(44)))
        cloudRow.tag="cloud_predictions";cloudRow.gravity=Gravity.CENTER_VERTICAL;cloudRow.visibility=GONE
        cloudRow.addView(TextView(c).apply { text="云端";setTextColor(muted);labelStyle(this,11);gravity=Gravity.CENTER },LayoutParams(dp(42),-1))
        cloudScroll.isHorizontalScrollBarEnabled=false;cloudScroll.addView(cloudCandidates,ViewGroup.LayoutParams(-2,-1))
        cloudRow.addView(cloudScroll,LayoutParams(0,-1,1f));addView(cloudRow,LayoutParams(-1,dp(36)))
        notice.tag="keyboard_notice";notice.setTextColor(muted);labelStyle(notice,11);notice.setPadding(dp(8),0,dp(8),0)
        notice.gravity=Gravity.CENTER_VERTICAL;notice.visibility=GONE
        notice.setOnClickListener { statusAction?.invoke() ?: Toast.makeText(context,statusText,Toast.LENGTH_LONG).show() }
        addView(notice,LayoutParams(-1,dp(28)))
        body.orientation=VERTICAL;body.tag="keyboard_body";content.addView(body,FrameLayout.LayoutParams(-1,-2))
        expandedScroll.tag="expanded_candidates";expandedScroll.visibility=GONE;expandedScroll.isFillViewport=true
        expandedScroll.addView(expandedWords,ViewGroup.LayoutParams(-1,-2));content.addView(expandedScroll,FrameLayout.LayoutParams(-1,-1))
        expandedScroll.setOnScrollChangeListener { _,_,y,_,_ ->
            if(expanded && y>0 && expandedWords.height-y-expandedScroll.height<dp(60))requestMore()
        }
        addView(content,LayoutParams(-1,-2))
        render();voice(false)
        setOnApplyWindowInsetsListener { _,insets ->
            // Android draws the switcher. This is only its safe area, with no app footer or globe.
            val safe=insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.captionBar() or WindowInsets.Type.displayCutout() or WindowInsets.Type.mandatorySystemGestures())
            setPadding(dp(6)+safe.left,dp(4),dp(6)+safe.right,dp(6)+safe.bottom);insets
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow();requestApplyInsets() }
    override fun onDetachedFromWindow() { removeCallbacks(clearNotice);removeCallbacks(clearAiMessage);clearAiMessage.run();showNotice=false;super.onDetachedFromWindow() }
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
        addState(intArrayOf(android.R.attr.state_pressed),bg(0xffd1e2d9.toInt(),radius))
        addState(intArrayOf(),bg(color,radius))
    }
    private fun labelStyle(v: TextView,maxSize: Int,minSize: Int=maxSize-3) {
        v.setTextSize(TypedValue.COMPLEX_UNIT_DIP,maxSize.toFloat());v.includeFontPadding=false
        v.setSingleLine(true);v.setHorizontallyScrolling(false);v.ellipsize=TextUtils.TruncateAt.END
        v.setAutoSizeTextTypeUniformWithConfiguration(minSize.coerceAtLeast(8),maxSize,1,TypedValue.COMPLEX_UNIT_DIP)
        v.typeface=Typeface.create("sans-serif",Typeface.NORMAL)
    }
    private fun button(label: String,size: Int=17,color: Int=Color.WHITE,action: ()->Unit): TextView = TextView(context).apply {
        text=label;contentDescription=label;labelStyle(this,size);gravity=Gravity.CENTER;setTextColor(ink);background=keyBg(color)
        setPadding(dp(4),0,dp(4),0);isClickable=true;isFocusable=false
        setOnClickListener { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);action() }
    }
    private fun icon(code: String,description: String,color: Int=secondary,action: ()->Unit)=KeyboardIcon(context,code).apply {
        tag=code;contentDescription=description;tint=ink;background=keyBg(color)
        setOnClickListener { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);action() }
    }
    private fun action(label: String,code: String,size: Int=17,color: Int=Color.WHITE): TextView = button(label,size,color) { press(code) }.apply { tag=code }
    private fun deleteKey(): View=icon("delete","删除") { press("delete") }.apply {
        val repeat=object: Runnable { override fun run() { key("delete");postDelayed(this,65) } }
        setOnTouchListener { _,e -> when(e.actionMasked) { MotionEvent.ACTION_DOWN->postDelayed(repeat,380);MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->removeCallbacks(repeat) };false }
        addOnAttachStateChangeListener(object: OnAttachStateChangeListener { override fun onViewAttachedToWindow(v: View) {};override fun onViewDetachedFromWindow(v: View) { removeCallbacks(repeat) } })
    }
    private fun space(): HoldSpaceKey=HoldSpaceKey(context,
        { active -> key(if(active)"voice_hold_start" else "voice_hold_end") },
        { if(pendingRender)render() }, { key("mic") }).apply {
        text="按住说话";labelStyle(this,11,9);gravity=Gravity.CENTER;setTextColor(muted);background=keyBg(Color.WHITE);isFocusable=false
        setPadding(dp(4),0,dp(4),0)
        setOnClickListener { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);if(isVoice)key("mic") else press("space") }
        spaceKey=this
    }
    private fun modeName()=if(symbols)"数字 / 符号" else if(!chinese)"英文" else if(nineKey)"九宫格" else "全键盘"
    private fun isPreedit(s: String)=s.isNotBlank() && s.matches(Regex("[a-zA-Z0-9'üv :·-]+"))
    private fun isIdleHint(s: String)=s.startsWith("简体九宫格 ·") || s.startsWith("简体全键盘 ·") ||
        s.startsWith("简体拼音已就绪") || s=="拼音已就绪" || s=="英文 · 长按空格说话"
    private fun updateStrip() {
        status.text=if(isIdleHint(statusText) || statusText=="长按空格说话 · 松开结束")modeName() else statusText
        status.contentDescription=statusText
        val showCandidates=hasCandidates && !isVoice
        status.visibility=if(showCandidates)View.GONE else View.VISIBLE
        candidateScroll.visibility=if(showCandidates)View.VISIBLE else View.GONE
        expandButton.visibility=if(showCandidates)VISIBLE else GONE
        notice.text=statusText;notice.contentDescription=statusText
        notice.visibility=if(showNotice && showCandidates)VISIBLE else GONE
        separator.visibility=if(showCandidates && chinese && !symbols)View.VISIBLE else View.GONE
        aiButton.visibility=if(showCandidates || isVoice || showNotice)View.GONE else View.VISIBLE
        clipButton.visibility=if(showCandidates || (showNotice && !isVoice))View.GONE else View.VISIBLE
    }
    /** Background AI failures must not resize the keyboard or displace a candidate under a finger. */
    fun aiStatus(s: String) {
        aiMessage=s;toolsButton.tint=0xffa06520.toInt()
        toolsButton.contentDescription="Loop 工具与设置；$s；长按查看"
        toolsButton.invalidate();removeCallbacks(clearAiMessage);postDelayed(clearAiMessage,8000)
    }
    fun status(s: String,action: (()->Unit)?=null) {
        composing=false;statusText=s;statusAction=action;removeCallbacks(clearNotice)
        // Notices have their own line; candidate selection remains available throughout.
        showNotice=s.isNotBlank() && !isIdleHint(s) && !isPreedit(s)
        updateStrip();if(showNotice)postDelayed(clearNotice,3500)
    }
    fun composition(preedit: String) {
        // Codes stay inside Rime. The single toolbar is entirely available for word candidates.
        composing=true;statusText=if(chinese && nineKey)"暂无候选词" else preedit
        statusAction=null;showNotice=false;removeCallbacks(clearNotice);updateStrip()
    }
    fun endComposition() { if(composing)status("长按空格说话 · 松开结束") }
    fun setMode(cn: Boolean,sym: Boolean=false) { chinese=cn;symbols=sym;render() }
    fun setNineKey(nine: Boolean) { nineKey=nine;render() }
    fun setHeightPreset(preset: KeyboardHeight) { if(heightPreset!=preset) { heightPreset=preset;render() } }
    fun setEnter(s: String) { enterLabel=s;render() }
    fun cancelSpaceGesture() { spaceKey?.cancelGesture() }
    fun voice(active: Boolean) {
        isVoice=active
        // Keep the same keyboard, space view, and height throughout both recording gestures.
        micButton.glyph=if(active)"stop" else "mic";micButton.contentDescription=if(active)"结束语音" else "语音输入"
        micButton.tint=if(active)Color.WHITE else green;micButton.background=keyBg(if(active)green else mint,21)
        clipButton.glyph=if(active)"cancel" else "clipboard";clipButton.contentDescription=if(active)"取消语音尾句" else "剪贴板";clipButton.invalidate()
        if(spaceKey?.holding!=true)spaceKey?.text=if(active)"结束语音" else "按住说话"
        spaceKey?.setTextColor(if(active)green else muted)
        setCandidates(emptyList())
        setPredictions(emptyList())
    }
    fun setPredictions(values: List<Pair<String,()->Unit>>) {
        if(touchingCloud) { deferredCloud=values.toList();return }
        deferredCloud=null;cloudCandidates.removeAllViews()
        values.forEach { (text,choose) -> cloudCandidates.addView(button(text,15,Color.TRANSPARENT,choose).apply {
            setTextColor(green);setPadding(dp(12),0,dp(12),0);contentDescription="$text，AI 候选"
        },LayoutParams(-2,-1)) }
        cloudRow.visibility=if(values.isNotEmpty() && !isVoice)VISIBLE else GONE
    }
    fun candidatePaging(more: Boolean,loading: Boolean=false) {
        if(hasMore==more && loadingMore==loading)return
        hasMore=more;loadingMore=loading
        if(expanded && !touchingCandidates)renderExpanded()
    }
    private fun requestMore() { if(hasMore && !loadingMore) { loadingMore=true;key("more_candidates") } }
    private fun setExpanded(value: Boolean) {
        expanded=value && hasCandidates && !isVoice
        if(expanded)expandedScroll.layoutParams.height=body.measuredHeight.takeIf { it>0 } ?: dp(heightPreset.padDp+4)
        body.visibility=if(expanded)INVISIBLE else VISIBLE;expandedScroll.visibility=if(expanded)VISIBLE else GONE
        expandButton.glyph=if(expanded)"collapse_candidates" else "expand_candidates";expandButton.invalidate()
        expandButton.contentDescription=if(expanded)"收起候选词" else "展开全部候选词"
        if(expanded) { renderExpanded();expandedScroll.scrollTo(0,0) }
    }
    private fun renderExpanded() {
        expandedWords.removeAllViews()
        candidateValues.forEachIndexed { i,(text,choose) -> expandedWords.addView(button(text,17,if(i==0)mint else Color.TRANSPARENT) {
            setExpanded(false);choose()
        }.apply { setPadding(dp(14),0,dp(14),0);minimumWidth=dp(56);setTextColor(if(text in candidateAiTexts)green else ink) },ViewGroup.LayoutParams(-2,dp(44))) }
        if(hasMore)expandedWords.addView(button(if(loadingMore)"正在加载…" else "更多候选词",13,secondary) { requestMore() },ViewGroup.LayoutParams(-2,dp(44)))
    }
    fun setCandidates(values: List<Pair<String,()->Unit>>,aiTexts: Set<String> = emptySet()) {
        // A late cloud answer must not replace or move the word currently under a finger.
        if(touchingCandidates) { deferredCandidates=values.toList() to aiTexts.toSet();return }
        deferredCandidates=null
        val previous=candidateValues.map { it.first }
        candidateValues=values.toList();candidateAiTexts=aiTexts.toSet()
        candidates.removeAllViews();hasCandidates=values.isNotEmpty()
        values.forEachIndexed { i,(text,choose) ->
            candidates.addView(button(text,16,if(i==0)mint else Color.TRANSPARENT,choose).apply {
                setTextColor(if(i==0 || text in aiTexts)green else ink);setPadding(dp(12),0,dp(12),0)
                if(text in aiTexts)contentDescription="$text，AI 候选"
            },LayoutParams(-2,-1).apply { rightMargin=dp(4) })
        }
        if(previous!=values.map { it.first } && previous!=values.take(previous.size).map { it.first }) {
            candidateScroll.scrollTo(0,0);expandedScroll.scrollTo(0,0)
        }
        if(!hasCandidates)setExpanded(false) else if(expanded)renderExpanded()
        updateStrip()
    }
    private fun render() {
        if(spaceKey?.tracking==true) { pendingRender=true;return }
        pendingRender=false;spaceKey=null;body.removeAllViews()
        if(chinese && nineKey && !symbols)renderNineKey() else renderFullKeys()
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
                val v=LetterKey(context,n.toString()).apply {
                    text=labels[n-1];labelStyle(this,if(n>=7)17 else 18);gravity=Gravity.CENTER;setTextColor(ink);background=keyBg(Color.WHITE)
                    setPadding(dp(4),dp(2),dp(4),0);tag=code;contentDescription="${labels[n-1]}，数字 $n";isClickable=true;isFocusable=false
                    setOnClickListener { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);press(code) }
                }
                row.addView(v,LayoutParams(0,-1,1f).apply { if(col<2)rightMargin=gap })
            }
            center.addView(row,LayoutParams(-1,rowHeight).apply { bottomMargin=gap })
        }
        val bottom=LinearLayout(context)
        bottom.addView(action("123","numbers",16),LayoutParams(0,-1,0.7f).apply { rightMargin=gap })
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
        for((label,code) in listOf((if(symbols)"ABC" else "123") to "symbols",(if(chinese)"中/英" else "英/中") to "language","，" to "comma","空格" to "space","。" to "period",enterLabel to "enter")) {
            val v=if(code=="space")space() else action(label,code,12,if(code=="enter")mint else Color.WHITE)
            bottom.addView(v,LayoutParams(0,dp(heightPreset.rowDp),if(code=="space")3.5f else 1.2f).apply { setMargins(dp(2),dp(4),dp(2),0) })
        };body.addView(bottom)
    }
    private fun row(keys: List<Pair<String,String>>,margin: Int=0) {
        val row=LinearLayout(context).apply { setPadding(margin,0,margin,0) }
        keys.forEach { (label,code) -> row.addView(if(code=="delete")deleteKey() else action(label,code),LayoutParams(0,dp(heightPreset.rowDp),1f).apply { setMargins(dp(2),dp(4),dp(2),0) }) };body.addView(row)
    }
    private fun press(code: String) {
        if(isVoice && code !in setOf("space","mic"))return
        if(code=="shift") { shift=!shift;render();return }
        key(code)
    }
    fun panel(title: String,entries: List<Pair<String,()->Unit>>) {
        cancelSpaceGesture();spaceKey=null;body.removeAllViews();setCandidates(emptyList());setPredictions(emptyList());status(title)
        val list=LinearLayout(context).apply { orientation=VERTICAL }
        entries.forEach { (label,choose) -> list.addView(button(label,14) { choose() }.apply { setPadding(dp(12),0,dp(12),0);gravity=Gravity.CENTER_VERTICAL },LayoutParams(-1,dp(43)).apply { topMargin=dp(4) }) }
        body.addView(ScrollView(context).apply { addView(list) },LayoutParams(-1,dp(heightPreset.padDp-44)))
        body.addView(button("返回键盘",13,secondary) { render();key("panel_close") },LayoutParams(-1,dp(40)).apply { topMargin=dp(4) })
    }
    private class LetterKey(c: Context,private val number: String): TextView(c) {
        private val corner=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=0xff9da6af.toInt();textAlign=Paint.Align.RIGHT;textSize=8.5f*resources.displayMetrics.density }
        override fun onDraw(canvas: Canvas) { super.onDraw(canvas);val d=resources.displayMetrics.density;canvas.drawText(number,width-6*d,11*d,corner) }
    }
}
