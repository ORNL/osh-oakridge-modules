# Processus d'occupation RS350

Le **Processus d'occupation RS350** surveille une voie équipée d'un détecteur spectroscopique **RS-350**, regroupe chaque fenêtre d'occupation ou d'alarme spectroscopique dans un événement d'**Occupation** OSCAR (incluant les spectres de premier plan/fond et l'analyse isotopique) et coordonne la capture des vidéos/images conservées sur la voie parente.

## Termes expliqués simplement

| Terme | Signification |
| --- | --- |
| **Détecteur spectroscopique RS-350** | Détecteur gamma/neutrons produisant des spectres d'énergie complets (format `N42`) en plus des taux de comptage bruts, permettant l'identification automatique des isotopes (`WebID`). |
| **Occupation (`Occupancy`)** | Un passage ou une fenêtre de mesure délimitée par le début et la fin d'occupation sur la voie. |

## Référence de configuration

- **Numéro de série (`Serial Number`)** : Identifiant unique pour cette instance de processus d'occupation RS-350.
- **Système parent (`Parent System (Containing RPM)`)** : UID du système de voie parent contenant le pilote RS-350 et les caméras. Laisser vide pour associer automatiquement la voie parente.
