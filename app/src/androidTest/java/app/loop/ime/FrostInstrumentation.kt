package app.loop.ime

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real native Rime smoke checks; no cloud, user text or account is used. */
class FrostInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments);start() }
    override fun onStart() {
        val report=StringBuilder()
        try {
            val old=File(targetContext.noBackupFilesDir,"rime/user/loop-frost-preservation-probe")
            old.parentFile!!.mkdirs();old.writeText("preserve-old-rime")
            val engine=RimeEngine(targetContext)
            val ready=CountDownLatch(1);var error: String?=null
            engine.prepare { error=it;ready.countDown() }
            check(ready.await(300,TimeUnit.SECONDS)) { "Dictionary deployment timed out" }
            check(error==null) { error.orEmpty() }
            check(old.readText()=="preserve-old-rime");old.delete()
            report.append("PASS deployment and preservation of old Rime directory\n")
            fun event(key: Int,kind: Int=0): RimeState {
                val done=CountDownLatch(1);var result=RimeState()
                engine.event(key,kind) { result=it;done.countDown() }
                check(done.await(30,TimeUnit.SECONDS));check(result.error==null);return result
            }
            val cases=listOf("nihao" to "你好","zhongguo" to "中国","shurufa" to "输入法",
                "rengongzhineng" to "人工智能","jisuanji" to "计算机","xian" to "西安")
            for(nine in listOf(true,false,true)) {
                event(if(nine)1 else 0,4)
                for((pinyin,word) in cases) {
                    event(0,2)
                    val code=if(nine)NineKey.encode(pinyin) else pinyin
                    var state=RimeState()
                    for(key in code)state=event(key.code)
                    check(word in state.candidates) { "Missing $word for $code: ${state.candidates}" }
                    check(event(state.candidates.indexOf(word),1).commit==word)
                    report.append("PASS ${if(nine)"T9" else "QWERTY"} $code -> $word\n")
                }
            }
            finish(Activity.RESULT_OK,Bundle().apply { putString("stream",report.toString());putString("result","PASS") })
        } catch(t: Throwable) {
            finish(Activity.RESULT_CANCELED,Bundle().apply { putString("stream",report.toString()+t.stackTraceToString());putString("result","FAIL") })
        }
    }
}
