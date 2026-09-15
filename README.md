# KontoKlar for Android

Native Android app prototype built with Kotlin and Jetpack Compose. The app uses a distinct name and package from Accountable; it is an independent implementation, not a copy of Accountable's private source code or backend.

## Implemented

- Dashboard with totals derived from locally saved invoices and expenses.
- Create invoice drafts with customer, description, amount, and a 14-day due date.
- Create local offer drafts, share draft PDFs, track sent/accepted/rejected/expired states, and convert accepted offers to a linked invoice draft once.
- Maintain a local customer directory with search and editable names, email, billing address, and tax identifier; select a saved customer for new invoices and snapshot those billing details into the invoice draft.
- Maintain a reusable product and service catalog with saved descriptions and gross prices; apply a catalog entry to new invoices and offers.
- Sequential invoice draft numbers, locally persisted invoice states (draft/sent/paid), PDF draft generation, and Android share sheet.
- Edit and delete invoice drafts while preserving their number; sent and paid invoices cannot be rewritten as drafts.
- Schedule one local Android reminder for a sent invoice from 09:00 on the day after its due date; marking it paid cancels the alarm and any visible reminder. The Android 13+ notification permission is requested when an invoice is first marked sent, and pending reminders are restored after reboot, time changes, and app updates.
- Local business profile settings for sender identity/address, optional tax identifiers, invoice prefix, payment term, and a stored VAT rate. The prefix and payment term are applied to newly created invoice drafts; VAT is not calculated or printed as a legal invoice tax breakdown.
- Manually record expenses with merchant, amount, category, date, and note.
- Edit, inspect, and delete saved expenses; reopen their attached image or PDF receipt.
- Attach a local image or PDF receipt to an expense; the expense list reports how many receipts are missing.
- Capture receipt photos through Android's camera app and use bundled, on-device ML Kit OCR to suggest merchant, date, and total. Suggestions remain editable and require user review.
- Persist those records locally with Android SharedPreferences.
- Export or restore a ZIP backup containing the local records, profile, and attached receipts; restoring is explicit and replaces the current local data only after confirmation.
- Share a local invoice/expense CSV through Android's share sheet for bookkeeping handoff; exports are marked as working data, not tax returns.
- Review a year-based summary of issued invoices, recorded expenses, the gross-recorded difference, open/overdue invoices, and expenses missing receipts. Draft invoices are excluded; this is not tax advice or a tax calculation.
- German and dot-decimal Euro input parsing, with unit tests.
- Navigation for invoices, expenses, tax overview, and account/settings areas.
- In-app check for the latest public GitHub release, private-cache APK download, SHA-256 verification against the GitHub asset digest, and direct handoff to Android's package installer.
- Android's system installer confirms package updates; it requires the one-time "Install unknown apps" permission.
- Clear distinction between local records and external banking/tax services.

## Not implemented yet

There is no account/login or cloud sync, bank/PSD2 connection, actual automatic transfer of tax reserves, legally complete invoice export or e-invoice transmission, tax calculation or submission, or accountant collaboration. OCR is local and heuristic; it is not guaranteed to read receipts correctly and always requires user review. Invoice PDFs are clearly marked as incomplete drafts and must not be used as tax documents. The tax screen is informational and does not submit declarations or provide binding calculations.

## Publishing an update

Push a version tag such as `v0.12.0`. The GitHub Actions workflow runs tests, builds a release APK, and attaches it to a public GitHub Release. The release must be signed with the same key as earlier APKs; the repository action needs the `KONTOKLAR_SIGNING_KEY_BASE64` secret. The current update signing key is the local Android debug keystore so that the already-built test APK can be upgraded; do not use this key for a public production launch.

## Build

Requires JDK 17+ and Android SDK Platform 35 / Build Tools 35.0.0.

```sh
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
