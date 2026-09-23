# BorsaTakip Production API v1 Contract

This document is the canonical wire contract for Android **V5.4.16+** and the production backend. The machine-readable route map is `routes-v1.json`.

## Authentication

Android protected requests use the centralized backend-security layer and send `X-Install-ID`. The compatibility bootstrap credential is `Authorization: Bearer <APP_API_KEY>`.

`POST /v1/auth/session` issues a signed, short-lived `BorsaSession` token bound to the installation id. In production, a strong `SESSION_TOKEN_SECRET` is mandatory; development/test may leave short-lived sessions disabled. Tokens carry a key id/version and honor `SESSION_TOKEN_NOT_BEFORE`. `SESSION_TOKEN_PREVIOUS_KEYS_JSON` supports an overlap window during key rotation; installation subject epochs allow immediate revocation of all older tokens for one installation. A token presented with a different/missing `X-Install-ID` is rejected.

`TRADEWIZE_API_KEY`, upstream access tokens and signing private keys never belong in the APK. Production Docker deployments run with `APP_ENV=production`; startup fails closed unless `APP_API_KEY`, a strong `SESSION_TOKEN_SECRET`, and TradeWize credentials are present.

## Canonical intervals

The public Android ↔ backend interval set is exactly:

`1m`, `3m`, `5m`, `10m`, `15m`, `30m`, `60m`, `1d`

Rules:

- Wire values are lowercase.
- `240m` is not a public Android interval.
- `1d` maps internally to provider `1D` when required.
- `10m` is built from two **closed** `5m` bars when the upstream lacks canonical 10m bars.
- Open bars are never accepted as technical-analysis bars.

## Health and BIST readiness

### `GET /v1/health`

Returns backend process metadata. Health is not market-data readiness.

### `GET /v1/preflight`

Returns independent blocks: `authentication`, `symbols`, `quote`, `history`, and `analysisMode`.

`analysisMode` is `REALTIME`, `DELAYED_ANALYSIS_AVAILABLE`, or `UNAVAILABLE`. Stale quote data is never promoted to realtime.

### `GET /v1/bist/symbols`

`items` must be a string array and `count == items.length`.

### `GET /v1/bist/quote/{symbol}`

Uses TradeWize last-price first, then recent ticks when required. If no fresh tick exists the endpoint fails closed rather than widening the realtime age budget.

### `GET /v1/bist/history/{symbol}`

Query: `range`, `interval`. `range=max` is a supported explicit all-time request and is translated to a broad real upstream time window; it no longer fails validation with HTTP 422. The backend returns the complete normalized result received for that range and **does not clip the response to the rolling cache limit**. Only the in-memory cache is truncated; actual historical coverage remains provider-source authoritative.

### `GET /v1/bist/history-window/{symbol}`

Query: `from`, `to`, `interval`.

History endpoints return closed bars only.

### `GET /v1/bist/snapshot-batch`

Canonical classic-BIST batch endpoint used by `MobileMarketDataProvider`.

Query examples:

`/v1/bist/snapshot-batch?symbols=THYAO,ASELS,EREGL`

`/v1/bist/snapshot-batch?symbols=THYAO,ASELS&interval=5m&from=<epoch-ms>&to=<epoch-ms>`

The batch uses the **same BIST quote resolver** as preflight and the single-quote endpoint. Stale `last-price` candidates therefore receive the same `recent-ticks` freshness fallback; a still-stale quote becomes a symbol-level error instead of being presented as live.

For manual scans Android supplies the selected timeframe so the returned history can be reused by `IntervalMarketDataProvider`; a second per-symbol history request is only used as a fallback when prefetched coverage is insufficient. Android also sends a stable `X-Request-ID` for a batch and reuses it across retries. The backend fingerprints request parameters, joins in-flight work and briefly caches a completed result so retry/cancellation does not create uncontrolled duplicate upstream work.

Contract invariants:

- Maximum requested symbols per call is published as `scanPolicy.snapshotBatchMaxSymbols` (default 20).
- Requested order and cardinality are preserved.
- Every requested symbol appears **exactly once** in `items`.
- A successful row contains `symbol`, `quote`, and `history`.
- A symbol-level failure still returns one row with `symbol` and `error`; symbols are never silently dropped.
- Android validates symbol identity, duplicate absence, row count, quote/history structure and OHLCV integrity.


## Short-lived backend session authentication

### `POST /v1/auth/session`

Production hardening path. Bootstrap requires the existing origin-bound long-lived API key plus `X-Install-ID`; development/test may keep it disabled by omitting `SESSION_TOKEN_SECRET`. The response returns a bounded `BorsaSession` token and expiry. Android stores this token through the same Keystore-backed encrypted settings layer and `BackendRequestSecurity` prefers it for normal backend calls until it nears expiry; the long-lived key remains the bootstrap/fallback credential. Session tokens are installation-bound in middleware and cannot mint further sessions.

### `POST /v1/auth/revoke-installation` (operator/bootstrap-key path)

Requires the long-lived bootstrap API key, increments the target installation subject epoch, and invalidates previously issued session tokens for that installation. A session token cannot call this administrative revoke path.

This is **not** device attestation. The backend contains V538 canonical SHA256withECDSA envelope/key-rotation/sequence/nonce signing infrastructure, but `attestationReady` remains false until a real realtime data engine plus real-device attestation/replay E2E is verified.

## Provider capabilities

### `GET /v1/provider/capabilities`

Top-level readiness has global meaning:

- `providerReady` = global/multi-market readiness
- `globalProviderReady` = same explicit global meaning
- `multiMarketReady` = all required UI markets ready
- `bistReady` = BIST realtime readiness only

Each market object exposes readiness and data-support fields independently.

The top-level `features` object is authoritative for optional protocol capabilities:

- `bistSnapshotBatch`
- `dynamicScanner`
- `realtimeScannerRest`
- `liveMarketWebSocket`
- `realtimeScannerWebSocket`
- `attestationReady`
- `attestationConfigured`
- `viopContractsReady`
- `tradingViewSignals`
- `researchFoundation`
- `allTimeHistory` (default false until explicit production provider coverage verification)
- `barHistoryCache`
- `ingressRateLimit`
- `shortLivedSessionAuth`
- `sessionKeyRotation`
- `sessionRevocation`
- `barCachePersistence`
- `distributedProviderQuotaConfigured`
- `fullBistFiveMinuteSla`

V5.4.16 enables verified implemented capabilities only. `allTimeHistory` is fail-closed by default even though `range=max` is implemented; deployment may set `ALL_TIME_HISTORY_VERIFIED=true` only after a provider E2E proves complete listing-history coverage. `scanPolicy` additionally publishes snapshot batch size/timeouts, bar concurrency, pacing and the configured provider quota scope. Android consumes those values with bounded fallbacks. Attested realtime REST and both WebSocket features remain fail-closed. `fullBistFiveMinuteSla=false` is deliberate: with the currently verified 5,000 requests/hour upstream ceiling, a cold 640-symbol bar refresh cannot honestly guarantee a complete five-minute cycle without a verified provider batch-bars capability.

## Dynamic scanner

### `GET /v1/scanner/opportunities`

This endpoint is only the dynamic/closed-bar scanner contract used by `DynamicMarketScannerClient`.

Query parameters: `market`, `assetType`, `timeframe`, `offset`, `limit`, `minScore`, `includeWatch`.

It must not be interpreted as the attested realtime scanner.

### Server-enforced pagination

`SCANNER_BATCH_SIZE=60` is a hard server cap. Mandatory coverage fields are `universeCount`, `scannedSymbols`, `remainingSymbols`, `nextOffset`, `coverageComplete`, and `partial`.

Android continues until `coverageComplete=true`; partial coverage is never presented as a full-market scan.

### Rate safety

Every upstream `/market-data/bars` request—including normal BIST history, history-window, VİOP history, snapshot-batch and dynamic scanner bars—passes through one global quota/pacing/retry gate. Production defaults to `UPSTREAM_QUOTA_SCOPE=all`, so quote/tick/metadata market-data GETs use the same conservative gate until provider documentation proves a narrower scope. `bars_only` is an explicit override, not the default.

A rolling in-memory cache keyed by market/symbol/interval reuses covered closed bars and incrementally fetches missing windows; it never fabricates candles. Cache state is bounded by per-series bar count, idle TTL, maximum key count, and maximum total cached bars. Optional atomic disk persistence/background prewarm can be enabled by deployment configuration; prewarm still uses the same quota-gated history path. `range=max` responses are returned from the untruncated normalized fetch while only the retained cache tail is bounded.

The provider gate enforces:

- minute weighted budget (`SCANNER_RATE_LIMIT_WEIGHT_PER_MINUTE`, default 120),
- hourly request ceiling (`SCANNER_REQUESTS_PER_HOUR`, default 5000),
- configurable request weight pending formal provider bar-weight verification,
- bounded retry,
- upstream `Retry-After` when HTTP 429 is returned; the cooldown is promoted to the shared market-data gate so peer bar/recent-tick callers also wait instead of only backing off the failed coroutine.

With the default hourly ceiling the effective request-start floor is at least **720 ms**, even if a lower configured pacing value is supplied. When `UPSTREAM_DISTRIBUTED_QUOTA=true`, an optional Redis/Lua gate shares the minute/hour/cooldown state across backend instances; without that explicit configuration the limiter remains process-local.

The backend must not invent a bar-count weight formula. When the provider's official formula is verified, the configurable weight model can be upgraded without changing the Android wire contract.


## VİOP contract universe

### `GET /v1/viop/contracts`

Query: `limit` (1–200), optional numeric `cursor`.

A real VİOP universe requires `TRADEWIZE_VIOP_CONTRACT_METADATA_PATH` to point at a verified upstream metadata route. Required normalized fields are `contractType=FUTURE`, `underlying`, `expiry`, `tickSize`, `multiplier`, and `lastTradingAt`. Missing fields are rejected rather than synthesized.

Response includes `items`, `totalCount`, `hasMore`, `nextCursor`, and `universeAsOf`. Metadata timestamp/freshness/completeness are validated; stale/partial/duplicate universes are rejected, expired contracts are excluded, and near-expiry contracts carry explicit data-quality metadata. Without valid metadata the capability is `viopContractsReady=false` and the endpoint returns a fail-closed 503.

## Optional application features

### `POST /v1/tradingview/webhook` (backend-only ingress)

When `TRADINGVIEW_WEBHOOK_SECRET` and `TRADINGVIEW_DB_PATH` are both configured, the backend accepts bounded JSON webhook payloads authenticated by `X-TradingView-Signature` HMAC-SHA256. Inserts are event-id and semantic-dedupe protected, persisted in SQLite/WAL, and subject to retention cleanup. Partial configuration is rejected at startup.

### `GET /v1/tradingview/signals`

Reads the real persistent store with `limit/cursor`, source timestamps and freshness metadata when TradingView is configured. Otherwise `tradingViewSignals=false` and the route returns structured 503 `FEATURE_DISABLED`; it never fabricates an empty success.

### `GET /v1/research/foundation`

Research remains structured 503 `FEATURE_DISABLED` until a verified backend source exists. Android fallback output is explicitly tagged `FALLBACK` rather than being presented as backend data.

## Backend ingress protection

Android→backend traffic has a token-bucket limiter separate from TradeWize upstream pacing. Requests are charged to a per-installation/credential bucket **and** a remote-IP bucket, so changing a caller-controlled installation id cannot by itself bypass ingress protection. Heavy scanner/snapshot/history routes carry higher ingress weights. HTTP 429 responses include `Retry-After`. Ingress identity state is additionally bounded by idle TTL and LRU entry count so caller-controlled installation ids cannot grow process memory without bound.

## Realtime scanner separation

### `GET /v1/realtime/opportunities`

Reserved for the attested V538 realtime contract used by `RealtimeScannerClient`.

It is intentionally **not** an alias of `/v1/scanner/opportunities`. Until provider identity, replay protection, trusted-time metadata, snapshot hashes and ECDSA attestation are implemented end to end, this endpoint returns a fail-closed 503 capability error.

## WebSockets

### `WS /v1/live`

Reserved for `LiveMarketSocket`.

### `WS /v1/scanner/live`

Reserved for `RealtimeScannerSocket`.

In V5.4.16 both routes exist only as explicit fail-closed capability routes. They return a structured `FEATURE_DISABLED` error and close rather than producing fake live data. Android treats that response as terminal for the corresponding realtime feature.

## Host migration

Android no longer treats every `*.railway.app` hostname as retired. Retired backend origins are supplied explicitly through `BORSA_RETIRED_BACKEND_HOSTS` / `borsa.retiredBackendHosts` as a semicolon-separated host list.

This preserves origin-bound API-key cleanup for truly retired backends without blocking a valid current Railway deployment.

## V5.4.16 hardening summary

- Added `/v1/bist/snapshot-batch` with exact one-row-per-symbol semantics.
- Split dynamic scanner and realtime scanner route contracts.
- Added explicit fail-closed realtime/WebSocket capabilities instead of route ambiguity.
- Added automatic Kotlin `/v1/...` discovery and bidirectional Android↔contract↔FastAPI route cross-checking.
- Unified all bar requests under hourly/minute pacing and `Retry-After` handling; added rolling/incremental bar cache.
- Unified preflight/single/batch BIST quote freshness through one resolver.
- Added fail-closed real-metadata VİOP universe/pagination contract.
- Added explicit TradingView/Research capability routes, ingress rate limiting and optional installation-bound short-lived sessions.
- Added production fail-closed APP API-key startup validation.
- Replaced blanket Railway retirement with explicit retired-host configuration.
