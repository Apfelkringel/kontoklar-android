# Security Policy

## Supported versions

Only the latest published release of KontoKlar is supported with security fixes.
Older releases are not patched retroactively; please update to the latest
release first and check whether the issue is still reproducible there.

## Reporting a vulnerability

Please do **not** open a public issue for a suspected security vulnerability,
and do **not** include real bank credentials, TANs, backup passphrases,
signing keys, or other peoples financial data in any report.

Use GitHubs private vulnerability reporting for this repository
(Security tab > Report a vulnerability). If private reporting is not
available for you, open an issue that describes only the affected area and
states that details will follow privately, without exploit details or
sensitive data.

A useful report includes:

- the app version (and, for the banking worker, the affected endpoint path
  without credentials),
- the steps to reproduce, with test data only,
- what you expected to happen and what happened instead,
- whether local data, backups, updates, or the banking API is affected.

## What is in scope

- Local storage and encryption of invoices, expenses, receipts, and backups
  (AES-256-GCM, Android Keystore, backup passphrase handling).
- The in-app update check and APK verification (SHA-256, package ID,
  version code, signing-certificate check).
- The Cloudflare Worker banking API (`banking-api/`): authentication,
  rate limits, provider-URL allowlists, and handling of bank data.
- Import parsers for bank statements, PDFs, and XRechnung/ZUGFeRD files.

Out of scope are the banks and providers themselves (finAPI, comdirect,
C24, Trade Republic): issues in their services should be reported to them.

## Handling

Reports are triaged as soon as possible. Fixes are prepared in a private
branch or pull request when needed and published as a new signed release;
the fix is not described in public before the release is available, so that
users can update first.
