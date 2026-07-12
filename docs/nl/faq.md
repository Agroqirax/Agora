# Veelgestelde vragen

## API en providers

### Hoe krijg ik een API-sleutel?

- **Google Gemini**: [Google AI Studio](https://aistudio.google.com/apikey) — gratis niveau beschikbaar
- **OpenAI**: [Platform API-sleutels](https://platform.openai.com/api-keys)
- **Antropisch**: [Console API-sleutels](https://console.anthropic.com/)
- **DeepSeek**: [Platform](https://platform.deepseek.com/)
- **OpenRouter**: [Sleutelpagina](https://openrouter.ai/keys)
- **Dapper zoeken**: [Dapper zoeken-API](https://api.search.brave.com/)

### Kan ik meerdere API-sleutels voor dezelfde provider gebruiken?

Ja. Elke provider ondersteunt meerdere benoemde sleutels. Tik op het keuzerondje om de actieve sleutel te selecteren. Handig om te wisselen tussen werk-/persoonlijke sleutels of om een ​​back-up bij de hand te hebben. Zie [API-providers](provider.md#api-keys).

### Hoe voeg ik een aangepaste provider toe?

Ga naar Instellingen → Provider → **+ Aangepaste provider toevoegen**. Voer een naam en basis-URL in. Elk OpenAI-compatibel eindpunt werkt. Zie [Aangepaste providers](provider.md#custom-providers).

---

## Lokale modellen

### Welke GGUF-modellen werken?

Agora ondersteunt het GGUF-formaat voor zowel chatten als insluiten. Chatmodellen moeten in het apparaatgeheugen passen (parameters 1–8B, afhankelijk van RAM). Inbeddingsmodellen zijn veel kleiner (100-500 MB). Zie [Lokale modellen](local-model.md).

### Hoe voer ik modellen offline uit?

Importeer een GGUF-chatmodel via Instellingen → Provider → Lokaal → **GGUF-model importeren**. Voor volledig offline semantisch zoeken importeert u ook een GGUF-inbeddingsmodel. Geen netwerkverbinding nodig.

### Waarom is mijn lokale model zo traag?

Lokale gevolgtrekking wordt uitgevoerd op de CPU van uw apparaat. Het is inherent langzamer dan cloud-API's. Tips: gebruik kleinere modellen (1–3B-parameters), lagere kwantisering (Q4_K_M), kortere contextvensters en sluit achtergrondapps.

---

## Insluitingen en zoeken

### Waarom mislukt mijn insluitingsmodeltest?

Veelvoorkomende oorzaken:

- **Verkeerde modelnaam** — controleer de exacte spelling, inclusief Ollama-tags (bijvoorbeeld `qwen3-embedding:8b` niet `qwen3-embedding`)
- **Verkeerde basis-URL** — zorg ervoor dat het eindpunt `/v1/embeddings` ondersteunt
- **Ontbrekende API-sleutel**: sommige providers vereisen authenticatie, zelfs voor insluitingen
- **Netwerk** — controleer de connectiviteit met het eindpunt

### Wat is het verschil tussen zoeken op trefwoord en RAG?

Zoeken op trefwoord komt overeen met exacte tekst. RAG (semantisch zoeken) komt overeen met de betekenis: "database-instellingen" kan "Kamerconfiguratie" vinden, zelfs zonder gedeelde woorden. RAG vereist een insluitingsmodel en berichten in de cache. Zie [Conversatie zoeken](search.md).

### Hoe gebruik ik Ollama voor insluitingen?

1. Installeer Ollama op een machine
2. Trek een inbeddingsmodel: `ollama pull qwen3-embedding:8b`
3. Voeg in Agora een model voor externe inbedding toe met de voorinstelling **Ollama**
4. Gebruik `http://<host>:11434/v1` als basis-URL
5. Voer de exacte modelnaam in, inclusief de tag (bijvoorbeeld `qwen3-embedding:8b`)
6. Laat de API-sleutel leeg

---

## Geheugen

### Wat is het verschil tussen actief geheugen en opgeslagen herinneringen?

**Actief geheugen** is één persistente context die bij elke API-aanroep wordt meegeleverd; het model ziet deze altijd. **Opgeslagen herinneringen** zijn een verzameling benoemde bestanden die het model op verzoek zoekt en ophaalt. Gebruik Active Memory voor hardnekkige feiten; gebruik opgeslagen herinneringen als referentiemateriaal. Zie [Geheugen en cache](memory.md).

### Kan het model mijn herinneringen wijzigen?

Ja, als u **Toegang tot opgeslagen herinneringen** en/of **Toegang tot actief geheugen** inschakelt in Instellingen → Geheugen. Het model kan via tooloproepen herinneringen aanmaken, lezen, bewerken en verwijderen. Alle machtigingen zijn standaard uitgeschakeld.

---

## Shell & Gereedschap

### Hoe stel ik externe shell-toegang in?

Implementeer de [Conch](https://github.com/newo-ether/conch) server op uw doelmachine en voeg vervolgens het apparaat toe in Instellingen → Shell met zijn URL en API-sleutel. Zowel Conch- als SSH-apparaten worden ondersteund. Zie [Remote Shell](shell.md).

### Kan ik op internet zoeken zonder een API-sleutel?

Ja. **DuckDuckGo Lite** is de standaard webzoekmachine en vereist geen API-sleutel. Het werkt kant-en-klaar: schakel gewoon Zoeken op internet in via Instellingen → Zoeken op internet. Voor een hogere betrouwbaarheid configureert u een van de API-gebaseerde providers (Brave, Serper, Tavily, SearXNG). Zie [Webzoeken](web-search.md).

### Is de shell-verbinding gecodeerd?

Ja. Conch maakt gebruik van ECDH-sleuteluitwisseling + AES-256-GCM-codering + HMAC-SHA256-ondertekening. Al het verkeer tussen Agora en de Conch-server is end-to-end gecodeerd.

---

## Gegevens

### Hoe maak ik een back-up van mijn gegevens?

Ga naar Instellingen → Gegevensbeheer → **Gegevens exporteren** om een ​​handmatige `.agora`-back-up te maken. Voor hands-off bescherming schakelt u **Automatische back-up** in Instellingen → Gegevensbeheer → Automatische back-up in. Er wordt regelmatig een back-up van uw gegevens gemaakt op de achtergrond. Zie [Gegevensportabiliteit](import-export.md).

### Kan ik importeren vanuit ChatGPT of Claude?

Ja. Exporteer uw gegevens uit ChatGPT of Claude (zij bieden `.zip`-bestanden) en importeer vervolgens in Instellingen → Gegevensbeheer → **Derde partij**. Zowel de strategieën Samenvoegen als Vervangen worden ondersteund. Zie [Gegevensportabiliteit](import-export.md#third-party-import).

### Zijn mijn API-sleutels opgenomen in de export?

Dat kan, maar het is optioneel. Op het exportscherm kunt u de opname van API-sleutels in- of uitschakelen. Er wordt een waarschuwing weergegeven wanneer u deze inschakelt. Sleutels worden in platte tekst opgeslagen in het `.agora`-bestand, dus neem ze alleen op voor volledige apparaatmigraties naar vertrouwde bestemmingen.

---

## Algemeen

### Waar worden mijn gegevens opgeslagen?

Alles wordt lokaal op uw Android-apparaat opgeslagen in een Room-database. Agora heeft geen servers, geen cloudsynchronisatie, geen telemetrie. Berichten worden rechtstreeks vanaf uw apparaat verzonden naar de AI-provider die u configureert.

### Ondersteunt Agora meerdere talen?

Ja. De gebruikersinterface van de app ondersteunt **Engels**, **中文 (Chinees)** en **繁體中文 (Traditioneel Chinees)**. Instellingen → Taal. Na het overschakelen is een herstart vereist.

### Hoe rapporteer ik een bug of vraag ik een functie aan?

Open een probleem op [GitHub](https://github.com/newo-ether/Agora/issues). Voor bijdragen, zie de sectie [Bijdragen](https://github.com/newo-ether/Agora#contributing) van de README.