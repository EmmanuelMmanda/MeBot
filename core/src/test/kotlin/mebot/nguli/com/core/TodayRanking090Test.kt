package mebot.nguli.com.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

class TodayRanking090Test {
    private val now=Instant.parse("2026-10-25T12:00:00Z").toEpochMilli()
    private fun item(id:String="one", temporal:FeedTemporal?=null)=FeedItem(id,"news","Software biashara learning","Security cloud",sourceUrl="https://example.com/$id",publishedAtMillis=now,fetchedAtMillis=now,temporal=temporal)
    @Test fun preparedScoresPreserveLegacyNonFreshnessComponentsAndMatchCurrentDirectFormula() {
        val random=Random(90)
        repeat(10) { round ->
            val modules=listOf("news","technology","business").map { FeedModule(id=it,kind=it,query="software biashara cloud") }
            val items=(0 until 100).map { i -> item("item-$i").copy(moduleId=modules[i%3].id,kind=modules[i%3].kind,relevance=random.nextInt(101),publishedAtMillis=now-random.nextLong(1_000_000_000L),deadline=if(i%5==0) "2026-10-26" else "") }
            val state=FeedState(timezone="Africa/Dar_es_Salaam",modules=modules,items=items,
                context=(0 until 80).map { ContextEntry(id="note-$it",text="software biashara security",scopes=setOf(modules[it%3].kind),updatedAtMillis=now) },
                savedIds=items.filterIndexed { i,_->i%3==0 }.map {it.id}.toSet(),hiddenIds=items.filterIndexed {i,_->i%7==0}.map {it.id}.toSet(),
                relevanceSignals=(0 until 500).map { RelevanceSignal("item-${it}",modules[it%3].id,it%3!=0,"Fixture",setOf("software","cloud","biashara"),now-random.nextLong(9_000_000_000L)) },
                editionSequence=round.toLong(),viewedAtMillis=items.take(30).associate {it.id to now-random.nextLong(100_000_000L)})
            val prepared=RecommendationPolicy.prepare(state,now)
            items.forEach { item ->
                val legacy = LegacyRecommendationPolicy.score(state,item,now)
                val direct = RecommendationPolicy.score(state,item,now)
                val snapshot = prepared.score(item)
                assertEquals(legacy.semantic, direct.semantic)
                assertEquals(legacy.interest, direct.interest)
                assertEquals(legacy.feedback, direct.feedback)
                assertEquals(legacy.novelty, direct.novelty)
                assertEquals(legacy.deadline, direct.deadline)
                assertEquals(legacy.exploration, direct.exploration)
                assertEquals(direct.toString(), snapshot.toString())
            }
        }
    }
    @Test fun unknownOldAndFutureDatesNeverBecomeTodayByFetch() {
        val window=TodayFeed.dayWindow(now,"Africa/Dar_es_Salaam")
        assertNull(TodayFeed.reason(item(),now,window))
        assertNull(TodayFeed.reason(item(temporal=FeedTemporal(TemporalProvenance.PUBLISHER,publishedAtMillis=window.startMillis-1)),now,window))
        assertNull(TodayFeed.reason(item(temporal=FeedTemporal(TemporalProvenance.PUBLISHER,publishedAtMillis=now+300001)),now,window))
        assertEquals(TodayReason.PUBLISHED,TodayFeed.reason(item(temporal=FeedTemporal(TemporalProvenance.PUBLISHER,publishedAtMillis=now)),now,window))
        assertEquals(TodayReason.UPDATED,TodayFeed.reason(item(temporal=FeedTemporal(TemporalProvenance.PUBLISHER,publishedAtMillis=window.startMillis-86400000,substantiveUpdatedAtMillis=now)),now,window))
    }
    @Test fun localDayRolloverDstAndExtremeZones() {
        assertEquals(23*3600000L,TodayFeed.dayWindow(Instant.parse("2026-03-08T12:00:00Z").toEpochMilli(),"America/New_York").let {it.endMillis-it.startMillis})
        assertEquals(25*3600000L,TodayFeed.dayWindow(Instant.parse("2026-11-01T12:00:00Z").toEpochMilli(),"America/New_York").let {it.endMillis-it.startMillis})
        for(zone in listOf("Africa/Dar_es_Salaam","Pacific/Kiritimati","Pacific/Pago_Pago")) {
            val day=TodayFeed.dayWindow(now,zone)
            assertEquals(day.date.plusDays(1),TodayFeed.dayWindow(day.endMillis,zone).date)
            assertNull(TodayFeed.reason(item(temporal=FeedTemporal(TemporalProvenance.PUBLISHER,publishedAtMillis=now)),day.endMillis,TodayFeed.dayWindow(day.endMillis,zone)))
        }
    }
    @Test fun forecastsRequireFreshValidityAndEventsKeepSourceDate() {
        val window=TodayFeed.dayWindow(now,"Africa/Dar_es_Salaam")
        val forecast=item(temporal=FeedTemporal(TemporalProvenance.FORECAST_PROVIDER,verifiedAtMillis=now,validFromMillis=now,validUntilMillis=now+3600000)).copy(kind="weather")
        assertEquals(TodayReason.FORECAST,TodayFeed.reason(forecast,now,window))
        assertNull(TodayFeed.reason(forecast.copy(temporal=forecast.temporal!!.copy(verifiedAtMillis=now-TodayFeed.FORECAST_MAX_AGE_MILLIS-1)),now,window))
        val event=item(temporal=FeedTemporal(TemporalProvenance.EVENT_SOURCE,relevanceDate="2026-10-25",sourceTimezone="Africa/Dar_es_Salaam",relevanceKind=TemporalRelevance.EVENT))
        assertEquals(TodayReason.HAPPENING,TodayFeed.reason(event,now,window))
        assertNull(TodayFeed.reason(event.copy(temporal=event.temporal!!.copy(sourceTimezone=null)),now,window))
    }
    @Test fun cachedProjectionPreservesIdentityAndDeduplicatesWithoutRequests() {
        val a=item(temporal=FeedTemporal(TemporalProvenance.PUBLISHER,publishedAtMillis=now))
        val b=a.copy(id="duplicate",sourceUrl=a.sourceUrl+"?utm_source=test")
        val state=FeedState(modules=listOf(FeedModule(id="news",kind="news")),items=listOf(a,b),requestsToday=17)
        assertEquals(listOf(a.id),TodayFeed.edition(state,now).map {it.item.id})
        assertEquals(17,state.requestsToday)
        assertTrue(TodayFeed.edition(state.copy(modules=listOf(FeedModule(id="news",kind="news",enabled=false))),now).isEmpty())
    }
    @Test fun sourceEvidenceMergeDoesNotPromoteGuessedAiDateOrRefreshOldForecast() {
        val old=item().copy(aiGenerated=true)
        val state=FeedState(items=listOf(old))
        assertNull(FeedLogic.merge(state,listOf(item()),now).items.single().temporal)
        val source=item(temporal=FeedTemporal(TemporalProvenance.PUBLISHER,publishedAtMillis=now))
        val merged=FeedLogic.merge(state,listOf(source),now).items.single()
        assertTrue(merged.aiGenerated)
        assertEquals(source.temporal,merged.temporal)
        assertEquals(old.moduleId,merged.moduleId)
        val forecast=item(temporal=FeedTemporal(TemporalProvenance.FORECAST_PROVIDER,
            verifiedAtMillis=now- TodayFeed.FORECAST_MAX_AGE_MILLIS-1,validFromMillis=now-1000,validUntilMillis=now+1000)).copy(kind="weather",aiGenerated=true)
        val renewed=FeedLogic.merge(FeedState(items=listOf(forecast)),listOf(forecast.copy(aiGenerated=false)),now).items.single()
        assertNull(TodayFeed.reason(renewed,now,TodayFeed.dayWindow(now,"UTC")))
    }

    @Test fun smallVisibleEditionWithLargeSavedHistoryPreservesScores() {
        val visible=(0 until 5).map { item("visible-$it") }
        val historical=(0 until 500).map { item("history-$it").copy(moduleId="paused") }
        val state=FeedState(modules=listOf(FeedModule(id="news",kind="news"),FeedModule(id="paused",kind="news",enabled=false)),
            items=visible+historical,savedIds=historical.map { it.id }.toSet())
        FeedLogic.validate(state)
        assertEquals(visible.map {it.id}.toSet(),FeedLogic.edition(state,now).map {it.id}.toSet())
        val snapshot=RecommendationPolicy.prepare(state,now)
        visible.forEach { item ->
            val legacy = LegacyRecommendationPolicy.score(state,item,now)
            val direct = RecommendationPolicy.score(state,item,now)
            val prepared = snapshot.score(item)
            assertEquals(legacy.semantic, direct.semantic)
            assertEquals(legacy.interest, direct.interest)
            assertEquals(legacy.feedback, direct.feedback)
            assertEquals(legacy.novelty, direct.novelty)
            assertEquals(legacy.deadline, direct.deadline)
            assertEquals(legacy.exploration, direct.exploration)
            assertEquals(direct.toString(), prepared.toString())
        }
    }

    @Test fun staleSourceCompetitorsCannotDistortTodayDiversity() {
        val today=FeedTemporal(TemporalProvenance.PUBLISHER,publishedAtMillis=now)
        val stale=item("stale").copy(relevance=100,sourceUrl="https://alpha.example/stale",publishedAtMillis=now-86400000,
            temporal=FeedTemporal(TemporalProvenance.PUBLISHER,publishedAtMillis=now-86400000))
        val a=item("today-a",today).copy(relevance=80,sourceUrl="https://alpha.example/a")
        val b=item("today-b",today).copy(relevance=75,sourceUrl="https://beta.example/b")
        val state=FeedState(timezone="UTC",modules=listOf(FeedModule(id="news",kind="news")),items=listOf(stale,a,b))
        assertEquals(listOf(b.id,a.id),FeedLogic.edition(state,now).filter { it.id != stale.id }.map {it.id})
        assertEquals(listOf(a.id,b.id),TodayFeed.edition(state,now).map {it.item.id})
        assertEquals(TodayFeed.edition(state.copy(items=listOf(a,b)),now),TodayFeed.edition(state,now))
    }
    @Test fun temporalImportsRejectMixedOrIncompleteEvidence() {
        val bad=listOf(
            FeedTemporal(TemporalProvenance.PUBLISHER),
            FeedTemporal(TemporalProvenance.PUBLISHER,publishedAtMillis=now,relevanceDate="2026-10-25"),
            FeedTemporal(TemporalProvenance.FORECAST_PROVIDER,verifiedAtMillis=now,validFromMillis=now),
            FeedTemporal(TemporalProvenance.FORECAST_PROVIDER,verifiedAtMillis=now,validFromMillis=now,validUntilMillis=now+1000,publishedAtMillis=now),
            FeedTemporal(TemporalProvenance.EVENT_SOURCE,relevanceDate="2026-10-25",relevanceKind=TemporalRelevance.EVENT),
            FeedTemporal(TemporalProvenance.EVENT_SOURCE,relevanceDate="2026-10-25",sourceTimezone="UTC")
        )
        bad.forEach { t -> assertThrows(IllegalArgumentException::class.java) { FeedLogic.validateItem(item(temporal=t)) } }
        TodayFeed.validate(FeedTemporal(TemporalProvenance.PUBLISHER,publishedAtMillis=now))
        TodayFeed.validate(FeedTemporal(TemporalProvenance.EVENT_SOURCE,publishedAtMillis=now-86400000,
            relevanceDate="2026-10-25",sourceTimezone="Pacific/Kiritimati",relevanceKind=TemporalRelevance.EVENT))
    }

}
