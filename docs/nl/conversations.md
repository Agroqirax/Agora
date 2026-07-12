# Gesprekken

Het gesprekssysteem van Agora is opgebouwd rond **niet-lineaire vertakkingen**. In tegenstelling tot de meeste chat-apps kunt u elk eerder bericht bewerken en alternatieve antwoordpaden verkennen zonder het oorspronkelijke gesprek te verliezen.

## Gesprekken creëren

Tik op **Nieuwe chat** in de gesprekslade, of begin gewoon met typen in het chatscherm. Bij uw eerste bericht wordt automatisch een nieuw gesprek aangemaakt.

Gesprekken krijgen een automatische titel na het eerste antwoord (als [Titel genereren](system-prompts.md#auto-title-generation) is ingeschakeld), of u kunt de naam ervan handmatig wijzigen.

## Gesprekken beheren

### Wissel van gesprek

Open de **gesprekkenlade** (hamburgermenu: materiaalmenu: of veeg naar rechts) en tik op een gesprek om het te openen.

### Hernoemen

1. Druk lang op een gesprek in de la
2. Tik op **Hernoemen**
3. Voer een nieuwe titel in en sla op

### Verwijderen

1. Druk lang op een gesprek in de la
2. Tik op **Verwijderen**
3. Bevestig de verwijdering. Deze actie kan niet ongedaan worden gemaakt

---

## Niet-lineaire vertakking

Dit is het kenmerkende kenmerk van Agora. Elk bericht kan een vertakkingspunt zijn.

### Een eerder bericht bewerken

1. Druk lang op een berichtballon (gebruiker of model)
2. Tik op **Bewerken**
3. Wijzig de berichtinhoud
4. Verzenden — Agora maakt vanaf dit punt een **nieuwe branch** aan

De oorspronkelijke tak blijft behouden. U kunt op elk moment tussen vestigingen wisselen.

### Hoe filialen werken

Elk bericht bevindt zich in een **boomstructuur**:

```tekst
Bericht 1 (gebruiker)
├── Bericht 2 (Model) ← origineel antwoord
└── Bericht 3 (Model) ← tak aangemaakt na bewerken Bericht 1
    ├── Bericht 4 (Gebruiker)
    └── ...
```

Wanneer u een bericht bewerkt en opnieuw genereert, wordt het nieuwe antwoord een broer of zus van het oorspronkelijke bericht; beide bestaan onder hetzelfde bovenliggende bericht.

### Van filiaal wisselen

Wanneer een bericht meerdere onderliggende (takken) heeft, toont de gebruikersinterface navigatieknoppen om tussen deze te schakelen. U kunt alternatieve paden verkennen zonder de context te verliezen.

### Waarom filiaal?

- **Ontdek alternatieven** — stel dezelfde vraag met andere bewoordingen
- **A/B-testprompts**: vergelijk reacties van verschillende systeemprompts of modellen
- **Fouten corrigeren** — corrigeer een typefout in uw vraag zonder de oorspronkelijke draad te verliezen
- **Itereren** — verfijn een prompt via meerdere versies terwijl alle pogingen behouden blijven

---

## Berichtbewerkingen

Houd een bericht lang ingedrukt om toegang te krijgen tot deze acties:

| Actie | Beschrijving |
|--------|------------|
| **Kopiëren** | Kopieer de berichttekst naar het klembord |
| **Bewerken** | Bewerk het bericht en maak een tak aan |
| **Informatie** | Metagegevens bekijken: tijdstempel, gebruikt model, aantal tokens |
| **Opslaan/Delen** | Houd afbeeldingen lang ingedrukt om ze op te slaan in de galerij of te delen |
| **Verwijderen** | Verwijder dit bericht en alle vervolgreacties |

!!! waarschuwing "Een bericht verwijderen"
    Als u een bericht verwijdert, worden ook alle reacties die daarop volgen verwijderd. Dit kan niet ongedaan worden gemaakt.

---

## De onderste balk

Het chatinvoergebied biedt snelle toegang tot essentiële bedieningselementen:

### Modelkiezer

Tik op de modelnaam aan de linkerkant van de onderste balk om de **modelkiezer** te openen. U kunt op elk moment van model wisselen, zelfs midden in een gesprek. Verschillende berichten in hetzelfde gesprek kunnen verschillende modellen gebruiken.

### Bijlagen

Tik op *+** (:material-plus:) om bestanden bij te voegen:

- **Foto's** — afbeeldingen uit uw galerij
- **Video's** — videobestanden (met ondersteuning voor frame-extractie)
- **Bestanden** — elk bestandstype, inclusief PDF's

Ondersteunde beeldformaten worden rechtstreeks naar modellen met zichtfunctie verzonden. PDF-bestanden openen een dialoogvenster voor paginaselectie.

### Verzenden

Typ uw bericht en tik op **Verzenden** (:material-send:). Het model streamt zijn antwoord token voor token.

---

## Streaming en weergave

### Realtime streaming

Antwoorden verschijnen woord voor woord terwijl het model ze genereert. Agora scrollt automatisch om de nieuwste inhoud zichtbaar te houden. Tik op de knop **naar beneden scrollen** (verschijnt wanneer u naar boven scrolt) om terug te gaan naar het live antwoord.

### Markdown-weergave

Modelreacties worden weergegeven met volledige ondersteuning voor prijsverlagingen:

- **Headers**, **vet**, *cursief*, `inline code`
- **Codeblokken** met syntaxisaccentuering (gebruik ````` ``` `````)
- **Tabellen**, blokcitaten, lijsten
- **LaTeX wiskunde** — inline `$E=mc^2$` en blok `$$\int_a^b f(x)dx$$` met verbeterde parsering (CJK-tekstdetectie, afhandeling van ontsnapte dollars)

Streaming-markdown wordt weergegeven met een **dubbelgebufferde crossfade**-techniek: de gebruikersinterface gaat soepel over tussen renderpassages in plaats van te flikkeren bij elk token, zelfs tijdens snelle streaming.

### Denkdisplay

Voor modellen die redeneren ondersteunen (OpenAI o-serie, Antropisch uitgebreid denken, Gemini denken, DeepSeek-R1), wordt het denkproces van het model weergegeven in **gegroepeerde, opvouwbare panelen**:

- Het paneel toont "Denken..." tijdens de redeneerfase
- Eenmaal voltooid, wordt de denkduur weergegeven (bijvoorbeeld "Gedacht gedurende 12 seconden")
- Tik om de gedachte-inhoud uit te vouwen/samen te vouwen
- Meerdere denkblokken zijn gegroepeerd voor een schonere presentatie
- Gereedschapsaanroepen die tijdens het denken worden gedaan, worden geteld (bijvoorbeeld 'Gedacht voor 8s, 2 gereedschappen genoemd')

### Gegenereerde afbeeldingen

Wanneer [Afbeelding genereren](image-generation.md) is ingeschakeld, verschijnen gegenereerde afbeeldingen inline in het gesprek als modelberichtbijlagen. Tik op een afbeelding om deze op volledig scherm te openen met gebarenbediening (knijpzoom, pannen, dubbeltikken). Druk lang om op te slaan of te delen.

---

## Instellingen per gesprek

Elk gesprek kan de algemene standaardwaarden overschrijven:

- **Model** — selecteer een ander model voor dit gesprek
- **Systeemprompt** — gebruik een andere systeeminstructie
- **Generatieparameters** — temperatuur, maximale tokens, denkniveau

Deze overschrijvingen worden ingesteld via het overloopmenu van het gesprek in de bovenste balk.

---

## Contextvenster

Agora volgt het tokengebruik in realtime. Wanneer een gesprek het contextvenster van het model overschrijdt, worden oudere berichten visueel **gedimd** om aan te geven dat ze zich buiten de actieve context bevinden. Het model "ziet" niet langer gedimde berichten, maar ze blijven zichtbaar in uw gebruikersinterface.

Pas de grootte van het contextvenster aan in **Instellingen → Generatie → Contextvenster**.