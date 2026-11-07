# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions use
[Semantic Versioning](https://semver.org/).

## [1.1.0] - 2026-11-07

### Added
- Opt-in automatic Google Drive app-data snapshots around 23:45 device-local time,
  with persistent Android background scheduling, network constraints and retries.
- Last automatic success and actionable failure/reauthorization status; optional
  notification alerts when attention is required.
- Tests for daily scheduling, opt-in/account changes, retries and safe restore paths.

### Notes
- Automatic timing is not exact and may be delayed by Android. Restore remains
  manual; merge appends records and replace requires confirmation.
- Google authorization and real Drive upload/restore must be tested using the
  owner's Google Cloud OAuth project, account, network and Play services.
- Android `versionCode` is 6. The supplied key remains the existing debug
  certificate for install-over compatibility, so these builds are not Play-ready.

## [1.0.0] - 2026-10-31

### Added
- Cash Book with running balances, search and date filters.
- Loan Ledger for money lent or borrowed, with optional linked Cash Book entries.
- Mobile-friendly ledger tables and confirmations for destructive actions.
- CSV, Excel and PDF exports; CSV and Excel import with fictional sample files.
- Local JSON backup and restore, and optional **manual** Google Drive app-data backup,
  restore, backup listing and last-backup time.
- System, light and dark themes.

### Release notes
- The existing app does **not** implement an automatic daily 23:45 Drive backup,
  a dedicated backup log, or an app lock. These are not included in v1.0.0.
- This release preserves the existing application ID, database schema and signing
  certificate; its Android `versionCode` is 5. The certificate is an Android debug
  certificate, so these artifacts are for update compatibility and are not Google
  Play-ready. Reprotecting a keystore copy does not undo any past key exposure.
