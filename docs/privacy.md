# Confidentialité de la publication

La publication initiale utilise un nouvel historique Git. Les anciens commits locaux,
contenant des informations propres au véhicule, n’ont pas été transférés.

- Plaques supprimées des textes et des textures GLB embarquées. Les octets des images
  ont été effacés, pas simplement masqués à l’écran.
- Captures README réalisées dans un navigateur isolé, avec modèles anonymisés et état
  de démonstration sans données de téléphone ou de trajet réelles.
- Aucun journal, PCAP, APK constructeur, clé de signature ou réglage local brut publié.
- Auteur Git de publication : identité du projet et adresse GitHub noreply.
- Débogage WebView désactivé dans l’APK non débogable de la release publique.

L’application journalise localement des trames MCU pour le diagnostic. Ces fichiers
ne sont pas envoyés à un serveur par le code C3 Twin. Zlink et le système Android ont
leurs propres comportements, indépendants du projet.

Avant de partager une issue, relire captures et journaux : plaques, VIN, coordonnées,
trajets, noms de téléphone, adresses réseau et notifications peuvent être personnels.
Ne jamais joindre `build/debug.keystore` ni les clés d’une future release.
