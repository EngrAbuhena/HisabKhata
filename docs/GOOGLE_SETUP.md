# Google Drive and OAuth setup

This is a checklist for manual restore and manual or opt-in automatic Drive backups.
A Google account, network connection and current Google Play services are needed.
Local ledger work and local JSON backups do not need Google setup.

1. Create or select a project in [Google Cloud Console](https://console.cloud.google.com/).
2. Enable **Google Drive API** under APIs & Services → Library.
3. Configure Google Auth Platform / the OAuth consent screen: app name, support
   and developer contact, audience, and any required privacy information. Add
   `https://www.googleapis.com/auth/drive.appdata` under data access if prompted.
   When the audience is External and the project is in testing, add every Google
   account that will test as a **test user**. Google may require verification before
   production use; follow the console's current instructions.
4. Find the **SHA-1 of each certificate that signs an installed build**, then
   create an **Android OAuth client** in that same project for package name
   `com.hisabkhata` and that SHA-1.

   | Certificate | SHA-1 |
   |---|---|
   | Supplied v4 debug APK | `C4:B2:95:7D:31:72:19:90:F1:67:69:28:55:A9:BE:C9:D5:70:50:D7` |
   | Supplied v1.0.0 and v1.1.0 release APKs | `C4:B2:95:7D:31:72:19:90:F1:67:69:28:55:A9:BE:C9:D5:70:50:D7` |

   These supplied builds use the **same signing key**, so one matching
   package/SHA-1 client covers them all; do not create an unnecessary duplicate. A debug
   build made on another computer usually has a different debug key: obtain its
   debug SHA-1 using `./gradlew :app:signingReport` and register another Android
   client. For any differently signed release, register that release's SHA-1 too.
   With Google Play App Signing, register the **app-signing certificate** SHA-1
   from Play Console for the version installed by Play, which can differ from an
   upload-key certificate.
5. On a device with Play services and the intended account added, open **Settings →
   Backup and restore → Sign in with Google**. Choose the account and grant the
   private app-data permission. Tap **Back up to Google Drive**, then use Refresh
   to list backups. Restore validates the selected JSON before merge or replace.
6. Turn on **Automatic daily backup** for backups around 11:45 p.m. in the device's
   local time. On Android 13 or newer, allow optional notifications if you want
   alerts. On the backup screen you can see the last automatic success and any
   retry, failure or reauthorization message. If prompted to reconnect, tap **Refresh
   backups** and grant the permission again.
7. To restore, tap **Refresh backups**, select a Drive backup and tap **Restore**,
   or choose a local JSON backup. Review the record counts, then choose **Merge**
   (appends records, even duplicates) or **Replace** (overwrites the current
   database after a second confirmation). Make a local backup before replacing.

No `google-services.json`, client secret or hard-coded access token is needed or
included in this repository. Do not commit one. Access tokens are obtained from
Google authorization for the operation; the selected account name and last-backup
time are stored locally.

## Troubleshooting and limits

Check the installed APK's exact package and signing SHA-1, Drive API enablement,
consent screen/test user, account, network and Play services. An OAuth configuration
can take time to propagate. App-data backups are hidden from ordinary Drive file
listings. The same Google Cloud project, app identity and Google account must be
used to find existing app-data backups. Sign-out turns automatic backups off and
attempts to revoke the scope; the app does not itself issue backup deletion requests.
Changing accounts also turns automatic backup off. No backup is restored automatically.

The 23:45 target is **not exact**: WorkManager may be delayed by Android, a lack of
network, power restrictions, device-off time or other system conditions. After a
force-stop, reopen the app to allow work to resume. On clock/time-zone changes the
app re-anchors its future schedule. Repeated failures are retried and visible in
the backup screen; notifications require permission on Android 13+. A running
upload may finish after the toggle is turned off. Each successful backup is a new
file, with no automatic retention limit, so manage old files and Drive quota yourself.
Backups over 32 MiB are rejected. Deleting app data in Google Drive can remove these
backups; keep and test a separate local JSON backup too. The app has no app lock.

Live authorization, upload, listing and restore were **not tested** in this
workspace because no user-owned Google Cloud OAuth project/account was available.
Perform an end-to-end backup and restore with your own test account before relying
on the feature. Google controls OAuth policy, account availability and service behavior.
