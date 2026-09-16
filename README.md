# KontoKlar for Android

Native Android app prototype built with Kotlin and Jetpack Compose. The app uses a distinct name and package from Accountable; it is an independent implementation, not a copy of Accountable's private source code or backend.

## Implemented

- Dashboard with totals derived from locally saved invoices and expenses.
- Create invoice drafts with customer, up to 20 separately described gross-price positions, and a configurable due date. Existing one-line invoices are loaded as a single position when edited.
- Create local offers with up to 20 separately described positions, share multi-page draft PDFs, track sent/accepted/rejected/expired states, and convert accepted offers to a linked invoice draft once while preserving all positions.
- Maintain a local customer directory with search and editable names, email, billing address, and tax identifier; select a saved customer for new invoices and snapshot those billing details into the invoice draft.
- Maintain a reusable product and service catalog with saved descriptions and gross prices; apply a catalog entry to new invoices and offers.
- Sequential invoice draft numbers, locally persisted invoice states (draft/sent/paid), PDF draft generation, and Android share sheet.
- Edit and delete invoice drafts while preserving their number; sent and paid invoices cannot be rewritten as drafts.
- Schedule one local Android reminder for a sent invoice from 09:00 on the day after its due date; marking it paid cancels the alarm and any visible reminder. The Android 13+ notification permission is requested when an invoice is first marked sent, and pending reminders are restored after reboot, time changes, and app updates.
- Local business profile settings for sender identity/address, e-invoice email, optional tax identifiers, invoice prefix, payment term, and VAT rate. The prefix and payment term are applied to new drafts; gross amounts are split into net and VAT for the limited XRechnung export.
- Save the configured VAT rate as a snapshot on each newly created invoice draft (including drafts converted from accepted offers); PDF, multi-position XRechnung, tax overview, and CSV use that saved rate even if the business profile rate later changes. Older invoices without a snapshot are called out and not silently assigned a rate in tax/CSV reports.
- Manually record expenses with merchant, amount, category, date, and note.
- Record the exact input-VAT amount shown on an expense receipt (optional), preserve it through backups and e-invoice imports, include it in the bookkeeping CSV, and show documented input VAT and the number of expenses missing a VAT breakdown in the yearly overview. These are source-document amounts, not a deductible-tax determination.
- Edit, inspect, and delete saved expenses; reopen their attached image or PDF receipt.
- Attach a local image or PDF receipt to an expense; the expense list reports how many receipts are missing.
- Capture receipt photos through Android's camera app and use bundled, on-device ML Kit OCR to suggest merchant, date, and total. Suggestions remain editable and require user review.
- Persist those records locally with Android SharedPreferences.
- Export encrypted AES-256-GCM ZIP backups containing local records, profile, and attached receipts; passphrases are stretched with PBKDF2-HMAC-SHA256, and restore replaces local data only after explicit confirmation. Older unencrypted ZIP backups remain importable.
- Share a local invoice/expense/bank transaction CSV through Android's share sheet for bookkeeping handoff, including explicitly recorded expense net/VAT amounts; exports are marked as working data, not tax returns.
- Review a year-based summary of issued invoices, recorded expenses, the gross-recorded difference, open/overdue invoices, and expenses missing receipts. Draft invoices are excluded; this is not tax advice or a tax calculation.
- Review recorded output VAT from issued invoices with a saved VAT-rate snapshot beside exact input VAT values reported from expense documents; records without a rate remain explicitly counted as unknown.
- German and dot-decimal Euro input parsing, with unit tests.
- Navigation for invoices, expenses, tax overview, and account/settings areas.
- In-app check for the latest public GitHub release, private-cache APK download, SHA-256 verification against the GitHub asset digest, and direct handoff to Android's package installer.
- Before opening the package installer, validate the downloaded APK's package ID, strictly higher Android version code, and signing-certificate set against the installed app. Invalid, mismatched, or non-upgrade APKs are rejected before installation.
- Export a limited domestic German XRechnung 3.0.2 UBL XML file with multiple invoice positions via Android's document picker after checking required invoice, addresses, electronic addresses, German VAT-ID, service-date, VAT inputs, and position-total/rounding consistency.
- Import incoming XRechnung and ZUGFeRD/Factur-X in UBL or CII XML, including extraction of the structured invoice from a ZUGFeRD PDF; review supplier/invoice/gross/VAT data, then save as an expense with the original XML or PDF attached. XML attachments are included in ZIP backups.
- Import local ISO 20022 CAMT.053 bank statements, deduplicate transactions, store them locally and suggest exact-amount invoice matches; marking an invoice paid always requires explicit confirmation. Imported transactions are included in ZIP backups.
- Generate a shareable invoice working-PDF populated from the saved sender profile, recipient snapshot, service/invoice/due dates, gross/net/VAT breakdown and IBAN when present. It remains conspicuously marked as a draft and not a final tax invoice.
- Android's system installer confirms package updates; it requires the one-time "Install unknown apps" permission.
- Clear distinction between local records and external banking/tax services.

## Not implemented yet

There is no account/login or cloud sync, live bank/PSD2 connection, automatic bank transaction synchronization, actual automatic transfer of tax reserves, PEPPOL network delivery, tax-return calculation or submission, or accountant collaboration. Bank statements must currently be imported manually in CAMT.053 format. XRechnung export currently supports limited multi-position domestic German invoices with one German VAT-ID and a single positive standard VAT rate; tax-number-only profiles, tax exemptions, small-business invoices, cross-border/reverse-charge cases, and government procurement routing are not supported. Review every XML and validate it with the official KoSIT validator before use; the export is not legal or tax advice. OCR is local and heuristic and always requires user review. Invoice PDFs remain clearly marked as incomplete drafts. The tax screen is informational and does not submit declarations or provide binding calculations.

## Publishing an update

Push a version tag such as `v0.23.0`. The GitHub Actions workflow runs tests, builds a release APK, and attaches it to a public GitHub Release. The release must be signed with the same key as earlier APKs; the repository action needs the `KONTOKLAR_SIGNING_KEY_BASE64` secret. The current update signing key is the local Android debug keystore so that the already-built test APK can be upgraded; do not use this key for a public production launch.

## Build

Requires JDK 17+ and Android SDK Platform 35 / Build Tools 34.0.0. The workspace-local SDK is kept under the ignored `build/android-sdk/` directory on the external SSD.

```sh
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
