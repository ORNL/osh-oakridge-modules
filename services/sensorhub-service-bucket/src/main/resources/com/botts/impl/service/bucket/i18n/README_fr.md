# Service de stockage en compartiments (Buckets)

Le **Service de stockage en compartiments** fournit un stockage d'objets sur le système de fichiers local pour les fichiers OSCAR, notamment les plans du site, les rapports générés (`reports/`), les pièces jointes d'adjudication, les modules d'extension EML et les médias d'événements conservés (`videos/`).

## Termes expliqués simplement

| Terme | Signification |
| --- | --- |
| **Compartiment (Bucket)** | Un dossier logique nommé géré par OSCAR (tel que `reports`, `videos`, `site-diagram` ou `eml`) qui stocke les fichiers sur disque et les sert via HTTPS authentifié. |
| **CORS (Partage de ressources entre origines multiples)** | En-têtes de sécurité standard permettant au navigateur exécutant le Visualiseur OSCAR de charger des images, des rapports et des médias depuis le serveur SensorHub. |

## Référence de configuration

- **Répertoire racine du stockage (`File Store Root Directory`)** : Dossier local où tous les compartiments et fichiers sont enregistrés.
- **Compartiments initiaux (`Initial Buckets`)** : Liste des noms de compartiments créés automatiquement au démarrage du service.
- **Taille maximale de fichier (Mo) (`Max File Size (MB)`)** : Taille maximale autorisée (en mégaoctets) pour un fichier téléversé.
- **Activer CORS (`Enable CORS`)** : Ajoute les en-têtes CORS permettant au Visualiseur OSCAR de récupérer et téléverser des fichiers.
- **Sécurité (`Security`)** : Règles de contrôle d'accès définissant quels utilisateurs authentifiés peuvent lire ou téléverser des objets.
