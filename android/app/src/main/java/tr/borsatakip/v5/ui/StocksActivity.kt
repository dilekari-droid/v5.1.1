package tr.borsatakip.v5.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tr.borsatakip.v5.R
import tr.borsatakip.v5.analysis.OpportunityDirectionalFilterPolicy
import tr.borsatakip.v5.data.MarketDataQuality
import tr.borsatakip.v5.data.BistIndexDataService
import tr.borsatakip.v5.data.ProviderRouter
import tr.borsatakip.v5.data.favorites.FavoriteRepository
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.Stock
import java.util.Locale

class StocksActivity : BaseActivity() {
    private lateinit var repo: FavoriteRepository
    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private var query: String = ""
    private var filter: UiFilter = UiFilter.ALL
    private var indexDataState: IndexDataState = IndexDataState.LOADING

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stocks)
        setupBottomNav()

        repo = FavoriteRepository.get(this)
        list = findViewById(R.id.stocksList)
        status = findViewById(R.id.stocksStatus)
        list.layoutManager = LinearLayoutManager(this)

        findViewById<TextView>(R.id.stocksFavorites).setOnClickListener {
            startActivity(Intent(this, FavoritesActivity::class.java))
        }
        findViewById<TextView>(R.id.stocksSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.stocksNotifications).setOnClickListener {
            startActivity(Intent(this, NotificationsActivity::class.java))
        }
        findViewById<android.view.View>(R.id.stocksScanCard).setOnClickListener {
            startActivity(Intent(this, BistScanActivity::class.java))
        }
        findViewById<TextView>(R.id.stocksScanAction).setOnClickListener {
            startActivity(Intent(this, BistScanActivity::class.java))
        }

        bindSearch()
        bindFilters()
        resetIndexCards("Veri bekleniyor")

        lifecycleScope.launch {
            repo.migrateLegacyIfNeeded()
            bindList()
            refreshIndexCards()
        }
    }

    private fun bindSearch() {
        findViewById<EditText>(R.id.stocksSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString().orEmpty().trim()
                lifecycleScope.launch { bindList() }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun bindFilters() {
        listOf(
            R.id.stocksFilterAll to UiFilter.ALL,
            R.id.stocksFilterLong to UiFilter.LONG,
            R.id.stocksFilterShort to UiFilter.SHORT
        ).forEach { (id, selectedFilter) ->
            findViewById<Button>(id).setOnClickListener {
                filter = selectedFilter
                updateFilterColors()
                lifecycleScope.launch { bindList() }
            }
        }

        val unsupported = listOf(
            R.id.stocksFilterBist30 to "BIST 30",
            R.id.stocksFilterBank to "Bankacılık",
            R.id.stocksFilterIndustry to "Sanayi",
            R.id.stocksFilterTech to "Teknoloji"
        )
        unsupported.forEach { (id, label) ->
            findViewById<Button>(id).setOnClickListener {
                status.text = "$label üyelik/sınıflandırma verisi mevcut veri modelinde doğrulanmadığı için filtre uygulanmadı."
            }
        }
        updateFilterColors()
    }

    private fun updateFilterColors() {
        val buttons = listOf(
            UiFilter.ALL to findViewById<Button>(R.id.stocksFilterAll),
            UiFilter.LONG to findViewById<Button>(R.id.stocksFilterLong),
            UiFilter.SHORT to findViewById<Button>(R.id.stocksFilterShort)
        )
        buttons.forEach { (kind, button) ->
            val selected = kind == filter
            val color = when {
                !selected -> getColor(R.color.chip_bg)
                kind == UiFilter.SHORT -> getColor(R.color.red)
                else -> getColor(R.color.green)
            }
            button.backgroundTintList = ColorStateList.valueOf(color)
            button.setTextColor(if (selected) Color.WHITE else getColor(R.color.text_primary))
        }
    }

    private suspend fun bindList() {
        if (!::repo.isInitialized || !::list.isInitialized) return
        val rawSource = AppSession.lastOpportunities
        val route = ProviderRouter.routingStatus()
        val providerUsable = UiTruthPolicy.providerHasDisplayableData(route)
        val screenDataUsable = providerUsable && indexDataState != IndexDataState.UNAVAILABLE
        val source = if (screenDataUsable) rawSource.filter(UiTruthPolicy::canShowOpportunity) else emptyList()
        val normalizedQuery = query.lowercase(Locale.getDefault())
        val requestedDirection = when (filter) {
            UiFilter.LONG -> OpportunityDirectionalFilterPolicy.Direction.LONG
            UiFilter.SHORT -> OpportunityDirectionalFilterPolicy.Direction.SHORT
            UiFilter.ALL -> OpportunityDirectionalFilterPolicy.Direction.NEUTRAL
        }
        val filtered = source.asSequence().filter { item ->
            val searchMatch = normalizedQuery.isBlank() || item.symbol.lowercase(Locale.getDefault()).contains(normalizedQuery) ||
                item.companyName.orEmpty().lowercase(Locale.getDefault()).contains(normalizedQuery)
            val effective = OpportunityDirectionalFilterPolicy.effectiveDirection(item)
            val filterMatch = when (filter) {
                UiFilter.ALL -> true
                UiFilter.LONG -> effective == OpportunityDirectionalFilterPolicy.Direction.LONG
                UiFilter.SHORT -> effective == OpportunityDirectionalFilterPolicy.Direction.SHORT
            }
            searchMatch && filterMatch
        }.sortedWith(
            compareByDescending<Opportunity> {
                if (filter == UiFilter.ALL) it.rankingScore else OpportunityDirectionalFilterPolicy.scoreFor(it, requestedDirection)
            }.thenBy { it.symbol.uppercase(Locale.ROOT) }
        ).toList()
        val favorites = repo.symbols()
        list.adapter = StocksCompactAdapter(
            filtered,
            favorites,
            click = { item ->
                AppSession.selected = item
                startActivity(Intent(this, StockDetailActivity::class.java).putExtra("opportunity", item))
            },
            toggleFavorite = { item ->
                lifecycleScope.launch {
                    repo.toggle(item.symbol, item.companyName)
                    bindList()
                }
            }
        )

        status.text = when {
            indexDataState == IndexDataState.LOADING -> "BIST piyasa özeti doğrulanıyor • sonuç listesi doğrulama tamamlanana kadar gizli."
            !providerUsable -> "Veri sağlayıcı doğrulanamadı • fiyat, grafik ve sinyal sonuçları gösterilmiyor."
            indexDataState == IndexDataState.UNAVAILABLE -> "BIST özet verisi doğrulanamadı • çelişkili görünümü önlemek için sonuç listesi gizlendi."
            rawSource.isNotEmpty() && source.isEmpty() -> "Kayıtlı sonuçlar var ancak güncel veri durumu doğrulanamadı • sayısal değerler gösterilmiyor."
            source.isEmpty() -> "Veri yok • gerçek BIST taraması çalıştırıldığında sonuçlar burada gösterilir. • ${UiTruthPolicy.providerLabel(route)}"
            filtered.isEmpty() -> "${source.size} doğrulanmış sonuç içinde ${filter.label} filtresine uyan kayıt yok. • ${UiTruthPolicy.providerLabel(route)}"
            else -> "${filtered.size} sonuç • ${filter.label} • skor yüksekten düşüğe • ${UiTruthPolicy.providerLabel(route)}"
        }
    }

    private suspend fun refreshIndexCards() {
        indexDataState = IndexDataState.LOADING
        resetIndexCards("Veri bekleniyor")
        val stocks = withContext(Dispatchers.IO) {
            BistIndexDataService(this@StocksActivity).loadMany(listOf("XU100", "XU030", "XUTUM"))
        }
        val ready = listOf(
            bindIndexStock(stocks["XU100"], R.id.index100Value, R.id.index100Change, R.id.index100Chart),
            bindIndexStock(stocks["XU030"], R.id.index30Value, R.id.index30Change, R.id.index30Chart),
            bindIndexStock(stocks["XUTUM"], R.id.indexAllValue, R.id.indexAllChange, R.id.indexAllChart)
        ).count { it }
        indexDataState = when (ready) {
            3 -> IndexDataState.READY
            0 -> IndexDataState.UNAVAILABLE
            else -> IndexDataState.PARTIAL
        }
        if (::repo.isInitialized && ::list.isInitialized) bindList()
    }

    private fun bindIndexStock(stock: Stock?, valueId: Int, changeId: Int, chartId: Int): Boolean {
        val value = findViewById<TextView>(valueId)
        val change = findViewById<TextView>(changeId)
        val chart = findViewById<StockSparklineView>(chartId)
        if (!UiTruthPolicy.canShowStock(stock)) {
            value.text = "Veri yok"
            change.text = "—"
            change.setTextColor(getColor(R.color.text_secondary))
            chart.setCandles(emptyList(), null)
            return false
        }
        val verified = requireNotNull(stock)
        val price = verified.quotePrice ?: verified.candles.last().close
        val previous = verified.previousClose ?: verified.candles.getOrNull(verified.candles.lastIndex - 1)?.close
        val pct = if (previous != null && previous.isFinite() && previous > 0.0) {
            ((price / previous) - 1.0) * 100.0
        } else null
        value.text = if (price >= 1000.0) "%,.2f".format(Locale.getDefault(), price) else "%.2f".format(Locale.getDefault(), price)
        val state = MarketDataQuality.uiStatus(verified.marketDataMetadata)
        change.text = listOfNotNull(pct?.let { "%+.2f%%".format(Locale.getDefault(), it) }, state).joinToString(" • ")
        change.setTextColor(
            when {
                pct == null -> getColor(R.color.text_secondary)
                pct > 0.0 -> getColor(R.color.green)
                pct < 0.0 -> getColor(R.color.red)
                else -> getColor(R.color.text_secondary)
            }
        )
        chart.setCandles(verified.candles, pct)
        return true
    }

    private fun resetIndexCards(message: String) {
        listOf(
            Triple(R.id.index100Value, R.id.index100Change, R.id.index100Chart),
            Triple(R.id.index30Value, R.id.index30Change, R.id.index30Chart),
            Triple(R.id.indexAllValue, R.id.indexAllChange, R.id.indexAllChart)
        ).forEach { (valueId, changeId, chartId) ->
            findViewById<TextView>(valueId).text = "Veri yok"
            findViewById<TextView>(changeId).apply {
                text = message
                setTextColor(getColor(R.color.text_secondary))
            }
            findViewById<StockSparklineView>(chartId).setCandles(emptyList(), null)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::repo.isInitialized && ::list.isInitialized) {
            lifecycleScope.launch {
                refreshIndexCards()
            }
        }
    }

    private enum class IndexDataState { LOADING, READY, PARTIAL, UNAVAILABLE }

    private enum class UiFilter(val label: String) {
        ALL("Tümü"), LONG("LONG"), SHORT("SHORT")
    }
}
