# API-providers

Agora maakt rechtstreeks verbinding met AI-providers – geen tussenpersoon, geen abonnement, geen telemetrie. U neemt uw eigen API-sleutels mee en alles loopt vanaf uw apparaat.

## Ingebouwde providers

| Aanbieder | Basis-URL | Modellen | Opmerkingen |
|----------|----------|--------|-------|
| **Google** | `https://generatievetaal.googleapis.com/v1beta` | Gemini-serie | Gratis niveau beschikbaar via Google AI Studio |
| **OpenAI** | `https://api.openai.com/v1` | GPT-4, GPT-4o, o-serie | Ondersteunde redeneermodellen |
| **Antropisch** | `https://api.anthropic.com/v1` | Claude-serie | Uitgebreid denken ondersteund |
| **DeepSeek** | `https://api.deepseek.com/v1` | DeepSeek-V3, DeepSeek-R1 | Ondersteunde redeneermodellen |
| **Qwen** | `https://dashscope-intl.aliyuncs.com/compatibel-mode/v1` | Qwen-serie | Via Alibaba DashScope |
| **Ollama** | `http://localhost:11434/v1` | Elk getrokken model | Zelf gehost, geen API-sleutel nodig |
| **OpenRouter** | `https://openrouter.ai/api/v1` | Multi-aanbieder | Toegang tot vele modellen via één API |
| **Lokaal** | N.v.t. | GGUF-modellen | Op apparaat via llama.cpp, volledig offline |

## Van provider wisselen

Tik op de providerkiezer in Instellingen om tussen providers te schakelen. Elke aanbieder onderhoudt zijn eigen:

- API-sleutels
- Basis-URL (bewerkbaar voor proxy's/zelf gehost)
- Modellijst

---

## API-sleutels

### Meerdere sleutels per provider

Elke provider ondersteunt meerdere benoemde API-sleutels. Dit maakt het volgende mogelijk:

- **Rotatie** — schakel tussen sleutels voor verschillende gebruiksniveaus
- **Organisatie** — gescheiden werk- en privégebruik
- **Fallback** — houd een back-upsleutel bij de hand

### Sleutels beheren

1. Ga naar **Instellingen → Provider**
2. Selecteer een aanbieder
3. Tik onder **API-sleutels** op **Nieuwe sleutel toevoegen**
4. Voer een **naam** in (bijvoorbeeld "Werk", "Persoonlijk", "Team gedeeld") en de **sleutelwaarde**
5. Tik op **Toevoegen**

Tik op het keuzerondje om de actieve sleutel in te stellen. Houd een toets lang ingedrukt om **Bewerken** of **Verwijderen**.

### Sleutelveiligheid

!!! waarschuwing
    API-sleutels worden lokaal opgeslagen in een gecodeerde Room-database. Ze worden nooit naar Agora-servers gestuurd (die zijn er niet). Ze worden echter in platte tekst geëxporteerd als u ze opneemt in een `.agora`-exportbestand.

---

## Aangepaste providers

Voeg een OpenAI-compatibel API-eindpunt toe:

1. Ga naar **Instellingen → Provider**
2. Tik op **+ Aangepaste provider toevoegen** onderaan de providerlijst
3. Voer in:
    - **Providernaam**: elke weergavenaam
    - **Basis-URL** — het API-eindpunt
4. Tik op **Toevoegen**

Agora haalt de modellenlijst op van `{base_url}/v1/models`. Eenmaal toegevoegd werken aangepaste providers precies zoals ingebouwde providers: voeg API-sleutels toe, synchroniseer modellen en chat.

### Gebruiksscenario's

- **Zelf gehost**: maak verbinding met vLLM, LocalAI, text-generation-webui of andere OpenAI-compatibele servers
- **Proxy's** — route via een bedrijfsproxy of API-gateway
- **Alternatieve eindpunten**: gebruik Azure OpenAI, Cloudflare AI Gateway of andere compatibele services

### Hernoemen of verwijderen

Druk lang op een aangepaste provider om **Hernoemen** of **Verwijderen**. Als u verwijdert, worden de provider en alle bijbehorende sleutels verwijderd.

!!! waarschuwing
    Ingebouwde providers kunnen niet worden hernoemd of verwijderd.

---

## Basis-URL overschrijven

Elke provider (inclusief ingebouwde) heeft een bewerkbare **Basis-URL**. Dit is handig voor:

- **Proxy's**: route via `https://my-proxy.example.com/v1`
- **Zelf gehost**: wijs naar uw eigen exemplaar
- **Regioroutering**: gebruik regiospecifieke eindpunten

---

## Modellen synchroniseren

Na het toevoegen van API-sleutels synchroniseert u de modellijst:

1. Ga naar **Instellingen → Modellen**
2. Tik op **Synchroniseren vanaf alle providers**
3. Agora haalt beschikbare modellen op bij elke geconfigureerde provider

Een snackbar toont de voortgang en resultaten van de synchronisatie. Vervolgens kunt u individuele modellen in-/uitschakelen en een standaard instellen.

---

## Providerspecifieke opmerkingen

### Google Tweelingen

- API-sleutels van [Google AI Studio](https://aistudio.google.com/apikey)
- Gratis niveau beschikbaar met tarieflimieten
- Ondersteunt code-uitvoering en zoekbasis (ingebouwde tools)

### OpenAI

- API-sleutels van [Platform](https://platform.openai.com/api-keys)
- Redeneringsmodellen (o1, o3) vereisen specifieke API-toegang
- Streaming, tools en visie worden allemaal ondersteund

### Antropisch

- API-sleutels van [Console](https://console.anthropic.com/)
- Uitgebreid denken met configureerbare tokenbudgetten
- Toolgebruik met ondersteuning voor parallelle oproepen

### Ollama

- Geen API-sleutel vereist (lokaal netwerk)
- Basis-URL doorgaans `http://<host>:11434/v1`
- Modellijst opgehaald uit Ollama's API
- Zie [FAQ](faq.md) voor Ollama-specifieke probleemoplossing

### OpenRouter

- Eén API-sleutel voor meer dan 200 modellen
- Prijzen per token variëren per model
- Goed voor het uitproberen van verschillende modellen zonder individuele provideraccounts

### Lokaal (llama.cpp)

- Geen netwerk vereist
- GGUF-modelbestanden opgeslagen op het apparaat
- Zie [Lokale modellen](local-model.md) voor installatie