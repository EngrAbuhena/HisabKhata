# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions use
[Semantic Versioning](https://semver.org/).

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
