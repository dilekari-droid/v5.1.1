from __future__ import annotations

import importlib
import sys
from pathlib import Path

BACKEND = Path(__file__).resolve().parents[1]
if str(BACKEND) not in sys.path:
    sys.path.insert(0, str(BACKEND))

provenance = importlib.import_module("e2e_provenance")


def test_provenance_requires_both_full_git_shas():
    good = "a" * 40
    assert provenance.verify_revision("", good)[0] is False
    assert provenance.verify_revision(good, "")[0] is False
    assert provenance.verify_revision("a" * 7, good)[0] is False
    assert provenance.verify_revision(good, "b" * 7)[0] is False


def test_provenance_rejects_mismatched_revisions():
    ok, detail = provenance.verify_revision("a" * 40, "b" * 40)
    assert ok is False
    assert "mismatch" in detail


def test_provenance_accepts_only_exact_revision_match():
    sha = "0123456789abcdef0123456789abcdef01234567"
    ok, detail = provenance.verify_revision(sha.upper(), sha)
    assert ok is True
    assert "matches" in detail
