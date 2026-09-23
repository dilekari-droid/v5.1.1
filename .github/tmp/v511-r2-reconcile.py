from pathlib import Path
import subprocess

R1 = "1a9edc6a5508dc33d660a4924d8e8516457e40c8"
FILES = [
    ".github/scripts/run-v5416-instrumentation.sh",
    "android/app/src/androidTest/java/tr/borsatakip/v5/ui/UiUxV3AcceptanceInstrumentationTest.kt",
    "android/app/src/main/res/layout/activity_opportunity.xml",
    "android/app/src/main/res/layout/activity_viop.xml",
    "backend/README.md",
    "backend/e2e_smoke.py",
    "backend/main.py",
    "backend/preapk_readiness.py",
    "backend/tests/test_contracts.py",
    "backend/tests/test_preapk_readiness.py",
]
subprocess.run(["git", "fetch", "origin", R1], check=True)
subprocess.run(["git", "checkout", R1, "--", *FILES], check=True)

# Preserve current main's stronger Bundle + SharedPreferences state model and
# extend it to persist the VIOP mode/tab as well.
p = Path("android/app/src/main/java/tr/borsatakip/v5/ui/ViopActivity.kt")
s = p.read_text()
replacements = [
    (
        "                showingUnderlying = true\n                applyDashboardFilter()",
        "                showingUnderlying = true\n                persistDashboardState()\n                applyDashboardFilter()",
    ),
    (
        "        showingUnderlying = false\n        applyDashboardFilter()",
        "        showingUnderlying = false\n        persistDashboardState()\n        applyDashboardFilter()",
    ),
    (
        "        outState.putBoolean(KEY_ADVANCED, uiAdvancedFilters)\n        super.onSaveInstanceState(outState)",
        "        outState.putBoolean(KEY_ADVANCED, uiAdvancedFilters)\n        outState.putBoolean(KEY_SHOWING_UNDERLYING, showingUnderlying)\n        super.onSaveInstanceState(outState)",
    ),
    (
        "        uiAdvancedFilters = savedInstanceState?.getBoolean(KEY_ADVANCED, prefs.getBoolean(KEY_ADVANCED, false)) ?: prefs.getBoolean(KEY_ADVANCED, false)\n    }",
        "        uiAdvancedFilters = savedInstanceState?.getBoolean(KEY_ADVANCED, prefs.getBoolean(KEY_ADVANCED, false)) ?: prefs.getBoolean(KEY_ADVANCED, false)\n        showingUnderlying = savedInstanceState?.getBoolean(KEY_SHOWING_UNDERLYING, prefs.getBoolean(KEY_SHOWING_UNDERLYING, false)) ?: prefs.getBoolean(KEY_SHOWING_UNDERLYING, false)\n    }",
    ),
    (
        "            .putBoolean(KEY_ADVANCED, uiAdvancedFilters)\n            .apply()",
        "            .putBoolean(KEY_ADVANCED, uiAdvancedFilters)\n            .putBoolean(KEY_SHOWING_UNDERLYING, showingUnderlying)\n            .apply()",
    ),
    (
        '        private const val KEY_ADVANCED = "advanced_filters"\n',
        '        private const val KEY_ADVANCED = "advanced_filters"\n        private const val KEY_SHOWING_UNDERLYING = "showing_underlying"\n',
    ),
]
for old, new in replacements:
    if old not in s:
        raise SystemExit(f"ViopActivity reconciliation anchor missing: {old[:100]!r}")
    s = s.replace(old, new, 1)
p.write_text(s)

# R1 test expected its old key names. Bind it to the current persistent model.
p = Path("backend/tests/test_contracts.py")
s = p.read_text()
start = s.index("def test_viop_filter_sort_state_survives_activity_recreation_source_contract():")
end = s.index("\n\ndef test_ui_accessibility_acceptance_covers_large_font_touch_targets_and_state_recreation():", start)
replacement = '''def test_viop_filter_sort_state_survives_recreation_and_reopen_source_contract():
    repo_root = Path(__file__).resolve().parents[2]
    source = (repo_root / "android" / "app" / "src" / "main" / "java" / "tr" / "borsatakip" / "v5" / "ui" / "ViopActivity.kt").read_text()
    assert "override fun onSaveInstanceState" in source
    assert "restoreDashboardState(savedInstanceState)" in source
    assert "persistDashboardState()" in source
    assert "PREFS_DASHBOARD" in source
    for key in ("KEY_QUERY", "KEY_CATEGORY", "KEY_DIRECTION", "KEY_SORT", "KEY_ADVANCED", "KEY_SHOWING_UNDERLYING"):
        assert key in source
    assert ".putBoolean(KEY_SHOWING_UNDERLYING, showingUnderlying)" in source
    assert "showingUnderlying = savedInstanceState?.getBoolean(KEY_SHOWING_UNDERLYING" in source'''
s = s[:start] + replacement + s[end:]
p.write_text(s)

# Reopen is now an actual Android instrumentation acceptance scenario.
p = Path("android/app/src/androidTest/java/tr/borsatakip/v5/ui/UiUxV3AcceptanceInstrumentationTest.kt")
s = p.read_text()
old = '''        SettingsStore(context).apply {
            baseUrl = ""
            apiKey = ""
            experimentalProvidersEnabled = false
            yahooFallbackEnabled = false
        }
    }
'''
new = '''        SettingsStore(context).apply {
            baseUrl = ""
            apiKey = ""
            experimentalProvidersEnabled = false
            yahooFallbackEnabled = false
        }
        context.getSharedPreferences("viop_dashboard_state", Context.MODE_PRIVATE).edit().clear().commit()
    }
'''
if old not in s:
    raise SystemExit("offline settings anchor missing")
s = s.replace(old, new, 1)
marker = '''    @Test
    fun critical_controls_fit_and_meet_touch_target_at_current_font_scale() {
'''
reopen = '''    @Test
    fun viop_filter_and_sort_state_survives_full_activity_reopen() {
        prepareOfflineSafeSettings()
        ActivityScenario.launch(ViopActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<android.widget.EditText>(R.id.viopSearch).setText("xu100")
                activity.findViewById<View>(R.id.filterShort).performClick()
                activity.findViewById<View>(R.id.sortMode).performClick()
                activity.findViewById<View>(R.id.detailedFilter).performClick()
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        ActivityScenario.launch(ViopActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("xu100", activity.findViewById<android.widget.EditText>(R.id.viopSearch).text.toString())
                assertEquals("Sırala: Sinyal", activity.findViewById<android.widget.Button>(R.id.sortMode).text.toString())
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.advancedFilterRow).visibility)
            }
        }
    }

'''
if marker not in s:
    raise SystemExit("instrumentation insertion anchor missing")
s = s.replace(marker, reopen + marker, 1)
p.write_text(s)
