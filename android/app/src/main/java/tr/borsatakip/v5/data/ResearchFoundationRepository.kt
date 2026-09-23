package tr.borsatakip.v5.data

import android.content.Context
import tr.borsatakip.v5.research.NewsResearchAdapter
import tr.borsatakip.v5.research.ResearchFoundationEngine
import tr.borsatakip.v5.research.ResearchSnapshot
import tr.borsatakip.v5.research.ResearchResultSource

/**
 * V5.2.1 research foundation repository.
 * Backend is preferred so API keys, source normalization and cross-source verification stay centralized.
 * If the V5.2.1 backend endpoint is unavailable, the same deterministic foundation can be built locally
 * from the real NewsRepository records. Neither path alters Ensemble/technical scores.
 */
class ResearchFoundationRepository(context: Context) {
    private val api = ResearchApiClient(context)
    private val news = NewsRepository(context)
    private val engine = ResearchFoundationEngine()

    suspend fun load(
        symbol: String,
        companyName: String?,
        category: String = "ALL",
        forceRefresh: Boolean = false,
        asOfTime: Long? = null
    ): Result<ResearchSnapshot> {
        api.load(symbol, category, asOfTime).getOrNull()?.let { return Result.success(it) }
        return runCatching {
            val newsResult = news.load(symbol, companyName, category, forceRefresh).getOrThrow()
            val fetchedAt = System.currentTimeMillis()
            val inputs = NewsResearchAdapter.toInputs(newsResult.items, fetchedAt)
            val effectiveAsOfTime = asOfTime ?: System.currentTimeMillis()
            engine.build(symbol, inputs, effectiveAsOfTime).copy(resultSource = ResearchResultSource.FALLBACK)
        }
    }
}
