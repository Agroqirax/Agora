# MCP-servers

Agora kan verbinding maken met [Model Context Protocol](https://modelcontextprotocol.io)-servers en het model de tools laten aanroepen die zij beschikbaar stellen: zoekmachines, databases, huisautomatisering, interne bedrijfs-API's of iets anders waar u of een derde partij een MCP-server voor heeft gebouwd.

!!! opmerking
    Agora ondersteunt momenteel alleen MCP **tools**. Hulpbronnen, aanwijzingen en steekproeven zijn nog niet geïmplementeerd.

## Hoe het werkt

```tekst
Agora (Android) ──HTTPS (streambaar HTTP-transport)──▶ MCP-server
                                                          │
                                                          ├── initialiseren
                                                          ├── tools/lijst
                                                          └── gereedschap/oproep
```

Agora spreekt het MCP **Streamable HTTP**-transport uit (een enkel HTTP-eindpunt, niet stdio of het oudere HTTP+SSE-transport). Bij het eerste gebruik opent het een sessie met `initialize`, geeft een lijst van de tools van de server weer met `tools/list`, en laat het model ze aanroepen met `tools/call`. De toollijst wordt ongeveer 30 seconden per server in de cache opgeslagen, zodat herhaalde berichten niet elke keer opnieuw worden gehandshaket; als de server de sessie beëindigt, maakt Agora bij het volgende gesprek automatisch opnieuw verbinding.

Het model beslist zelf wanneer een MCP-tool wordt gebruikt, net zoals het besluit om webzoekopdrachten of de shell te gebruiken: er is geen handmatige trigger.

## Beveiliging

MCP-servers die u toevoegt, zijn willekeurige code die u vertrouwt met toegang tot tools, dus Agora gaat hier standaard voorzichtig mee om:

- **Alleen-lezen tools worden uitgevoerd zonder te vragen.** Als een server een tool markeert met `readOnlyHint`, roept Agora deze automatisch aan.
- **Al het andere vraagt ​​om bevestiging.** Als een tool niet gemarkeerd is als alleen-lezen, behandelt Agora het als potentieel destructief (inclusief tools die simpelweg helemaal geen `destructieveHint` declareren) en toont een bevestigingsdialoog met de toolnaam en argumenten voordat het wordt uitgevoerd.
- Met **"Deze server altijd toestaan"** kunt u de prompt voor de rest van de sessie overslaan. Dit wordt gereset wanneer Agora opnieuw opstart.
- **Verificatie wordt alleen verzonden naar de server die u hebt geconfigureerd.** Een Bearer-token of aangepaste header die u toevoegt, wordt uitsluitend naar de URL van die server verzonden.

!!! waarschuwing
    Als de URL van een server gewoon 'http://' gebruikt in plaats van 'https://', wordt elk Bearer-token of elke header die u configureert onversleuteld verzonden. Geef de voorkeur aan `https://`-eindpunten, vooral boven niet-vertrouwde netwerken.

## Installatie

### Stap 1: Koop een MCP-server

Dit kan een openbare MCP-server zijn, een server die uw organisatie intern beheert of een server die u zelf host. Het moet het **Streambare HTTP**-transport op één enkele URL weergeven (meestal eindigend op `/mcp`).

### Stap 2: Voeg het toe in Agora

1. Ga naar **Instellingen → MCP-servers**
2. Schakel **MCP-tools inschakelen** in
3. Tik op **Server toevoegen**
4. Vul de servergegevens in:

| Veld | Beschrijving | Voorbeeld |
| ----------------- | ---------------------------------------------------------------------------------------- | ------------------------------- |
| **Naam** | Weergavenaam voor deze server | `Thuisassistent` |
| **Beschrijving** | Optionele opmerking over waar het voor is. Als u dit veld leeg laat, wordt in plaats daarvan de host van de server weergegeven.                 | `Bedient lampen en thermostaten` |
| **Server-URL** | Het MCP Streamable HTTP-eindpunt | `https://voorbeeld.com/mcp` |
| **Toondertoken** | Optioneel — verzonden als `Authorisatie: Bearer <token>` | Het API-token van uw server |
| **Extra kopteksten** | Optioneel: één per regel, als `Naam: waarde`, voor servers die verificatie of routering via een aangepaste header verwachten | `X-Api-Key: geheim` |
| **Time-out** | Time-out per verzoek, 5–120 seconden | `30` |

5. Tik op **Verbinding testen** om te verifiëren dat Agora de server kan bereiken en om te zien hoeveel tools er beschikbaar zijn, voordat u opslaat.
6. Tik op **Opslaan**.

Zodra een server succesvol verbinding heeft gemaakt (via Testverbinding of door daadwerkelijk gebruik in een chat), verschijnen de naam en versie (zoals gerapporteerd door de server) als een kleine badge naast de vermelding in de serverlijst.

### Stap 3: Gebruik

Stuur een bericht dat gebruik kan maken van een van de tools van de server. Als het hulpprogramma niet alleen-lezen is, wordt u de eerste keer gevraagd dit toe te staan; daarna wordt het onthouden voor de rest van de sessie (of totdat u het ontkent).

## Ondersteuning voor meerdere servers

Voeg zoveel servers toe als je wilt: een zoek-API, een intern ticketingsysteem, een domotica-hub. Elke tool wordt onafhankelijk geconfigureerd en geverifieerd, en hun tools krijgen automatisch een naamruimte (bijvoorbeeld `mcp__home_assistant__turn_on_light`), zodat tools met dezelfde naam van verschillende servers nooit met elkaar in botsing komen.

Als u **Enable MCP Tools** uitschakelt, of het eigen selectievakje **Enabled** van een enkele server, worden de tools verwijderd van wat het model kan zien, zonder de configuratie ervan te verwijderen.

## Destructieve tooloproepen bevestigen

U kunt bevestigingsvragen volledig uitschakelen met **Bevestig destructieve MCP-toolaanroepen** in **Instellingen → MCP-servers**. Alleen-lezen-tools worden altijd uitgevoerd zonder te vragen, ongeacht deze instelling. Als u dit uitschakelt, wordt elk hulpprogramma van elke ingeschakelde server onmiddellijk en zonder prompt uitgevoerd. Schakel het dus alleen uit voor servers die u volledig vertrouwt.

## Problemen oplossen

### Testverbinding mislukt

- Controleer de **Server-URL** nogmaals: deze moet het volledige eindpunt zijn (bijvoorbeeld `.../mcp`), niet alleen de host
- Als de server verificatie vereist, controleer dan of de **Bearer Token** of **Extra Headers** correct zijn
- Bevestig dat de server het Streamable HTTP-transport implementeert, en niet stdio of het oudere HTTP+SSE-transport
- Controleer of de URL bereikbaar is vanaf uw apparaat (niet alleen vanaf het netwerk van uw desktop)

### Het model roept de tool nooit aan

- Controleer of **Enable MCP Tools** en het selectievakje **Enabled** van de specifieke server beide zijn ingeschakeld
- Probeer **Testverbinding** om te bevestigen dat de server momenteel dat hulpmiddel vermeldt
- Sommige modellen zijn terughoudender in het noemen van onbekende tools zonder duidelijke reden in het gesprek. Probeer expliciet te zijn over wat je gedaan wilt hebben

### Verzoeken blijven een time-out vertonen

- Verhoog de **Time-out** van de server als de tools traag zijn (bijvoorbeeld langlopende zoekopdrachten of automatiseringen)
- Een trage of overbelaste server wordt maximaal één keer per 30 seconden opnieuw geprobeerd in plaats van bij elk bericht, zodat een tijdelijke storing uw gesprek niet herhaaldelijk zal vertragen

### Bevestigingsprompt toont onverwachte argumenten

Het bevestigingsvenster toont de exacte argumenten die het model gaat verzenden. Als ze er verkeerd uitzien, weiger dan de oproep. Het model zal de weigering meestal zien en de volgende poging aanpassen.