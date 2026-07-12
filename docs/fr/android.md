# Outils Android

Agora peut accéder en toute sécurité à certaines fonctionnalités du système Android lorsque le modèle en a besoin. Ces outils permettent au modèle de récupérer votre position actuelle, de lire vos contacts ou d'interagir avec votre calendrier, tout en respectant le système d'autorisations d'Android.

## Outils disponibles

| Outil | Fonction |

| ------------ | ----------------------------------------------------- |

| **Position** | Récupérer la position approximative ou précise de l'appareil |

| **Contacts** | Rechercher et lire les contacts enregistrés sur l'appareil |

| **Calendrier** | Lire les événements à venir et créer de nouvelles entrées dans le calendrier |

Le modèle détecte automatiquement les outils activés et détermine leur utilité au cours d'une conversation.

## Confidentialité et autorisations

Les outils Android nécessitent les autorisations d'exécution Android standard.

Lors de la première utilisation de l'un de ces outils, Agora demandera l'autorisation Android appropriée. Les autorisations ne sont demandées que lors de la première utilisation d'un outil.

!!! Remarque

Vous pouvez révoquer les autorisations à tout moment depuis les paramètres Android de votre appareil.

## Configuration

1. Accédez à **Paramètres → Android**

2. Activez les outils que vous souhaitez que le modèle utilise :

- **Localisation**

- **Contacts**

- **Calendrier**

3. Accordez les autorisations Android demandées lorsque vous y êtes invité.

Une fois activés, ces outils permettront au modèle d'y accéder automatiquement lorsqu'ils seront utiles au cours d'une conversation.

## Localisation

L'outil Localisation permet au modèle de déterminer la position actuelle de votre appareil.

Utilisations typiques :

- Trouver des lieux à proximité
- Fournir des informations météorologiques locales

- Recommandations géolocalisées
- Estimer les temps de trajet

- Répondre aux questions sur votre zone géographique

Selon les paramètres de votre appareil et les autorisations accordées, la localisation peut être approximative ou précise.

## Contacts

L'outil Contacts permet au modèle de rechercher des contacts enregistrés sur votre appareil.

Utilisations typiques :

- Recherche de numéros de téléphone
- Recherche d'adresses e-mail
- Identification des contacts enregistrés
- Sélection de contacts pour la messagerie ou toute autre communication

Le modèle accède uniquement aux informations de contact nécessaires pour répondre à votre demande.

## Calendrier

L'outil Calendrier permet au modèle de consulter votre calendrier et de créer des événements.

Utilisations typiques :

- Consultation de votre agenda
- Affichage des événements à venir

- Recherche de disponibilités
- Création de rendez-vous
- Consultation des détails des réunions

La création ou la modification d'événements nécessite l'autorisation d'écriture dans le calendrier.

## Sécurité

Les outils Android utilisent le système d'autorisations intégré d'Android.

- Demande d'autorisation lors de l'exécution avant la première utilisation
- Les autorisations peuvent être révoquées à tout moment
- Les outils désactivés sont inaccessibles
- Tous les accès sont effectués localement via le système d'autorisations d'Android

Agora ne peut accéder à vos données protégées sans votre autorisation.

## Dépannage

### Autorisation refusée

Si votre appareil indique ne pas pouvoir accéder à un outil :

- Vérifiez que l’outil est activé dans **Paramètres → Android**

- Assurez-vous que l’autorisation Android requise a été accordée

- Si nécessaire, révoquez puis accordez à nouveau l’autorisation dans les paramètres Android

### Localisation indisponible

- Assurez-vous que les services de localisation sont activés sur votre appareil

- Déplacez-vous dans une zone où la couverture GPS ou réseau est meilleure

- Autorisez la localisation précise si une plus grande précision est requise

### Calendrier ou contacts vides

Vérifiez que votre appareil contient des événements dans son calendrier ou des contacts et que l’autorisation Android correspondante a été accordée.
