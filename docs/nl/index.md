# Agora-gebruikershandleiding

Welkom bij de Agora-gebruikershandleiding. Agora is een BYOK (Bring Your Own Key) LLM-client voor Android met toegang van meerdere providers, niet-lineaire vertakkende gesprekken, oproepen van agentische tools en apparaatbediening op afstand.

## Snelle koppelingen

### Aan de slag

- **[Aan de slag](getting-started.md)** — installeer, configureer en verzend uw eerste bericht
- **[FAQ](faq.md)** — antwoorden op veelgestelde vragen

### Kernfuncties

- **[Conversations](conversations.md)** — niet-lineaire vertakkingen, berichtbewerkingen, streaming, weergave van prijsverlagingen
- **[API Providers](provider.md)** — maak verbinding met OpenAI, Anthropic, Google, DeepSeek, Ollama en aangepaste eindpunten
- **[Models](models.md)** — modellen, aliassen, modelsynchronisatie per provider in-/uitschakelen
- **[Systeemprompts](system-prompts.md)** — editor met drie secties, vervanging van variabelen, schakelen per gesprek
- **[Generatie](generation.md)** — temperatuur, top P, max. tokens, denken, frequentie/aanwezigheidsboetes
- **[Titelgeneratie](title-generation.md)** — genereer automatisch gesprekstitels
- **[Image Transcription](transcription.md)** — pijplijn van beeld naar tekst voor visueel blinde providers
- **[Afbeelding genereren](image-generation.md)** — genereren van tekst naar afbeelding als chattool
- **[Uiterlijk](appearance.md)** — themamodus, kleurenschema, dynamische kleur, schemastijl, vervagingseffecten

### Agenthulpmiddelen

- **[Overzicht](tools.md)** — hoe het aanroepen van meerdere rondes werkt
- **[Web Search](web-search.md)** — DuckDuckGo Lite, Brave, Serper, Tavily, SearXNG-integratie
- **[Remote Shell (Conch)](shell.md)** — gecodeerde uitvoering van externe opdrachten, bestandsbewerkingen, MCP-integratie
- **[Sandbox](sandbox.md)** — lokale Alpine Linux-omgeving voor geïsoleerde uitvoering van opdrachten

### Kennisbeheer

- **[Conversation Search](search.md)** — zoeken op trefwoord en semantische (RAG) via de chatgeschiedenis
- **[Embedding / RAG](embedding.md)** — configureer insluitmodellen voor semantisch ophalen
- **[Geheugen en cache](memory.md)** — actief geheugen, opgeslagen herinneringen, automatisch cachen

### Meer

- **[Lokale modellen](local-model.md)** — voer GGUF-modellen uit op het apparaat via llama.cpp
- **[PDF Import](pdf-import.md)** — PDF-pagina's extraheren en naar vision-modellen verzenden
- **[Gegevensportabiliteit](import-export.md)** — export/importeer .agora-bestanden, automatische back-up, import van Claude en ChatGPT
- **[Taal](taal.md)** — schakel tussen Engels, 中文, 繁體中文 of systeemstandaard
- **[Over](about.md)** — versie-informatie, updates, documentatieschakelaars, links, beoordelingen

---

## Over Agora

Agora is een BYOK Android-client voor AI-hoofdgebruikers:

- **Geen tussenpersonen**: directe API-verbindingen, geen telemetrie, geen tracking
- **Opslag op het apparaat**: alles bevindt zich lokaal in een Room-database
- **Niet-lineaire gesprekken**: bewerk eerdere berichten en verken alternatieve takken
- **Standaard agent**: toolaanroep in meerdere rondes met zoeken op het web, het genereren van afbeeldingen, het uitvoeren van code, shell, bestandsbewerkingen en geheugen
- **Afstandsbediening**: Beheer servers via het gecodeerde Conch-protocol
- **Open source**: MIT-licentie, [bron op GitHub](https://github.com/newo-ether/Agora)