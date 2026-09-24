package tr.borsatakip.v5.ui

import tr.borsatakip.v5.data.ProviderRouter
import tr.borsatakip.v5.model.DataMode
import tr.borsatakip.v5.model.MarketDataMetadata
import tr.borsatakip.v5.model.MarketDataState
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.Stock
import tr.borsatakip.v5.model.ViopContract
import tr.borsatakip.v5.model.ViopQuote

/**
 * Canonical presentation truth gate shared by the high-traffic market screens.
 *
 * UI never upgrades or invents data. Numeric price/change/chart/signal values are rendered only
 * when the underlying model carries a displayable market-data state. UNKNOWN/STALE/OFFLINE and
 * UNVERIFIED are represented with an em dash and an explanatory state instead of zero.
 */
enum class UiDataAvailability { READY, DELAYED, STALE, UNAVAILABLE }

object UiTruthPolicy {
    private val displayableStates = setOf(MarketDataState.LIVE, MarketDataState.DELAYED, MarketDataState.FALLBACK)

    fun availability(metadata: MarketDataMetadata?): UiDataAvailability = when (metadata?.state) {
        MarketDataState.LIVE -> UiDataAvailability.READY
        MarketDataState.DELAYED, MarketDataState.FALLBACK -> UiDataAvailability.DELAYED
        MarketDataState.STALE -> UiDataAvailability.STALE
        MarketDataState.OFFLINE, MarketDataState.UNKNOWN, null -> UiDataAvailability.UNAVAILABLE
    }

    fun canShowStock(stock: Stock?): Boolean {
        if (stock == null) return false
        val price = stock.quotePrice ?: stock.candles.lastOrNull()?.close
        if (price == null || !price.isFinite() || price <= 0.0) return false
        val metadata = stock.marketDataMetadata ?: return false
        return metadata.state in displayableStates && !metadata.isOffline
    }

    fun canShowViopQuote(quote: ViopQuote?): Boolean {
        if (quote == null || !quote.price.isFinite() || quote.price <= 0.0) return false
        val metadata = quote.marketDataMetadata ?: return false
        return metadata.state in displayableStates && !metadata.isOffline
    }

    fun canShowViopContract(contract: ViopContract?): Boolean {
        if (contract == null) return false
        val price = contract.lastPrice ?: return false
        if (!price.isFinite() || price <= 0.0) return false
        val metadata = contract.marketDataMetadata ?: return false
        return metadata.state in displayableStates && !metadata.isOffline
    }

    fun canShowOpportunity(item: Opportunity): Boolean {
        if (!item.price.isFinite() || item.price <= 0.0) return false
        item.marketDataMetadata?.let { metadata ->
            return metadata.state in displayableStates && !metadata.isOffline
        }
        // Legacy/persisted records without metadata are accepted only when their data mode was
        // explicitly classified. UNVERIFIED must never be promoted to a numeric UI state.
        return item.dataMode in setOf(DataMode.REALTIME, DataMode.DELAYED, DataMode.EOD) &&
            item.exchangeTimestamp > 0L
    }

    fun providerHasDisplayableData(status: ProviderRouter.RoutingStatus): Boolean {
        if (status.activeProviderId.equals("none", ignoreCase = true) || status.circuitOpen) return false
        val state = status.dataState.uppercase()
        if (state.contains("DOĞRULANMADI") || state.contains("ÇEVRİMDIŞI") || state.contains("ESKİ VERİ")) return false
        return state.contains("CANLI") ||
            state.contains("DOĞRULANMIŞ") ||
            state.contains("GECİKMELİ") ||
            state.contains("YEDEK KAYNAK")
    }

    fun providerLabel(status: ProviderRouter.RoutingStatus): String = when {
        !providerHasDisplayableData(status) -> "Veri sağlayıcı doğrulanamadı"
        status.fallbackActive || status.dataState.contains("GECİKMELİ", ignoreCase = true) ->
            "${status.activeProviderLabel} • Gecikmeli veri"
        else -> "${status.activeProviderLabel} • Hazır"
    }

    fun userMessage(raw: String?, fallback: String = "Veri doğrulanamadı. Lütfen bağlantıyı yeniden doğrulayın."): String {
        val text = raw.orEmpty().trim()
        if (text.isBlank()) return fallback
        val upper = text.uppercase()
        return when {
            upper.contains("BIST_QUOTE_ERROR") || upper.contains("STALE_DATA") || upper.contains("STALE") ||
                upper.contains("CURRENT-SESSION") || upper.contains("CURRENT_SESSION") ->
                "Piyasa veri kaynağından güncel veri alınamıyor."
            upper.contains("AUTH_ERROR") || upper.contains("UNAUTHORIZED") || upper.contains("FORBIDDEN") ||
                upper.contains("401") || upper.contains("403") ->
                "Veri sağlayıcı kimlik doğrulaması başarısız. Bağlantı ayarlarını kontrol edin."
            upper.contains("RATE_LIMIT") || upper.contains("429") ->
                "Veri sağlayıcı istek sınırına ulaşıldı. Bir süre sonra yeniden deneyin."
            upper.contains("TLS") || upper.contains("DNS") || upper.contains("NETWORK") || upper.contains("TIMEOUT") ||
                upper.contains("CONNECTION") ->
                "Piyasa veri servisine şu anda ulaşılamıyor."
            upper.contains("NO_CONTRACT") || upper.contains("VIOP_CONTRACT") ->
                "Doğrulanmış aktif VİOP kontratı bulunamadı."
            upper.contains("HISTORY") || upper.contains("OHLCV") ->
                "Teknik analiz için gerekli geçmiş piyasa verisi alınamadı."
            else -> fallback
        }
    }

    fun valueOrDash(available: Boolean, value: Int): String = if (available) value.coerceAtLeast(0).toString() else "—"
}
