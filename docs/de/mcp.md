# MCP-Server

Agora kann eine Verbindung zu [Model Context Protocol](https://modelcontextprotocol.io)-Servern herstellen und das Modell die von ihnen bereitgestellten Tools aufrufen lassen – Suchmaschinen, Datenbanken, Hausautomation, unternehmensinterne APIs oder alles andere, wofür Sie oder ein Dritter einen MCP-Server erstellt haben.

!!! Hinweis
    Agora unterstützt derzeit nur MCP-**Tools**. Ressourcen, Eingabeaufforderungen und Probenahmen sind noch nicht implementiert.

## Wie es funktioniert

„Text
Agora (Android) ──HTTPS (Streamable HTTP-Transport)──▶ MCP-Server
                                                          │
                                                          ├── initialisieren
                                                          ├── Werkzeuge/Liste
                                                          └── Werkzeuge/Aufruf
„

Agora spricht den MCP **Streamable HTTP**-Transport (ein einzelner HTTP-Endpunkt, nicht stdio oder der ältere HTTP+SSE-Transport). Bei der ersten Verwendung öffnet es eine Sitzung mit „initialize“, listet die Tools des Servers mit „tools/list“ auf und lässt das Modell sie mit „tools/call“ aufrufen. Die Tool-Liste wird pro Server etwa 30 Sekunden lang zwischengespeichert, sodass bei wiederholten Nachrichten nicht jedes Mal ein erneuter Handshake erfolgt. Wenn der Server die Sitzung beendet, stellt Agora beim nächsten Anruf automatisch wieder eine Verbindung her.

Das Modell entscheidet selbst, wann ein MCP-Tool verwendet wird, genauso wie es sich für die Verwendung der Websuche oder der Shell entscheidet – es gibt keinen manuellen Auslöser.

## Sicherheit

Bei den von Ihnen hinzugefügten MCP-Servern handelt es sich um willkürlichen Code, dem Sie den Tool-Zugriff anvertrauen möchten. Agora behandelt sie daher standardmäßig mit Vorsicht:

- **Schreibgeschützte Tools werden ohne Nachfrage ausgeführt.** Wenn ein Server ein Tool mit „readOnlyHint“ markiert, ruft Agora es automatisch auf.
- **Alles andere erfordert eine Bestätigung.** Wenn ein Tool nicht als schreibgeschützt markiert ist, behandelt Agora es als potenziell destruktiv – einschließlich Tools, die überhaupt keinen „destructiveHint“ deklarieren – und zeigt vor der Ausführung einen Bestätigungsdialog mit dem Namen und den Argumenten des Tools an.
- **Mit „Diesen Server immer zulassen“** können Sie die Eingabeaufforderung für den Rest der Sitzung überspringen. Dies wird zurückgesetzt, wenn Agora neu startet.
- **Die Authentifizierung wird nur an den von Ihnen konfigurierten Server gesendet.** Ein von Ihnen hinzugefügter Bearer-Token oder benutzerdefinierter Header wird ausschließlich an die URL dieses Servers gesendet.

!!! Warnung
    Wenn die URL eines Servers einfach „http://“ anstelle von „https://“ verwendet, werden alle von Ihnen konfigurierten Bearer-Token oder Header unverschlüsselt übertragen. Bevorzugen Sie „https://“-Endpunkte, insbesondere gegenüber nicht vertrauenswürdigen Netzwerken.

## Einrichtung

### Schritt 1: Besorgen Sie sich einen MCP-Server

Dies kann ein öffentlicher MCP-Server sein, einer, den Ihre Organisation intern betreibt, oder einer, den Sie selbst hosten. Es muss den **Streamable HTTP**-Transport unter einer einzigen URL verfügbar machen (normalerweise mit der Endung „/mcp“).

### Schritt 2: Fügen Sie es in Agora hinzu

1. Gehen Sie zu **Einstellungen → MCP-Server**
2. Aktivieren Sie **MCP-Tools aktivieren**
3. Tippen Sie auf **Server hinzufügen**
4. Geben Sie die Serverdetails ein:

| Feld | Beschreibung | Beispiel |
| ----------------- | ------------------------------------------------------------------------------------------------------------------- | --------------------------------- |
| **Name** | Anzeigename für diesen Server | „Heimassistent“ |
| **Beschreibung** | Optionaler Hinweis zum Zweck. Bleibt das Feld leer, wird stattdessen der Host des Servers angezeigt.                 | „Steuert Lichter und Thermostate“ |
| **Server-URL** | Der MCP Streamable HTTP-Endpunkt | `https://example.com/mcp` |
| **Inhabertoken** | Optional – gesendet als „Authorization: Bearer <token>“ | Das API-Token Ihres Servers |
| **Zusätzliche Header** | Optional – einer pro Zeile als „Name: Wert“ für Server, die Authentifizierung oder Routing durch einen benutzerdefinierten Header | erwarten `X-Api-Key: Secret` |
| **Zeitüberschreitung** | Timeout pro Anfrage, 5–120 Sekunden | `30` |

5. Tippen Sie auf **Verbindung testen**, um vor dem Speichern zu überprüfen, ob Agora den Server erreichen kann, und um zu sehen, wie viele Tools es verfügbar macht.
6. Tippen Sie auf **Speichern**.

Sobald ein Server erfolgreich eine Verbindung herstellt – entweder über eine Testverbindung oder durch die tatsächliche Verwendung in einem Chat – werden sein Name und seine Version (wie vom Server gemeldet) als kleines Abzeichen neben seinem Eintrag in der Serverliste angezeigt.

### Schritt 3: Verwendung

Senden Sie eine Nachricht, die eines der Tools des Servers verwenden könnte. Wenn das Tool nicht schreibgeschützt ist, werden Sie beim ersten Mal aufgefordert, es zuzulassen. Danach wird es für den Rest der Sitzung gespeichert (oder bis Sie es leugnen).

## Multi-Server-Unterstützung

Fügen Sie so viele Server hinzu, wie Sie möchten – eine Such-API, ein internes Ticketsystem, einen Home-Automation-Hub. Jedes wird unabhängig konfiguriert und authentifiziert und seine Tools werden automatisch mit einem Namensraum versehen (z. B. „mcp__home_assistant__turn_on_light“), sodass identisch benannte Tools von verschiedenen Servern nie kollidieren.

Wenn Sie **MCP-Tools aktivieren** oder das Kontrollkästchen **Aktiviert** eines einzelnen Servers deaktivieren, werden seine Tools aus dem sichtbaren Bereich des Modells entfernt, ohne dass seine Konfiguration gelöscht wird.

## Bestätigen destruktiver Toolaufrufe

Sie können Bestätigungsaufforderungen mit **Destruktive MCP-Tool-Aufrufe bestätigen** unter **Einstellungen → MCP-Server** vollständig deaktivieren – schreibgeschützte Tools werden unabhängig von dieser Einstellung immer ohne Nachfrage ausgeführt. Wenn Sie es deaktivieren, wird jedes Tool von jedem aktivierten Server sofort und ohne Aufforderung ausgeführt. Deaktivieren Sie es daher nur für Server, denen Sie vollkommen vertrauen.

## Fehlerbehebung

### Testverbindung schlägt fehl

- Überprüfen Sie die **Server-URL** noch einmal – es sollte sich um den vollständigen Endpunkt (z. B. „.../mcp“) handeln, nicht nur um den Host
– Wenn der Server eine Authentifizierung erfordert, überprüfen Sie, ob das **Bearer-Token** oder die **Extra-Header** korrekt sind
– Bestätigen Sie, dass der Server den Streamable HTTP-Transport implementiert, nicht stdio oder den alten HTTP+SSE-Transport
- Überprüfen Sie, ob die URL von Ihrem Gerät aus erreichbar ist (nicht nur vom Netzwerk Ihres Desktops).

### Das Modell ruft das Tool nie auf

- Bestätigen Sie, dass **MCP-Tools aktivieren** und das Kontrollkästchen **Aktiviert** des jeweiligen Servers beide aktiviert sind
- Versuchen Sie **Verbindung testen**, um zu bestätigen, dass der Server dieses Tool derzeit auflistet
- Manche Models zögern eher, unbekannte Tools ohne klaren Grund im Gespräch zu nennen – versuchen Sie, deutlich zu machen, was Sie tun möchten

### Bei Anfragen kommt es immer wieder zu Zeitüberschreitungen

- Erhöhen Sie das **Timeout** des Servers, wenn seine Tools langsam sind (z. B. lang laufende Suchvorgänge oder Automatisierungen).
- Ein langsamer oder überlasteter Server wird höchstens einmal alle 30 Sekunden und nicht bei jeder Nachricht erneut versucht, sodass ein vorübergehender Ausfall Ihre Konversation nicht wiederholt zum Stillstand bringt

### Bestätigungsaufforderung zeigt unerwartete Argumente

Im Bestätigungsdialog werden die genauen Argumente angezeigt, die das Modell senden wird. Wenn sie falsch aussehen, lehnen Sie den Anruf ab – das Modell erkennt die Ablehnung normalerweise und passt seinen nächsten Versuch an.