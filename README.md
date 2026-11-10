# Phone Guard

A phone app for Android 10+ (written for a Nothing Phone (3a) Lite). It keeps a copy of every contact,
from all Google accounts, phone storage and SIM. When contacts disappear, it alerts you, records the
likely cause, and puts them back.

## Install on the phone
1. Build the APK (see Build below) and copy it to the phone, or run `adb install app-release.apk`.
2. Open it → **Set as default phone app** → **Allow access to contacts and calls**.
3. Guard tab:
   - **App activity → Allow**: records which apps were open when contacts vanish.
   - **Backup folder → Choose**: pick a folder, e.g. Documents. A daily `.vcf` copy goes there.
4. Nothing OS: Settings → Apps → Phone Guard → Battery → **Unrestricted**, so checks aren't delayed.

## How it works
- `Guard.kt`: snapshots every editable raw contact (Google, phone storage, Exchange…) plus SIM
  records (Android 12+). It re-checks on every contact change (JobScheduler content trigger) and
  every 6 h. Lost contacts are kept with their full data in `events.json` until you restore or
  ignore them.
- `Snap.kt`: the diff. A contact counts as lost only if no live contact has the same name and a
  shared number, so merges, account moves and re-syncs don't raise alarms. When 5 or more contacts
  and over 30% of an account vanish at once, it never auto-restores (that would create duplicates
  if sync comes back).
- Restore puts each contact back in its original account. If that account's sync is off or the
  account is gone, it goes to phone storage instead.
- Deletes made inside this app are not alarms.

## Build
`./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk` (debug-signed, for sideloading).
Test: `./gradlew testDebugUnitTest` (diff logic).

Not built yet: contact photos and group labels are not restored; no video calls; the missed-call
notification comes from the system (it may show a number instead of the name).

## License
MIT, see [LICENSE](LICENSE).
