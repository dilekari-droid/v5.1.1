#!/usr/bin/env bash
set -euo pipefail

ready=0
for _ in $(seq 1 90); do
  if adb shell service check package 2>/dev/null | grep -q 'Service package: found'; then
    ready=1
    break
  fi
  sleep 2
done

test "$ready" = "1"
adb shell service check activity | grep -q 'Service activity: found'
./android/gradlew -p android --no-daemon connectedDebugAndroidTest

# API 35 performs the extra large-font accessibility acceptance pass. This keeps the normal
# 31/33/34/35 lifecycle matrix intact while proving the critical V5.4.16 controls at 200% font.
sdk="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
if [ "$sdk" = "35" ]; then
  reset_font() {
    adb shell settings put system font_scale 1.0 >/dev/null 2>&1 || true
  }
  trap reset_font EXIT
  adb shell settings put system font_scale 2.0
  sleep 2
  ./android/gradlew -p android --no-daemon connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=tr.borsatakip.v5.ui.UiUxV3AcceptanceInstrumentationTest#critical_controls_fit_and_meet_touch_target_at_current_font_scale \
    -Pandroid.testInstrumentationRunnerArguments.largeFont=true
  reset_font
  trap - EXIT
fi
