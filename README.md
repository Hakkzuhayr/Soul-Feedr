# Loop Player (Android)

A simple MP3 player built around looping.

## Features
- Open any MP3 (or other audio) file from your phone, or use **Open with → Loop Player** from a file manager
- Play / pause, seek bar, ±5 s skip
- **Loop button** cycles: `OFF → WHOLE TRACK → A↔B SECTION`
  - Whole track: gapless repeat
  - A-B section: tap **Set A** at the start of the part, **Set B** at the end, and that part repeats forever
  - Fine-tune A and B by ±0.1 s; the loop region is highlighted in orange on the seek bar
  - Loop counter shows how many times it has repeated
- Keeps playing with the screen off; controls in the notification / lock screen
- Pauses automatically when headphones are unplugged

## Build option 1 – Android Studio
1. Install Android Studio (https://developer.android.com/studio).
2. File → Open → select this `LoopPlayer` folder. Let Gradle sync (Android Studio will set up the Gradle wrapper).
3. Plug in your phone (USB debugging on) and press ▶ Run, or use Build → Build APK(s).

## Build option 2 – GitHub (no install)
Push this folder to a GitHub repository. The included workflow builds the APK automatically;
download it from the repository's **Actions** tab (artifact `LoopPlayer-apk`) and install it on your phone
(allow "install unknown apps").

Requires Android 8.0 or newer.
