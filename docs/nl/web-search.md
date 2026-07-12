# Zoeken op internet

Laat het model op internet zoeken en webpagina's in realtime ophalen. Indien ingeschakeld kan het model actuele informatie opzoeken, feiten verifiëren, documentatie ophalen of onderwerpen onderzoeken – allemaal autonoom via tool calling.

## Ondersteunde providers

| Aanbieder | Beschrijving | Gratis niveau | Opstelling |
|----------|-------------|-----------|-------|
| **DuckDuckGo Lite** | Anoniem, geen API-sleutel nodig | Ja (onbeperkt, beste inspanning) | Geen installatie – werkt kant-en-klaar |
| **Dapper** | Privacygerichte zoek-API | Ja (2.000 zoekopdrachten/maand) | [api.search.brave.com](https://api.search.brave.com/) |
| **Serper** | Snelle Google Zoeken-API | Ja (2.500 zoekopdrachten/maand) | [serper.dev](https://serper.dev) |
| **Tavily** | AI-geoptimaliseerd zoeken, gebouwd voor LLM-agenten | Ja (1.000 zoekopdrachten/maand) | [tavily.com](https://tavily.com) |
| **SearXNG** | Zelf-gehoste metazoekmachine | Zelf gehost (onbeperkt) | Uw eigen exemplaar |

## Installatie

### DuckDuckGo Lite

DuckDuckGo Lite is de **standaard** zoekmachine – geen API-sleutel vereist, werkt onmiddellijk.

1. Ga in Agora naar **Instellingen → Zoeken op internet**
2. Selecteer **DuckDuckGo Lite** als zoekmachine
3. Geen sleutel of URL nodig – begin meteen met zoeken

!!! opmerking "Beste service"
    DuckDuckGo Lite maakt gebruik van HTML-scraping van `lite.duckduckgo.com`. DDG kan de lay-out, snelheidslimiet wijzigen of geautomatiseerde verzoeken blokkeren. Het wordt geleverd als een expliciete, beste optie zonder sleutel. Als u betrouwbaarheid nodig heeft, configureer dan een van de onderstaande API-gebaseerde providers.

### Dapper

1. Haal een API-sleutel op van [Brave Search API](https://api.search.brave.com/)
2. Ga in Agora naar **Instellingen → Zoeken op internet**
3. Selecteer **Brave** als zoekmachine
4. Plak uw API-sleutel

### Serper

1. Haal een API-sleutel op van [serper.dev](https://serper.dev)
2. Ga in Agora naar **Instellingen → Zoeken op internet**
3. Selecteer **Serper**
4. Plak uw API-sleutel

### Tavily

1. Haal een API-sleutel op van [tavily.com](https://tavily.com)
2. Ga in Agora naar **Instellingen → Zoeken op internet**
3. Selecteer **Tavily**
4. Plak uw API-sleutel

### SearXNG

1. Zet een SearXNG-instantie op (zelf-gehost) of gebruik een openbare instantie
2. Ga in Agora naar **Instellingen → Zoeken op internet**
3. Selecteer **SearXNG**
4. Voer de **Basis-URL** van uw exemplaar in (bijvoorbeeld `https://searx.be`)
5. API-sleutel is optioneel (alleen nodig als uw exemplaar authenticatie vereist)

!!! waarschuwing "Openbare instanties"
    Openbare SearXNG-instanties zijn vaak beperkt of onbetrouwbaar. Voor consistent gebruik wordt zelfhosting aanbevolen.

---

## Configuratie

### Maximale resultaten

Stel in hoeveel zoekresultaten er per zoekopdracht moeten worden opgehaald: **1–10**. Standaard is apparaatafhankelijk. Meer resultaten geven het model meer context, maar kosten meer tokens.

### Inschakelen/Uitschakelen

Schakel **Enable Web Search** in op de instellingenpagina voor Web Search. Indien uitgeschakeld, kan het model de webzoekfunctie niet oproepen.

---

## Hoe het model zoeken gebruikt

Wanneer u een vraag stelt waarvoor actuele of externe informatie nodig is, roept het model automatisch webzoeken aan:

1. **Zoeken**: het model roept de zoek-API aan met een zoekopdracht die het formuleert
2. **Ophalen**: het model kan optioneel de volledige pagina-inhoud ophalen van resultaat-URL's
3. **Synthetiseer**: het model leest de resultaten en integreert deze in zijn reactie

U ziet elke zoekopdracht en ophaalactie als inline toolkaarten in het gesprek.

### Voorbeeld

```tekst
Jij: "Wat is de nieuwste versie van Python?"
Model: [Zoekt naar "nieuwste Python-versie 2026"]
       [Leest resultaat]
       "Python 3.14.0 werd uitgebracht in oktober 2025..."
```

---

## Web ophalen

Naast zoeken kan het model ook specifieke webpagina's ophalen en lezen. Wanneer het model een URL tegenkomt in de zoekresultaten, kan het `web_fetch` aanroepen om de volledige pagina-inhoud op te halen:

- De opgehaalde inhoud wordt omgezet in een prijsverlaging
- Het model verwerkt het en haalt er relevante informatie uit
- Ophaalresultaten worden weergegeven als gereedschapskaarten

---

## Privacyoverwegingen

Bij gebruik van zoeken op internet:

- Uw zoekopdrachten gaan naar de zoekmachine (Brave, Serper, enz.), niet naar Agora
- Agora registreert of bewaart uw zoekopdrachten niet (behalve in het gesprek zelf)
- SearXNG zelfhosting geeft u de meeste privacy: zoekopdrachten blijven op uw infrastructuur