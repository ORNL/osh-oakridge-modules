# Système de détecteur mobile

Déploie un détecteur de rayonnement mobile — un **sac à dos RS-350** ou un détecteur portable **Kromek D5** — sous forme de système suivi dans OSCAR. Utilisez ce type de module plutôt que les pilotes bruts RS-350/D5 ou un **Système de voie** fixe (qui affiche des options de caméras, WebRelay et OCR véhicules inadaptées à une unité mobile).

Le détecteur apparaît dans OSCAR Viewer avec suivi GPS en direct sur la carte, marqueurs d'alarme en temps réel, identification spectroscopique des isotopes (`WebID` pour RS-350) et adjudication des épisodes d'alarme dans le tableau des événements.

## Termes expliqués simplement

| Terme | Signification |
| --- | --- |
| **Sac à dos RS-350** | Détecteur spectroscopique portable gamma/neutrons diffusant des spectres `N42`, des alarmes et des positions GPS sur TCP. |
| **Kromek D5 portable** | Détecteur spectroscopique portatif interrogé à 1 Hz sur TCP pour l'état radiométrique, les spectres à 4 096 canaux et la position GPS. |
| **Connexion du détecteur (`Detector Connection`)** | Hôte et port TCP faisant foi pour le détecteur mobile, synchronisés automatiquement avec le pilote enfant. |

## Référence de configuration

- **Nom du module (`Module Name`)** : Nom affiché du détecteur mobile (12 caractères maximum).
- **Identifiant unique (`Unique ID`)** : Suffixe d'identifiant unique (par exemple `backpack-01`), associé à `urn:osh:system:lane:<uniqueID>`.
- **Clés de vue opérationnelle (`Operational View Keys`)** : Étiquettes facultatives en minuscules limitant les opérateurs ayant accès à cette unité mobile.
- **Connexion du détecteur (`Detector Connection`)** : Sélectionnez **Sac à dos RS-350** ou **Kromek D5**, puis renseignez l'**Hôte distant** et le **Port distant**.
