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
