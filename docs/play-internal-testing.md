# Running your own build in a real car via Google Play internal testing

Android Auto in a real car only accepts apps installed through Google Play (see the [README](../README.md#️-installing-it-in-a-real-car-google-play-is-required)). An **internal test track** is the official way to do that for a private app:

- only the Google accounts you add as testers can see and install it,
- there is no Google review for internal tests,
- a new release is usually available within minutes.

Play Console menu names change from time to time. If something is called differently, use the search box at the top of the Play Console.

## 1. Prepare your build

1. **Pick your own package name.** In [app/build.gradle.kts](../app/build.gradle.kts) change `applicationId = "de.i4energie"` to something unique, e.g. `io.github.<yourname>.i4energie`. Package names on Google Play are global and permanent.
2. **Create an upload key** (once):
   ```bash
   mkdir ~/.i4energie-signing
   keytool -genkeypair -keystore ~/.i4energie-signing/upload.jks -alias upload \
     -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Upload"
   ```
3. **Create `~/.i4energie-signing/keystore.properties`:**
   ```properties
   storeFile=/home/you/.i4energie-signing/upload.jks
   storePassword=<the password you chose>
   keyAlias=upload
   keyPassword=<the password you chose>
   ```
   On Windows, `~` is `C:\Users\<you>` and the path in `storeFile` uses forward slashes, e.g. `C:/Users/you/.i4energie-signing/upload.jks`.
4. **Back up that folder.** Without the upload key you cannot publish updates (Google support can reset it, but that takes days).
5. **Build the app bundle:**
   ```bash
   ./gradlew testDebugUnitTest bundleRelease
   ```
   Result: `app/build/outputs/bundle/release/app-release.aab`.

## 2. Google Play developer account (once)

1. Go to [play.google.com/console/signup](https://play.google.com/console/signup) with the Google account that is on your phone.
2. Choose a **personal** account, pay the one-time 25 USD fee, and verify your identity. Verification can take a few days.

New personal accounts need a closed test with many testers before a *public* release. That does **not** apply to internal testing.

## 3. Create the app

1. Play Console → **Create app**.
2. App name e.g. `i4 Energie`, default language, *App*, *Free*. Enter your package name if asked.
3. Accept the declarations → **Create app**.

## 4. Privacy policy and app content

Google wants a publicly readable privacy policy URL, even for internal tests. A short page is enough. It has to be readable in a browser without login (no PDF, no download prompt).

Then complete the tasks under **Dashboard → Set up your app**. Answers that fit this app:

| Task | Answer |
|---|---|
| App access | Some functionality is restricted: needs the user's own Home Assistant URL and access token |
| Ads | No ads |
| Content rating | Utility/other, all questions "No" |
| Target audience | 18+ |
| Data safety | No data collected or shared (data only goes to the user's own Home Assistant) |
| Government / financial / health apps | No |

## 5. Form factor

Under **Test and release → Setup → Advanced settings → Form factors**:

- Do **not** add *Android Automotive OS*. That is the Google system built into some cars (Volvo, Polestar…), not Android Auto, and it would require a separate build and review.
- If *Android Auto* is offered, add it and accept the terms. If it is not listed, nothing to do: Google detects Android Auto apps from the manifest (`com.google.android.gms.car.application`).

## 6. Internal test: testers and release

1. **Test and release → Testing → Internal testing**.
2. Tab **Testers** → create an email list containing your own Google account → save and tick it.
3. Copy the **join link** ("How testers join your test").
4. Tab **Releases** → **Create new release**.
5. First time only: accept **Play App Signing** (Google manages the app signing key, you keep the upload key).
6. Upload `app-release.aab`, add release notes, **Next** → **Save and publish** / **Start rollout**.

Red errors on the review page usually mean an app content task from step 4 is still missing.

## 7. Install on the phone

1. If you installed the app via adb before with the same package name: **uninstall it first.** It is signed with a different key and Play cannot update it. (The debug variant `…debug` can stay.)
2. Open the join link on the phone, signed in with the tester account → **Become a tester**.
3. Follow the link to the Play Store → **Install**. If it says "item not found", wait 10–15 minutes.
4. Open the app, enter your Home Assistant URL and token, save, and test the connection.

The app should now show up in Android Auto in your car and stay there. "Unknown sources" is no longer needed.

## Updates

1. Increase `versionCode` (and optionally `versionName`) in [app/build.gradle.kts](../app/build.gradle.kts).
2. `./gradlew bundleRelease`
3. Internal testing → **Create new release** → upload the new `app-release.aab`. The Play Store updates the app on your phone.

## Troubleshooting

| Problem | Fix |
|---|---|
| "Package name already in use" | Choose a different `applicationId` |
| "Version code already used" | Increase `versionCode` |
| "Signed with the wrong key" | You uploaded a debug build. Upload `app/build/outputs/bundle/release/app-release.aab` |
| Play Store: "Item not found" | Wait 10–15 min; check you are signed in with the tester account |
| App not visible in the car | Connect the phone to a PC and check `adb logcat | grep CAR.VALIDATOR`. `Package DENIED` means Android Auto does not consider it a Play install. |
| Build error "not a regular file" (project in a cloud-synced folder) | Delete `app/build` and rebuild. Exclude `build` and `.gradle` from syncing. |
