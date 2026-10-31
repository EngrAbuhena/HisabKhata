# Google Drive and OAuth setup

This is a checklist for the existing **manual** Google Drive backup feature.
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
   | Supplied v1.0.0 release APK | `C4:B2:95:7D:31:72:19:90:F1:67:69:28:55:A9:BE:C9:D5:70:50:D7` |

   These two supplied builds use the **same signing key**, so one matching
   package/SHA-1 client covers both; do not create an unnecessary duplicate. A debug
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

No `google-services.json`, client secret or hard-coded access token is needed or
included in this repository. Do not commit one. Access tokens are obtained from
Google authorization for the operation; the selected account name and last-backup
time are stored locally.

## Troubleshooting and limits

Check the installed APK's exact package and signing SHA-1, Drive API enablement,
consent screen/test user, account, network and Play services. An OAuth configuration
can take time to propagate. App-data backups are hidden from ordinary Drive file
listings. Sign-out attempts to revoke the granted scope but does not delete backups.
The app does not implement automatic scheduled backup at 23:45 or an app lock.

Live authorization, upload, listing and restore were **not tested** in this
workspace because no user-owned Google Cloud OAuth project/account was available.
Perform an end-to-end backup and restore with your own test account before relying
on the feature. Google controls OAuth policy, account availability and service behavior.
