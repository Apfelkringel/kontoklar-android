# Community-Lösungen für kostenlose Bankanbindungen

Recherche: 17.09.2026. Ziel ist eine dauerhaft kostenlose, read-only Lösung für die eigenen Konten von C24, comdirect und Trade Republic.

## Ergebnis

| Bank / Weg | Community-Fund | Bewertung für KontoKlar |
|---|---|---|
| comdirect REST | [wuttke/comdirect-api](https://github.com/wuttke/comdirect-api), außerdem [python-comdirect-api](https://github.com/keisentraut/python-comdirect-api) und [go-comdirect](https://github.com/jsattler/go-comdirect) | Beste kostenlose Option für das eigene comdirect-Konto. Die Bank stellt eine offizielle REST-API für eigene Anwendungen bereit; eigene OAuth-Clientdaten und Session-TAN bleiben erforderlich. Der native Kotlin-Adapter liegt in `app/src/main/java/de/kontoklar/app/ComdirectReadOnly.kt`. |
| C24 FinTS/HBCI | [hbci4java](https://github.com/hbci4j/hbci4java), [python-fints](https://github.com/raphaelm/python-fints) und [FinTS-Bankenliste](https://www.fints.org/de/hersteller/bankenliste) | FinTS ist als Protokoll frei verfügbar, aber für C24 ist in der geprüften offiziellen Dokumentation kein unabhängiger C24-FinTS-Endpunkt bestätigt. C24 beschreibt seinen PSD2-XS2A-Zugang über finAPI. Deshalb wird kein nicht verifizierter Host in die App eingebaut. |
| Trade Republic privat | [tr-api](https://github.com/cdamken/tr-api), [clitr](https://github.com/rtfpessoa/clitr), [TradeRepublic_Connector](https://github.com/cdamken/TradeRepublic_Connector) | Technisch auslesbar über private App-/Web-Endpunkte, Login, Cookies, WAF und Push-Freigabe. Diese Projekte sind keine offizielle TR-Drittdienst-API; ein Projekt ist bereits archiviert. Nicht für KontoKlar verwenden: fragil, sicherheitlich problematisch und möglicherweise nicht mit den Nutzungsbedingungen vereinbar. |

## Architekturentscheidung

1. **comdirect:** offizielle REST-API, ausschließlich read-only. KontoKlar speichert keine PIN, kein Passwort und keinen OAuth-Token im neuen Adapter. Die Session-TAN muss vom Nutzer selbst bestätigt werden.
2. **C24:** lokale Verarbeitung offizieller CSV-/XLSX-/CAMT.053-Exporte. Ein direkter Adapter kommt erst hinzu, wenn C24 einen offiziell dokumentierten kostenlosen Zugang für diesen Zweck bestätigt.
3. **Trade Republic:** lokaler PDF-/CSV-Import. Keine privaten WebSocket-/REST-Endpunkte, kein Cookie-Import und kein Login-Scraper.
4. **FinTS:** kann später als optionaler generischer Adapter geprüft werden. Vor einer Aktivierung müssen Bankparameterdaten, TAN-Verfahren, Datenschutz und Android-Credential-Sicherheit mit echten Testkonten verifiziert werden; die allgemeine FinTS-Bankenliste allein ist dafür kein ausreichender Nachweis.

## Warum kein „kostenloser Aggregator“

Ein Community-Client kann Softwarekosten vermeiden, ersetzt aber weder die Bankfreigabe noch eine mögliche regulatorische Rolle für eine Anwendung mit mehreren Nutzern. Für den persönlichen Betrieb bleibt die lokale Importlösung tatsächlich kostenlos. Ein automatischer Live-Abruf aller drei Banken ohne Anbietergebühr ist durch die geprüften Community-Projekte nicht belastbar belegt.

## Quellen

- [comdirect REST API für eigene Anwendungen](https://www.comdirect.de/cms/kontakt-zugaenge-api.html)
- [C24: PSD2-/XS2A-Zugang über finAPI](https://hilfe.c24.de/hc/de/articles/360017014279-Wie-ist-PSD2-bei-der-C24-Bank-umgesetzt)
- [Trade Republic: offizielle PSD2-Seite](https://traderepublic.com/de-de/psd2/account-information)
- [Trade Republic `tr-api` Community-Projekt](https://github.com/cdamken/tr-api)
- [Trade Republic `clitr` Community-Projekt](https://github.com/rtfpessoa/clitr)
- [hbci4java](https://github.com/hbci4j/hbci4java)
- [FinTS-Bankenliste mit Aktualitätshinweis](https://www.fints.org/de/hersteller/bankenliste)
