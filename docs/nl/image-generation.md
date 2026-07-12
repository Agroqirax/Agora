# Beeldgeneratie

Genereer afbeeldingen op basis van tekstprompts met behulp van een tekst-naar-afbeelding-model, rechtstreeks in uw gesprekken.

## Wat het doet

Wanneer het genereren van afbeeldingen is ingeschakeld, kan Agora uw aanwijzingen omzetten in afbeeldingen met behulp van een speciaal tekst-naar-afbeelding-model (zoals DALL·E, GPT-Image, Imagen, FLUX, Stable Diffusion, Seedream, Qwen-Image en vele andere). De gegenereerde afbeelding wordt terug in het gesprek opgenomen, zodat u er net als elk ander antwoord op kunt herhalen.

Het genereren van afbeeldingen maakt gebruik van **eigen modelselectie**, onafhankelijk van het model waarmee u chat, zodat u met het ene model kunt chatten en met het andere model afbeeldingen kunt genereren.

## Installatie

1. Ga naar **Instellingen → Afbeelding genereren**
2. Schakel **Afbeelding genereren inschakelen** in
3. Tik op **Model** en kies een tekst-naar-afbeelding-model
4. Stel optioneel de **Standaardgrootte** (breedte x hoogte) in

!!! opmerking "BYOK - speciale inloggegevens"
    Het genereren van afbeeldingen maakt gebruik van een **eigen speciale API-sleutel en basis-URL**, onafhankelijk van uw chatproviders. Hierdoor kun je voor beeld een andere dienst gebruiken dan voor chat. Ga naar **Instellingen → Afbeelding genereren** om de sleutel, basis-URL, model en standaardgrootte te configureren.

## Modelselectie

Tik op **Model** om het model te kiezen dat voor het genereren wordt gebruikt.

- De kiezer toont modellen die lijken op tekst-naar-afbeelding-modellen, gefilterd uit al uw gesynchroniseerde modellen, zodat de lijst kort blijft.
- Als het gewenste model niet in de lijst staat (een ongebruikelijke naam), schakelt u **Alle modellen weergeven** in om uit de volledige lijst te kiezen.
- Alleen een correct gesynchroniseerde `Provider:model`-invoer telt als een geldige selectie. Synchroniseer uw modellen eerst onder **Instellingen → API-providers** / **Modellen beheren** als de lijst leeg is.

## Standaardgrootte

Stelt de standaard uitvoerafmetingen in, ingevoerd als **breedte × hoogte** in pixels (bijvoorbeeld `1024` × `1024`).

- De standaardwaarde is `1024 × 1024`.
- Ondersteunde formaten zijn afhankelijk van het model en de aanbieder. Als een model een formaat afwijst, probeer dan een waarde die het documenteert (veelgebruikte opties zijn `1024×1024`, `1024×1792`, `1792×1024`).

## Hoe het werkt

1. Schakel het genereren van afbeeldingen in en selecteer een afbeeldingsmodel
2. Vraag tijdens een gesprek aan de assistent om een afbeelding te maken
3. Agora stuurt het verzoek door naar het geconfigureerde beeldmodel met behulp van de inloggegevens van die provider
4. De gegenereerde afbeelding wordt teruggevoerd in het gesprek

!!! fooi
    Wees specifiek in uw opdracht: beschrijf het onderwerp, de stijl, de compositie en de belichting. Duidelijke aanwijzingen leveren veel betere resultaten op dan vage aanwijzingen.