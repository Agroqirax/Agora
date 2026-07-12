# Beeldtranscriptie

Laat een visiemodel afbeeldingen beschrijven, zodat modellen met alleen tekst ze kunnen begrijpen.

## Wat het doet

Wanneer u een afbeelding naar een tekstmodel stuurt, kan Agora een apart visiemodel gebruiken om eerst een tekstbeschrijving van de afbeelding te genereren. Deze beschrijving wordt vervolgens opgenomen in de prompt die naar uw hoofdmodel wordt verzonden.

Hierdoor kunt u afbeeldingen met elk model gebruiken, zelfs modellen die visie niet van nature ondersteunen.

## Installatie

1. Ga naar **Instellingen → Afbeeldingtranscriptie**
2. Kies een **Transcriptiemodel** — dit moet een model zijn dat goed kan zien (bijv. GPT-4o, Gemini Flash, Qwen-VL)
3. Voeg modellen toe aan **Ingeschakelde modellen**: dit zijn de modellen met alleen tekst die afbeeldingsbeschrijvingen ontvangen
4. Pas **Batchgrootte** aan als u veel afbeeldingen tegelijk verzendt (hoeveel afbeeldingen moeten worden beschreven per API-aanroep)

!!! tip "Lokale Visiemodellen"
    U kunt een lokaal visiemodel (met mmproj) gebruiken als transcriptiemodel. Hierdoor blijft de beeldverwerking op het apparaat.

## Hoe het werkt

1. Je voegt een afbeelding toe aan je bericht
2. Agora detecteert dat uw huidige model het gezichtsvermogen niet ondersteunt
3. De afbeelding wordt eerst naar het transcriptiemodel gestuurd
4. Het transcriptiemodel genereert een tekstbeschrijving
5. Deze beschrijving wordt toegevoegd aan uw berichttekst
6. De gecombineerde tekst wordt naar uw hoofdmodel verzonden

---

## Batchgrootte

Bepaalt hoeveel afbeeldingen per API-aanroep naar het transcriptiemodel worden beschreven.

- **1** — Beschrijf één afbeelding tegelijk (meer API-aanroepen, nauwkeuriger)
- **5–10** — Beschrijf meerdere afbeeldingen per aanroep (minder API-aanroepen, er kunnen details verloren gaan)

Standaard is apparaatafhankelijk. Lagere waarden geven betere resultaten, maar kosten meer.

---

## Modelselectie

### Transcriptiemodel

Dit is het visiemodel dat beeldbeschrijvingen genereert. Kies het meest capabele vision-model dat voor u beschikbaar is.

### Ingeschakelde modellen

Dit zijn de modellen met alleen tekst die gebruik maken van beeldtranscriptie. Alleen modellen in deze lijst ontvangen getranscribeerde afbeeldingsbeschrijvingen. Andere modellen ontvangen afbeeldingen direct (als ze deze ondersteunen) of helemaal niet.