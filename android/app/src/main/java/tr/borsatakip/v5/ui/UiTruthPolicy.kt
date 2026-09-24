package tr.borsatakip.v5.ui

import tr.borsatakip.v5.data.ProviderFailureCode
import tr.borsatakip.v5.data.ProviderReadinessSnapshot
import tr.borsatakip.v5.data.ProviderRouter
import tr.borsatakip.v5.data.ProviderState
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

enum class UiStatusTone { POSITIVE, WARNING, NEGATIVE, MUTED, INFO }

data class ProviderUiStatus(
    val availability: UiDataAvailability,
    val badge: String,
    val modeLabel: String,
    val tone: UiStatusTone,
    val message: String,
)

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

    fun formatDataAge(ms: Long?, relative: Boolean = false): String {
        if (ms == null) return "bilinmiyor"
        if (ms < 0L) return "saat doğrulaması bekleniyor"
        val value = when {
            ms < 60_000L -> "${ms / 1000L} sn"
            ms < 3_600_000L -> "${ms / 60_000L} dk"
            ms < 86_400_000L -> "${ms / 3_600_000L} sa"
            else -> "${ms / 86_400_000L} gün"
        }
        return if (relative) "$value önce" else value
    }

    fun providerStatus(snapshot: ProviderReadinessSnapshot): ProviderUiStatus {
        val delayed = snapshot.message.contains("GECİKMELİ", ignoreCase = true)
        val sessionClose = snapshot.message.contains("KAPANIŞ", ignoreCase = true)
        return when (snapshot.state) {
            ProviderState.PROVIDER_READY -> ProviderUiStatus(
                UiDataAvailability.READY,
                if (sessionClose) "KAPANIŞ HAZIR" else "HAZIR",
                if (sessionClose) "KAPANIŞ ANALİZİ" else "CANLI VERİ",
                if (sessionClose) UiStatusTone.WARNING else UiStatusTone.POSITIVE,
                userMessage(snapshot.message, if (sessionClose) "Kapanış verisi doğrulandı." else "Veri sağlayıcı hazır."),
            )
            ProviderState.PROVIDER_STALE_READY -> ProviderUiStatus(
                if (snapshot.failureCode == ProviderFailureCode.NONE && (delayed || sessionClose)) UiDataAvailability.DELAYED else UiDataAvailability.STALE,
                when {
                    delayed -> "GECİKMELİ ANALİZ"
                    sessionClose -> "KAPANIŞ HAZIR"
                    else -> "YENİDEN TEST"
                },
                when {
                    delayed -> "GECİKMELİ ANALİZ"
                    sessionClose -> "KAPANIŞ ANALİZİ"
                    else -> "VERİ DOĞRULAMASI GEREKLİ"
                },
                UiStatusTone.WARNING,
                userMessage(snapshot.message, "Veri sağlayıcı doğrulaması eski; yeniden test gerekli."),
            )
            ProviderState.PROVIDER_ERROR -> ProviderUiStatus(
                UiDataAvailability.UNAVAILABLE, "HATA", "VERİ YOK", UiStatusTone.NEGATIVE,
                userMessage(snapshot.message, "Veri sağlayıcı doğrulanamadı."),
            )
            ProviderState.PROVIDER_TESTING -> ProviderUiStatus(
                UiDataAvailability.UNAVAILABLE, "TEST EDİLİYOR", "DOĞRULANIYOR", UiStatusTone.INFO,
                "Veri sağlayıcı doğrulanıyor.",
            )
            ProviderState.PROVIDER_CONFIGURED -> ProviderUiStatus(
                UiDataAvailability.UNAVAILABLE, "TEST GEREKLİ", "DOĞRULAMA GEREKLİ", UiStatusTone.WARNING,
                "Veri sağlayıcı yapılandırıldı ancak henüz doğrulanmadı.",
            )
            ProviderState.PROVIDER_NOT_CONFIGURED -> ProviderUiStatus(
                UiDataAvailability.UNAVAILABLE, "YAPILANDIRILMAMIŞ", "YAPILANDIRILMAMIŞ", UiStatusTone.MUTED,
                "Veri sağlayıcı yapılandırılmamış.",
            )
        }
    }

    fun providerFailureTitle(code: ProviderFailureCode, timeframeLabel: String): String = when (code) {
        ProviderFailureCode.BACKEND_URL_MISSING, ProviderFailureCode.API_KEY_MISSING, ProviderFailureCode.INVALID_HTTPS -> "VERİ SERVİSİ YAPILANDIRILMAMIŞ"
        ProviderFailureCode.AUTH_ERROR -> "KİMLİK DOĞRULAMA HATASI"
        ProviderFailureCode.RATE_LIMIT -> "İSTEK SINIRI"
        ProviderFailureCode.NETWORK_TIMEOUT, ProviderFailureCode.NETWORK_ERROR, ProviderFailureCode.DNS_ERROR, ProviderFailureCode.TLS_ERROR, ProviderFailureCode.SERVER_ERROR -> "VERİ SERVİSİNE ULAŞILAMIYOR"
        ProviderFailureCode.EMPTY_DATA, ProviderFailureCode.BIST_HISTORY_ERROR, ProviderFailureCode.STALE_DATA -> "$timeframeLabel VERİ SERVİSİ HAZIR DEĞİL"
        else -> "VERİ ALINAMADI"
    }

    fun providerFailureMessage(code: ProviderFailureCode, timeframeLabel: String, detail: String?): String = when (code) {
        ProviderFailureCode.BACKEND_URL_MISSING, ProviderFailureCode.API_KEY_MISSING, ProviderFailureCode.INVALID_HTTPS -> "$timeframeLabel taraması için Production Backend bağlantısı gerekli."
        ProviderFailureCode.AUTH_ERROR -> "Production Backend kimlik doğrulaması başarısız. API anahtarını kontrol edin."
        ProviderFailureCode.RATE_LIMIT -> "$timeframeLabel verisi için istek sınırı aşıldı. Tarama sahte sonuç üretmeden durduruldu."
        ProviderFailureCode.EMPTY_DATA, ProviderFailureCode.BIST_HISTORY_ERROR, ProviderFailureCode.STALE_DATA -> "$timeframeLabel periyodu için güncel OHLCV verisi doğrulanamadı."
        else -> userMessage(detail, "$timeframeLabel veri servisine şu anda ulaşılamıyor.")
    }

    fun valueOrDash(available: Boolean, value: Int): String = if (available) value.coerceAtLeast(0).toString() else "—"
}
