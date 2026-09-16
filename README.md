# KontoKlar for Android

Native Android app prototype built with Kotlin and Jetpack Compose. The app uses a distinct name and package from Accountable; it is an independent implementation, not a copy of Accountable's private source code or backend.

## Implemented

- Dashboard with totals derived from locally saved invoices and expenses.
- Year-selectable dashboard with monthly bars comparing issued invoice gross amounts and recorded expense gross amounts by document date; invoice drafts are excluded and the view is explicitly not a cash-flow, profit, or tax calculation.
- Create invoice drafts with customer, up to 20 separately described gross-price positions, and a configurable due date. Existing one-line invoices are loaded as a single position when edited. Manually or bank-imported partial payments retain a paid-to-date amount and calculate the remaining balance without marking the invoice fully paid prematurely.
- Create local offers with up to 20 separately described positions, share multi-page draft PDFs, track sent/accepted/rejected/expired states, and convert accepted offers to a linked invoice draft once while preserving all positions.
- Maintain a local customer directory with search and editable names, email, billing address, and tax identifier; select a saved customer for new invoices and snapshot those billing details into the invoice draft.
- Maintain a reusable product and service catalog with saved descriptions and gross prices; apply a catalog entry to new invoices and offers.
- Sequential invoice draft numbers, locally persisted invoice states (draft/sent/paid), PDF draft generation, and Android share sheet.
- Create monthly, quarterly, or annual recurring invoice plans from an existing invoice, preserve month-end billing days, pause/resume plans, and generate a numbered draft only after the user confirms a due occurrence; the next date and new invoice are committed together. Plans travel in encrypted backups. Sending remains a separate manual action.
- Create monthly, quarterly, or annual recurring expense plans, preserve month-end dates, pause/resume plans, and record a due occurrence only after user confirmation; the next date and new expense are committed together. Only the manually entered receipt VAT amount is copied; no receipt or tax classification is generated. Plans travel in encrypted backups.
- Edit and delete invoice drafts while preserving their number; sent and paid invoices cannot be rewritten as drafts.
- Schedule one local Android reminder for a sent invoice from 09:00 on the day after its due date; marking it paid cancels the alarm and any visible reminder. The Android 13+ notification permission is requested when an invoice is first marked sent, and pending reminders are restored after reboot, time changes, and app updates.
- Prepare an editable, polite email payment reminder for overdue invoices with the current open balance and saved sender IBAN; the user reviews and sends it in their email app. Partial balances, paid invoices, drafts, and not-yet-overdue invoices are handled explicitly; no message is sent automatically.
- Local business profile settings for sender identity/address, e-invoice email, optional tax identifiers, invoice prefix, payment term, and VAT rate. The prefix and payment term are applied to new drafts; gross amounts are split into net and VAT for the limited XRechnung export.
- Store user-entered business activity and legal-form descriptions with the sender profile for document context; these fields do not infer legal or tax treatment.
- Save the configured VAT rate as a snapshot on each newly created invoice draft (including drafts converted from accepted offers); PDF, multi-position XRechnung, tax overview, and CSV use that saved rate even if the business profile rate later changes. Older invoices without a snapshot are called out and not silently assigned a rate in tax/CSV reports.
- Record invoice payment receipts individually with amount, actual entry date, and source (manual or reconciled CAMT bank transaction); partial-payment history stays linked to the invoice, is included in encrypted backups and bookkeeping CSV exports, and matched historic CAMT entries are migrated without inventing dates for older manual balances.
- Record manual and bank-matched expense payments with actual payment dates, including partial payments; the dated history is encrypted in backups and included in bookkeeping exports, while the tax overview separates recorded cash movements from document-date totals.
- Manually record expenses with merchant, amount, category, date, and note.
- Record the exact input-VAT amount shown on an expense receipt (optional), preserve it through backups and e-invoice imports, include it in the bookkeeping CSV, and show documented input VAT and the number of expenses missing a VAT breakdown in the yearly overview. These are source-document amounts, not a deductible-tax determination.
- Edit, inspect, and delete saved expenses; reopen their attached image or PDF receipt.
- Attach a local image or PDF receipt to an expense; the expense list reports how many receipts are missing.
- Capture receipt photos through Android's camera app and use bundled, on-device ML Kit OCR to suggest merchant, date, and total plus an unambiguous keyword-based expense-category hint. All suggestions remain editable and require user review; category hints are not tax classifications.
- Persist records and receipt attachments locally encrypted with AES-256-GCM using a key held in Android Keystore; existing preference records and internal receipt files are migrated, new picked/captured/imported receipts are encrypted when saved, and decrypted working copies are temporary. Automatic device backup is disabled so Keystore-bound ciphertext is not restored without its key. Use the explicit encrypted in-app backup to move records between devices.
- Export encrypted AES-256-GCM ZIP backups containing local records, profile, and attached receipts; passphrases are stretched with PBKDF2-HMAC-SHA256, and restore replaces local data only after explicit confirmation. Older unencrypted ZIP backups remain importable.
- Share a local invoice/expense/bank transaction CSV through Android's share sheet for bookkeeping handoff, including explicitly recorded expense net/VAT amounts; exports are marked as working data, not tax returns.
- Review a year-based summary of issued invoices, recorded expenses, the gross-recorded difference, open/overdue invoices, and expenses missing receipts. Draft invoices are excluded; this is not tax advice or a tax calculation.
- Review the selected year's recorded expenses grouped by their user-entered categories and amounts; blank categories are explicitly grouped as "Ohne Kategorie". This is an organizational summary, not a tax classification or EÜR calculation.
- Keep a local checklist of user-entered tax deadlines with due dates, notes, overdue state, and completion status. With Android notifications allowed, show one local notification at 09:00 on each user-entered due date; completing/deleting a deadline cancels it, and alarms are restored after reboot/time-zone changes. KontoKlar does not derive legal deadlines.
- Review recorded output VAT from issued invoices with a saved VAT-rate snapshot beside exact input VAT values reported from expense documents; records without a rate remain explicitly counted as unknown.
- German and dot-decimal Euro input parsing, with unit tests.
- Navigation for invoices, expenses, tax overview, and account/settings areas.
- In-app check for the latest public GitHub release, private-cache APK download, SHA-256 verification against the GitHub asset digest, and direct handoff to Android's package installer.
- Before opening the package installer, validate the downloaded APK's package ID, strictly higher Android version code, and signing-certificate set against the installed app. Invalid, mismatched, or non-upgrade APKs are rejected before installation.
- Export a limited domestic German XRechnung 3.0.2 UBL XML file with multiple invoice positions via Android's document picker after checking required invoice, addresses, electronic addresses, German VAT-ID, service-date, VAT inputs, and position-total/rounding consistency.
- Import incoming XRechnung and ZUGFeRD/Factur-X in UBL or CII XML, including extraction of the structured invoice from a ZUGFeRD PDF; review supplier/invoice/gross/VAT data, then save as an expense with the original XML or PDF attached. XML attachments are included in ZIP backups.
- Import local ISO 20022 CAMT.053 bank statements, deduplicate and store transactions, suggest invoice matches for incoming payments and expense matches for outgoing payments, and persist user-confirmed reconciliation links; marking an invoice paid always requires explicit confirmation. Imported transactions are included in backups and bookkeeping CSV exports.
- Import C24 and comdirect transaction CSV exports plus Trade Republic account-statement PDFs locally on-device (in addition to CAMT.053); bank credentials are never requested or transmitted. The TR parser accepts only recognized statement layouts and rejects ambiguous booking rows rather than guessing.
- Provide the Android-side live Open Banking flow: backend-supplied bank list, provider-hosted authorization redirect, explicit sync, local transaction import, and disconnect. The app stores only a random per-installation API bearer token in encrypted preferences; provider credentials stay server-side.
- Generate a shareable invoice working-PDF populated from the saved sender profile, recipient snapshot, service/invoice/due dates, gross/net/VAT breakdown and IBAN when present. It remains conspicuously marked as a draft and not a final tax invoice.
- Android's system installer confirms package updates; it requires the one-time "Install unknown apps" permission.
- Clear distinction between local records and external banking/tax services.

## Not implemented yet

The live Open Banking Android integration requires a deployed HTTPS backend and an approved provider account, neither of which is configured yet; until then, use local statement import. Provider coverage for C24, comdirect depots and Trade Republic must be confirmed against the actual provider account before release. See [docs/open-banking.md](docs/open-banking.md) for the API contract and production gates. There is no account/login or cloud sync, automatic bank transaction synchronization, actual automatic transfer of tax reserves, PEPPOL network delivery, tax-return calculation or submission, or accountant collaboration. XRechnung export currently supports limited multi-position domestic German invoices with one German VAT-ID and a single positive standard VAT rate; tax-number-only profiles, tax exemptions, small-business invoices, cross-border/reverse-charge cases, and government procurement routing are not supported. Review every XML and validate it with the official KoSIT validator before use; the export is not legal or tax advice. OCR is local and heuristic and always requires user review. Invoice PDFs remain clearly marked as incomplete drafts. The tax screen is informational and does not submit declarations or provide binding calculations.

## Publishing an update

Push a new, higher version tag to build and publish a signed APK through GitHub Actions. Release builds require a private, dedicated signing key; debug signing is never used for release APKs. See [docs/android-releases.md](docs/android-releases.md) for key handling and the one-time migration from debug-signed test installations.

## Build

Requires JDK 17+ and Android SDK Platform 35 / Build Tools 34.0.0. The workspace-local SDK is kept under the ignored `build/android-sdk/` directory on the external SSD.

```sh
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
The Trade Republic PDF extraction integration test can be run on a connected Android device or emulator with `./gradlew connectedDebugAndroidTest`.
