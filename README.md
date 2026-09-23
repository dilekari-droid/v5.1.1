# BorsaTakipV5-Production

Production repository for the Android app and the TradeWize-backed FastAPI service.

## Current version

- Android: **V5.4.16** (`versionCode 556`, package `tr.borsatakip.v5`)
- Backend contract: **V5.4.16**
- Canonical intervals: `1m, 3m, 5m, 10m, 15m, 30m, 60m, 1d`
- Dynamic scanner hard page size: **60**
- Classic BIST snapshot batch size: **20**
- Effective default scanner pacing floor: **720 ms** from the 5,000 requests/hour ceiling

## Repository layout

- `android/` — canonical Android source
- `backend/` — FastAPI production backend
- `backend/tests/` — runtime and contract regression tests
- `contracts/api-v1.md` — canonical Android ↔ backend contract
- `contracts/routes-v1.json` — machine-readable route/client cross-map
- `.github/workflows/backend-ci.yml` — Python compile, contract tests and Docker build
- `.github/workflows/android-build.yml` — Android security gates, JVM/instrumentation tests, debug APK assembly/signature/alignment verification
- `.github/workflows/production-e2e.yml` — manual live production contract smoke test

## Production rules

1. No APK silently falls back to an unknown backend.
2. `APP_API_KEY`, TradeWize credentials, access tokens and private signing material are never committed or embedded in the APK.
3. Production backend startup fails when `APP_ENV=production` and `APP_API_KEY` is empty.
4. Stale data is never promoted to realtime.
5. `/v1/scanner/opportunities` is dynamic/closed-bar analysis only; attested realtime uses `/v1/realtime/opportunities`.
6. Unsupported realtime/WebSocket features fail closed and are exposed as capability=false.
7. `/v1/bist/snapshot-batch` returns exactly one row per requested symbol.
8. Scanner pacing obeys both minute and hourly limits and respects upstream `Retry-After`.
9. Android `/v1/...` routes are auto-discovered from Kotlin source and checked bidirectionally against `contracts/routes-v1.json` and FastAPI routes.
10. All upstream bar requests share one quota/pacing/retry gate and a bounded rolling cache; timeframe-aware batch data is reused by Android when valid.
11. VİOP contract discovery is fail-closed unless verified metadata is configured; missing expiry/tick/multiplier/last-trading fields are never synthesized.
12. TradingView/Research routes are explicit capability-gated fail-closed contracts instead of accidental 404s.
13. Manual BIST scan completion is published only after AtomicFile result persistence is verified; normal service shutdown cannot be reclassified as OS interruption.
14. Android backend requests carry a non-secret installation id, ingress quotas are layered by installation/auth + IP, and optional short-lived `BorsaSession` auth is supported.
15. Retired backend hosts are explicit configuration, not a blanket `*.railway.app` ban.

## Deployment

Deploy `backend/` with TradeWize credentials and `APP_API_KEY`. Docker sets `APP_ENV=production` automatically. `backend/render.yaml` contains the non-secret scanner/readiness defaults.

Android may receive the deployed HTTPS backend URL through `BORSA_BACKEND_URL` or Gradle property `borsa.backendUrl`. API keys remain runtime secrets and are stored origin-bound in Android encrypted storage.

## Release signing

A production release build requires external keystore variables (`BORSA_KEYSTORE_PATH`, `BORSA_STORE_PASSWORD`, `BORSA_KEY_ALIAS`, `BORSA_KEY_PASSWORD`) plus trusted attestation public keys when attested realtime is enabled. Debug APKs are installable verification builds, not Play Store production releases.
