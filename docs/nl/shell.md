# Externe schaal (schelp)

Agora kan opdrachten uitvoeren op externe machines via het [Conch](https://github.com/newo-ether/conch) protocol – een end-to-end gecodeerde veilige shell ontworpen voor AI-agenten.

## Hoe het werkt

```tekst
Agora (Android) ──ECDH + AES-256-GCM──▶ Conch-server (Linux/macOS/Windows)
                                           │
                                           ├── Voer opdrachten uit
                                           ├── Bestanden lezen/schrijven/bewerken
                                           ├── Glob- en grep-zoeken
                                           └── Resultaten retourneren
```

Het model beslist wanneer de shell wordt gebruikt: het kan de serverstatus controleren, bestanden beheren, scripts uitvoeren of problemen autonoom oplossen.

## Beveiliging

Conch maakt gebruik van sterke encryptie en anti-misbruikbescherming:

- **ECDH-sleuteluitwisseling** — kortstondige sleutels per sessie
- **AES-256-GCM-codering** — al het verkeer gecodeerd
- **HMAC-SHA256-ondertekening** — berichtintegriteit geverifieerd
- **Beperking van de tokenbucketsnelheid** — voorkomt misbruik
- **Nonce-based anti-replay** — elk verzoek is uniek

!!! opmerking
    Commando's worden uitgevoerd met de machtigingen van de gebruiker die de Conch-server draait. Gebruik een beperkt gebruikersaccount voor gevoelige omgevingen.

---

## Installatie

### Stap 1: Conch Server implementeren

Implementeer de Conch-server op uw doelmachine. Zie de [Conch-repository](https://github.com/newo-ether/conch) voor installatie-instructies.

### Stap 2: Apparaat toevoegen aan Agora

1. Ga naar **Instellingen → Shell**
2. Schakel **Shell-tool** in
3. Tik op **Apparaat toevoegen**
4. Kies het apparaattype: **Conch** of **SSH**
5. Vul de apparaatgegevens in:

=== "Conch"

    | Veld | Beschrijving | Voorbeeld |
    |-------|------------|---------|
    | **Naam** | Weergavenaam voor dit apparaat | `Server bouwen` |
    | **Beschrijving** | Optionele opmerking over deze machine | `Office Ubuntu-box` |
    | **Server-URL** | Conch-servereindpunt (host:poort) | `http://192.168.1.100:14216` |
    | **API-sleutel** | Authenticatietoken | Van Conch-serverconfiguratie |
    | **Time-out** | Time-out opdracht in seconden | `30` |

=== "SSH"

    | Veld | Beschrijving | Voorbeeld |
    |-------|------------|---------|
    | **Naam** | Weergavenaam voor dit apparaat | `VPS-server` |
    | **Beschrijving** | Optionele opmerking over deze machine | `Productiewebserver` |
    | **Gastheer** | SSH-hostnaam of IP-adres | `192.168.1.200` |
    | **Poort** | SSH-poort | `22` |
    | **Gebruiker** | SSH-gebruikersnaam | `wortel` |
    | **Wachtwoord** | SSH-wachtwoord | Uw SSH-wachtwoord |

Tik op **Toevoegen** om op te slaan.

### Stap 3: Gebruik

Eenmaal geconfigureerd, heeft het model toegang tot het apparaat. Er is geen handmatige trigger: het model ontdekt automatisch beschikbare shell-apparaten en roept deze op wanneer dat nodig is.

---

## Ondersteuning voor meerdere apparaten

Voeg meerdere shell-apparaten toe om het model op verschillende machines te laten werken:

- **Bouw server** — compileer en test code
- **Thuislab** — beheer zelfgehoste services
- **Ontwikkelings-VM** — bewerk code en voer scripts uit

Elk apparaat is onafhankelijk geconfigureerd met zijn eigen naam, URL en inloggegevens. Het model kan ze van elkaar onderscheiden en voor elke taak het juiste apparaat kiezen.

---

## Beschikbare bewerkingen

### Opdrachtuitvoering (`shell_execute`)

Voer een willekeurig shell-commando uit en ontvang stdout-, stderr- en exitcode.

### Bestandsbewerkingen

| Gereedschap | Functie |
|------|----------|
| `bestand_lezen` | Een bestand lezen van het externe bestandssysteem |
| `bestand_schrijven` | Een bestand schrijven of overschrijven |
| `bestandsbewerking` | Voer exacte stringvervangingen uit in een bestand |
| `bestand_glob` | Zoek bestanden die overeenkomen met een glob-patroon |
| `bestand_grep` | Zoek bestandsinhoud met regex |

Alle bestandsbewerkingen verlopen via het gecodeerde Conch-kanaal.

---

## MCP-integratie

Conch kan ook dienen als **Claude Desktop MCP-server**. Als u Claude Code of een andere MCP-client gebruikt, kunt u Conch configureren als toolprovider voor externe toegang tot bestanden en shells vanaf uw bureaublad.

Zie de [Conch-documentatie](https://github.com/newo-ether/conch) voor MCP-installatie-instructies.

---

## Problemen oplossen

### Apparaat wordt weergegeven als niet beschikbaar
- Controleer of de Conch-server actief is
- Controleer of de URL bereikbaar is vanaf uw Android-apparaat
- Controleer de firewallregels op de server

### Time-out voor opdrachten
- Verhoog de time-out in apparaatinstellingen
- Controleer of de opdracht niet blijft hangen (gebruikersinvoer vereist, enz.)

### Authenticatie mislukt
- Controleer of de API-sleutel overeenkomt met de serverconfiguratie
- Genereer sleutels indien nodig