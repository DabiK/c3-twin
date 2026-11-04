# Provenance et redistribution

Ce dépôt assemble du code spécifique C3 Twin, des dépendances npm et des assets 3D.
Aucune licence globale n’est déclarée à ce stade : ne pas ajouter une licence au nom
du propriétaire sans décision explicite, ni annoncer tous les éléments comme librement redistribuables.

- Les versions JavaScript sont enregistrées dans `c3-twin-app/package-lock.json`.
  Consulter les licences des paquets installés avant redistribution (Three.js, esbuild,
  glTF Transform, Draco, meshoptimizer).
- Les fichiers `web/draco/` sont des décodeurs tiers : préserver leurs mentions.
- Les modèles GLB et leurs droits de redistribution restent à documenter par leur auteur.
  Seul le modèle final allégé anonymisé est publié ; les sources de modélisation restent hors dépôt.
- Les notes CAN initiales citent canbox-core et des discussions QF001 ; les liens disponibles
  sont conservés dans `autoradio/QF001_donnees_voiture.md`. Les essais sur notre voiture
  étayent le mapping documenté, sans établir la compatibilité universelle.
- Zlink, les APK QF, les bibliothèques extraites et les captures de téléphone ne sont pas
  des dépendances à publier avec C3 Twin. Ils ne sont pas requis pour compiler l’application.

Le dépôt public fournit une release APK de préversion. Le choix d’une licence globale
reste distinct de la publication du code.
