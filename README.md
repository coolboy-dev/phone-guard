# Phone Guard

A background contact guard for Android 10+ (written for a Nothing Phone (3a) Lite). It keeps a copy of
every contact, from all Google accounts, phone storage and SIM. Contacts that disappear come back.
Keep using your normal phone app (Google Phone etc.); Phone Guard runs alongside it.

## Download
**[⬇ Download PhoneGuard.apk](https://github.com/coolboy-dev/phone-guard/releases/latest/download/PhoneGuard.apk)** (latest release, Android 10+)

Open the file on the phone and allow "Install unknown apps" for your browser or file manager when asked.

## Set up
1. Install the APK above, or build it yourself (see Build below).
2. Open it → **Allow access to contacts**.
3. Guard tab:
   - **Run in background → Allow**, then **Allow** in the pop-up. This is the "Unrestricted" battery
     setting, so checks aren't paused (important on Nothing OS and other battery-saving phones).
   - **App activity → Allow**: records which apps were open when contacts vanish.
   - **Backup folder → Choose**: pick a folder, e.g. Documents. A daily `.vcf` copy goes there.

## How deleting works
- **To delete a contact, use Phone Guard**: Contacts tab → open the contact → 🗑. It asks for your
  fingerprint (or screen-lock PIN). The contact is then deleted everywhere, including your Google
  account. A phone with no screen lock can't delete from here.
- **Contacts deleted anywhere else** (Google Contacts, Google Phone, contacts.google.com, another
  device, or whatever keeps deleting them) are brought back automatically, with a small
  "Brought back" notice. If many contacts vanish at once, it asks first, because an account may
  have been removed and restoring could create duplicates.
- **Call history is never touched.** Calls deleted in your phone app's Recents stay deleted.

## How it works
- `Guard.kt`: snapshots every editable raw contact (Google, phone storage, Exchange…) plus SIM
  records (Android 12+). It re-checks on every contact change (JobScheduler content trigger) and
  every 6 h. Lost contacts are kept with their full data in `events.json` until restored or ignored.
- `Snap.kt`: the diff. A contact counts as lost only if no live contact has the same name and a
  shared number, so merges, account moves and re-syncs don't raise alarms.
- Restore puts each contact back in its original account. For a Google account, Google sync then
  uploads it again, so it's back on every device. If that account's sync is off or the account is
  gone, it goes to phone storage instead.
- Each loss is logged with the account, the time, sync state, and which apps were open or recently
  updated, to help find out what is deleting contacts.

## Build
`./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk` (debug-signed, for sideloading).
Test: `./gradlew testDebugUnitTest` (diff logic).

Limits: contact photos and group labels are not restored; a restored Google contact is a new copy
(the original stays in Google's trash for 30 days).

## License
MIT, see [LICENSE](LICENSE).
