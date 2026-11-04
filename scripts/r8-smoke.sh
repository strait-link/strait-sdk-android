#!/usr/bin/env bash
# R8 smoke: build examples/android as a minified release (R8, as apps ship it), install it on the adb device
# (ANDROID_SERIAL picks one), open a custom-scheme link and wait for the SDK's LinkEvent in logcat.
# Catches SDK code that breaks only after R8 (reflection, a missing keep rule), which the JVM tests can't see.
# The link resolves on the device; the dummy key and the .invalid endpoint keep it off the network.
set -euo pipefail
cd "$(dirname "$0")/.."
P=com.straitlink.example
WANT='direct link (custom_scheme, closed): matched=true path=/42'  # straitsample://product/42 = https://product/42

./gradlew -p examples/android assembleRelease --no-daemon \
  -PSTRAIT_PUBLISHABLE_KEY=st_pub_live_r8smoke -PSTRAIT_ENDPOINT=https://r8-smoke.invalid
adb get-state >/dev/null || { echo "FAIL: no device on adb"; exit 2; }
adb install -r examples/android/app/build/outputs/apk/release/app-release.apk
adb shell am force-stop $P
trap 'adb shell am force-stop $P' EXIT  # don't leave it in front on a shared device
adb shell am start -W -a android.intent.action.VIEW -d 'straitsample://product/42?color=red' $P
pid="$(adb shell pidof $P | tr -d '\r')"
for _ in $(seq 20); do
  got="$(adb logcat -d --pid="$pid" -s StraitExample:I | grep StraitExample || true)"
  if [[ "$got" == *"$WANT"* ]]; then echo "$got"; echo "PASS: R8 release build handled the link"; exit 0; fi
  sleep 1
done
echo "logcat (pid $pid): ${got:-nothing from StraitExample}"
adb logcat -d --pid="$pid" -b crash,main | tail -20
echo "FAIL: no LinkEvent from the R8 release build within 20 s"; exit 1
