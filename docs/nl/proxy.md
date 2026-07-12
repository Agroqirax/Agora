# Netwerkproxy

Leid al het netwerkverkeer van Agora via een HTTP- of SOCKS-proxy. Dit is handig op beperkte netwerken, voor het routeren van verzoeken via een specifieke gateway, of wanneer een provider alleen bereikbaar is via een proxy.

De proxy is van toepassing op **al het** uitgaande verkeer: chatproviders, het ophalen van modellen, zoeken op internet, insluitingen, het ophalen van webpagina's en het indienen van crashrapporten.

## Installatie

Open **Instellingen → Netwerk → Proxy** en schakel **Proxy inschakelen** in en configureer vervolgens:

| Veld | Beschrijving |
|-------|------------|
| **Type** | 'HTTP', 'HTTPS' of 'SOCKS5'. HTTP/HTTPS-tunnel HTTPS-verkeer door de proxy via `CONNECT`; SOCKS5 naar voren op socketniveau. |
| **Gastheer** | Hostnaam of IP-adres van proxyserver (bijvoorbeeld `127.0.0.1`). |
| **Poort** | Proxyserverpoort (bijvoorbeeld `7890`). |
| **Gebruikersnaam / Wachtwoord** | Optioneel. Alleen nodig als uw proxy authenticatie vereist. |

Wijzigingen worden onmiddellijk van kracht; u hoeft de app niet opnieuw te starten.

## Lijst overslaan

Hosts en adresbereiken in de **Bypass-lijst** maken **direct** verbinding en negeren de proxy. Plaats één item per regel. De standaardlijst houdt loopback- en privéadressen (LAN) direct:

```
lokalehost
127.0.0.1
10.0.0.0/8
172.16.0.0/12
192.168.0.0/16
::1
```

Elke regel kan zijn:

- een exacte host — `localhost`, `192.168.1.10`
- een IPv4 CIDR-bereik — `10.0.0.0/8`
- een wildcard-achtervoegsel — `*.example.com`

Dit is de reden waarom een lokale Ollama-server (bijvoorbeeld `http://192.168.1.50:11434`) via uw LAN blijft werken terwijl al het andere via de proxy gaat.

## Opmerkingen

- **HTTPS** type gebruikt hetzelfde proxyprotocol als HTTP (een HTTP `CONNECT` proxy); kies het als uw proxy het label 'HTTPS' heeft.
- Het proxywachtwoord wordt alleen opgenomen in **gecodeerde gegevensexports als "API-sleutels opnemen" is ingeschakeld**.
- Als aanvragen mislukken en er time-outs optreden nadat u de proxy hebt ingeschakeld, controleer dan nogmaals de host/poort en of het proxytype overeenkomt met uw server.