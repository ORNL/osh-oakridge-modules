# Portique de détection radiologique Rapiscan

Le pilote Rapiscan se connecte à un RPM par TCP et publie les données gamma, neutroniques, d'occupation et les données associées.

## Configuration

- Saisissez un numéro de série unique et l'ID de la voie.
- Dans **Paramètres de communication**, ajoutez un fournisseur TCP et saisissez l'hôte et le port du RPM.
- Configurez les délais, les nouvelles tentatives et la vérification d'accessibilité.
- Saisissez la position du capteur lorsqu'elle n'est pas héritée d'un système de voie.
- Utilisez **Configuration du matériel RPM** pour l'intervalle gamma, le maintien de l'occupation et la valeur seuil N sigma.
- Activez **Analyse EML** uniquement pour une voie EML VM250 après avoir téléversé l'archive externe du module EML dans **Services > Module de service OSCAR > Chemin du module EML**, puis saisissez son état de collimation et sa largeur. Les alarmes sans menace EML (`RELEASE`) sont masquées du tableau de bord et adjugées automatiquement comme **Sans menace EML** (code `9999`) avec métadonnées interrogeables.
- Activez **Démarrage automatique** pour démarrer le pilote avec le nœud.
