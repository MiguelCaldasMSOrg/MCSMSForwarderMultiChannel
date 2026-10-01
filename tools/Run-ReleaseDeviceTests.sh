#!/usr/bin/env bash
set -euo pipefail

variant="${1:?Expected regular or minified}"
expected_api="${2:?Expected Android API level}"
case "$variant" in
  regular|minified) ;;
  *) printf 'Unsupported release variant\n' >&2; exit 2 ;;
esac

artifact_dir="release-device-tests/$variant"
result_dir="device-test-results/$variant"
mkdir -p "$result_dir"

capture_failure() {
  exit_code=$?
  if [[ "$exit_code" -ne 0 ]]; then
    adb logcat -d > "$result_dir/logcat.txt" 2>&1 || true
    adb exec-out screencap -p > "$result_dir/failure.png" 2>/dev/null || true
  fi
  exit "$exit_code"
}
trap capture_failure EXIT

test -f "$artifact_dir/app.apk"
test -f release-device-tests/ui-test.apk
adb wait-for-device
actual_api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
test "$actual_api" = "$expected_api"
printf '%s\n' "$actual_api" > "$result_dir/api-level.txt"
adb install -r "$artifact_dir/app.apk"

run_tests() {
  runner_package="$1"
  report="$2"
  adb shell am instrument -w -r "$runner_package/androidx.test.runner.AndroidJUnitRunner" | tee "$report"
  grep -Eq '^OK \([1-9][0-9]* tests?\)' "$report"
  if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed|INSTRUMENTATION_STATUS_CODE: -(3|4)' "$report"; then
    exit 1
  fi
}

if [[ "$variant" = regular ]]; then
  test -f "$artifact_dir/test.apk"
  adb install -r -t "$artifact_dir/test.apk"
  run_tests com.miguelcaldas.mcsmsforwardermultichannel.test "$result_dir/platform-and-configuration.txt"
  adb uninstall com.miguelcaldas.mcsmsforwardermultichannel.test
fi
adb install -r -t release-device-tests/ui-test.apk
run_tests com.miguelcaldas.mcsmsforwardermultichannel.devicetests "$result_dir/ui.txt"
adb uninstall com.miguelcaldas.mcsmsforwardermultichannel.devicetests
adb uninstall com.miguelcaldas.mcsmsforwardermultichannel