# Zandbak

Agora kan lokaal op uw apparaat een lichtgewicht Alpine Linux-omgeving draaien - geen internetverbinding vereist. Met de sandbox kan het model pakketten installeren en opdrachten uitvoeren in een geïsoleerd rootbestandssysteem.

!!! opmerking "Beschikbaarheid"
    De sandbox is beschikbaar in alle builds. U kunt het openen via **Instellingen → Shell → Sandboxbeheer** of rechtstreeks via **Instellingen → Sandbox**.

## Hoe het werkt

De sandbox maakt gebruik van een Alpine Linux-rootbestandssysteem dat is geïmplementeerd in de app-privéopslag van uw apparaat. Met een minimale op `apk` gebaseerde pakketbeheerder kunt u software in deze omgeving installeren, en opdrachten worden uitgevoerd in een op root gebaseerde container. De sandbox kan ook worden geïntegreerd met het **Storage Access Framework (SAF)** van Android via een DocumentsProvider, waardoor andere apps toegang krijgen tot sandbox-bestanden.

Dit is **niet** een volledige virtuele machine; het is een lichtgewicht container voor gebruikersruimte die de hostkernel deelt. Het biedt voldoende isolatie voor veilig experimenteren, terwijl het gebruik van bronnen laag blijft.

---

## VPN-interferentie — kritiek

!!! gevaar "Schakel uw VPN uit voordat u Sandbox Networking gebruikt"

    VPN-applicaties verstoren de DNS-resolutie van pro. Dit is waarom:

    **Oorzaak: PRot heeft geen isolatie van de netwerknaamruimte.**

    PRoot gebruikt `ptrace` om syscalls te onderscheppen en bestandspaden om te leiden, maar het ondersteunt **geen** `CLONE_NEWNET` (Linux-netwerknaamruimten). Alle processen in de sandbox delen rechtstreeks de netwerkstack van het Android-hostsysteem. Er is geen virtuele netwerkinterface, geen geïsoleerde routeringstabel en geen onafhankelijke DNS-configuratie.

    **Hoe een VPN op Android DNS binnen proot verbreekt:**

    1. Android VPN-apps gebruiken de `VpnService` API, die een **TUN-interface** creëert: een virtueel netwerkapparaat dat **al** apparaatverkeer onderschept, inclusief verkeer van binnenuit proot
    2. Om DNS-lekken buiten de gecodeerde tunnel te voorkomen, stuurt de VPN **al het verkeer op poort 53 (DNS)** om naar zijn eigen DNS-servers
    3. Binnen proot, wanneer een applicatie `getaddrinfo()` (de standaard libc DNS-resolver) aanroept, gaat het verzoek via de systeemresolver van Android - die de VPN al heeft onderschept
    4. Op Android 12+ heeft Google de DNS-resolver herwerkt, waardoor `getaddrinfo()` binnen proot-omgevingen bijzonder kwetsbaar is ([termux/proot#215](https://github.com/termux/proot/issues/215))
    5. De TUN-routering van de VPN en het DNS-pad van de systeemresolver conflicteren binnen proot: de oplosser verzendt een DNS-query, de VPN TUN onderschept deze, maar het antwoord reikt nooit terug via de `ptrace`-laag van proot

    **Waargenomen symptomen:**

    | Operatie | Resultaat |
    |-----------|--------|
    | `ping 1.1.1.1` | ✅ Werkt (direct IP, geen DNS nodig) |
    | `ping google.com` | ❌ Mislukt — "Tijdelijke fout in naamomzetting" |
    | `apk voeg python3 toe` | ❌ Mislukt - kan `dl-cdn.alpinelinux.org` | niet oplossen
    | `krul https://example.com` | ❌ Mislukt - fout in naamresolutie |
    | `krul https://1.1.1.1` | ✅ Werkt (IP directe verbinding) |

    **Opgelost:** Schakel uw VPN volledig uit voordat u een netwerkbewerking in de sandbox uitvoert (pakketten installeren, `curl`, `wget`, enz.). U kunt de VPN opnieuw inschakelen nadat de netwerkbewerkingen zijn voltooid.

    Dit is een fundamentele beperking van de architectuur van proot: het kan de netwerkstack niet virtualiseren wanneer een Android VPN de DNS-routering van het systeem via een TUN-interface overschrijft.

---

## Installatie

### Installeer het rootbestandssysteem

De eerste keer dat u de sandbox opent, ziet u een dashboard dat aangeeft dat rootfs niet is geïnstalleerd. Tik op **Installeren** om het Alpine rootbestandssysteem te downloaden en uit te pakken.

!!! info "Opslaggebruik"
    De basis-rootfs gebruikt ongeveer 100-200 MB. Geïnstalleerde pakketten nemen extra ruimte in beslag. Het totale schijfgebruik wordt weergegeven op het dashboard.

---

## Pakketbeheer

### Installeer een pakket

1. Typ de pakketnaam in het tekstveld (bijvoorbeeld `python3`)
2. Tik op **Installeren**
3. Bekijk de terminaluitvoer voor de voortgang van de installatie

U kunt ook op een **snelinstallatiechip** tikken voor veelgebruikte pakketten:

```
python3 git curlwget
openssh nodejs build-base htop
```

### Geïnstalleerde pakketten

Onder de installatiesectie worden alle geïnstalleerde pakketten vermeld met hun:

- **Naam** — de naam van het Alpine-pakket
- **Versie** — de geïnstalleerde versie
- **Beschrijving** — een korte samenvatting (ingekort)

### Een pakket verwijderen

Tik op het :material-close:-pictogram op een geïnstalleerd pakket om het te verwijderen. Er verschijnt een bevestigingsvenster voordat u het verwijdert.

---

## Dashboard

Wanneer de sandbox klaar is, toont het dashboard:

- **Schijfgebruik** — een voortgangsbalk en numerieke weergave (MB of GB)
- **Aantal geïnstalleerde pakketten** — totaal aantal pakketten

---

## Terminaluitgang

Bij het installeren of verwijderen van pakketten verschijnt de terminaluitvoer in een schuifbare, monospace-weergave met een donker thema onder het invoerveld. De uitvoer scrollt automatisch om de laatste regels te volgen.

Gebruik dit om:
- Bewaak de voortgang van de installatie
- Debug mislukte pakketbewerkingen
- Bekijk welke bestanden een pakket installeert

---

## Sandbox opnieuw instellen

De **Gevarenzone** onderaan bevat de optie **Sandbox opnieuw instellen**. Hierdoor worden het rootbestandssysteem en alle geïnstalleerde pakketten volledig verwijderd.

!!! gevaar "destructieve actie"
    Als u de sandbox opnieuw instelt, wordt de gehele Alpine-omgeving verwijderd. U moet daarna de rootfs en alle pakketten opnieuw installeren. Een bevestigingsvenster voorkomt onbedoelde resets.