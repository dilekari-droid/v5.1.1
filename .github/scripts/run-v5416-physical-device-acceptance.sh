#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

EVIDENCE_DIR="${PHYSICAL_EVIDENCE_DIR:-$ROOT/physical-device-evidence/$(date -u +%Y%m%dT%H%M%SZ)}"
ALLOW_EMULATOR="${PHYSICAL_ALLOW_EMULATOR:-0}"
COLLECT_ONLY="${PHYSICAL_COLLECT_ONLY:-0}"
mkdir -p "$EVIDENCE_DIR"

command -v adb >/dev/null || { echo "adb is required" >&2; exit 2; }
test -x android/gradlew || { echo "android/gradlew is missing or not executable" >&2; exit 2; }

adb start-server >/dev/null
mapfile -t DEVICES < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
if [[ ${#DEVICES[@]} -ne 1 ]]; then
  echo "Exactly one authorized Android device is required; found ${#DEVICES[@]}" >&2
  adb devices -l >&2
  exit 3
fi
SERIAL="${DEVICES[0]}"
ADB=(adb -s "$SERIAL")

prop() { "${ADB[@]}" shell getprop "$1" | tr -d '\r'; }
QEMU="$(prop ro.kernel.qemu)"
MODEL="$(prop ro.product.model)"
MANUFACTURER="$(prop ro.product.manufacturer)"
SDK="$(prop ro.build.version.sdk)"
RELEASE="$(prop ro.build.version.release)"
FINGERPRINT="$(prop ro.build.fingerprint)"

if [[ "$QEMU" == "1" && "$ALLOW_EMULATOR" != "1" ]]; then
  echo "Refusing emulator: physical-device acceptance requires real hardware." >&2
  exit 4
fi

{
  echo "timestampUtc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "sourceCommit=$(git rev-parse HEAD 2>/dev/null || echo UNKNOWN)"
  echo "serial=$SERIAL"
  echo "manufacturer=$MANUFACTURER"
  echo "model=$MODEL"
  echo "androidRelease=$RELEASE"
  echo "apiLevel=$SDK"
  echo "fingerprint=$FINGERPRINT"
  echo "ro.kernel.qemu=${QEMU:-0}"
  echo "physicalHardware=$([[ "$QEMU" == "1" ]] && echo false || echo true)"
} | tee "$EVIDENCE_DIR/device.txt"

"${ADB[@]}" shell dumpsys battery > "$EVIDENCE_DIR/battery-before.txt" || true
"${ADB[@]}" shell dumpsys power > "$EVIDENCE_DIR/power-before.txt" || true
"${ADB[@]}" shell dumpsys package tr.borsatakip.v5 > "$EVIDENCE_DIR/package-before.txt" || true
"${ADB[@]}" logcat -c || true

if [[ "$COLLECT_ONLY" != "1" ]]; then
  set +e
  ./android/gradlew -p android --no-daemon connectedDebugAndroidTest 2>&1 | tee "$EVIDENCE_DIR/instrumentation.log"
  TEST_RC=${PIPESTATUS[0]}
  set -e
  echo "$TEST_RC" > "$EVIDENCE_DIR/instrumentation.exit-code"
  if [[ "$TEST_RC" -ne 0 ]]; then
    "${ADB[@]}" logcat -d -v threadtime > "$EVIDENCE_DIR/logcat-failure.txt" || true
    echo "Instrumentation failed; physical acceptance cannot PASS." >&2
    exit "$TEST_RC"
  fi
fi

"${ADB[@]}" logcat -d -v threadtime > "$EVIDENCE_DIR/logcat-after-instrumentation.txt" || true
"${ADB[@]}" shell dumpsys activity services tr.borsatakip.v5 > "$EVIDENCE_DIR/services-after-instrumentation.txt" || true

cat > "$EVIDENCE_DIR/manual-checklist.tsv" <<'EOF'
scenario	status	evidence_or_note
notification_permission_granted	NOT_RUN	
notification_permission_denied	NOT_RUN	
screen_lock_unlock	NOT_RUN	
app_home_background_return	NOT_RUN	
activity_recreate_rotate	NOT_RUN	
process_kill_recovery	NOT_RUN	
network_off_on_recovery	NOT_RUN	
notification_stop_action	NOT_RUN	
state_reconnect_after_reopen	NOT_RUN	
result_persistence	NOT_RUN	
battery_saver	NOT_RUN	
doze	NOT_RUN	
reboot_recovery	NOT_RUN	
force_stop_recovery	NOT_RUN	
EOF

cat > "$EVIDENCE_DIR/README.txt" <<EOF
Physical-device evidence initialized for:
  $MANUFACTURER $MODEL / Android $RELEASE / API $SDK

Instrumentation: $([[ "$COLLECT_ONLY" == "1" ]] && echo NOT_RUN || echo PASS)
Manual lifecycle scenarios remain NOT_RUN until an operator performs them and updates manual-checklist.tsv with PASS/FAIL plus evidence notes.
Do not call physical-device acceptance PASS while any required scenario remains NOT_RUN.
Protocol: android/PHYSICAL_DEVICE_ACCEPTANCE.md
EOF

echo "Physical-device baseline collected: $EVIDENCE_DIR"
echo "Manual lifecycle scenarios are intentionally NOT_RUN; follow android/PHYSICAL_DEVICE_ACCEPTANCE.md."
