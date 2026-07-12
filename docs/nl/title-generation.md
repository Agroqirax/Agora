# Titelgeneratie

Genereer automatisch gesprekstitels op basis van de eerste uitwisseling.

## Wat het doet

Wanneer u een nieuw gesprek start, kan Agora automatisch een korte, betekenisvolle titel genereren op basis van uw eerste bericht en de reactie van het model. Dit vervangt de algemene titel 'Nieuwe chat'.

## Installatie

1. Ga naar **Instellingen → Titel genereren**
2. Schakel **Titels automatisch genereren** in
3. Kies optioneel een **Model** voor het genereren van titels (gebruikt standaard het huidige gespreksmodel)

!!! tip "Modelkeuze"
    Voor het genereren van titels worden zeer weinig tokens gebruikt. U kunt een goedkoop, snel model gebruiken (zoals GPT-4o Mini of een lokaal model) zonder dat dit de kwaliteit van uw gesprek beïnvloedt.

## Hoe het werkt

1. Je verzendt je eerste bericht in een nieuw gesprek
2. Het model reageert (zoals gewoonlijk)
3. Nadat het antwoord is voltooid, verzendt Agora een apart, klein verzoek om een titel te genereren
4. De gegenereerde titel wordt opgeslagen en weergegeven in de conversatielijst

Het genereren van titels wordt slechts één keer per gesprek uitgevoerd, bij de eerste uitwisseling.

## Titelgeneratiemodel

Specifiek voor het genereren van titels kunt u een ander model gebruiken:

- **Standaard** (geen selectie) — Gebruikt hetzelfde model als het gesprek
- **Specifiek model** — Gebruikt altijd dat model voor het genereren van titels, ongeacht welk model voor het gesprek wordt gebruikt

Het gebruik van een speciaal snel model voor titels kan de latentie en de kosten verlagen.