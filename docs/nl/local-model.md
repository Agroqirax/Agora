# Lokale modellen

Voer LLM's rechtstreeks uit op uw Android-apparaat met behulp van GGUF-modelbestanden en llama.cpp. Geen netwerk vereist, geen API-sleutels, volledig privé.

## Hoe het werkt

Agora bundelt llama.cpp via Android NDK (CMake). Wanneer u een GGUF-bestand importeert, draait het model volledig op de CPU van uw apparaat; er verlaten geen gegevens het apparaat.

## Vereisten

- Alleen modellen in het **GGUF-formaat** (de standaard voor llama.cpp)
- **Apparaatgeheugen**: het model moet in het beschikbare RAM-geheugen passen. Als vuistregel:
    - 1–3B parametermodellen: 4–6 GB RAM
    - 7–8B parametermodellen: 6–8 GB RAM
- **Opslag**: GGUF-bestanden variëren van ~500 MB (gekwantiseerde kleine modellen) tot 5+ GB

!!! waarschuwing
    Lokale inferentie is CPU-intensief en veel langzamer dan cloud-API's. Het is het beste voor offline gebruik, privacygevoelige inhoud of experimenten, niet voor snelle chat met veel volume.

---

## Een chatmodel importeren

1. Download een GGUF-modelbestand naar uw apparaat (zie aanbevolen bronnen hieronder)
2. Ga naar **Instellingen → Provider**
3. Selecteer **Lokaal** als provider
4. Tik op **GGUF-model importeren**
5. Selecteer het `.gguf`-bestand op uw apparaat
6. Configureer het model:

| Parameter | Beschrijving | Voorbeeld |
|-----------|-------------|--------|
| **Model-ID** | Identificatie in kleine letters, geen spaties | `qwen3-8b` |
| **alias** | Weergavenaam | `Qwen 3 8B` |
| **Contextgrootte** | Maximaal contextvenster in tokens | `4096` |
| **Temperatuur** | Willekeurigheid (0,0–2,0) | `0,7` |
| **Top-P** | Drempel voor kernbemonstering (0,0–1,0) | `0,9` |
| **Max. aantal tokens** | Maximale generatielengte | `2048` |

7. Tik op **Toevoegen**

Het model is geïmporteerd en direct klaar voor gebruik.

---

## Een inbeddingsmodel importeren

Inbeddingsmodellen zijn kleiner en worden gebruikt voor semantisch zoeken:

1. Ga naar **Instellingen → Conversatie zoeken**
2. Tik op **Lokaal model toevoegen**
3. Selecteer een `.gguf`-inbeddingsmodelbestand
4. Geef het een naam
5. Tik op **Toevoegen**

Zie [Embedding / RAG](embedding.md) voor het instellen van de zoekfunctie.

---

## Het actieve model selecteren

Na het importeren van een of meerdere modellen:

1. Ga naar **Instellingen → Provider → Lokaal**
2. Alle geïmporteerde modellen worden weergegeven
3. Tik op het **keuzerondje** naast het model dat u wilt gebruiken
4. Het geselecteerde model wordt actief wanneer **Lokaal** wordt gekozen als chatprovider

---

## Lokale modellen beheren

### Hernoemen

Tik op een model om de alias ervan te wijzigen of parameters aan te passen (temperatuur, contextgrootte, enz.).

### Verwijderen

Houd een model ingedrukt en tik op **Verwijderen**. Hierdoor wordt het model uit Agora verwijderd en wordt het GGUF-bestand uit de opslag verwijderd.

---

## Aanbevolen modellen

### Chatmodellen

| Model | Maat | RAM nodig | Opmerkingen |
|-------|------|-----------|-------|
| Qwen 3 1.7B | ~1 GB | 3–4 GB | Goede kwaliteit voor zijn formaat |
| Lama 3.2 3B | ~2 GB | 4–5 GB | Solide rondom |
| Qwen 3 8B | ~5 GB | 7–8 GB | Beste kwaliteit, hoog RAM-geheugen |

### Modellen inbedden

| Model | Maat | Opmerkingen |
|-------|------|-------|
| BGE Klein NL v1.5 | ~130MB | Goede Engelse inbedding |
| BGE Kleine ZH v1.5 | ~130MB | Chinees geoptimaliseerd |
| Nomic Embed-tekst v1.5 | ~270MB | Goede meertalige |

### Waar GGUF-bestanden te verkrijgen zijn

- [Knuffelend gezicht](https://huggingface.co/models?library=gguf) — zoek naar "GGUF"
- [bartowski's gekwantiseerde modellen](https://huggingface.co/bartowski) — brede selectie, goed georganiseerd

!!! fooi
    Zoek naar 'Q4_K_M'-kwantisering; deze biedt de beste balans tussen kwaliteit en grootte voor chatmodellen.

---

## Prestatietips

- **Kleinere context = sneller**: begin met 2048 en verhoog deze alleen indien nodig
- **Lagere quant = sneller**: Q4_K_M is sneller dan Q6_K of Q8
- **Andere apps sluiten**: Lokale inferentie heeft zoveel mogelijk RAM nodig
- **Aansluiten**: Inference is CPU-intensief en bij langdurig gebruik raakt de batterij leeg