"""Fail-closed production E2E source provenance gate for V5.4.16.

The deployment revision is read from the live backend's public /v1/health
response. A repository variable may be supplied as an additional assertion,
but it is never accepted as the sole provenance source. E2E may continue only
when the expected GitHub source revision and the live deployment revision are
full 40-hex Git SHAs and exactly identical.
"""
from __future__ import annotations

import json
import os
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import httpx

_SHA40 = re.compile(r"^[0-9a-fA-F]{40}$")


def _norm(value: str | None) -> str:
    return (value or "").strip().lower()


def verify_revision(expected: str | None, deployed: str | None) -> tuple[bool, str]:
    expected_sha = _norm(expected)
    deployed_sha = _norm(deployed)
    if not expected_sha:
        return False, "BORSA_E2E_EXPECTED_REVISION is required"
    if not deployed_sha:
        return False, "live deployment revision is required"
    if not _SHA40.fullmatch(expected_sha):
        return False, "expected revision must be a full 40-hex Git SHA"
    if not _SHA40.fullmatch(deployed_sha):
        return False, "live deployment revision must be a full 40-hex Git SHA"
    if deployed_sha != expected_sha:
        return False, f"deployment revision mismatch: deployed={deployed_sha} expected={expected_sha}"
    return True, "live deployment revision matches expected Git source revision"


def extract_live_revision(payload: dict[str, Any], headers: httpx.Headers | None = None) -> str:
    for key in ("revision", "deploymentRevision", "gitSha", "commitSha"):
        value = _norm(str(payload.get(key) or ""))
        if value:
            return value
    if headers is not None:
        for key in ("x-deployment-revision", "x-render-git-commit"):
            value = _norm(headers.get(key))
            if value:
                return value
    return ""


def fetch_live_revision(base_url: str, timeout_seconds: float = 15.0) -> str:
    base = (base_url or "").strip().rstrip("/")
    if not base.startswith("https://"):
        raise ValueError("BORSA_E2E_BASE_URL must be an explicit HTTPS URL")
    with httpx.Client(base_url=base, timeout=timeout_seconds, follow_redirects=False) as client:
        response = client.get("/v1/health", headers={"Accept": "application/json"})
        response.raise_for_status()
        payload = response.json()
        if not isinstance(payload, dict) or payload.get("ok") is not True:
            raise RuntimeError("live /v1/health did not return ok=true")
        return extract_live_revision(payload, response.headers)


def main() -> int:
    expected = os.getenv("BORSA_E2E_EXPECTED_REVISION", "")
    declared = os.getenv("BORSA_E2E_DEPLOYMENT_REVISION", "")
    base_url = os.getenv("BORSA_E2E_BASE_URL", "")
    artifact_path = os.getenv("BORSA_E2E_PROVENANCE_ARTIFACT_PATH", "production-e2e-provenance.json").strip()
    timeout_seconds = float(os.getenv("BORSA_E2E_PROVENANCE_TIMEOUT_SECONDS", "15"))

    live_revision = ""
    error: str | None = None
    try:
        live_revision = fetch_live_revision(base_url, timeout_seconds)
        ok, detail = verify_revision(expected, live_revision)
        if ok and declared:
            declared_sha = _norm(declared)
            if not _SHA40.fullmatch(declared_sha):
                ok = False
                detail = "declared BORSA_E2E_DEPLOYMENT_REVISION must be a full 40-hex Git SHA"
            elif declared_sha != _norm(live_revision):
                ok = False
                detail = f"declared deployment revision disagrees with live health revision: declared={declared_sha} live={_norm(live_revision)}"
    except Exception as exc:
        ok = False
        detail = f"live provenance lookup failed: {type(exc).__name__}: {exc}"
        error = detail

    payload = {
        "schemaVersion": 2,
        "appVersion": "5.4.16",
        "checkedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "backendOrigin": (base_url or "").strip().rstrip("/") or None,
        "expectedRevision": _norm(expected) or None,
        "liveDeploymentRevision": _norm(live_revision) or None,
        "declaredDeploymentRevision": _norm(declared) or None,
        "result": "PASS" if ok else "FAIL",
        "detail": detail,
        "error": error,
    }
    if artifact_path:
        target = Path(artifact_path)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(payload, sort_keys=True))
    if not ok:
        print(f"PROVENANCE FAILED: {detail}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
