# Processus de données d'occupation

Le **Processus de données d'occupation** surveille le flux du portique de détection de rayonnement (RPM) d'une voie, regroupe chaque passage de véhicule ou de piéton dans un enregistrement structuré d'**Occupation** (avec heure de début, heure de fin, indicateurs d'alarme gamma/neutrons, comptages maximaux et images/clips vidéo associés) et déclenche la capture des médias conservés en cas d'alarme.

## Termes expliqués simplement

| Terme | Signification |
| --- | --- |
| **Occupation (`Occupancy`)** | Un passage unique d'un véhicule, d'un conteneur ou d'une personne dans une voie de détection entre le début (`GS`) et la fin (`GX`) d'occupation. |
| **UUID d'événement (`Event UUID`)** | Identifiant unique global attribué à chaque événement d'occupation pour la recherche, l'adjudication et le référencement dans les rapports. |
| **UID du système parent (`Parent System UID`)** | Identifiant unique (`urn:osh:system:lane:...`) du système de voie regroupant le portique RPM et les caméras. |

## Référence de configuration

- **Numéro de série (`Serial Number`)** : Identifiant unique pour cette instance de processus d'occupation (correspond généralement au nom de la voie).
- **Système parent (`Parent System (Containing RPM)`)** : UID du système de voie contenant le pilote RPM et les caméras. S'il est laissé vide, le processus utilise automatiquement le système de voie parent.
