# MYRA native Android app

This is a native Kotlin/Jetpack Compose Android project kept alongside the existing Expo MYRA project. It targets Android 8.0 (API 26) and newer. The Expo project has not been replaced.

## Build an APK from a phone

The project includes a GitHub Actions workflow at `.github/workflows/myra-android-apk.yml`. After this project is pushed to a **private GitHub repository**:

1. Open the repository's **Actions** tab in GitHub (a phone browser may be easier than the GitHub app).
2. Open **Build MYRA Android APK** and wait for both unit tests and the APK build to pass.
3. In that successful run, download the `myra-android-debug-apk` artifact ZIP and extract `app-debug.apk`.
4. Open the APK on Android. If prompted, allow your browser or file manager to install unknown apps, then confirm installation.

The workflow runs `testDebugUnitTest` before `assembleDebug`. The APK is signed with the Android debug signing identity, retained in the repository's GitHub Actions cache, and is for personal sideloading—not Play Store distribution. The uploaded build artifact expires after 30 days. **An APK is not considered ready until this workflow succeeds.**

## First run

1. Install and open MYRA.
2. Obtain a Gemini API key from Google's AI Studio and enter it in **AI Providers**. The key is required. OpenAI is optional and is used only when Gemini hits quota/service availability errors.
3. Tap **Encrypt and save on this phone**. Keys are encrypted using Android Keystore and stored locally; they are not embedded in the APK or sent to this project.
4. Grant microphone and notification permissions, then tap **Start hands-free** and confirm with the phone's biometric or secure screen lock.
5. A persistent notification appears while the microphone session is active. Use its Stop action or the in-app button to stop listening.

The `GEMINI_API_KEY` Replit secret is not compiled into this APK. The installed app makes provider requests directly from the phone using the key entered on that device. Google/OpenAI account eligibility, quota, network access, and any provider charges are the phone owner's responsibility. No OpenAI key is required to build or run MYRA.

If Gemini quota is exhausted and no OpenAI key is stored, MYRA says that quota is exhausted and that no reply was generated. If a configured fallback also fails, MYRA reports that failure and does not present a failed response as successful.

## Implemented phone actions

- Recognizes short spoken commands in Indian English (`en-IN`) using Android's speech-recognition service. Speech recognition may use an on-device service when available; otherwise the Android provider may process audio online according to that provider's settings.
- Speaks replies through Android Text-to-Speech, requesting `en-IN`. The installed engine/voice controls voice quality and gender; a natural Indian female voice is **not guaranteed**. Install an `en-IN` voice in Android's speech settings if one is available.
- Sets or changes the media-stream volume.
- Opens eligible installed launcher apps. Financial apps are rejected using Android's finance category and a package/name denylist. The denylist is not a guarantee against a new app that is incorrectly categorized or named.
- Prepares a phone number in Android's dialer. The user must review it and tap Call; MYRA does not place or answer calls.
- Uses Gemini 2.5 Flash for general assistant answers, with optional OpenAI `gpt-5-mini` fallback on quota, server availability, or network failures.

Spotify playback/control is not implemented in this build. Opening Spotify is not the same as starting or controlling playback; MYRA does not claim to have played music.

## Android permissions and limits

| Permission | Why MYRA requests it |
| --- | --- |
| Microphone (`RECORD_AUDIO`) | Speech recognition during a session the user explicitly starts. |
| Notifications (`POST_NOTIFICATIONS`, Android 13+) | Shows the active microphone foreground-service notification and user-confirmable actions. |
| Foreground service / microphone service | Keeps the visible listening session eligible to run while the app is not in front. |
| Wake lock (`WAKE_LOCK`) | Helps keep the CPU awake while the user has enabled listening; increases battery use. |
| Modify audio settings | Changes media-stream volume. |
| Internet (`INTERNET`) | Calls the configured Gemini/OpenAI provider and may be used by Android's speech-recognition service. |
| Biometric (`USE_BIOMETRIC`) | Requires the phone's strong biometric or device credential before starting a session. |

### Important limitations

- **This is not speaker/voice biometric verification.** Android's secure biometric prompt verifies the person starting a session. It does not authenticate every speaker. Anyone close enough to the phone may issue commands while listening is active.
- Listening is user-started and visible; there is no always-on wake word. MYRA repeatedly restarts Android's speech recognizer after each utterance. Android/OEM battery policies, recognizer timeouts, lock-screen behavior, missing language packs, and network conditions can interrupt it. Android does not guarantee a third-party app can keep listening continuously.
- Android restricts starting activities from the background and while locked. App-launch and dialer requests made off-screen become notifications; the user must unlock/tap to continue. MYRA does not bypass the lock screen.
- Calls are dialer-only; answering, ending, or placing calls without user confirmation is not supported.
- Known banking/UPI apps are not launched by MYRA, and there is no user override. Android does not provide a complete, authoritative list of every financial app, so an incorrectly labeled/categorized unknown app cannot be guaranteed blocked.
- A debug APK is not a Play Store release. The Actions build has not been tested on the user's specific phone until installed and exercised there.

## Quick manual checks after install

1. Start a session with phone authentication; confirm the persistent notification appears.
2. Say “set volume to 35 percent”; verify media volume changes and MYRA speaks.
3. Ask a simple question; verify the Gemini reply is spoken.
4. With an invalid/unavailable Gemini setup, verify the app reports an error rather than claiming success. If Gemini quota is exhausted and no OpenAI key is configured, verify the explicit quota message.
5. Say “open PhonePe” or “open my bank app”; verify MYRA refuses.
6. Say “call 9876543210”; verify the dialer opens with the number and requires the user to tap Call.
7. Lock the phone and test only actions Android allows in that state. App launch and dialing should require tapping the notification and unlocking.

