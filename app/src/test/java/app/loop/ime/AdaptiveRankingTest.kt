package app.loop.ime

import org.junit.Assert.*
import org.junit.Test

class AdaptiveRankingTest {
    private val now=1800000000000L
    private fun term(text: String,count: Int,days: Double=0.0,context: String="")=Term(text,"shishi",count+1,false,"choice",now-(days*CandidateRanking.DAY).toLong(),evidence=listOf(
        RankingEvidence(text,count,now-(days*CandidateRanking.DAY).toLong(),if(context.isEmpty())emptyMap() else mapOf(context to count))))
    @Test fun establishedHabitCanBeatAnAccidentalRecentChoiceButFreshHabitBeatsStaleFrequency() {
        val habitual=term("事实",12,0.02);val accidental=term("实施",1)
        assertEquals("事实",CandidateRanking.sort(listOf(accidental,habitual),now).first().text)
        assertEquals("实施",CandidateRanking.sort(listOf(term("事实",12,180.0),accidental),now).first().text)
    }
    @Test fun contextDisambiguatesHomophonesAndFallsBackOnUnseenOrEmptyContext() {
        val facts=term("事实",9,0.0,"这是");val implement=term("实施",4,0.3,"开始")
        val words=listOf(facts,implement)
        assertEquals("实施",CandidateRanking.sort(words,now,"计划开始").first().text)
        assertEquals("事实",CandidateRanking.sort(words,now,"这是").first().text)
        assertEquals("事实",CandidateRanking.sort(words,now,"陌生").first().text)
        assertEquals("事实",CandidateRanking.sort(words,now,"").first().text)
        val p=CandidateRanking.scores(words,"开始",now)
        assertEquals(1.0,p.sum(),1e-12);assertTrue(p.all { it>0 && it<1 })
    }
    @Test fun longerContextOverridesAmbiguousSuffixAndShorterContextStillGeneralizes() {
        val a=term("事实",3,0.0,"这是");val b=term("实施",5,0.0,"于是")
        assertEquals("事实",CandidateRanking.sort(listOf(a,b),now,"这是").first().text)
        assertEquals("实施",CandidateRanking.sort(listOf(a,b),now,"于是").first().text)
        assertEquals("实施",CandidateRanking.sort(listOf(a,b),now,"是").first().text)
    }
    @Test fun decayIsContinuousAcrossTheOldSevenDayBoundaryAndRejectsFutureDates() {
        val words=listOf(term("事实",2,7.0),term("实施",3,9.0))
        val before=CandidateRanking.scores(words,now=now-1);val after=CandidateRanking.scores(words,now=now+1)
        assertTrue(kotlin.math.abs(before[0]-after[0])<1e-6)
        assertEquals(0.0,CandidateRanking.decay(now+60001,now,CandidateRanking.DAY.toDouble()),0.0)
        assertEquals(0.5,CandidateRanking.decay(now-CandidateRanking.DAY,now,CandidateRanking.DAY.toDouble()),1e-12)
    }
    @Test fun contextsHaveUnicodeAndSentenceBoundariesAndBoundedCounts() {
        assertEquals("开始",CandidateRanking.context("秘密。开始"))
        assertEquals("",CandidateRanking.context("秘密。"))
        assertEquals("𠀀开始",CandidateRanking.context("𠀀开始"))
        val values=CandidateRanking.cleanContexts(linkedMapOf("开始" to 8,"这是" to 5,"bad key" to 20),10)
        assertEquals(mapOf("开始" to 8,"这是" to 2),values)
    }
    @Test fun chronologicalSyntheticReplayImprovesOverLastUsedOnlyWithoutFutureLeakage() {
        // Train solely on preceding selections. Alternating contexts defeat a pure last-use rule.
        val rows=mutableMapOf<String,MutableList<RankingEvidence>>()
        var adaptive=0;var recency=0;var evaluated=0;var previous=""
        repeat(100) { i ->
            val context=if(i%2==0)"这是" else "开始";val target=if(i%2==0)"事实" else "实施"
            val terms=listOf("事实","实施").map { text -> val e=rows[text].orEmpty();Term(text,"shishi",1+e.sumOf { it.count },false,"choice",e.maxOfOrNull { it.time } ?: 0,evidence=e) }
            if(i>=4) {
                evaluated++;if(CandidateRanking.sort(terms,now+i,context).first().text==target)adaptive++
                if(previous==target)recency++
            }
            rows.getOrPut(target) { mutableListOf() }.add(RankingEvidence("$i",1,now+i,mapOf(context to 1)))
            previous=target
        }
        assertEquals(96,evaluated);assertEquals(96,adaptive);assertEquals(0,recency)
    }
    @Test fun boundedCandidateBatchRemainsFiniteAndReportsHostScoringCost() {
        val terms=(0 until 64).map { i -> Term("词$i","ci",513,false,"choice",now,evidence=(0 until 32).map { j ->
            RankingEvidence("$i-$j",16,now,mapOf("计划开始" to 1)+(0 until 15).associate { "前"+('一'+it)+"开始" to 1 })
        }) }
        repeat(10) { CandidateRanking.scores(terms,"计划开始",now) }
        val times=(0 until 30).map {
            val start=System.nanoTime();val p=CandidateRanking.scores(terms,"计划开始",now)
            val elapsed=(System.nanoTime()-start)/1000000.0
            assertEquals(1.0,p.sum(),1e-9);assertTrue(p.all { it.isFinite() && it>0 });elapsed
        }.sorted()
        println("Adaptive ranking host scoring: 64 candidates x 32 origins x 16 contexts; p50=${times[15]} ms; p95=${times[28]} ms (not phone latency)")
    }
}
