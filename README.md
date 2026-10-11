# Pin Scheduler

Android app for scheduling Pinterest Pins across days and times.

- **New pin**: one image with title, description, link, alt text, board, and an exact date/time.
- **Bulk schedule**: pick many images, set daily time slots (e.g. 9 AM, 2 PM, 8 PM), a start date and "every N days"; the app spreads the images across those slots.
- Queue with Upcoming / Posted / Failed tabs, edit, post now, retry, delete.
- Posts in the background with Android WorkManager at the scheduled time and notifies on success or failure.

## Download

Every push to `main` builds a new APK and publishes it at
**[Releases → latest](https://github.com/mnameerturk/auto-pinterest-app/releases/latest)**.

## One-time Pinterest setup

1. Create an app at [developers.pinterest.com](https://developers.pinterest.com/) → My apps.
2. Add the redirect URI `https://mnameerturk.github.io/auto-pinterest-app/callback.html` to the app.
3. In Pin Scheduler → Settings, enter the App ID and secret and tap **Connect with Pinterest**.
4. While the Pinterest app has **Trial** access, keep **Trial mode (sandbox)** on: pins are only visible to you. Apply for **Standard** access (Pinterest asks for a demo video of the login flow and a privacy policy: `https://mnameerturk.github.io/auto-pinterest-app/privacy.html`), then switch sandbox off.

The `docs/` folder holds the login callback and privacy pages; GitHub Pages must serve it (Settings → Pages → Deploy from branch → `main` / `/docs`).

## Signing

`signing/pinscheduler.jks` is a fixed key so each new build installs over the previous one. It's committed for convenience in this personal project; don't reuse it for a Play Store release.
