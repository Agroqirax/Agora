# Geheugen en cache

Agora heeft een persistent geheugensysteem waarmee het model informatie uit gesprekken kan onthouden. Gecombineerd met automatische, op insluiting gebaseerde caching, biedt het een kennisbank die meegroeit met uw gebruik.

## Geheugentypen

### Actief geheugen

Eén enkele, altijd actieve geheugencontext die wordt meegeleverd bij **elke API-aanroep** naar het model. Zie het als een plakbriefje dat het model altijd ziet.

**Gebruik actief geheugen voor:**
- Uw naam, voorkeuren en achtergrond
- Projectcontext die het model altijd moet weten
- Vaste instructies die voor alle gesprekken gelden
- Feiten die je niet meer hoeft te herhalen

**Voorbeeld van actieve geheugeninhoud:**
```tekst
Gebruiker: Newo Ether
Voorkeuren: Geeft de voorkeur aan Chinees voor een informeel gesprek, Engels voor technische onderwerpen.
Project: Agora bouwen - een BYOK Android LLM-client.
Codeerstijl: Kotlin, Jetpack Compose, MVVM-architectuur.
```

#### Actief geheugen bewerken

1. Ga naar **Instellingen → Geheugen**
2. Scroll naar **Actief geheugen**
3. Tik op **Actief geheugen bewerken**
4. Voer uw inhoud in
5. Tik op **Opslaan**

Het model kan het actieve geheugen ook bijwerken via toolaanroepen als **Access Active Memory** is ingeschakeld.

---

### Opgeslagen herinneringen

Een verzameling benoemde geheugenbestanden die het model kan doorzoeken, lezen, maken, bewerken en verwijderen. In tegenstelling tot Active Memory (altijd verzonden), worden opgeslagen herinneringen op verzoek opgehaald.

**Gebruik opgeslagen herinneringen voor:**
- Referentiemateriaal (API-documenten, configuratiedetails, opdrachten)
- Projectspecifieke opmerkingen
- Lessen en inzichten uit eerdere gesprekken
- Alles wat u wilt dat het model zich herinnert wanneer dit relevant is

#### Handmatig herinneringen creëren

1. Ga naar **Instellingen → Geheugen**
2. Tik op **Geheugen toevoegen**
3. Voer in:
    - **Titel** — beschrijvende naam
    - **Beschrijving** — korte samenvatting (gebruikt voor zoekmatching)
    - **Inhoud** — de volledige geheugeninhoud
4. Tik op **Maken**

#### Door modellen gemaakte herinneringen

Wanneer **Toegang tot opgeslagen herinneringen** is ingeschakeld, kan het model geheugenbestanden maken, lezen, bijwerken en verwijderen via toolaanroepen. Hierdoor kan het model:

- Onthoud de feiten die je vertelt
- Bewaar nuttige codefragmenten of configuraties
- Bouw in de loop van de tijd een kennisbank op
- Ruim verouderde informatie op

---

## Geheugenrechten

Bepaal waartoe het model toegang heeft:

| Instelling | Locatie | Wanneer inschakelen |
|---------|----------|---------------|
| **Toegang tot opgeslagen herinneringen** | Instellingen → Geheugen | U wilt dat het model geheugenbestanden |
| **Toegang tot actief geheugen** | Instellingen → Geheugen | U wilt dat het model de persistente context | bijwerkt
| **Toegang tot eerdere gesprekken** | Instellingen → Gesprek zoeken | U wilt dat het model de chatgeschiedenis | doorzoekt

Alle drie zijn standaard **uit**. Schakel alleen in wat u nodig heeft.

---

## Automatische cache

Auto-caching genereert automatisch insluitingen voor nieuwe berichten zodra ze binnenkomen. Hierdoor blijft uw gesprekszoekindex up-to-date zonder handmatige tussenkomst.

### Automatische cache inschakelen

1. Ga naar **Instellingen → Conversatie zoeken**
2. Kies een insluitingsmodel (als u dat nog niet heeft gedaan – zie [Embedding / RAG](embedding.md))
3. Schakel onder **Caching** de optie **Nieuwe berichten automatisch cachen** in

Indien ingeschakeld, wordt elk nieuw bericht (gebruiker en model) automatisch ingebed en geïndexeerd voor semantisch zoeken.

### Handmatig cachen

Als automatisch cachen is uitgeschakeld, kunt u berichten handmatig in de cache opslaan:

1. Ga naar **Instellingen → Conversatie zoeken**
2. Tik op **Cache** — berekent de insluitingen voor alle niet in de cache opgeslagen berichten
3. Vooruitgang wordt weergegeven als een cirkelvormige indicator

Tik op **Opnieuw cachen** om de hele index helemaal opnieuw op te bouwen. Hiermee worden alle in de cache opgeslagen insluitingen verwijderd en wordt elk bericht opnieuw verwerkt. Gebruik wanneer:
- Je hebt de inbeddingsmodellen gewijzigd
- De cache lijkt beschadigd of verouderd
- Zoekresultaten zijn onverwacht slecht

!!! waarschuwing
    Opnieuw cachen is onomkeerbaar en kan enige tijd duren, afhankelijk van het aantal berichten en de snelheid van het insluitingsmodel.

### Cachestatus

De instellingen van het insluitingsmodel laten zien hoeveel berichten in de cache zijn opgeslagen versus niet in de cache:
- **"Alle N berichten in cache"** — up-to-date
- **"X van Y berichten niet in de cache opgeslagen"** — achterstand bij verwerking

---

## Geheugentool-oproepen in chat

Als het model geheugentools gebruikt, zie je inline-kaarten:

| Gereedschap | Kaarttekst |
|------|-----------|
| Kijk omhoog | "N opgeslagen herinneringen bekeken" |
| Lees | "Lees [geheugennaam]" |
| Opslaan | "Opgeslagen [geheugennaam]" |
| Bewerken | "[geheugennaam] bijgewerkt" |
| Verwijder | "[geheugennaam] verwijderd" |
| Bijwerken actief | "Actief geheugen bijgewerkt" |

Tik op een kaart om de volledige inhoud te zien die is gelezen of geschreven.

---

## Beste praktijken

- **Houd het actieve geheugen beknopt** — het is opgenomen in elke API-aanroep, dus uitgebreide inhoud verspilt tokens
- **Gebruik beschrijvende titels voor opgeslagen herinneringen** — titels helpen het model de juiste herinnering te vinden
- **Schakel automatische cache in** als u regelmatig gesprekken zoekt
- **Opnieuw cachen na het wisselen van insluitingsmodel**: verschillende modellen produceren incompatibele insluitingen