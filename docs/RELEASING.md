# Releasing Hisab Khata

## Protect the existing key first

The v1.0.0 artifact was signed with the **same private key** as the previous debug
APK (`androiddebugkey`). The separately delivered keystore is a password-reprotected
copy of that existing key, not a new signing identity. Its certificate SHA-1 and
SHA-256, alias and passwords are in the separately delivered `KEYSTORE_INFO.txt`.
Keep both files outside this repository, and make at least two protected offline
backups. Losing the key can prevent future install-over updates. Never commit,
email publicly, attach to a GitHub Release, or paste either file into an issue.

This key originated as an Android debug key whose old copy used Android's default
password. Reprotecting the delivered copy does not remove or secure any older copy.
If the key was exposed, changing its password does not make the private key secret
again. **These artifacts are not Google Play-ready:** [Android's signing guidance](https://developer.android.com/studio/publish/app-signing)
says Google Play does not accept apps signed with a debug certificate. Although this
is a non-debuggable release build, its signing certificate is still a debug certificate.
Using a new signing key normally prevents install-over of the existing app. First
make and test a local JSON backup, and plan a supported signing-key migration or a
fresh installation. Register the new SHA-1 with Google OAuth as well.

## Local signed and unsigned builds

1. Install JDK 17, Android SDK Platform 35 and Build-Tools 35.0.0. Open a terminal
   in the project root.
2. Copy `keystore.properties.example` to the ignored `keystore.properties`. Fill in
   `storeFile` (absolute path or a path relative to the project root),
   `storePassword`, `keyAlias`, and `keyPassword`. On Windows use forward slashes
   in the properties path (for example `C:/secure/release.keystore`) or escape
   backslashes as required by Java properties. Keep the actual keystore outside the
   repository. If the file exists it takes precedence over environment variables.
   Alternatively, **do not create** `keystore.properties` and export
   `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` in the
   environment. Avoid commands that expose passwords in shell history or logs.
3. Run:

   ```sh
   ./gradlew testDebugUnitTest lint assembleRelease bundleRelease
   ```

   The signed files are `app/build/outputs/apk/release/app-release.apk` and
   `app/build/outputs/bundle/release/app-release.aab`. Check the APK certificate:

   ```sh
   "$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs \
     app/build/outputs/apk/release/app-release.apk
   ```

   Also use `jarsigner -verify` on the AAB and compare its certificate with the
   APK's. Compute SHA-256 checksums after giving both files their release names.
   Without either complete signing input, Gradle prints an explicit warning and
   emits an **unsigned** APK/AAB; do not upload or install those as signed artifacts.

Release code is minified with R8. To test the actual non-debuggable release on a
**test device or emulator**, first build a signed APK and install that same APK with
`adb install -r app/build/outputs/apk/release/app-release.apk`. Export `JAVA_HOME`,
`ANDROID_HOME`, `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`
(the probe must be signed by the same certificate as the release), then run:

```sh
./scripts/run_release_probe.sh
```

The script checks the installed APK against the build, uses its matching R8 mapping,
and signs a temporary standalone instrumentation APK without printing passwords.
The probe uses an **in-memory** Room database, not the real ledger: it checks JSON
backup, replace/merge restore and linked records, duplicate-safe import, CSV/XLSX
import/export, PDF generation, Google Identity request/client construction, and
instantiation of the disabled automatic WorkManager worker. It skips worker execution
if automatic backup is already enabled on the test device. It does not sign in or
contact Google. It leaves a separate test package, which you
can remove with `adb uninstall com.hisabkhata.releaseprobe`. A live authorization
and Drive round trip still require your own configured account and Play services.

### Android Studio alternative

Choose **Build → Generate Signed Bundle / APK**, select Android App Bundle or APK,
choose the existing private keystore and alias, enter its passwords, choose the
`release` build variant, and finish the wizard. Repeat for the other format. Verify
the certificate and checksums as above. Do not let Android Studio commit or share
your keystore or passwords.

### Creating a key for a genuinely new signing identity

Do **not** run this for an update that must keep the existing certificate. If you
are intentionally creating a new identity, the following prompts for passwords
instead of placing them in shell history:

```sh
keytool -genkeypair -v -storetype PKCS12 \
  -keystore /safe/off-repo/hisab-release.keystore -alias hisab-release \
  -keyalg RSA -keysize 3072 -validity 10000
```

Record the alias, certificate fingerprints and passwords in a separate secure
password manager, back up the keystore offline, and register its SHA-1 in Google
Cloud. A new key signs a different Android identity: it cannot silently replace a
previously installed app signed with the old key.

## GitHub Actions secrets

After creating a private or public repository, add these **four repository secrets**
under Settings → Secrets and variables → Actions, or use the GitHub CLI below:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | Base64 encoding of the entire private keystore file |
| `KEYSTORE_PASSWORD` | Keystore/store password from the private info file |
| `KEY_ALIAS` | Key alias (`androiddebugkey` for the supplied key) |
| `KEY_PASSWORD` | Key password from the private info file |

In Bash on Linux/macOS, with `gh` authenticated and the repository selected:

```sh
# Replace the path with your securely stored keystore; GNU and macOS compatible.
base64 < /safe/off-repo/HisabKhata-1.0.0.keystore | tr -d '\n' | gh secret set KEYSTORE_BASE64
printf %s 'androiddebugkey' | gh secret set KEY_ALIAS
read -r -s -p 'Keystore password: ' STORE_SECRET; printf '\n'
printf %s "$STORE_SECRET" | gh secret set KEYSTORE_PASSWORD
read -r -s -p 'Key password: ' KEY_SECRET; printf '\n'
printf %s "$KEY_SECRET" | gh secret set KEY_PASSWORD
unset STORE_SECRET KEY_SECRET
```

For PowerShell, the equivalent encoding is
`[Convert]::ToBase64String([IO.File]::ReadAllBytes('C:\safe\HisabKhata-1.0.0.keystore'))`
(pipeline the result to `gh secret set KEYSTORE_BASE64`). Never paste its output
into the repository. The release workflow fails before building if a secret is
missing, restores the keystore only in the runner's temporary directory, verifies
signatures, and removes that copy after the job.

## Tagging and publishing

1. For each subsequent release, increase `versionCode` beyond every installed or
   published build, set `versionName`, and add its `## [x.y.z] - YYYY-MM-DD`
   section to `CHANGELOG.md`. Test the change and review the staged file list.
2. Commit the changes on `main`. Make an annotated tag matching the version:
   `git tag -a vX.Y.Z -m 'Hisab Khata vX.Y.Z'`.
3. Configure all four Actions secrets **before** pushing the tag. Push the branch
   and tag to your own remote. The workflow rejects a tag/version mismatch or a
   missing changelog section, builds signed APK/AAB files, and creates the GitHub
   Release using that exact changelog section.
4. Download the APK, AAB and `SHA256SUMS.txt` from the GitHub Release or workflow,
   verify checksums and signatures, then test installation and Google Drive with
   your own account. Review GitHub's workflow permissions if release creation fails.

The prepared repository already contains the `v1.0.0` annotated tag; do not create
it again. Nothing is pushed automatically from this workspace.
