# Precision Image Splitter — Android app

A native Android wrapper (WebView) around the Precision Image Splitter web app.
Everything is bundled in the APK (Tailwind CSS and JSZip included), so it works offline.

## Getting the APK

Every push that touches this folder runs the **Build Image Splitter APK** GitHub
Actions workflow, which publishes `ImageSplitter.apk` on the repository's
**Releases** page (and as a workflow artifact). You can also start it manually
from the Actions tab ("Run workflow").

Install: open the release on your phone, download `ImageSplitter.apk`, tap it, and
allow "Install unknown apps" for your browser/file manager if prompted.
Requires Android 8.0 or newer.

## In the app

- **Open Image / Select from Device** opens the Android image picker.
- Tapping a piece, or **Download ZIP**, saves to `Downloads/ImageSplitter/`.

## Building locally

Needs JDK 17 and the Android SDK (platform 35):

```
cd image-splitter-android
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

## Editing the page

The page lives in `app/src/main/assets/index.html`. If you add new Tailwind
classes, regenerate the stylesheet:

```
npx tailwindcss@3.4.17 -c web/tailwind.config.js -i web/input.css -o app/src/main/assets/tailwind.css --minify
```

## Signing

`app/release.keystore` (password `imagesplitter`) is committed so every build is
signed with the same key and installs as an update over the previous version.
It's fine for personal sideloading; before publishing the app anywhere, use your
own key via the `SIGNING_STORE_FILE`, `SIGNING_STORE_PASSWORD`,
`SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD` environment variables.
