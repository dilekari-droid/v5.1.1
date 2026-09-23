package tr.borsatakip.v5.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import tr.borsatakip.v5.research.ConfidenceBreakdown
import tr.borsatakip.v5.research.Evidence
import tr.borsatakip.v5.research.EvidenceGraph
import tr.borsatakip.v5.research.EventType
import tr.borsatakip.v5.research.ResearchClaim
import tr.borsatakip.v5.research.ResearchEvent
import tr.borsatakip.v5.research.ResearchSnapshot
import tr.borsatakip.v5.research.ResearchResultSource
import tr.borsatakip.v5.research.ResearchStatus
import tr.borsatakip.v5.research.SourceQuality
import tr.borsatakip.v5.research.SourceType
import tr.borsatakip.v5.research.VerificationStatus
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Client for the V5.2.1 backend research foundation contract. */
class ResearchApiClient(context: Context) {
    private val appContext = context.applicationContext
    private val settings = SettingsStore(appContext)
    private val capabilities = MarketCapabilityClient(appContext)
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    suspend fun load(symbol: String, category: String = "ALL", asOfTime: Long? = null): Result<ResearchSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            val capability = capabilities.load().getOrThrow()
            require(capability.features.researchFoundation) { "Research foundation backend capability etkin değil." }
            val base = settings.baseUrl.trim().trimEnd('/')
            require(ProviderReadinessService.isValidHttps(base)) { "Research backend HTTPS adresi yapılandırılmalıdır." }
            val safeSymbol = URLEncoder.encode(symbol.trim().uppercase(), "UTF-8")
            val safeCategory = URLEncoder.encode(category.trim().uppercase(), "UTF-8")
            val asOfPart = asOfTime?.takeIf { it > 0L }?.let { "&asOfTime=$it" }.orEmpty()
            val request = BackendRequestSecurity.apply(
                Request.Builder()
                    .url("$base/v1/research/foundation?symbol=$safeSymbol&category=$safeCategory$asOfPart")
                    .header("Accept", "application/json"),
                settings
            ).get().build()
            client.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "Research backend HTTP ${response.code} döndürdü." }
                val body = response.body?.string().orEmpty()
                require(body.isNotBlank()) { "Research backend boş yanıt döndürdü." }
                parse(JSONObject(body))
            }
        }
    }

    private fun parse(root: JSONObject): ResearchSnapshot {
        val graph = root.getJSONObject("graph")
        val evidence = graph.getJSONArray("evidence").objects().map { x ->
            val q = x.getJSONObject("quality")
            Evidence(
                evidenceId = x.getString("evidenceId"),
                sourceId = x.getString("sourceId"),
                canonicalSourceId = x.getString("canonicalSourceId"),
                originSourceId = x.optString("originSourceId").takeIf { it.isNotBlank() && it != "null" },
                sourceType = enumOr(SourceType.UNKNOWN, x.optString("sourceType")),
                sourceName = x.optString("sourceName"),
                title = x.optString("title"),
                url = x.optString("url").takeIf { it.startsWith("https://") },
                eventTime = x.longOrNull("eventTime"),
                publishedAt = x.longOrNull("publishedAt"),
                receivedAt = x.optLong("receivedAt", 0L),
                parsedAt = x.optLong("parsedAt", 0L),
                availableAt = x.optLong("availableAt", 0L),
                quality = SourceQuality(
                    authorityScore = q.optDouble("authorityScore", 0.0),
                    originalityScore = q.optDouble("originalityScore", 0.0),
                    independenceScore = q.optDouble("independenceScore", 0.0),
                    freshnessScore = q.optDouble("freshnessScore", 0.0),
                    claimRelevanceScore = q.optDouble("claimRelevanceScore", 0.0),
                    historicalAccuracyScore = q.doubleOrNull("historicalAccuracyScore"),
                    parsingConfidence = q.optDouble("parsingConfidence", 0.0)
                ),
                independenceGroupId = x.getString("independenceGroupId"),
                claimIds = x.optJSONArray("claimIds").strings(),
                supportsClaim = x.optBoolean("supportsClaim", true),
                providerVerified = x.optBoolean("providerVerified", false),
                sourceIdentityVerified = x.optBoolean("sourceIdentityVerified", false),
                temporalEligible = x.optBoolean("temporalEligible", false)
            )
        }
        val claims = graph.getJSONArray("claims").objects().map { x ->
            ResearchClaim(
                claimId = x.getString("claimId"),
                eventId = x.optString("eventId").takeIf { it.isNotBlank() && it != "null" },
                text = x.optString("text"),
                status = enumOr(VerificationStatus.INSUFFICIENT, x.optString("status")),
                supportingEvidenceIds = x.optJSONArray("supportingEvidenceIds").strings(),
                contradictingEvidenceIds = x.optJSONArray("contradictingEvidenceIds").strings(),
                confidence = x.optDouble("confidence", 0.0)
            )
        }
        val events = graph.getJSONArray("events").objects().map { x ->
            ResearchEvent(
                eventId = x.getString("eventId"),
                eventType = enumOr(EventType.OTHER, x.optString("eventType")),
                symbol = x.optString("symbol"),
                eventTime = x.longOrNull("eventTime"),
                publishedAt = x.longOrNull("publishedAt"),
                firstSeenAt = x.optLong("firstSeenAt", 0L),
                relatedClaimIds = x.optJSONArray("relatedClaimIds").strings(),
                relatedEvidenceIds = x.optJSONArray("relatedEvidenceIds").strings()
            )
        }
        val c = root.getJSONObject("confidence")
        return ResearchSnapshot(
            researchId = root.getString("researchId"),
            symbol = root.getString("symbol"),
            asOfTime = root.getLong("asOfTime"),
            createdAt = root.getLong("createdAt"),
            status = enumOr(ResearchStatus.FAILED, root.optString("status")),
            graph = EvidenceGraph(
                events = events,
                claims = claims,
                evidence = evidence,
                rejectedEvidenceIds = graph.optJSONArray("rejectedEvidenceIds").strings(),
                duplicateEvidenceIds = graph.optJSONArray("duplicateEvidenceIds").strings()
            ),
            confidence = ConfidenceBreakdown(
                sourceConfidence = c.optDouble("sourceConfidence", 0.0),
                claimConfidence = c.optDouble("claimConfidence", 0.0),
                freshness = c.optDouble("freshness", 0.0),
                dataCompleteness = c.optDouble("dataCompleteness", 0.0),
                crossSourceConsistency = c.optDouble("crossSourceConsistency", 0.0),
                parsingConfidence = c.optDouble("parsingConfidence", 0.0)
            ),
            dataVersion = root.optString("dataVersion"),
            rulesVersion = root.optString("rulesVersion"),
            modelVersion = root.optString("modelVersion"),
            sourceSnapshotHash = root.optString("sourceSnapshotHash"),
            resultSource = ResearchResultSource.BACKEND
        )
    }

    private inline fun <reified T : Enum<T>> enumOr(default: T, raw: String): T =
        runCatching { enumValueOf<T>(raw.trim().uppercase()) }.getOrDefault(default)

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else buildList {
        for (i in 0 until length()) optJSONObject(i)?.let(::add)
    }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else buildList {
        for (i in 0 until length()) optString(i).takeIf { it.isNotBlank() }?.let(::add)
    }

    private fun JSONObject.longOrNull(name: String): Long? =
        if (!has(name) || isNull(name)) null else optLong(name).takeIf { it > 0L }

    private fun JSONObject.doubleOrNull(name: String): Double? =
        if (!has(name) || isNull(name)) null else optDouble(name, Double.NaN).takeIf { it.isFinite() }
}
