"""Fail-closed production E2E source provenance gate for V5.4.16.

This helper never guesses a deployed revision. The caller must provide both the
GitHub source revision expected by the E2E run and the revision independently
recorded for the deployment. E2E may continue only when both are full 40-hex
Git SHAs and are exactly identical.
"""
from __future__ import annotations

import json
import os
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

_SHA40 = re.compile(r"^[0-9a-fA-F]{40}$")


def _norm(value: str | None) -> str:
    return (value or "").strip().lower()


def verify_revision(expected: str | None, deployed: str | None) -> tuple[bool, str]:
    expected_sha = _norm(expected)
    deployed_sha = _norm(deployed)
    if not expected_sha:
        return False, "BORSA_E2E_EXPECTED_REVISION is required"
    if not deployed_sha:
        return False, "BORSA_E2E_DEPLOYMENT_REVISION is required"
    if not _SHA40.fullmatch(expected_sha):
        return False, "expected revision must be a full 40-hex Git SHA"
    if not _SHA40.fullmatch(deployed_sha):
        return False, "deployment revision must be a full 40-hex Git SHA"
    if deployed_sha != expected_sha:
        return False, f"deployment revision mismatch: deployed={deployed_sha} expected={expected_sha}"
    return True, "deployment revision matches expected Git source revision"


def main() -> int:
    expected = os.getenv("BORSA_E2E_EXPECTED_REVISION", "")
    deployed = os.getenv("BORSA_E2E_DEPLOYMENT_REVISION", "")
    artifact_path = os.getenv("BORSA_E2E_PROVENANCE_ARTIFACT_PATH", "production-e2e-provenance.json").strip()
    ok, detail = verify_revision(expected, deployed)
    payload = {
        "schemaVersion": 1,
        "appVersion": "5.4.16",
        "checkedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "expectedRevision": _norm(expected) or None,
        "deploymentRevision": _norm(deployed) or None,
        "result": "PASS" if ok else "FAIL",
        "detail": detail,
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
