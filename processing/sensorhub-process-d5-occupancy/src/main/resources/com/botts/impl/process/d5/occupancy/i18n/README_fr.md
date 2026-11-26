# Traitement d'occupation Kromek D5

Le **Traitement d'occupation Kromek D5** surveille un détecteur mobile ou système parent équipé d'un détecteur portable **Kromek D5**, regroupe les indicateurs d'alarme de dose, gamma et neutrons à 1 Hz en épisodes distincts et publie chaque épisode sous forme d'événement d'**Occupation** OSCAR (incluant les comptages maximaux, la catégorie d'alarme et la position GPS).

## Termes expliqués simplement

| Terme | Signification |
| --- | --- |
| **Détecteur portable Kromek D5** | Détecteur spectroscopique portable gamma/neutrons interrogé par TCP ou USB/série, transmettant les alarmes actives, des spectres à 4 096 canaux et les coordonnées GPS. |
| **Épisode d'alarme (`Occupancy`)** | Fenêtre d'alarme radiologique ouverte dès le front montant d'une alarme D5 et clôturée après 3 secondes sans alarme active. |

## Référence de configuration

- **Numéro de série (`Serial Number`)** : Identifiant unique pour cette instance de traitement d'occupation Kromek D5.
- **Système parent (`Parent System (Containing RPM)`)** : UID du système parent contenant le pilote Kromek D5. Laisser vide pour associer automatiquement le système englobant.
