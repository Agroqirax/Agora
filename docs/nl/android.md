# Android-tools

Agora heeft veilige toegang tot bepaalde Android-systeemfuncties wanneer het model deze nodig heeft. Met deze tools kan het model uw huidige locatie ophalen, contacten lezen of met uw agenda communiceren, met respect voor het Android-toestemmingssysteem.

## Beschikbare tools

| Tool | Doel |

| ------------ | ----------------------------------------------------- |

| **Locatie** | De geschatte of precieze locatie van het apparaat ophalen |

| **Contacten** | Contacten zoeken en lezen die op het apparaat zijn opgeslagen |

| **Agenda** | Aankomende evenementen lezen en nieuwe agendapunten aanmaken |

Het model detecteert automatisch ingeschakelde tools en bepaalt wanneer deze nuttig zijn tijdens een gesprek.

## Privacy en toestemmingen

Android-tools vereisen standaard Android-runtime-toestemmingen.

De eerste keer dat het model een van deze tools probeert te gebruiken, vraagt ​​Agora de juiste Android-toestemming aan. Toestemmingen worden alleen aangevraagd wanneer een tool voor het eerst nodig is.

!!! Opmerking

U kunt de machtigingen op elk moment intrekken via de Android-instellingen van uw apparaat.

## Instellen

1. Ga naar **Instellingen → Android**
2. Schakel de tools in die u wilt dat het model gebruikt:

- **Locatie**

- **Contacten**

- **Agenda**

3. Geef de gevraagde Android-machtigingen wanneer daarom wordt gevraagd.

Zodra deze zijn ingeschakeld, heeft het model automatisch toegang tot deze tools wanneer ze nuttig zijn tijdens een gesprek.

## Locatie

Met de tool Locatie kan het model de huidige locatie van uw apparaat bepalen.

Typische toepassingen zijn:

- Plaatsen in de buurt vinden
- Lokale weersinformatie verstrekken
- Locatiegebonden aanbevelingen
- Reistijden schatten
- Vragen beantwoorden over uw huidige omgeving

Afhankelijk van uw apparaatinstellingen en verleende machtigingen kan de locatie bij benadering of precies zijn.

## Contacten

Met de tool Contacten kan het model zoeken naar contacten die op uw apparaat zijn opgeslagen.

Typische toepassingen zijn onder andere:

- Telefoonnummers opzoeken
- E-mailadressen vinden
- Opgeslagen contacten identificeren
- Contacten selecteren voor berichten of communicatietaken

Het model heeft alleen toegang tot de contactgegevens die nodig zijn om uw verzoek te verwerken.

## Agenda

Met de Agenda-tool kan het model uw agenda lezen en afspraken aanmaken.

Typische toepassingen zijn onder andere:

- Uw agenda bekijken
- Aankomende afspraken bekijken
- Beschikbare tijd vinden
- Afspraken maken
- Vergaderdetails bekijken

Voor het aanmaken of wijzigen van afspraken is schrijftoegang tot de agenda vereist.

## Beveiliging

Android-tools maken gebruik van het ingebouwde machtigingssysteem van Android.

- Er wordt tijdens de uitvoering om machtigingen gevraagd vóór het eerste gebruik
- Machtigingen kunnen op elk moment worden ingetrokken
- Uitgeschakelde tools zijn niet toegankelijk
- Alle toegang vindt lokaal plaats via het machtigingsframework van Android

Agora heeft geen toegang tot beveiligde gegevens zonder uw toestemming.

## Probleemoplossing

### Toegang geweigerd

Als het model meldt dat het geen toegang heeft tot een tool:

- Controleer of de tool is ingeschakeld in **Instellingen → Android**
- Controleer of de vereiste Android-toestemming is verleend
- Trek de toestemming indien nodig in en verleen deze opnieuw in de Android-instellingen

### Locatie niet beschikbaar

- Zorg ervoor dat Locatieservices zijn ingeschakeld op uw apparaat
- Ga naar een gebied met betere GPS- of netwerkdekking
- Geef toestemming voor een nauwkeurige locatie als een hogere nauwkeurigheid vereist is

### Agenda of contacten zijn leeg

Controleer of uw apparaat agenda-items of contacten bevat en of de bijbehorende Android-toestemming is verleend.
