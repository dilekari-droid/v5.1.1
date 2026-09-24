package tr.borsatakip.v5.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tr.borsatakip.v5.data.ProviderRouter
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.DataMode
import tr.borsatakip.v5.model.MarketDataMetadata
import tr.borsatakip.v5.model.MarketDataState
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.Stock
import tr.borsatakip.v5.model.TechnicalSnapshot

class UiTruthPolicyTest {
    private val technical = TechnicalSnapshot(null, null, null, null, null, null, null, null, null, null, null, null, null)

    private fun metadata(state: MarketDataState, offline: Boolean = false) = MarketDataMetadata(
        providerId = "p",
        source = "provider",
        marketDataTimestamp = 1L,
        deviceReceivedAt = 1L,
        isLive = state == MarketDataState.LIVE,
        isDelayed = state != MarketDataState.LIVE,
        delayDurationMs = null,
        lastSuccessfulUpdateAt = 1L,
        state = state,
        isOffline = offline
    )

    @Test
    fun stale_or_unknown_stock_never_renders_numeric_values() {
        val candle = Candle(1L, 10.0, 10.0, 10.0, 10.0, 100.0)
        assertFalse(UiTruthPolicy.canShowStock(Stock("X", null, listOf(candle), "p", 1L, quotePrice = 10.0, marketDataMetadata = metadata(MarketDataState.STALE))))
        assertFalse(UiTruthPolicy.canShowStock(Stock("X", null, listOf(candle), "p", 1L, quotePrice = 10.0, marketDataMetadata = null)))
        assertTrue(UiTruthPolicy.canShowStock(Stock("X", null, listOf(candle), "p", 1L, quotePrice = 10.0, marketDataMetadata = metadata(MarketDataState.LIVE))))
    }

    @Test
    fun unverified_opportunity_is_not_promoted_to_real_market_data() {
        val base = Opportunity(
            symbol = "THYAO", companyName = "THY", price = 285.4, dailyChangePct = 1.84,
            score = 80, riskScore = 20, direction = "LONG", technicalLabel = "test", volumeLabel = "test",
            kapLabel = "—", liquidityLabel = "test", support = null, resistance = null, source = "test",
            dataTimestamp = 1L, candles = emptyList(), technical = technical, exchangeTimestamp = 1L
        )
        assertFalse(UiTruthPolicy.canShowOpportunity(base.copy(dataMode = DataMode.UNVERIFIED)))
        assertTrue(UiTruthPolicy.canShowOpportunity(base.copy(dataMode = DataMode.REALTIME)))
        assertFalse(UiTruthPolicy.canShowOpportunity(base.copy(dataMode = DataMode.REALTIME, marketDataMetadata = metadata(MarketDataState.STALE))))
    }

    @Test
    fun provider_none_is_unavailable_and_zero_is_not_used_for_missing_counter() {
        assertFalse(UiTruthPolicy.providerHasDisplayableData(ProviderRouter.RoutingStatus()))
        assertEquals("—", UiTruthPolicy.valueOrDash(false, 0))
        assertEquals("0", UiTruthPolicy.valueOrDash(true, 0))
    }

    @Test
    fun technical_error_is_redacted_for_user_facing_text() {
        assertEquals(
            "Piyasa veri kaynağından güncel veri alınamıyor.",
            UiTruthPolicy.userMessage("BIST_QUOTE_ERROR • Upstream quote is not live/current-session (age=901s)")
        )
    }


    @Test
    fun formatDataAge_hasCanonicalBoundaries() {
        assertEquals("0 sn", UiTruthPolicy.formatDataAge(0L))
        assertEquals("59 sn", UiTruthPolicy.formatDataAge(59_999L))
        assertEquals("1 dk", UiTruthPolicy.formatDataAge(60_000L))
        assertEquals("1 sa", UiTruthPolicy.formatDataAge(3_600_000L))
        assertEquals("1 gün", UiTruthPolicy.formatDataAge(86_400_000L))
        assertEquals("1 dk önce", UiTruthPolicy.formatDataAge(60_000L, relative = true))
        assertEquals("bilinmiyor", UiTruthPolicy.formatDataAge(null))
    }

    @Test
    fun providerStatus_neverPromotesStaleErrorToReady() {
        val stale = tr.borsatakip.v5.data.ProviderReadinessSnapshot(
            state = tr.borsatakip.v5.data.ProviderState.PROVIDER_STALE_READY,
            failureCode = tr.borsatakip.v5.data.ProviderFailureCode.STALE_DATA,
            message = "Provider READY doğrulamasının süresi doldu",
        )
        val presentation = UiTruthPolicy.providerStatus(stale)
        assertEquals(UiDataAvailability.STALE, presentation.availability)
        assertEquals(UiStatusTone.WARNING, presentation.tone)

        val delayed = stale.copy(
            failureCode = tr.borsatakip.v5.data.ProviderFailureCode.NONE,
            message = "GECİKMELİ ANALİZ",
        )
        assertEquals(UiDataAvailability.DELAYED, UiTruthPolicy.providerStatus(delayed).availability)
    }
}
