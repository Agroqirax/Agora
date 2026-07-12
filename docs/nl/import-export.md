# Gegevensportabiliteit

Agora slaat al uw gegevens op het apparaat op en biedt volledige import-/exportmogelijkheden. U bent eigenaar van uw gegevens: verplaats ze naar binnen, verplaats ze naar buiten en maak er een back-up van.

## Exporteren

Exporteer uw gegevens naar een enkel `.agora`-bestand: een draagbaar archief dat alles bevat wat Agora opslaat.

### Wat wordt geëxporteerd

U kiest wat u wilt opnemen:

| Categorie | Inhoud |
|----------|----------|
| **Gesprekken en berichten** | Alle chatgeschiedenis, berichtenbomen, takken |
| **Herinneringen** | Actief geheugen en alle opgeslagen geheugenbestanden |
| **Systeemprompts** | Alle aangepaste systeempromptsjablonen |
| **Instellingen** | App-configuratie en voorkeuren |
| **API-sleutels** | Alle geconfigureerde API-sleutels |

!!! gevaar "API Keys Warning"
    API-sleutels worden geëxporteerd in **platte tekst**. Iedereen met het `.agora`-bestand kan uw sleutels lezen. Schakel het exporteren van API-sleutels alleen in als u de bestemming vertrouwt en het bestand veilig verwerkt.

### Hoe te exporteren

1. Ga naar **Instellingen → Gegevensbeheer**
2. Tik op **Gegevens exporteren**
3. Selecteer welke categorieën u wilt opnemen
4. Tik op **Exporteren**
5. Kies waar u het `.agora`-bestand wilt opslaan

---

## Importeren

Herstel gegevens van een eerdere `.agora`-export.

### Importstrategieën

Bij het importeren kiest u hoe Agora omgaat met gegevens die al op uw apparaat staan:

| Strategie | Gedrag |
|----------|----------|
| **Samenvoegen** | Voeg nieuwe items toe, behoud bestaande. Als er een item met dezelfde ID bestaat, overschrijft de importversie dit. |
| **Vervangen** | Wis alle bestaande gegevens in de geselecteerde categorieën en importeer vervolgens. Een nieuw begin. |
| **Overslaan** | Importeer alleen items die geen conflict hebben. Bestaande items blijven onaangeroerd. |

!!! fooi
    Gebruik **Samenvoegen** in de meeste gevallen. Er worden veilig nieuwe gegevens toegevoegd, terwijl de gegevens die al op uw apparaat staan behouden blijven.

### Hoe te importeren

1. Ga naar **Instellingen → Gegevensbeheer**
2. Tik op **Gegevens importeren**
3. Selecteer een `.agora`-bestand
4. Bekijk het importvoorbeeld – kijk wat er in het bestand staat (exportdatum, versie, aantal inhoud)
5. Kies een importstrategie
6. Tik op **Importeren**

!!! gevaar "API Keys Warning"
    Als het exportbestand API-sleutels bevat, waarschuwt Agora u voordat het importeert. Sleutels worden geïmporteerd in platte tekst. Ga alleen verder als u de bron van het bestand vertrouwt.

---

## Importeren door derden

Importeer gesprekken van andere AI-chatplatforms.

Zowel Claude als ChatGPT exporteren uw gegevens als een **`.zip`-archief**. Agora importeert die `.zip` rechtstreeks — het is niet nodig om het eerst uit te pakken, en Agora accepteert **geen** losse `.json`-bestanden.

### Importeren van Claude

**1. Exporteren vanuit Claude.** Ga naar [Claude](https://claude.ai/) → **Instellingen → Gegevensbediening → Gegevens exporteren**. Claude bereidt het archief snel voor (meestal binnen **minder dan een uur**) en stuurt je een downloadlink per e-mail.

!!! waarschuwing "Download onmiddellijk"
    Claude's downloadlink **verloopt snel**. Pak de `.zip` zodra de e-mail binnenkomt. Als u te lang wacht, verdwijnt de link en moet u een nieuwe export aanvragen.

**2. Importeren in Agora.**

1. Ga naar **Instellingen → Gegevensbeheer → Derden → Importeren van Claude**
2. Selecteer het geëxporteerde `.zip`-bestand
3. Bekijk het voorbeeld: bekijk het aantal gesprekken en berichten
4. Kies de strategie **Samenvoegen** of **Vervangen**
5. Tik op **Importeren**

!!! opmerking
    Agora leest de gespreksgegevens rechtstreeks uit Claude's `.zip`-export. Bijlagen worden gedetecteerd en weergegeven in het voorbeeld, maar alleen de berichttekst wordt geïmporteerd; bijlagebestanden zelf niet.

### Importeren vanuit ChatGPT

**1. Exporteren vanuit ChatGPT.** Ga naar [ChatGPT](https://chatgpt.com/) → **Instellingen → Gegevensbeheer → Gegevens exporteren**. ChatGPT verwerkt het verzoek en stuurt u een downloadlink per e-mail zodra deze klaar is.

!!! info "Wees geduldig"
    Het duurt doorgaans **1-2 dagen** voordat de export van ChatGPT arriveert. Dit is normaal: wacht op de e-mail in plaats van opnieuw een aanvraag in te dienen.

**2. Importeren in Agora.**

1. Ga naar **Instellingen → Gegevensbeheer → Derden → Importeren uit ChatGPT**
2. Selecteer het gedownloade `.zip`-bestand
3. Bekijk het voorbeeld
4. Kies de strategie **Samenvoegen** of **Vervangen**
5. Tik op **Importeren**

!!! opmerking
    Zowel gebruikers- als assistentberichten worden geïmporteerd. Berichtrollen blijven behouden.

---

## Bestandsformaat

Het `.agora`-bestand is een JSON-gebaseerd archief. Als je technisch onderlegd bent, kun je het met standaard gereedschap inspecteren of verwerken. Het formaat is ontworpen voor voorwaartse en achterwaartse compatibiliteit.

---

## Automatische back-up

Agora kan automatisch volgens een schema een back-up van uw gegevens maken. U hoeft er niet aan te denken om te exporteren: Agora regelt het voor u.

### Hoe het werkt

- Automatische back-up wordt periodiek op de achtergrond uitgevoerd met behulp van Android WorkManager
- Wanneer een back-up nodig is, exporteert Agora uw geselecteerde categorieën naar de geconfigureerde map
- Er verschijnt alleen een melding als een back-up mislukt: succesvolle back-ups zijn stil
- Oude back-ups worden automatisch verwijderd op basis van uw bewaarinstellingen

### Configuratie

1. Ga naar **Instellingen → Gegevensbeheer → Automatische back-up**
2. Schakel **Automatische back-up** in/uit
3. Stel **Back-up elke** in: kies 1 dag, 3 dagen, 5 dagen, 1 week of 1 maand
4. Kies **Inhoud exporteren** — selecteer welke categorieën u wilt opnemen. API-sleutels **kunnen** worden opgenomen (er wordt een waarschuwing weergegeven als u dat vakje aanvinkt) - schakel dit alleen in als de back-uplocatie privé en veilig is. API-sleutels worden **niet** standaard meegeleverd.
5. **Back-uplocatie** instellen — tik om een map te kiezen (standaard ingesteld op `Download/Agora/Backup`)
6. Schakel **Oude back-ups automatisch verwijderen** in/uit en stel de periode **Verwijder ouder dan** in

!!! info "Beperking automatisch verwijderen"
    De verwijderperiode moet langer zijn dan de back-upperiode. Als u bijvoorbeeld elke week een back-up maakt, kunnen back-ups na één maand of één jaar automatisch worden verwijderd – nooit eerder. Dit voorkomt dat u uw enige back-up verwijdert voordat er een nieuwe wordt gemaakt.

!!! opmerking
    Automatische back-up maakt gebruik van WorkManager van Android om de betrouwbaarheid te garanderen, zelfs als de app wordt gesloten of het apparaat opnieuw wordt opgestart. Back-ups kunnen tijdens de Doze-modus enigszins worden vertraagd om de batterij te sparen.

---

## Beste praktijken

- **Exporteer regelmatig** als back-up: bewaar het bestand op een veilige plek
- **Schakel automatische back-up in** voor geplande hands-off bescherming
- **Neem geen API-sleutels op** bij routinematige exports: schakel sleutelexport alleen in voor volledige apparaatmigraties
- **Gebruik Samenvoegen voor incrementele import** — Vervangen is destructief
- **Voorbeeld vóór importeren** — controleer de exportdatum en het aantal inhoud om te bevestigen dat dit het juiste bestand is