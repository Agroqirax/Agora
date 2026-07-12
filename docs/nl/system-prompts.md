# Systeemprompts

Systeemprompts definiëren de persoonlijkheid, het gedrag en de basisregels van het model. Agora geeft u nauwkeurige controle over hoe instructies worden samengesteld en naar het model worden verzonden.

## Editor met drie secties

Elke systeempromptsjabloon heeft drie onafhankelijk bewerkbare secties:

```tekst
┌─────────────────────────────────┐
│ Systeemprompt │ ← Kerninstructies (persona, regels, toon)
├─────────────────────────────────┤
│ Gebruikersvoorvoegsel │ ← Wordt vóór elk gebruikersbericht toegevoegd
├─────────────────────────────────┤
│ Gebruikersachtervoegsel │ ← Wordt toegevoegd na elk gebruikersbericht
└─────────────────────────────────┘
```

### Systeemprompt

Het belangrijkste instructieblok. Hier definieer je:

- **Persona**: "Je bent een senior Python-ontwikkelaar die zich richt op schone architectuur."
- **Regels**: "Reageer altijd in het Chinees. Gebruik opsommingstekens voor lijsten."
- **Beperkingen**: "Verontschuldig je nooit. Wees beknopt. Geef de voorkeur aan code boven uitleg."

### Gebruikersvoorvoegsel en achtervoegsel

Deze verpakken elk bericht dat u verzendt:

- **Gebruikersvoorvoegsel** — toegevoegd vóór uw berichttekst. Handig voor herinneringen of contexttags.
- **Gebruikersachtervoegsel** — toegevoegd na uw berichttekst. Handig voor afsluitinstructies.

**Voorbeeld**: als uw voorvoegsel `[Context: bezig met Agora-documenten]` is en het achtervoegsel `\n\nBeantwoord alstublieft in Markdown.`, ontvangt het model:

```tekst
[Context: werken aan Agora-documenten]
Hoe configureer ik zoeken op internet?
Reageer alstublieft in Markdown.
```

---

## Ingebouwde sjablonen

Agora wordt geleverd met een bibliotheek met kant-en-klare promptsjablonen, onderverdeeld in 4 categorieën:

| Categorie | Beschrijving | Voorbeeld |
|----------|-------------|---------|
| **Algemeen** | Veelzijdige assistenten voor dagelijkse taken | Behulpzame assistent, uitlegger, samenvatter |
| **Codering** | Softwareontwikkeling en codebeoordeling | Code-recensent, debugger, architect |
| **Creatief** | Schrijven, verhalen vertellen en ideeën bedenken | Verhalenverteller, dichter, brainstormpartner |
| **Analyse** | Data-analyse, onderzoek en kritiek | Data-analist, onderzoeker, advocaat van de duivel |

Ingebouwde sjablonen kunnen worden gebruikt zoals ze zijn of kunnen worden bewerkt om aan uw behoeften te voldoen. Ze dienen als uitgangspunt. Pas de drie secties aan zodat ze bij uw workflow passen.

---

## Een prompt maken

1. Ga naar **Instellingen → Systeemprompts**
2. Tik op **Nieuwe prompt toevoegen**
3. Voer een **titel** in (bijvoorbeeld "Vertaler", "Coderecensent", "Chinese assistent")
4. Vul de drie secties in:
    - Tik op **Tekst toevoegen** om statische inhoud te schrijven
    - Tik op **Variabele toevoegen** om dynamische waarden in te voegen
5. Tik op **Opslaan**

### Artikelen opnieuw ordenen

Binnen elke sectie kunt u meerdere tekstblokken en variabelen hebben. Houd een item lang ingedrukt om:

- **Omhoog** / **Omlaag** — opnieuw ordenen binnen de sectie
- **Verwijderen** — verwijder het item

---

## Variabele vervanging

Variabelen worden vervangen door dynamische waarden wanneer het bericht wordt verzonden:

| Variabel | Breidt uit naar | Voorbeeld | Wanneer opgelost |
|----------|-----------|---------|---------------|
| `{tijd}` | Huidige tijd (UU:mm:ss) | `14:30:00` | Snelle compilatie |
| `{datum}` | Huidige datum (JJJJ-MM-DD) | `2026-05-10` | Snelle compilatie |
| `{sent_time}` | Tijd verzonden bericht (UU:mm) | `10:05` | Per bericht |
| `{sent_date}` | Datum verzonden bericht (JJJJ-MM-DD) | `11-05-2026` | Per bericht |
| `{actief_geheugen}` | Inhoud van het actieve geheugen | `[Uw opgeslagen geheugeninhoud]` | Snelle compilatie |
| `{model_id}` | Momenteel geselecteerde model-ID | `gemini-1,5-flits` | Snelle compilatie |

**Variabelen per bericht** (`{sent_time}`, `{sent_date}`) worden elke keer dat u een bericht verzendt, opgelost, zodat ze de exacte verzendtijd weergeven. **Variabelen op promptniveau** (`{time}`, `{date}`, `{active_memory}`, `{model_id}`) worden opgelost wanneer de systeemprompt wordt gecompileerd.

!!! fooi
    Gebruik `{sent_date}` voor datumgevoelige aanwijzingen zoals "Vandaag is het {sent_date}. Houd er bij het bespreken van recente gebeurtenissen rekening mee dat uw kennis verouderd kan zijn." Gebruik `{active_memory}` om het persistente geheugen van het model in systeeminstructies te injecteren.

### Een variabele toevoegen

1. Tik in een willekeurige sectie van de editor op **Variabele toevoegen**
2. Selecteer de variabele in de kiezer
3. Het verschijnt als een pil/chip in de sectie: sleep om te verplaatsen

---

## Aanwijzingen beheren

### Instellen als standaard

Tik op het keuzerondje naast een prompt om dit de **algemene standaard** te maken. Alle gesprekken gebruiken deze prompt, tenzij deze wordt overschreven.

### Overschrijven per gesprek

Voor elk gesprek kan een andere systeemprompt worden gebruikt:

1. Open een gesprek
2. Tik op het overloopmenu (:material-dots-vertical:) in de bovenste balk
3. Selecteer **Gespreksprompt**
4. Kies een prompt uit de lijst

De instelling per gesprek overschrijft alleen de algemene standaard voor dat gesprek.

### Bewerken of verwijderen

- Tik op een prompt om deze te **bewerken**
- Druk lang op en selecteer **Verwijderen** om het te verwijderen

!!! waarschuwing
    Het verwijderen van een systeemprompt is permanent. Gesprekken die hiervan gebruik maakten, zullen terugvallen op de mondiale standaard.

---

## Geen systeemprompt

Als er geen systeemprompt is geselecteerd, ontvangt het model geen speciale instructies; het gedraagt zich volgens de basistraining. Dit is soms wenselijk voor testen of voor modellen die beter presteren zonder systeeminstructies.

Als u geen prompt wilt gebruiken, selecteert u **Geen** in de promptlijst.

---

## Automatische titelgeneratie

Agora kan na de eerste reactie automatisch gesprekstitels genereren:

1. Ga naar **Instellingen → Titel genereren**
2. Schakel **Titel automatisch genereren** in
3. Kies een **Titelmodel**:
    - **Gebruik huidig model** — gebruikt welk model dan ook actief is in het gesprek
    - **Selecteer titelmodel** — kies een specifiek snel/goedkoop model voor het genereren van titels

Indien ingeschakeld, verschijnt er een korte snackbalk "Titel genereren..." na het eerste modelantwoord, en wordt de conversatie automatisch hernoemd van "Zonder titel" naar een beschrijvende titel.

---

## Snelle voorbeelden

### Vertaler

```jaml
Systeemprompt: |
  Je bent een professionele vertaler. Vertaal gebruikersinvoer naar het Engels.
  Behoud de opmaak, codeblokken en technische termen. Voeg geen uitleg toe.
```

### Code-recensent

```jaml
Systeemprompt: |
  Je bent een senior code-reviewer. Wanneer code wordt weergegeven:
  1. Identificeer bugs en randgevallen
  2. Stel prestatieverbeteringen voor
  3. Controleer op beveiligingsproblemen
  Wees specifiek. Verwijs indien mogelijk naar regelnummers.
```

### Chinese assistent

```jaml
Systeemprompt: |
  你是一个乐于助人的中文助手。用简洁、清晰的中文回答问题。
Gebruikersachtervoegsel: |
  \n\n请用中文回答。
```