# Guide des agents — C3 Twin

## Lire avant d’agir

Lire `README.md`, `docs/compatibilite.md`, `docs/installation.md` et `docs/architecture.md`.
Le périmètre actuel est un launcher Android avec ouverture directe de Zlink et pastille
retour. **Ne pas reprendre l’intégration du protocole CarPlay** sans nouvelle demande.
`experiments/tlink4/` est une archive incompatible avec Zlink 5, pas une source de vérité.

## Prendre le contexte réel

- Inspecter `git status` ; préserver le travail existant et les préférences utilisateur.
- Identifier explicitement le serial ADB, le modèle, l’API et le firmware. Toutes les
  commandes appareil doivent utiliser `adb -s <SERIAL>` ; ne pas réutiliser une IP historique.
- Ne pas confondre affichage de démo, simulation, capture hors ligne et validation physique.
- Demander une manipulation du véhicule seulement à l’arrêt et pour une observation précise.
- Un appareil indisponible n’autorise pas à prétendre une installation réussie.

## Installation guidée

1. Vérifier la compatibilité ; le code MCU est spécifique QF et le profil feux à cette C3.
2. `cd c3-twin-app && npm ci && ./tools/check.sh`.
3. Installer avec `./installer_autoradio.sh <SERIAL> install` dans le périmètre demandé.
4. Vérifier dashboard, données réelles et parcours Zlink/retour. Préserver la clé de signature.
5. Ne choisir le launcher qu’une fois demandé et l’application utilisable : action `launcher`.
   Lire la sauvegarde produite, vérifier HOME Android et le réglage QF puis la touche Home.
6. Rapporter séparément l’installation, les tests réellement faits et ceux restant physiques
   (notamment démarrage après contact). Ne pas forcer un reboot pour remplacer cette validation.

Ne pas désinstaller une app pour résoudre un conflit de signature, désactiver le launcher
constructeur, modifier le firmware, écrire des trames arbitraires ou effacer des données
sans instruction spécifique. Ne pas publier journaux, captures, clés ou APK constructeur.

## Développement

- Sources web : `web/main.js`, `web/index.html`, `web/alertes.js`, pas `web/app.js` généré.
- Code livré : `android/src/`. Archives hors de ce dossier, jamais ajoutées au manifeste.
- Maintenir le mapping CAN dans le décodeur, ses tests capturés, et la distinction inconnu/faux.
- Conserver le profil mobile performant, les noms de meshes, le thème blanc cassé/dark,
  la possibilité de masquer les apps et le menu transparent épuré.
- Ne pas modifier les clés localStorage ou effacer le stockage pour un simple changement visuel.
- Exécuter `./tools/check.sh` après les changements de code/build. Pour les scripts appareil,
  utiliser un faux ADB ou la syntaxe locale avant toute exécution réelle.
- Consulter la documentation actuelle pour les nouvelles APIs ; les comportements du
  firmware se vérifient sur l’appareil, pas dans la documentation Android générique.
- Mettre à jour les guides avec les changements de commandes ou de comportement.

Pour un inventaire volumineux et indépendant de code/logs, déléguer en lecture seule
à `luna_worker` si disponible ; demander des preuves et évaluer le résultat. Garder
les choix d’architecture, de compatibilité et de sécurité dans l’agent principal.

Pour une question d’API, de SDK ou de CLI, utiliser Context7 :
`npx ctx7@latest library "Nom officiel" "question précise"`, puis
`npx ctx7@latest docs /organisation/projet "question précise"` avec l’ID retourné.
Exécuter les requêtes réseau hors sandbox si nécessaire, sans données sensibles.
En cas de quota dépassé, le signaler ; ne pas présenter une réponse non vérifiée comme documentée.
