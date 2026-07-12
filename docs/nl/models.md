# Modellen

Beheer welke AI-modellen beschikbaar zijn en stel uw standaardmodel voor gesprekken in.

## Modellijst

Op de pagina **Modellen** worden alle modellen weergegeven die Agora kent, gerangschikt per aanbieder:

- **Standaardmodel** — Het model dat wordt gebruikt voor nieuwe gesprekken. Tik om te wijzigen.
- **Beschikbare modellen** — Vouw elke provider uit om de bijbehorende modellen te bekijken. Schakel degene in die u wilt gebruiken.

### Modellen in-/uitschakelen

Schakel het selectievakje naast een model in of uit om de beschikbaarheid ervan te wijzigen. Uitgeschakelde modellen verschijnen niet in de modelkiezer in gesprekken.

### Modellen hernoemen

Tik op het bewerkingspictogram (pen) naast een model om het een aangepaste alias te geven. Deze alias wordt overal in de app weergegeven in plaats van de technische model-ID.

### Modellen synchroniseren

Tik op **Modellen synchroniseren** om de nieuwste beschikbare modellen van alle geconfigureerde API-providers op te halen. Hiervoor zijn een internetverbinding en geldige API-sleutels vereist.

!!! tip "Lokale modellen"
    Lokale modellen verschijnen onder het gedeelte **Lokale** provider. Ze worden afzonderlijk beheerd in **Instellingen → Providers → Lokaal**.

---

## Standaardmodel

Voor alle nieuwe gesprekken wordt het **Standaardmodel** gebruikt. Om het te veranderen:

1. Tik op de standaardmodelrij bovenaan de pagina Modellen
2. Selecteer een model uit de lijst (alleen ingeschakelde modellen worden weergegeven)
3. De wijziging gaat onmiddellijk in

U kunt het model per gesprek overschrijven via de modelkiezer van het chatscherm.

---

## Modelaliassen

Met modelaliassen kunt u beschrijvende namen geven aan modellen met lange technische ID's. U kunt bijvoorbeeld de naam 'openai/gpt-4o-mini' hernoemen naar gewoon 'GPT-4o Mini'.

Aliassen worden overal weergegeven: de modelkiezer, gesprekskoppen en instellingenpagina's.

Om een ​​alias te verwijderen, wist u het tekstveld en slaat u het op.

---

## Problemen oplossen

### Modellen verschijnen niet

- Tik op **Modellen synchroniseren** om de lijst te vernieuwen
- Controleer of u een geldige API-sleutel voor de provider heeft in **Instellingen → Providers**
- Controleer uw internetverbinding
- Sommige providers zijn mogelijk tijdelijk niet beschikbaar

### Lokale modellen niet getoond

- Importeer een GGUF-modelbestand in **Instellingen → Providers → Lokaal**
- Het model moet een geldig GGUF-formaat hebben