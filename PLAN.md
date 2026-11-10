# ContactGuard Dialer — Plan

**Status 2026-11-10:** all 5 phases built and tested on an emulator (Android 17). See README.md.

## Problem
Contacts vanish on a Nothing phone. Nothing ships Google Phone + Google Contacts. The cause is not
confirmed (see "Diagnose" below). Possible causes from research:

1. **Play Services sync change (v23.20+)**: turning contact sync off for an account now *removes* those
   contacts from the phone. They stay on contacts.google.com.
2. **Same Google account on several devices** + "Automatically back up & sync device contacts":
   another device or the sync job deletes or moves contacts.
3. **Server-side deletes** (Google Contacts web, a second phone, a merge/dedupe) get synced down.
4. The Google Phone update that **removed the Contacts tab**, which makes contacts *look* gone.

**r/NOTHING report (Phone (2), 1y old, unsolved):** contacts deleted for months with Google sync
**off**; changing the save location didn't help; a commenter blames the Google Contacts app. This fits
cause 1: with sync off, Google Play Services removes that account's contacts from the phone. So the
deletions are local and nothing gets backed up, which makes our own snapshot the only copy.
→ The guard must record each account's sync state (`ContentResolver.getSyncAutomatically`) at
  deletion time. It must not restore into a Google account with sync off (it gets removed again).
  Restore to local storage instead, and tell the user to turn sync on.

**But: her phone already has sync ON**, so cause 1 may not apply. Treat the cause as **unknown**.
Suspects to rule in or out: Google Contacts app, Nothing OS itself, another app with contact access,
SIM, Bluetooth (car or watch), a second device on the same account.

### Diagnose, don't assume
Android never says *who* deleted a contact, so the guard logs the context around each deletion and
the pattern will point to the culprit:
- account type/name of each lost contact, plus that account's sync state
- `DELETED=1` seen first (deleted through the provider on the phone) vs. gone directly (sync adapter)
- apps in the foreground just before (UsageStatsManager; she grants usage access once)
- recent package updates (`PACKAGE_REPLACED`: Google Contacts, Play Services, Nothing apps)
- Bluetooth connections (car/watch phonebook sync), reboots, account added/removed
- With sync on, contacts.google.com → Trash shows **"deleted from <device>"**. Check it first.

A replacement dialer alone fixes none of these. The real fix is a **guard** that snapshots contacts,
spots deletions, and puts them back. The dialer is the shell around it.

**Do now, before any code:** contacts.google.com → Trash (30 days) and Settings → Undo changes.

## What Android allows (constraints)
- `ContactsContract` covers every account in one provider: each Google account, local "Phone"
  storage, and SIM (shows up as an ICC account on most ROMs). One reader covers all of them.
- `ContactsContract.DeletedContacts` (API 18+) logs *which* contact IDs were deleted and when, kept
  for 30 days. It stores **no data**, so we must keep our own snapshot to restore anything.
- `RawContacts.DELETED = 1` means "deleted on this phone, waiting for sync". A contact that vanishes
  *without* that flag was deleted by the sync adapter, i.e. on the server. That tells us the cause.
- Restore = insert the raw contact again with the **same ACCOUNT_TYPE/ACCOUNT_NAME**. For `com.google`
  the Google sync adapter uploads it, so it comes back on every device.
- SIM: `SimPhonebookContract` (API 31+) for read/write on SIM. SIM entries are name + number only.
- Background: `JobScheduler` with `addTriggerContentUri(Contacts.CONTENT_URI)` wakes us on any
  contact change, even when the app isn't running. Built into Android, no library needed.
- Default dialer: `RoleManager.ROLE_DIALER` + an `InCallService` (we own the in-call screen).
  Holding the dialer role also keeps the app from being killed, which keeps the guard alive.

## Architecture (one module, Kotlin + Compose)
```
app/
  guard/
    Snapshot.kt      read all raw contacts + data rows → JSON (per account)
    GuardJob.kt      JobService: content-trigger + 6h periodic; diff snapshot vs live
    Restore.kt       ContentProviderOperation batch insert into original account / SIM
    VcfExport.kt     rolling .vcf export to a user-picked folder (SAF), survives uninstall
  dialer/
    DialpadScreen, RecentsScreen, ContactsScreen, CallScreen
    CallService.kt   InCallService
  MainActivity.kt    tabs: Keypad · Recents · Contacts · Guard
```
Storage: JSON snapshots in app files dir (last 7 days kept). No database until JSON is too slow.

## Detection logic
On each trigger:
1. Read live contacts → map keyed by `(accountType, accountName, sourceId or rawId)`.
2. Compare with last snapshot. Missing = candidate deletion.
3. Check `DeletedContacts` since last run, plus whether the row had `DELETED=1`. Tag each one
   **local delete** or **sync/server delete**.
4. **Mass-delete guard**: if more than 20% of an account disappears at once, treat it as "account sync
   off / account removed". Don't auto-restore (that would make duplicates when sync returns). Notify
   and tell the user what happened.
5. Otherwise notify: "3 contacts removed from you@gmail.com. Restore?", with auto-restore as an option.
6. Before re-inserting, dedupe by normalized phone number + name against live contacts.
7. Save the new snapshot. Keep a deletion log so the Guard tab shows when, which account, and the cause.

## Phases
1. **Guard MVP** (fixes the actual problem): permissions, snapshot, JobScheduler trigger, diff,
   notification, restore to the original account, deletion log screen. Usable while Google Phone
   is still the dialer.
2. **Backups**: VCF export to a chosen folder, plus a manual "export all / import" button.
3. **Dialer**: keypad, recents (CallLog), contacts list with an account filter, ROLE_DIALER request.
4. **In-call**: InCallService call screen (answer, hang up, mute, speaker, keypad). Must be solid,
   because a broken call screen is worse than lost contacts.
5. **SIM**: SimPhonebookContract read/restore on API 31+.

## Permissions
READ/WRITE_CONTACTS, GET_ACCOUNTS, READ_CALL_LOG, CALL_PHONE, READ_PHONE_STATE,
POST_NOTIFICATIONS. ROLE_DIALER covers call handling.

## Testing
- Emulator with a Google account: delete on contacts.google.com → guard catches it within a sync cycle.
- Delete locally → tagged as a local delete.
- Turn off account sync → mass-delete guard fires, no duplicates.
- Unit check: the diff function on fake snapshots.

## Open questions
- Exact phone model and Android / Nothing OS version (needed for the SIM API, min SDK 31 vs 29).
- Auto-restore by default, or ask first? (Proposed: ask first, with an opt-in toggle.)
- Build all phases, or ship the Guard first so your mother can start using it right away?
