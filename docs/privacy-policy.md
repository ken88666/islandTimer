# Privacy Policy for islandTimer

Last updated: 8 November 2026

islandTimer is an Android app that tracks how long you spend in the apps you select, and shows the running time as a capsule on Xiaomi HyperOS's Super Island. It is a local, single-user utility: there are no accounts, no servers, and the app does not declare the INTERNET permission, so no data can be sent anywhere.

## What the app uses on your device

**Usage access.** To calculate usage time, the app uses Android's UsageStats API — the same mechanism the system's own Digital Wellbeing features rely on. Enabling "Usage access" lets the app read the daily foreground durations for the apps you have selected. Those numbers are aggregated into daily and weekly totals and stored on the device.

**Accessibility service.** The app offers an optional accessibility service for one specific reason: the UsageStats API is not real-time enough to start and stop a timer the moment you switch apps. The service watches window-change events to determine the current foreground app. Its window-content capability is used only to confirm the foreground package name, so that transient windows (lock screen, notification shade, launcher animations) don't stop the timer by mistake. The service does not read or store screen content.

**Notifications.** The timer capsule on Super Island is delivered as an Android notification, which requires notification permission. The notification is rendered by the system on your device.

**Battery and background settings.** On MIUI/HyperOS devices, background apps are often frozen or killed unless the user explicitly exempts them. To keep the timer accurate, the app includes shortcuts to the relevant settings pages (battery optimization, auto-start). Those settings are changed by you, in the system UI.

## Data on the device

The app stores two things locally: the list of apps you have chosen to track, and their accumulated durations. There is no other data — no identifiers, no advertising ID, no analytics SDK. Nothing is uploaded, shared, or sold, because the app has no network capability at all. Uninstalling the app removes this local data along with it.

## Children

The app is a screen-time utility aimed at adults and is not directed at children under 13. Since no data leaves the device, the app does not collect information from anyone, children included.

## Changes to this policy

If the app's behaviour changes — for example, a future version adds account sync — this page will be updated before or alongside that release.

## Contact

Questions about the app or this policy: hesihan2000s@gmail.com
