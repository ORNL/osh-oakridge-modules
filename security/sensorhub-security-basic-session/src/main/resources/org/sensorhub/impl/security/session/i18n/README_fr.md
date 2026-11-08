# Authentification de base par session

Le module **Authentification de base par session** authentifie les requêtes du navigateur et de l'API à l'aide d'un témoin (cookie) de session opaque côté serveur après vérification des identifiants.

## Termes expliqués simplement

| Terme | Signification |
| --- | --- |
| **Témoin de session opaque (Opaque Session Cookie)** | Un jeton aléatoire stocké par le navigateur après la connexion. Au lieu de renvoyer le mot de passe à chaque requête, le navigateur envoie ce jeton et OSCAR vérifie la session active sur le serveur. |
| **Délai d'inactivité vs Délai absolu** | Le **délai d'inactivité** déconnecte l'utilisateur après une période sans requête ; le **délai absolu** impose une reconnexion après une durée maximale totale, même en cas d'activité. |
| **Politique SameSite** | Règle du navigateur contrôlant l'envoi du témoin lors de requêtes intersites. `Strict` limite le témoin au nœud local ; `None` autorise les connexions fédérées multi-nœuds via HTTPS. |

## Référence de configuration

- **Nom du témoin de session (`Session cookie name`)** : Nom du cookie contenant le jeton de session.
- **Délai d'inactivité (secondes) (`Idle timeout`)** : Durée maximale sans requête avant expiration de la session.
- **Délai absolu (secondes) (`Absolute timeout`)** : Durée de vie maximale d'une session, même active.
- **Témoin sécurisé (`Secure cookie`)** : Transmet uniquement le témoin via HTTPS. Conserver activé en production.
- **Politique SameSite (`SameSite policy`)** : Règle de partage (`Strict`, `Lax` ou `None`).
- **Chemin de redirection par défaut (`Default redirect path`)** : Chemin local ouvert après connexion lorsqu'aucune destination préalable n'est enregistrée.
