# Gesprek zoeken

Agora kan uw hele gespreksgeschiedenis doorzoeken – door trefwoordmatching of semantisch (betekenisgebaseerd) ophalen met behulp van inbeddingsmodellen.

## Zoekmethoden

### Zoeken op trefwoord

Snelle, exacte tekstmatching. Er wordt gezocht naar letterlijke exemplaren van uw zoekopdracht in de berichtinhoud.

**Beste voor:**
- Het vinden van een specifieke zin of term
- Snelle zoekopdrachten wanneer u de exacte bewoording onthoudt
- Geen configuratie – werkt onmiddellijk

**Beperkingen:**
- Mist synoniemen en gerelateerde concepten
- Geen begrip van de betekenis

### Semantisch zoeken (RAG)

Maakt gebruik van insluitingsmodellen om berichten te vinden op basis van **betekenis**, niet op exacte woorden. Een zoekopdracht naar "hoe de database in te stellen" kan berichten over "Kamerconfiguratie" vinden, zelfs als het woord "database" nooit verschijnt.

**Beste voor:**
- Gesprekken zoeken op onderwerp of thema
- Brede zoekopdrachten waarbij u de exacte formulering niet meer weet
- Het ontdekken van gerelateerde discussies in verschillende gesprekken

**Vereisten:**
- Er moet een inbeddingsmodel worden geconfigureerd (zie [Embedding / RAG](embedding.md))
- Berichten moeten in de cache worden opgeslagen (insluitingen gegenereerd)

---

## Instellen

### 1. Voeg een inbeddingsmodel toe

Zie [Embedding / RAG](embedding.md) voor gedetailleerde instellingen. U kunt gebruiken:
- **Remote-modellen** (OpenAI, Mistral, Voyage, Ollama, enz.)
- **Lokale modellen** (GGUF-bestanden, volledig offline)

### 2. Kies Zoekmethoden

In **Instellingen → Gesprek zoeken**:

| Instelling | Beschrijving |
|---------|------------|
| **Modelzoekmethode** | Hoe het model zoekt wanneer het de tool `search_conversations` aanroept |
| **Handmatige zoekmethode** | Hoe de zoekbalk in de conversatielade werkt |

Stel elk in op **Trefwoord** of **Semantisch (RAG)**.

### 3. Zoekbereik configureren

| Instelling | Bereik | Beschrijving |
|---------|-------|------------|
| **Contextberichten per zoekhit** | 4–32 | Hoeveel omringende berichten moeten bij elke overeenkomst worden opgenomen (stappen: 4, 8, 12, 16, 20, 24, 28, 32) |
| **Max. zoekresultaten** | 5–30 | Maximaal aantal wedstrijden dat moet worden geretourneerd (stappen: 5, 10, 15, 20, 25, 30) |
| **Overeenkomstdrempel** | 0,0–1,0 | Alleen RAG: minimale gelijkenisscore voor een wedstrijd. Hoger = strenger. Standaard: 0,5 |

### 4. Berichten cachen

Als u RAG gebruikt, tikt u op **Cache** om insluitingen voor alle bestaande berichten te genereren. Schakel **Auto-cache** in om de index automatisch bijgewerkt te houden.

---

## Zoeken gebruiken

### Handmatig zoeken (zoekbalk)

1. Open de **gesprekkenlade** (hamburgermenu: materiaalmenu: of veeg naar rechts)
2. Tik bovenaan op de zoekbalk
3. Typ uw vraag
4. De resultaten verschijnen hieronder. Tik op een resultaat om dat gesprek te openen bij het overeenkomende bericht

### Door modellen geïnitieerde zoekopdracht

Wanneer **Toegang tot eerdere gesprekken** is ingeschakeld (Instellingen → Geheugen), kan het model autonoom uw geschiedenis doorzoeken:

```tekst
Jij: "Wat hebben we vorige week besloten over het API-ontwerp?"
Model: [Zoekt naar "API-ontwerpbeslissing"]
       "Afgelopen dinsdag hebben we besloten om..."
```

De zoekopdracht verschijnt als toolkaart in het gesprek.

---

## Gelijkenisdrempel

Met de schuifregelaar **Gelijkheidsdrempel** (0,0 tot 1,0) bepaalt u hoe nauwkeurig een bericht moet overeenkomen om te worden opgenomen in de RAG-resultaten:

- **Laag (0,3–0,5)**: meer resultaten, mogelijk losjes gerelateerde inhoud
- **Medium (0,5–0,7)**: Evenwichtig — goede standaard
- **Hoog (0,7–0,9)**: minder resultaten, alleen zeer nauwe overeenkomsten

Begin met de standaardwaarde en pas deze aan op basis van uw resultaten. Als je te veel irrelevante matches krijgt, verhoog dan de drempel. Als u relevante gesprekken mist, verlaag deze dan.

---

## Weergave zoekresultaten

In de conversatielade tonen de zoekresultaten:

- **Gesprekstitel** (of 'Zonder titel')
- **Overeenkomend bericht** — het gebruikers- of modelbericht dat overeenkomt
- **Rollabel** — Gebruiker of model
- **Contextberichten** — omringende berichten voor context

Tik op een resultaat om het gesprek te openen en naar het bijbehorende bericht te scrollen.