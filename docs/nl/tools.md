# Agentische hulpmiddelen

De modellen van Agora kunnen autonoom tools gebruiken: ze beslissen wat ze zoeken, uitvoeren, lezen of onthouden zonder dat je elke actie handmatig hoeft te activeren. Tools werken in **multi-round loops**: het model kan een tool aanroepen, het resultaat lezen en vervolgens besluiten een andere tool aan te roepen of te reageren.

## Hoe gereedschapsoproep werkt

1. Je stuurt een bericht
2. Het model besluit dat er externe informatie of actie nodig is
3. Er wordt een **tool call** verzonden: een gestructureerd verzoek met een toolnaam en argumenten
4. Agora voert de tool uit op het apparaat of op een externe server
5. Het resultaat wordt teruggekoppeld naar het model
6. Het model kan een ander hulpmiddel oproepen of een definitief antwoord produceren

Deze lus kan binnen één berichtbeurt meerdere keren worden herhaald.

## Beschikbare hulpmiddelen

### Zoeken op internet

Zoek op internet en haal webpagina's op. Het model kan actuele informatie opzoeken, feiten verifiëren of documentatie opvragen.

- **Aanbieders**: DuckDuckGo Lite (standaard, geen sleutel), Brave, Serper, Tavily, SearXNG
- **Configuratie**: Instellingen → Zoeken op internet
- **Gids**: [Webzoeken](web-search.md)

### Beeld genereren

Genereer afbeeldingen op basis van tekstprompts met behulp van een speciaal tekst-naar-afbeelding-model. Afbeeldingen worden inline in het gesprek weergegeven en kunnen op volledig scherm worden bekeken.

- **Provider**: BYOK — gebruikt uw eigen API-sleutel en basis-URL, losgekoppeld van het chatmodel
- **Configuratie**: Instellingen → Afbeelding genereren
- **Handleiding**: [Afbeelding genereren](image-generation.md)

### Code-uitvoering

Code uitvoeren in een geïsoleerde omgeving:

- **Gemini Code Execution** — ingebouwde code-uitvoering voor Gemini-modellen (geen installatie)
- **Sandbox** — lokale Alpine Linux-omgeving via PRot, met pakketbeheer en SAF-bestandstoegang

### Externe shell

Voer opdrachten uit op externe machines via het [Conch](https://github.com/newo-ether/conch) protocol. Het model kan de serverstatus controleren, bestanden beheren of scripts uitvoeren.

- **Protocol**: end-to-end gecodeerd (ECDH + AES-256-GCM)
- **Configuratie**: Instellingen → Shell
- **Gids**: [Remote Shell](shell.md)

### MCP-servers

Maak verbinding met [Model Context Protocol](https://modelcontextprotocol.io)-servers en laat het model de tools aanroepen die ze beschikbaar stellen: zoeken, databases, domotica of interne API's.

- **Transport**: Streamable HTTP (alleen tools - nog geen bronnen/prompts)
- **Configuratie**: Instellingen → MCP-servers
- **Gids**: [MCP-servers](mcp.md)

### Android

- **Locatie**: haal de geschatte of precieze locatie van het apparaat op
- **Contacten**: zoek en lees contacten die op het apparaat zijn opgeslagen
- **Kalender**: lees aankomende evenementen en maak nieuwe agenda-items

### Bestandsbewerkingen

Bestanden lezen, schrijven, bewerken, glob-zoeken en grep-zoeken op externe apparaten via het Conch-protocol. Het model kan externe bestandssystemen rechtstreeks manipuleren.

!!! opmerking
    Bestandsbewerkingen vereisen een geconfigureerd Conch shell-apparaat. Zie [Remote Shell](shell.md) voor installatie.

### Geheugen

Permanente kennisopslag die gesprekken omvat:

- **Actief geheugen** — altijd inbegrepen bij elke API-aanroep. Gebruik voor feiten, voorkeuren of context die het model altijd moet onthouden.
- **Opgeslagen herinneringen** — een verzameling benoemde geheugenbestanden die het model kan zoeken, lezen, schrijven en bewerken via toolaanroepen.

Zie [Geheugen en cache](memory.md) voor details.

### Gesprek zoeken

Het model kan uw eerdere gespreksgeschiedenis doorzoeken met behulp van trefwoord- of semantische (RAG) methoden. Hierdoor kan naar eerdere discussies worden verwezen zonder dat u deze handmatig hoeft te zoeken en te delen.

Zie [Conversation Search](search.md) voor instellingen.

---

## Tool-UI in Chat

Wanneer een tool wordt aangeroepen, zie je deze inline in het gesprek:

<div class="rasterkaarten" markdown>

- **:materiaal-voortgangssleutel: Tool Call Banner**

  ***

  Toont de naam van het hulpmiddel en de korte status (bijvoorbeeld:material-magnify: "Zoeken naar 'laatste AI-nieuws' op internet").

- **:materiaalcontrolecirkel: gereedschapsresultaat**

  ***

  Toont na uitvoering het opgemaakte resultaat of de samenvatting (bijvoorbeeld '5 resultaten gevonden voor 'laatste AI-nieuws'').

</div>

### Uitbreidbare details

Tik op een tooloproep om deze uit te vouwen en te zien:

- **Argumenten**: de exacte parameters die naar de tool zijn verzonden
- **Resultaat** — de onbewerkte uitvoer van de uitvoering van het gereedschap
- **Status** — succes, mislukking of gedeeltelijke resultaten

### Mislukte oproepen

Als een gereedschapsoproep mislukt, wordt het model op de hoogte gesteld van de fout en kan het het opnieuw proberen of aanpassen. U ziet een rode banner met de foutmelding.

---

## Gereedschapsrechten

U bepaalt tot welke tools het model toegang heeft:

| Instelling | Locatie | Standaard |
| ------------------ | ----------------------------------------- | ------- |
| Zoeken op internet | Instellingen → Zoeken op internet | Uit |
| Schil | Instellingen → Shell | Uit |
| MCP-servers | Instellingen → MCP-servers | Uit |
| Geheugen (opgeslagen) | Instellingen → Geheugen → Toegang tot opgeslagen herinneringen | Uit |
| Geheugen (actief) | Instellingen → Geheugen → Toegang tot actief geheugen | Uit |
| Eerdere gesprekken | Instellingen → Geheugen → Toegang tot eerdere gesprekken | Uit |
| Gesprek zoeken | Instellingen → Gesprek zoeken | Aan\* |

\*De mogelijkheid van het model om gesprekken te doorzoeken is afhankelijk van de configuratie van een insluitingsmodel. Zonder zoekfunctie is alleen zoeken op trefwoorden mogelijk.

---

## Multi-ronde gereedschapslussen

Het model kan meerdere tooloproepen aan elkaar koppelen. Bijvoorbeeld:

1. Gebruiker: "Wat is de nieuwste Linux-kernelversie en draait deze op mijn server?"
2. Model roept `web_search("nieuwste Linux-kernelversie")` aan
3. Model roept `shell_execute("uname -r", device="my-server")` aan
4. Model vergelijkt resultaten en reageert

Elke tooloproep en het resultaat ervan verschijnen als afzonderlijke inline-items in het gesprek vóór het definitieve tekstantwoord.