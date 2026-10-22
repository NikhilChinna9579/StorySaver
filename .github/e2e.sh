#!/usr/bin/env bash
# Runs inside the emulator step. Sets up a fake WhatsApp folder, runs the instrumentation tests,
# exercises the share sheet, and collects screenshots + logs into e2e-out/.
set -uo pipefail
OUT=e2e-out
mkdir -p "$OUT"
PKG=com.storysaver.app
adb wait-for-device
adb shell settings put global window_animation_scale 0 || true
# The stock launcher often stops responding on CI emulators and its dialog covers the screen.
adb shell settings put global hide_error_dialogs 1 || true
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher > /dev/null 2>&1 || true
sleep 15

# ---- Fake WhatsApp status folders (main account + a second account) ----
WA=/sdcard/Android/media/com.whatsapp/WhatsApp
S1="$WA/Media/.Statuses"
S2="$WA/accounts/999/Media/.Statuses"
adb shell mkdir -p "$S1" "$S2" "'$WA/Media/WhatsApp Images/Sent'"
adb push fixtures/img1.jpg "$S1/AB12CD34EF56.jpg"
adb push fixtures/img2.jpg "$S1/BC23DE45FA67.jpg"
adb push fixtures/vid1.mp4 "$S1/CD34EF56AB78.mp4"
adb push fixtures/vid2.mp4 "$S1/DE45FA67BC89.mp4"
adb push fixtures/vid3.mp4 "$S1/EF56AB78CD90"          # no extension: must be detected by content
adb shell touch "$S1/.nomedia"
adb shell touch "$S1/FA67BC89DE01.mp4"                 # empty = unfinished download: skipped
adb push fixtures/img3.jpg "$S2/0A1B2C3D4E5F.jpg"
adb push fixtures/vid4.mp4 "$S2/1B2C3D4E5F6A.mp4"
adb push fixtures/img1.jpg "$WA/Media/WhatsApp Images/Sent/chat.jpg"   # chat media: must NOT be listed
adb shell ls -laR "$WA" > "$OUT/fake-whatsapp-tree.txt" 2>&1

# ---- Install and run instrumentation tests ----
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# Fixtures for the on-device merge test
FX=/sdcard/Android/data/$PKG/files/fixtures
adb shell mkdir -p "$FX"
adb push fixtures/yt-video.mp4 "$FX/yt-video.mp4"
adb push fixtures/yt-audio.m4a "$FX/yt-audio.m4a"
for f in trim-src.mp4 trim-rot.mp4 trim-src.m4a; do [ -f "fixtures/$f" ] && adb push "fixtures/$f" "$FX/$f"; done
adb logcat -c
for cls in SmokeTest Mp4MergerTest MediaTrimmerTest WhatsAppE2ETest InstagramE2ETest BackgroundDownloadE2ETest ShareSheetE2ETest HomeQuickLinkE2ETest YouTubeE2ETest TrimE2ETest DownloadsTabE2ETest; do
  echo "=== $cls" >> "$OUT/instrument.txt"
  adb shell am instrument -w -e class "$PKG.$cls" "$PKG.test/androidx.test.runner.AndroidJUnitRunner" >> "$OUT/instrument.txt" 2>&1
done
cat "$OUT/instrument.txt"

adb pull "/sdcard/Android/data/$PKG/files/shots" "$OUT/shots" || true
cp "$OUT"/shots/*.png "$OUT/" 2>/dev/null || true

# ---- Share sheet: Instagram link shared into the app ----
adb shell am force-stop "$PKG"
adb shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "'https://www.instagram.com/reel/DeyQf_PzHlD/?exln=dmcxMDJ0YmxzMnFn'" \
  -n "$PKG/.share.ShareActivity"
sleep 25
adb exec-out screencap -p > "$OUT/30_share_link_flow.png"

# ---- Share sheet: a video file shared into the app ----
# Uses the reel the Instagram test saved (owned by the app). A shell-started share can't grant
# access to files it doesn't own; real apps (Gallery, WhatsApp) grant it themselves.
adb shell am force-stop "$PKG"
ID=$(adb shell content query --uri content://media/external/video/media --projection _id:_display_name \
     | grep instagram | sed -n 's/.*_id=\([0-9]*\).*/\1/p' | head -1)
echo "shared video id: $ID" >> "$OUT/instrument.txt"
if [ -n "$ID" ]; then
  adb shell am start -a android.intent.action.SEND -t video/mp4 \
    --eu android.intent.extra.STREAM "content://media/external/video/media/$ID" -n "$PKG/.share.ShareActivity"
  sleep 10
  adb exec-out screencap -p > "$OUT/31_share_file_flow.png"
fi

# ---- What actually got saved ----
{
  echo "Movies/StorySaver:"; adb shell ls -la /sdcard/Movies/StorySaver 2>&1
  echo "Pictures/StorySaver:"; adb shell ls -la /sdcard/Pictures/StorySaver 2>&1
  echo "Music/StorySaver:"; adb shell ls -la /sdcard/Music/StorySaver 2>&1
} > "$OUT/files.txt"
adb logcat -d -v brief "*:E" | grep -iE "storysaver|AndroidRuntime|FATAL" | head -200 > "$OUT/logcat-errors.txt" || true
adb logcat -d -v brief | grep -i "$PKG" | tail -400 > "$OUT/logcat-app.txt" || true

grep -q "FAILURES!!!\|INSTRUMENTATION_FAILED\|Process crashed" "$OUT/instrument.txt" && echo "E2E FAILURES" && exit 1
exit 0
