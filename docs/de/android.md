# Android-Tools

Agora kann sicher auf ausgewählte Android-Systemfunktionen zugreifen, wenn das Modell diese benötigt. Mit diesen Tools kann das Modell Ihren aktuellen Standort abrufen, Kontakte lesen oder mit Ihrem Kalender interagieren, wobei die Android-Berechtigungen beachtet werden.

## Verfügbare Tools

| Tool | Zweck |

| ------------ | ----------------------------------------------------- |

| **Standort** | Den ungefähren oder genauen Standort des Geräts abrufen |

| **Kontakte** | Auf dem Gerät gespeicherte Kontakte suchen und lesen |

| **Kalender** | Anstehende Ereignisse lesen und neue Kalendereinträge erstellen |

Das Modell erkennt automatisch aktivierte Tools und entscheidet, wann diese während eines Gesprächs nützlich sind.

## Datenschutz & Berechtigungen

Android-Tools benötigen Standard-Android-Laufzeitberechtigungen.

Wenn das Modell zum ersten Mal versucht, eines dieser Tools zu verwenden, fordert Agora die entsprechende Android-Berechtigung an. Berechtigungen werden nur angefordert, wenn ein Tool zum ersten Mal benötigt wird.

!!! Hinweis
Sie können Berechtigungen jederzeit in den Android-Einstellungen Ihres Geräts widerrufen.

## Einrichtung

1. Gehen Sie zu **Einstellungen → Android**.
2. Aktivieren Sie die gewünschten Funktionen:

- **Standort**

- **Kontakte**

- **Kalender**

3. Erteilen Sie die angeforderten Android-Berechtigungen.

Nach der Aktivierung kann das Modell automatisch auf diese Funktionen zugreifen, wenn sie während eines Gesprächs hilfreich sind.

## Standort

Mit der Standortfunktion kann das Modell den aktuellen Standort Ihres Geräts ermitteln.

Typische Anwendungsfälle:

- Orte in der Nähe finden
- Lokale Wetterinformationen abrufen
- Standortbezogene Empfehlungen geben
- Reisezeiten schätzen
- Fragen zu Ihrer Umgebung beantworten

Abhängig von Ihren Geräteeinstellungen und den erteilten Berechtigungen kann der Standort ungefähr oder genau sein.

## Kontakte

Mit der Kontaktefunktion kann das Modell die auf Ihrem Gerät gespeicherten Kontakte durchsuchen.

Typische Anwendungsfälle:

- Telefonnummern suchen
- E-Mail-Adressen finden
- Gespeicherte Kontakte identifizieren
- Kontakte für Nachrichten oder andere Kommunikationsaufgaben auswählen

Das Modell greift nur auf die Kontaktinformationen zu, die zur Erfüllung Ihrer Anfrage erforderlich sind.

## Kalender

Das Kalender-Tool ermöglicht es dem Modell, Ihren Kalender zu lesen und Termine zu erstellen.

Typische Anwendungsfälle:

- Terminkalender einsehen
- Anstehende Termine anzeigen
- Verfügbare Zeiten finden
- Termine erstellen
- Besprechungsdetails einsehen

Zum Erstellen oder Bearbeiten von Terminen ist eine Schreibberechtigung für den Kalender erforderlich.

## Sicherheit

Android-Tools nutzen das integrierte Berechtigungssystem von Android.

- Vor der ersten Verwendung werden Sie um Berechtigungen gebeten.

- Berechtigungen können jederzeit widerrufen werden.

- Auf deaktivierte Tools kann nicht zugegriffen werden.

Der gesamte Zugriff erfolgt lokal über das Berechtigungsframework von Android.

Agora kann ohne Ihre Zustimmung nicht auf geschützte Daten zugreifen.

## Fehlerbehebung

### Zugriff verweigert

Wenn Ihr Gerät meldet, dass es nicht auf ein Tool zugreifen kann:

- Überprüfen Sie, ob das Tool unter **Einstellungen → Android** aktiviert ist.

- Stellen Sie sicher, dass die erforderliche Android-Berechtigung erteilt wurde.

- Entziehen Sie die Berechtigung gegebenenfalls und erteilen Sie sie erneut in den Android-Einstellungen.

### Standort nicht verfügbar

- Stellen Sie sicher, dass die Ortungsdienste auf Ihrem Gerät aktiviert sind.

- Begeben Sie sich in einen Bereich mit besserer GPS- oder Netzabdeckung.

- Erteilen Sie die Berechtigung für einen präzisen Standort, falls eine höhere Genauigkeit erforderlich ist.

### Kalender oder Kontakte sind leer

Überprüfen Sie, ob Ihr Gerät Kalendereinträge oder Kontakte enthält und ob die entsprechende Android-Berechtigung erteilt wurde.
