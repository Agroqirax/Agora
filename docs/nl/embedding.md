# Inbedding / RAG

Inbeddingsmodellen zetten tekst om in numerieke vectoren die de betekenis vastleggen. Agora gebruikt deze vectoren voor semantisch zoeken (RAG) in uw gespreksgeschiedenis, waarbij berichten worden gevonden op basis van wat ze betekenen, en niet alleen op basis van de woorden die ze bevatten.

## Hoe het werkt

1. Elk bericht wordt naar een inbeddingsmodel verzonden
2. Het model retourneert een vector (een lijst met getallen) die de betekenis van het bericht vertegenwoordigt
3. Wanneer u zoekt, wordt uw zoekopdracht ook ingesloten
4. Agora berekent **cosinusovereenkomst** tussen de queryvector en alle berichtvectoren
5. Berichten met gelijkenis boven uw drempelwaarde worden als overeenkomsten geretourneerd

## Ondersteunde providers

| Aanbieder | Basis-URL | Vereist API-sleutel | Opmerkingen |
|----------|----------|----------------|-------|
| **OpenAI** | `https://api.openai.com/v1` | Ja | `tekst-embedding-3-klein`, `tekst-embedding-3-groot` |
| **Mistral** | `https://api.mistral.ai/v1` | Ja | `mistral-embed` |
| **Reis AI** | `https://api.voyageai.com/v1` | Ja | `reis-3`, `reis-3-lite` |
| **SiliconFlow** | `https://api.siliconflow.cn/v1` | Ja | `BAAI/bge-large-zh-v1.5` (Chinees geoptimaliseerd) |
| **Ollama** | `http://localhost:11434/v1` | Nee | `qwen3-embedding`, `nomic-embed-text`, enz. |
| **Aangepast** | Elke | Optioneel | Elk OpenAI-compatibel insluitingseindpunt |
| **Lokaal** | N.v.t. | Nee | GGUF-inbeddingsmodellen via llama.cpp |

---

## Een inbeddingsmodel toevoegen

### Op afstand (API)

1. Ga naar **Instellingen → Conversatie zoeken**
2. Tik op **Extern model toevoegen**
3. Configureer:

| Veld | Beschrijving |
|-------|------------|
| **Aanbieder** | Selecteer uit de vervolgkeuzelijst (OpenAI, Mistral, Voyage, SiliconFlow, Ollama, Custom) |
| **Modelnaam** | De exacte model-ID (bijvoorbeeld `text-embedding-3-small`) |
| **Basis-URL** | Automatisch ingevuld voor bekende providers; bewerkbaar voor proxy's |
| **API-sleutel** | Laat dit leeg om automatisch op te lossen vanaf de sleutel van uw chatprovider, of voer een speciale sleutel in |
| **Batchgrootte** | Berichten die per API-verzoek moeten worden ingesloten (1–100) |

4. Tik op **Toevoegen** — er wordt een verbindingstest uitgevoerd voordat deze wordt opgeslagen

!!! fooi
    Het API-sleutelveld is optioneel als u dezelfde provider al voor chat heeft geconfigureerd. Laat het leeg en Agora lost uw chat-API-sleutel automatisch op.

### Lokaal (GGUF)

1. Ga naar **Instellingen → Conversatie zoeken**
2. Tik op **Lokaal model toevoegen**
3. Importeer een `.gguf`-inbeddingsmodelbestand (bijvoorbeeld `bge-small-en-v1.5-q4_k.gguf`)
4. Geef het een naam
5. Tik op **Toevoegen**

Insluitingsmodellen zijn doorgaans veel kleiner dan chatmodellen: maximaal een paar honderd MB.

### Ollama

1. Installeer Ollama op een machine
2. Trek een inbeddingsmodel: `ollama pull qwen3-embedding:8b`
3. Voeg in Agora een extern model toe:
    - Aanbieder: **Ollama**
    - Basis-URL: `http://<host>:11434/v1`
    - Modelnaam: `qwen3-embedding:8b` (inclusief de `:tag`)
    - API-sleutel: leeg laten
4. Tik op **Toevoegen**

!!! opmerking
    Ollama-achtervoegseltags zoals `:8b`, `:latest` maken deel uit van de modelnaam. Gebruik de exacte naam uit de `ollama-lijst`.

---

## Caching

Nadat u een model heeft toegevoegd, moet u uw berichten in de cache opslaan (insluitingen genereren):

1. Tik op **Cache** op het insluitingsmodel
2. Agora verwerkt alle niet-gecachte berichten in batches
3. Een circulaire voortgangsindicator geeft de huidige voortgang weer
4. Voltooiing: "Alle N berichten in de cache"

### Automatische cache

Schakel **Auto-cache** in om nieuwe berichten automatisch in te sluiten zodra ze binnenkomen. Hierdoor blijft uw zoekindex altijd up-to-date.

### Opnieuw cachen

Tik op **Opnieuw cachen** om alle bestaande insluitingen te verwijderen en helemaal opnieuw op te bouwen. Gebruik wanneer:

- Overstappen naar een ander inbeddingsmodel
- De kwaliteit van de inbedding lijkt verslechterd
- De cache is inconsistent

!!! waarschuwing
    Het opnieuw in de cache plaatsen kan niet ongedaan worden gemaakt en kan lang duren bij grote berichtgeschiedenissen.

---

## Batchgrootte

De instelling **Batchgrootte** (1-100) bepaalt hoeveel berichten er per API-verzoek worden verzonden tijdens het cachen:

- **Hoger**: snellere caching, maar grotere API-payloads
- **Lager**: kleinere verzoeken, langzamer maar betrouwbaarder bij trage verbindingen

Begin met de standaardwaarde en pas deze aan als u time-outs tegenkomt (verlaag deze) of snellere caching wilt (verhoog deze).

---

## Je configuratie testen

Wanneer u een extern model toevoegt, voert Agora een automatische verbindingstest uit. Als het mislukt:

1. Controleer de modelnaam - voeg tags toe voor Ollama (`:8b`, `:latest`)
2. Controleer of de basis-URL bereikbaar is vanaf uw apparaat
3. Bevestig dat de API-sleutel geldig is (indien nodig)
4. Probeer een bekende modelnaam voor die provider

Veel voorkomende fouten:
- **"Verkeerde modelnaam"** — controleer de exacte spelling, inclusief tags
- **"Verkeerde basis-URL"** — zorg ervoor dat het eindpunt `/v1/embeddings` ondersteunt
- **"Ontbrekende API-sleutel"** — sommige providers vereisen authenticatie
- **"Netwerkfout"** — controleer de connectiviteit

---

## Aanbevelingen van aanbieders

| Gebruiksscenario | Aanbevolen aanbieder |
|----------|---------------------|
| **Beste kwaliteit (Engels)** | Reis AI `reis-3` |
| **Beste kwaliteit (Chinees)** | SiliconFlow `BAAI/bge-groot-zh-v1.5` |
| **Gratis / zelf gehost** | Ollama `qwen3-embedding` of `nomic-embed-text` |
| **Volledig offline** | Lokale GGUF `bge-small-en-v1.5` |
| **Gebruikt OpenAI al** | OpenAI `text-embedding-3-small` (goedkoop, snel) |