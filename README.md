# Persian Bridge

I built this so I can talk to my Persian friends on Telegram without pretending my Persian is better than it is. It translates what they write into English, and it turns my English into the Persian people really type in chats. I always press Send myself. The app never does.

## What it does

- Floating bubble that only shows while Telegram is the app in front. I can drag it, it snaps to an edge and remembers where I left it. Tap opens the panel, long press lets me hide it or stop it.
- Read tab: translates their latest messages to English, with the Persian original underneath in smaller right to left text.
- Write tab: turns my English into colloquial Persian, shows a back translation so I can check the meaning, and flags anything ambiguous.
- Copy puts the Persian on the clipboard. Fill chat types it into Telegram's message box. If the box already has text, it asks before replacing it.
- Two modes. Smart uses Gemini (my own free API key). Private translates on the phone with ML Kit and sends nothing anywhere.

## What I never let it do

- Press Send, or tap anything in Telegram. The only action I use besides reading is setting the text of the message box.
- Look at any other app. The accessibility service is limited to `org.telegram.messenger` in its config, and my code checks the package again before it reads a single node.
- Store chat text. Messages, translations and previews live in memory and are dropped when I close the panel.

## Setup

1. Install the APK (see Building below).
2. Open Persian Bridge and go down the Set up list. Each row turns green by itself when it is done.
3. Allow "Show over other apps".
4. Turn on the accessibility service (see the Android 13 note right below).
5. Allow notifications so I can see the Stop button while the bubble runs.
6. Download the offline Persian models once, ideally on Wi-Fi. Private mode needs them.
7. Optional: add my Gemini key in Settings to use Smart.
8. Tap Start the bubble, then open a chat in Telegram.

### Android 13 and the greyed out switch

Android 13 blocks accessibility for apps installed from a file. If the switch is greyed out: try to turn it on once, then open App info for Persian Bridge, tap the three dots at the top right, choose Allow restricted settings, and try the switch again. I could not test this on a device while building, so the exact wording on my phone may differ.

## Getting the free Gemini key

1. Open Google AI Studio (the Get a free key button in Settings opens it).
2. Create an API key.
3. Paste it into Settings and tap Save key. The app asks Google which models that key can use and lists them right there.
4. Pick a model from the list, then tap Test.

I use a key from a separate Google account on purpose. On the free tier Google can use what I send to improve its products and people may review it. That is the main reason Private mode is the default and Smart is opt in per chat.

The free limits depend on the model and Google changes them. The app counts my requests per day (Pacific time, like Google's quota reset) so I can see how close I am, but my real limits are in AI Studio.

## Privacy design

New chats always start in Private. A chat only gets Smart after I allow it in that chat, and I also have a global Always Private switch.

Before anything goes to Gemini, text goes through these layers on the phone:

1. Normalization. Persian and Arabic digits, letter variants, ZWNJ, spaced out letters and simple leetspeak are flattened so tricks do not hide a value.
2. Structured values. Regex plus checksums (Luhn for cards, mod 97 for IBAN, the Iranian national ID checksum) and ML Kit Entity Extraction for English and Arabic. ML Kit has no Persian model, so Persian relies on my own rules.
3. Sensitive topics. A word list in English, Roman Urdu, Finglish and Persian. It is a lexicon with a phonetic key, not a trained model, so it misses things and sometimes flags harmless text. I treat it as a nudge.
4. Redaction. Values and names become placeholders like `[PHONE_1]`. When the answer comes back, every placeholder must appear exactly as often as it went out. If not, I throw the answer away and translate on the phone instead.
5. Preview. If anything was flagged, I see exactly what would leave the phone and choose: translate on the phone, send the hidden version, or cancel.
6. Learned list. If Gemini refuses a text, I add it to an encrypted list and similar text is flagged from then on.

The chat name is treated as a name too, because it is almost always the other person.

### Where my data lives

- One file in the app's no backup folder, encrypted with AES-256-GCM. The key is generated inside the Android Keystore and never leaves it. StrongBox is used when the phone has it.
- `allowBackup` is off and the data extraction rules exclude everything, so nothing goes to cloud backup or a device transfer.
- HTTPS only, system certificate authorities only, no cleartext traffic.
- My API key travels only in the `x-goog-api-key` header, never in a URL.
- No analytics, no crash reporting, no logging. R8 strips `Log` calls in the release build.
- The settings screens use `FLAG_SECURE`. The panel does too, so screenshots cannot capture a translation.
- Overlay windows ignore touches while something else covers them, so another app cannot trick me into tapping the bubble or panel.
- Copied text is marked sensitive and cleared from the clipboard after a minute, but only if it still carries my marker.

## Known limits

- Reading Telegram is best effort. Telegram does not promise a stable accessibility tree. Message text usually comes through, but who sent what and the chat name are guesses from the layout. If the title cannot be read, the chat counts as unknown and Smart stays off. When reading fails, the Read tab lets me paste a message instead.
- With the keyboard open, the panel is meant to shrink above it. I could not check that on a device.
- Private mode is literal and stiffer than Smart. I say so in the result.
- I only support the Play Store Telegram (`org.telegram.messenger`). For another variant I would change the package in `accessibility_config.xml`, in `ChatReader.TELEGRAM` and in the manifest `queries`.
- Android may kill the bubble service on some phones with aggressive battery savers. Starting it again from the app fixes it.

## Why prompts, context and a glossary instead of fine tuning

I have no dataset of my own chats and I do not want to make one. A good system prompt with examples of natural Persian, the last few chat lines as context, a tone and notes per chat, and a glossary for names and fixed words give me almost all of the gain. I can change any of them in a minute, and nothing about my conversations goes into a training pipeline.

## Which model

I don't hardcode the choice anymore. When I save a key (or tap Refresh in Settings) the app calls Google's `models.list` with that key, follows the page tokens, and keeps the Gemini text models that support `generateContent`. Image, speech, live and embedding models are filtered out because they can't return the JSON I need. The list is saved with my encrypted settings, so I see it without a network call next time.

If my current pick is not in the list, the app moves to the best match on its own. `gemini-3.8-flash` is the preferred default, and it is the strongest Flash model that was on the free tier when I built this. Spoken Persian is where weaker models sound like a textbook. The model id goes into a URL, so I only accept plain ids (letters, digits, dot, dash, underscore). The fallback preference lives in the `Models` object in `Store.kt`.

## Building the APK

### Without the Android SDK (what I actually do)

I don't have the SDK installed, so GitHub builds it for me with `.github/workflows/build.yml`.

1. I push this folder to a private GitHub repo, on the `main` branch.
2. In the repo I open Actions, pick Build APK and tap Run workflow. A push to `main` also starts it.
3. When it turns green I open the run and download `persian-bridge-debug` from Artifacts. It is a zip with the APK inside.
4. I copy the APK to my phone and open it.

The debug APK is signed with the standard debug key, which is fine for my own phone. For a release APK I add four repository secrets (Settings, Secrets and variables, Actions): `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`. To get the first one I run `base64 -w0 persian-bridge.jks` locally and paste the output. With all four present the workflow also uploads `persian-bridge-release`. The secrets never reach the logs, and the keystore file is deleted from the runner right after the build.

I pinned each action to a full commit SHA, not a tag, so a moved tag can't change what runs in my build. When I bump one I look up the new tag's SHA first.

### On my own machine

I need a JDK (17 or newer, Android Studio's bundled one works) and the Android SDK with platform 37.

Debug build for quick testing:

```
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

Signed release build, which is the one I actually keep on my phone:

```
keytool -genkeypair -v -keystore persian-bridge.jks -alias bridge -keyalg RSA -keysize 4096 -validity 10000
```

Then I create `keystore.properties` in the project root (it is gitignored, like `*.jks`):

```
storeFile=persian-bridge.jks
storePassword=...
keyAlias=bridge
keyPassword=...
```

```
./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/app-release.apk`. Without `keystore.properties` the release APK is unsigned and Android will not install it.

To install: `adb install -r app/build/outputs/apk/release/app-release.apk`, or copy the file to the phone and open it. I keep the keystore and its passwords out of the repo and backed up somewhere safe, because losing it means I cannot update the app in place.

## Project layout

```
app/src/main/java/app/persianbridge/
  Store.kt         encrypted settings, models list, clipboard helper
  Privacy.kt       the privacy layers
  Translator.kt    prompts, Gemini client, ML Kit, and the Bridge that ties them together
  ChatReader.kt    the Telegram only accessibility service
  BubbleService.kt the bubble, menu and panel windows
  Panel.kt         the panel's state and screens
  Ui.kt            theme, fonts, icons, shared components
  MainActivity.kt  setup screen
  SettingsUi.kt    settings screen
.github/workflows/build.yml   cloud APK build
```

I kept the files few and split them by job, not by layer. Anything bigger than this app does not need more folders.

## Look

Teal means the text stays on my phone. Amber means it goes to Google. Everything else stays quiet so those two colors are the only thing that stands out. Fonts are Instrument Sans for the interface and Vazirmatn for Persian. Both are licensed under the SIL Open Font License, and the license texts are in `app/src/main/assets/font-licenses.txt`.