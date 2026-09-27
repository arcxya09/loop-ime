package app.loop.ime

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** App-owned encrypted outbox. Database rejection never discards the only copy of an edit. */
internal class DraftWriter(private val c: Context,private val vault: Vault=Vault(c),
    private val store: ()->PersonalStore={ PersonalStore.get(c) }): AutoCloseable {
    private val pending=ConcurrentHashMap<String,DraftSnapshot>()
    private val worker=Executors.newSingleThreadScheduledExecutor { Thread(it,"Loop-draft-journal").apply { isDaemon=true } }
    private val scheduled=AtomicBoolean()
    private val flushing=AtomicBoolean()
    private val closed=AtomicBoolean()
    @Volatile var lastError="";private set
    fun offer(value: DraftSnapshot) {
        check(!closed.get())
        pending.compute(value.id) { _,old -> if(old==null || value.revision>old.revision)value else old }
        schedule(0)
    }
    fun recover() { schedule(0) }
    fun settle() {
        worker.submit {
            for((id,value) in pending.entries.toList()) {
                val bytes=value.encode()
                try { vault.put(PREFIX+id,bytes) } finally { bytes.fill(0) }
                pending.remove(id,value)
            }
        }.get(15,TimeUnit.SECONDS)
        LoopApp.io.submit {
            for(name in vault.names(PREFIX)) {
                val bytes=vault.get(name) ?: continue
                try { store().applyDraft(DraftSnapshot.decode(bytes));vault.removeIfSame(name,bytes) } finally { bytes.fill(0) }
            }
        }.get(30,TimeUnit.SECONDS)
    }
    private fun schedule(delay: Long) {
        if(closed.get())return
        if(scheduled.compareAndSet(false,true))worker.schedule({
            scheduled.set(false)
            var failed=false
            for((id,value) in pending.entries.toList()) {
                try {
                    val bytes=value.encode()
                    try { vault.put(PREFIX+id,bytes) } finally { bytes.fill(0) }
                    pending.remove(id,value)
                } catch(e: Exception) { failed=true;report(e) }
            }
            flush()
            if(pending.isNotEmpty())schedule(if(failed)2000 else 0)
        },delay,TimeUnit.MILLISECONDS)
    }
    private fun report(e: Exception) {
        DiagnosticLog.failure(DiagnosticLog.Area.STORAGE,e)
        lastError="记忆待保存（${e.javaClass.simpleName}），将自动重试"
        Prefs(c).set("memory_last_error",lastError)
    }
    private fun flush() {
        if(!flushing.compareAndSet(false,true))return
        try { LoopApp.io.execute {
            if(closed.get()) { flushing.set(false);return@execute }
            var failed=false
            try {
                for(name in vault.names(PREFIX)) {
                    val bytes=vault.get(name) ?: continue
                    try { store().applyDraft(DraftSnapshot.decode(bytes));vault.removeIfSame(name,bytes) } finally { bytes.fill(0) }
                }
                if(pending.isEmpty()) { lastError="";Prefs(c).set("memory_last_error","") }
            } catch(e: Exception) { failed=true;report(e) }
            finally {
                flushing.set(false)
                if(failed || vault.names(PREFIX).isNotEmpty())schedule(if(failed)2000 else 0)
            }
        } } catch(e: java.util.concurrent.RejectedExecutionException) { flushing.set(false);report(e);schedule(1000) }
    }
    /** Called before explicit deletion on the storage queue; the journal worker never waits for that queue. */
    fun discard(id: String?=null) = worker.submit {
        if(id==null)pending.clear() else pending.remove(id)
        vault.names(PREFIX).filter { id==null || it==PREFIX+id }.forEach { vault.removeIfSame(it,null) }
    }
    internal fun hasPending(id: String)=pending.containsKey(id) || vault.names(PREFIX).contains(PREFIX+id)
    override fun close() { closed.set(true);worker.shutdownNow() }
    companion object {
        private const val PREFIX="pending.memory."
        private var instance: DraftWriter?=null
        @Synchronized fun get(c: Context)=instance ?: DraftWriter(c.applicationContext).also { instance=it }
    }
}
