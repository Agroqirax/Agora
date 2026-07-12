# Aan de slag

Deze handleiding begeleidt u bij het installeren van Agora, het toevoegen van uw eerste API-sleutel en het verzenden van uw eerste bericht.

## Installatie

### Van F-Droid (aanbevolen)

Agora is beschikbaar op F-Droid, de open-source Android-app store.

1. Installeer [F-Droid](https://f-droid.org/) op uw apparaat
2. Open F-Droid, zoek naar **Agora**
3. Tik op **Installeren**

### Van GitHub-releases

1. Bezoek de [Releases-pagina](https://github.com/newo-ether/Agora/releases)
2. Download het nieuwste `.apk`-bestand
3. Open het bestand op uw apparaat en bevestig de installatie wanneer daarom wordt gevraagd

### Bouw vanuit de bron

Als je liever zelf bouwt:

1. Kloon de repository:
   ```
   git-kloon https://github.com/newo-ether/Agora.git
   ```
2. Open het project in [Android Studio](https://developer.android.com/studio) (Ladybug of nieuwer)
3. Synchroniseer Gradle en bouw

Vereisten: Android SDK 34+, JDK 17+.

---

## Eerste lancering

Wanneer u Agora voor de eerste keer opent, ziet u een welkomstscherm met tekstinvoer. Voordat u kunt chatten, moet u een provider en een API-sleutel configureren.

### Stap 1: Voeg een API-sleutel toe

1. Tik op het pictogram **Instellingen** (tandwiel rechtsonder) in de navigatiebalk
2. Tik onder **Services** op **Provider**
3. Selecteer een provider uit de lijst (bijvoorbeeld **OpenAI**, **Anthropic**, **Google**)
4. Tik op **Nieuwe sleutel toevoegen**
5. Voer een naam in voor uw sleutel (bijvoorbeeld 'Persoonlijk') en plak uw API-sleutel
6. Tik op **Toevoegen**

??? tip "Waar haal ik een API-sleutel?"
    - **Google Gemini**: [Google AI Studio](https://aistudio.google.com/apikey) — gratis niveau beschikbaar
    - **OpenAI**: [Platform API-sleutels](https://platform.openai.com/api-keys)
    - **Antropisch**: [Console API-sleutels](https://console.anthropic.com/)
    - **DeepSeek**: [Platform](https://platform.deepseek.com/)
    - **OpenRouter**: [Sleutelpagina](https://openrouter.ai/keys)

    Zie de pagina [API Providers](provider.md) voor details over elke provider.

### Stap 2: Modellen synchroniseren

1. Ga terug naar Instellingen en tik op **Modellen** (onder **Services**)
2. Tik op **Synchroniseren vanaf alle providers**
3. Agora haalt de nieuwste modellenlijst op voor alle geconfigureerde providers
4. Eenmaal gesynchroniseerd, tikt u op een model om dit in te stellen als uw **Standaardmodel**

### Stap 3: Verzend uw eerste bericht

1. Tik op de **pijl terug** om terug te keren naar het chatscherm
2. Typ een bericht in het invoerveld onderaan
3. Tik op **Verzenden** (papieren vlakpictogram)

Het model zal zijn reactie in realtime streamen.

---

## App-indeling

Agora heeft een strakke lay-out gecentreerd rond het chatscherm:

### Bovenste balk

- **Gesprekstitel** — geeft de huidige gespreksnaam weer (tik om de naam te wijzigen)
- **Hamburgermenu** (:material-menu:) — opent de conversatielade
- **Overloopmenu** (:material-dots-vertical:) — instellingen per gesprek (model, systeemprompt, generatieparameters)

### Gesprekkenlade

Tik op het **hamburgermenu** of veeg vanaf de linkerrand naar rechts om het te openen:

- **Zoekbalk**: vind eerdere gesprekken op trefwoord of semantische zoekopdracht
- **Gesprekslijst** — alle gesprekken, de nieuwste eerst
- **Instellingen** (:material-cog:) — configureer providers, modellen, aanwijzingen en meer
- **Nieuwe chat** — start een nieuw gesprek

### Chatscherm

- **Berichtgebied**: schuifbare gespreksgeschiedenis met weergave van prijsverlagingen
- **Onderste balk**: tekstinvoer, modelkiezer, bijlageknop (+) en verzendknop

---

## Volgende stappen

- [Webzoekopdracht instellen] (web-search.md) — DuckDuckGo Lite werkt kant-en-klaar, geen API-sleutel nodig
- [Configureer het genereren van afbeeldingen](image-generation.md) — tekst-naar-afbeelding rechtstreeks in gesprekken
- [Automatische back-up instellen](import-export.md#auto-backup) — hands-off periodieke gegevensbescherming
- [Verken agentische tools](tools.md) — shell-uitvoering, bestandsbewerkingen, geheugen en meer
- [Gegevens importeren](import-export.md) van Claude of ChatGPT
- [Voer lokale modellen uit](local-model.md) voor offline gebruik