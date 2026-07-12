# Generatieparameters

Bepaal hoe modellen reacties genereren – van contextlengte tot creativiteitsinstellingen.

## Contextvenster

**Max. contextberichten** stelt in hoeveel recente berichten als context naar het model worden verzonden. Standaard: **20**.

- **5–20** — Kortere context, snellere reacties, minder tokengebruik
- **20–50** — Langere context voor complexe gesprekken met meerdere beurten
- **50–100** — Maximale context voor zeer lange discussies (mogelijk tegen tokenlimieten)

Dit geldt voor alle modellen. Het daadwerkelijke contextvenster in tokens is afhankelijk van uw model en berichtlengte.

---

## Temperatuur

Controleert de willekeur in de modeluitvoer. Bereik: **0,0 – 2,0**.

- **0,0 – 0,3** — Meer deterministisch, consistent, feitelijk
- **0,5 – 0,8** — Evenwichtige creativiteit (aanbevolen standaard)
- **1,0 – 2,0** — Willekeuriger, creatiever en onvoorspelbaarder

Een hogere temperatuur betekent dat het model waarschijnlijk minder waarschijnlijke woorden kiest. Een lagere temperatuur produceert meer gerichte, repetitieve resultaten.

!!! tip "Wanneer aanpassen"
    - **Code / Feiten**: Gebruik lage temperatuur (0,0 – 0,3)
    - **Creatief schrijven**: gebruik hoge temperaturen (0,8 – 1,2)
    - **Algemene chat**: gebruik gemiddelde temperatuur (0,5 – 0,7)

---

## Top P (nucleusbemonstering)

Beheert de diversiteit van de tokenselectie. Bereik: **0,0 – 1,0**.

Het model houdt alleen rekening met de kleinste set tokens waarvan de cumulatieve waarschijnlijkheid groter is dan `top_p`.

- **0,1** — Zeer gefocust, alleen de meest waarschijnlijke tokens
- **0,5** — Matige diversiteit
- **0,9 – 1,0** — Volledige diversiteit (aanbevolen standaard)

Meestal pas je *ofwel* temperatuur *of* top P aan – niet allebei.

---

## Standaard maximale tokens

Stelt een maximale tokenlimiet in voor modelreacties. Als het model is ingesteld, genereert het niet meer dan dit aantal tokens in één reactie. Indien **niet ingesteld** (standaard), geldt het eigen maximum van het model.

Beschikbare voorinstellingen:

```
256 512 1024 2048
4096 8192 16384 32768
```

!!! tip "Niet instellen voor flexibiliteit"
    Laat dit in de meeste gevallen niet ingesteld. Stel alleen een limiet in als u een consistente reactieduur nodig heeft (bijvoorbeeld korte samenvattingen) of als u de kosten wilt beperken.

---

## Frequentieboete

Vermindert de neiging van het model om dezelfde woorden te herhalen. Bereik: **-2,0 – 2,0**.

- **Positieve waarden** (0,1 – 1,0) — Ontmoedig herhaling
- **Nul** (0,0) — Geen boete (standaard)
- **Negatieve waarden** (-1,0 – -0,1) — Moedig herhaling aan

---

## Aanwezigheidsstraf

Moedigt het model aan om over nieuwe onderwerpen te praten. Bereik: **-2,0 – 2,0**.

- **Positieve waarden** (0,1 – 1,0) — Stimuleer onderwerpdiversiteit
- **Nul** (0,0) — Geen boete (standaard)
- **Negatieve waarden** — Blijf bij het huidige onderwerp

---

## Denken / Redeneren

Maakt keten-van-gedachte-redenering mogelijk voor ondersteunde modellen (bijv. DeepSeek R1, Qwen3, Claude).

Indien ingeschakeld, genereert het model interne redenering voordat het definitieve antwoord wordt geproduceerd. Dit verbetert de nauwkeurigheid voor complexe taken, maar duurt langer en gebruikt meer tokens.

### Denkniveau

- **Laag** — Minimale redenering, sneller
- **Gemiddeld** — Gebalanceerd (standaard)
- **Hoog** — Maximale redenering voor complexe problemen

!!! waarschuwing "Niet alle modellen ondersteunen denken"
    De denkmodus vereist een model dat redeneringstokens ondersteunt. Als uw model dit niet ondersteunt, heeft deze instelling geen effect.

---

## Visualiseer contextuitrol

Indien ingeschakeld geeft Agora visueel aan welke berichten zijn opgenomen in het huidige contextvenster en welke zijn uitgerold (uitgesloten vanwege de limiet van het contextvenster). Dit helpt je te begrijpen:

- Hoeveel van uw gesprek het model kan "zien"
- Wanneer oudere berichten uit hun context vallen
- Of u het contextvenster moet vergroten

De visualisatie verschijnt als een subtiele markering in de gespreksweergave.

---

## Hoe parameters werken

Alle generatieparameters zijn **nullable**: als ze niet expliciet zijn ingesteld, worden ze niet naar het model verzonden en gebruikt het model zijn eigen standaardwaarden. Elke parameter heeft een resetoptie om de waarde terug te zetten naar 'niet ingesteld'.

---

## Overschrijvingen per gesprek

U kunt de generatieparameters voor individuele gesprekken overschrijven met behulp van het dialoogvenster **Geavanceerde instellingen** in het chatscherm (druk lang op de knop Verzenden of gebruik het menu ⋮).