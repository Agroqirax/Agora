# Serveurs MCP

Agora peut se connecter aux serveurs [Model Context Protocol](https://modelcontextprotocol.io) et laisser le modèle appeler les outils qu'ils exposent : moteurs de recherche, bases de données, domotique, API internes de l'entreprise ou tout autre élément pour lequel vous ou un tiers avez créé un serveur MCP.

!!! remarque
    Agora ne prend actuellement en charge que les **outils** MCP. Les ressources, les invites et l’échantillonnage ne sont pas encore implémentés.

## Comment ça marche

```texte
Agora (Android) ──HTTPS (Transport HTTP streaming)──▶ Serveur MCP
                                                          │
                                                          ├── initialiser
                                                          ├── outils/liste
                                                          └── outils/appel
```

Agora parle le transport MCP **Streamable HTTP** (un seul point de terminaison HTTP, pas stdio ou l'ancien transport HTTP+SSE). Lors de la première utilisation, il ouvre une session avec `initialize`, répertorie les outils du serveur avec `tools/list` et permet au modèle de les invoquer avec `tools/call`. La liste d'outils est mise en cache pendant environ 30 secondes par serveur afin que les messages répétés ne soient pas à nouveau établis à chaque fois ; si le serveur met fin à la session, Agora se reconnecte automatiquement au prochain appel.

Le modèle décide lui-même quand utiliser un outil MCP, de la même manière qu'il décide d'utiliser la recherche sur le Web ou le shell : il n'y a pas de déclencheur manuel.

## Sécurité

Les serveurs MCP que vous ajoutez sont du code arbitraire auquel vous choisissez de faire confiance pour accéder aux outils. Agora les traite donc avec prudence par défaut :

- **Les outils en lecture seule s'exécutent sans demander.** Si un serveur marque un outil avec `readOnlyHint`, Agora l'appelle automatiquement.
- **Tout le reste demande une confirmation.** Si un outil n'est pas marqué en lecture seule, Agora le traite comme potentiellement destructeur — y compris les outils qui ne déclarent tout simplement pas de « destructiveHint » du tout — et affiche une boîte de dialogue de confirmation avec le nom de l'outil et ses arguments avant de l'exécuter.
- **"Toujours autoriser ce serveur"** vous permet d'ignorer l'invite pour le reste de la session. Ceci se réinitialise au redémarrage d'Agora.
- **L'authentification est envoyée uniquement au serveur que vous avez configuré.** Un jeton Bearer ou un en-tête personnalisé que vous ajoutez est envoyé uniquement à l'URL de ce serveur.

!!! avertissement
    Si l'URL d'un serveur utilise « http:// » au lieu de « https:// », tout jeton Bearer ou en-tête que vous configurez voyage en clair. Préférez les points de terminaison « https:// », en particulier sur les réseaux non fiables.

## Configuration

### Étape 1 : Obtenez un serveur MCP

Il peut s'agir d'un serveur MCP public, d'un serveur géré en interne par votre organisation ou d'un serveur que vous hébergez vous-même. Il doit exposer le transport **Streamable HTTP** sur une seule URL (se terminant généralement par `/mcp`).

### Étape 2 : Ajoutez-le dans Agora

1. Accédez à **Paramètres → Serveurs MCP**
2. Activez **Activer les outils MCP**
3. Appuyez sur **Ajouter un serveur**
4. Remplissez les détails du serveur :

| Champ | Descriptif | Exemple |
| ----------------- | -------------------------------------------------------------------------------------------------------------------- | --------------------------------- |
| **Nom** | Nom d'affichage pour ce serveur | `Assistant à domicile` |
| **Description** | Remarque facultative sur son utilité. Si ce champ est laissé vide, l'hôte du serveur est affiché à la place.                 | `Contrôle les lumières et les thermostats` |
| **URL du serveur** | Le point de terminaison HTTP MCP Streamable | `https://exemple.com/mcp` |
| **Jeton du porteur** | Facultatif — envoyé sous la forme « Autorisation : porteur <jeton> » | Le jeton API de votre serveur |
| **En-têtes supplémentaires** | Facultatif — un par ligne, comme « Nom : valeur », pour les serveurs qui attendent une authentification ou un routage par un en-tête personnalisé | `X-Api-Key : secret` |
| **Délai d'attente** | Délai d'expiration par requête, 5 à 120 secondes | '30' |

5. Appuyez sur **Test de connexion** pour vérifier qu'Agora peut atteindre le serveur et voir combien d'outils il expose, avant d'enregistrer.
6. Appuyez sur **Enregistrer**.

Une fois qu'un serveur se connecte avec succès - soit à partir d'un test de connexion, soit à partir d'une utilisation réelle dans une discussion - son nom et sa version (tels que rapportés par le serveur) apparaissent sous la forme d'un petit badge à côté de son entrée dans la liste des serveurs.

### Étape 3 : Utiliser

Envoyez un message qui pourrait utiliser l'un des outils du serveur. Si l'outil n'est pas en lecture seule, vous serez invité à l'autoriser la première fois ; après cela, il est mémorisé pour le reste de la session (ou jusqu'à ce que vous le refusiez).

## Prise en charge multi-serveurs

Ajoutez autant de serveurs que vous le souhaitez : une API de recherche, un système de billetterie interne, un hub domotique. Chacun est configuré et authentifié indépendamment, et leurs outils sont automatiquement dotés d'un espace de noms (par exemple `mcp__home_assistant__turn_on_light`) afin que les outils portant le même nom provenant de différents serveurs n'entrent jamais en collision.

La désactivation de **Activer les outils MCP** ou de la case à cocher **Activé** d'un seul serveur supprime ses outils de ce que le modèle peut voir sans supprimer sa configuration.

## Confirmation des appels d'outils destructeurs

Vous pouvez désactiver entièrement les invites de confirmation avec **Confirmer les appels destructeurs de l'outil MCP** dans **Paramètres → Serveurs MCP** : les outils en lecture seule s'exécutent toujours sans demande, quel que soit ce paramètre. Le désactiver signifie que tous les outils de chaque serveur activé s'exécutent immédiatement sans invite, donc désactivez-le uniquement pour les serveurs en qui vous avez entièrement confiance.

## Dépannage

### Le test de connexion échoue

- Vérifiez à nouveau l'**URL du serveur** : il doit s'agir du point de terminaison complet (par exemple `.../mcp`), pas seulement de l'hôte.
- Si le serveur nécessite une authentification, vérifiez que le **Bearer Token** ou les **Extra Headers** sont corrects.
- Confirmez que le serveur implémente le transport HTTP Streamable, et non stdio ou l'ancien transport HTTP+SSE.
- Vérifiez que l'URL est accessible depuis votre appareil (pas seulement depuis le réseau de votre ordinateur)

### Le modèle n'appelle jamais l'outil

- Confirmez que **Activer les outils MCP** et que la case **Activé** du serveur spécifique sont toutes deux activées.
- Essayez **Test Connection** pour confirmer que le serveur répertorie actuellement cet outil
- Certains modèles sont plus réticents à appeler des outils inconnus sans raison claire dans la conversation : essayez d'être explicite sur ce que vous souhaitez faire.

### Les requêtes continuent d'expirer

- Augmentez le **Timeout** du serveur si ses outils sont lents (par exemple, recherches ou automatisations de longue durée)
- Un serveur lent ou surchargé est réessayé au maximum toutes les 30 secondes plutôt qu'à chaque message, afin qu'une panne temporaire ne bloque pas votre conversation à plusieurs reprises.

### L'invite de confirmation affiche des arguments inattendus

La boîte de dialogue de confirmation affiche les arguments exacts que le modèle est sur le point d'envoyer. S'ils semblent faux, refusez l'appel - le modèle verra généralement le refus et ajustera sa prochaine tentative.