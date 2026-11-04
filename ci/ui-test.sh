#!/usr/bin/env bash
# Runs on a virtual phone in GitHub Actions: installs the APK, drives the main screens
# and saves screenshots + crash logs to ./shots for review.
set -x
mkdir -p shots
PKG=com.glass.launcher

shot() { sleep "${2:-2}"; adb exec-out screencap -p > "shots/$1.png"; }
home() { adb shell input keyevent KEYCODE_HOME; sleep 1.5; }

# Tap the first on-screen element whose text contains $1 (Compose exposes text to UI Automator).
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml shots/ui.xml >/dev/null 2>&1
  python3 - "$1" <<'EOF'
import re, sys, subprocess
want = sys.argv[1]
xml = open("shots/ui.xml", encoding="utf-8").read()
for m in re.finditer(r'text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    if want in m.group(1):
        x = (int(m.group(2)) + int(m.group(4))) // 2
        y = (int(m.group(3)) + int(m.group(5))) // 2
        subprocess.run(["adb", "shell", "input", "tap", str(x), str(y)])
        print("tapped", want, x, y); break
else:
    print("NOT FOUND:", want)
EOF
}

adb install -r GlassLauncher.apk || exit 1
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell cmd role add-role-holder android.app.role.HOME $PKG 0 || true
adb shell cmd package set-home-activity $PKG/.ui.LauncherActivity || true
adb shell settings put secure enabled_accessibility_services $PKG/$PKG.lock.LockService
adb shell settings put secure accessibility_enabled 1

home; sleep 4
shot 00-welcome 1
tap_text "בוא נתחיל"; shot 00-welcome2 2
adb shell input keyevent KEYCODE_BACK
home; home
shot 01-home 5

# Widgets page (left of the first page by default).
adb shell input swipe 120 1300 960 1300 250; shot 02-today 3

# App Library (right of the last page by default).
home
for i in 1 2 3 4; do adb shell input swipe 960 1300 120 1300 250; sleep 1; done
shot 03-library 3
# Tap the small cluster (bottom-left quarter) of the first category card (the right-hand card).
adb shell input tap 200 720; shot 04-library-folder 2
# Tap the first app inside the opened folder: it must open (screen shows another app).
adb shell input tap 860 700; shot 04b-folder-app-opened 3
home; for i in 1 2 3 4; do adb shell input swipe 960 1300 120 1300 250; sleep 1; done
adb shell input tap 540 2200; sleep 1   # tap outside closes the folder
adb shell input swipe 540 1900 540 500 300; sleep 1
adb shell input swipe 540 1900 540 500 300; sleep 1
shot 05-library-bottom 2

# Search: swipe down in the middle of the home screen.
home; home
adb shell input swipe 540 1300 540 2000 300; shot 06-search 2
adb shell input text "set"; shot 07-search-typed 2

# Control Center: swipe down from the top-right part.
home; home
adb shell input swipe 540 400 540 1100 300; shot 08-control 2

# Long press on an empty spot of the home screen.
home; home
adb shell input swipe 540 1650 540 1650 900; shot 09-menu 2

# Settings imitation: open the system Settings like anyone would.
home
adb shell am start -a android.settings.SETTINGS
shot 10-splash 0.6
shot 11-decoy 3
tap_text "אפליקציות"; shot 12-decoy-apps 2
tap_text "הצגת כל"; shot 13-decoy-allapps 2

# Launcher's own settings.
home
adb shell am start -n $PKG/.SettingsEntry; shot 14-settings 3
adb shell input swipe 540 1900 540 600 300; sleep 1
shot 15-settings-scrolled 2
tap_text "תצוגה ועיצוב"; shot 16-look 2
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell input swipe 540 1900 540 600 300; sleep 1
tap_text "רקעים"; shot 17-wallpapers 2
tap_text "ים"; sleep 2
home; shot 18-home-wallpaper 3
# Edit mode + widget gallery.
adb shell input swipe 540 1650 540 1650 900; sleep 1
tap_text "הוספת ווידג"; shot 19-gallery 2
tap_text "בינוני"; shot 20-home-widget 3

# Messages: become the SMS app, receive a text, open it, reply.
for p in READ_SMS SEND_SMS RECEIVE_SMS RECEIVE_MMS READ_CONTACTS POST_NOTIFICATIONS READ_PHONE_NUMBERS; do adb shell pm grant $PKG android.permission.$p || true; done
adb shell cmd role add-role-holder android.app.role.SMS $PKG 0 || true
adb emu sms send 0501234567 "שלום! מה שלומך? נפגשים מחר בשמונה?" || true
sleep 3
adb emu sms send 0527654321 "תודה רבה" || true
sleep 2
adb shell am start -n $PKG/.messages.MessagesActivity; shot 21-messages 3
tap_text "שלום"; shot 22-thread 3
adb shell input tap 540 2250; sleep 1
adb shell input text "Great"; sleep 1
shot 23-typing 1
adb shell input keyevent KEYCODE_BACK; sleep 1
home
# Search: a sum and a Hebrew date.
adb shell input swipe 540 900 540 1500 250; sleep 1.5
adb shell input text "12*7"; shot 24-spotlight-calc 2

adb logcat -d -b crash > shots/crash.txt
adb logcat -d | grep -iE "glass|AndroidRuntime|FATAL" | tail -400 > shots/log.txt
exit 0
