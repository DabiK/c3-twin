# Démarche et preuves

## Partir du matériel, pas des noms commerciaux

Les premières explorations ont distingué le firmware QF/Jitu de FYT, puis identifié
`mcu_service` et les applications QF. La lecture des messages a permis de relier les
états physiques à des trames. Les notes historiques sont dans
[`autoradio/QF001_donnees_voiture.md`](../autoradio/QF001_donnees_voiture.md) ; leurs
hypothèses initiales ne remplacent pas les validations ci-dessous.

## Rendre le modèle utilisable sur l’autoradio

Le modèle initial rendait environ 4 images/s. Sur le profil Android, désactivation
des ombres dynamiques, de la réfraction et de l’anticrénelage, puis réduction ciblée
du modèle : 931 970 → 457 453 triangles, carrosserie et vitres conservées.
La mesure finale ponctuelle était d’environ 34 ms par image (29 images/s), avec
fluidité au doigt confirmée. Ce n’est pas une garantie sur tous les matériels.

```bash
cd c3-twin-app
node tools/alleger.mjs /chemin/vers/modele-source.glb web/car-mobile.glb --head-unit
```

## Identifier les feux par changements contrôlés

Le 4 novembre 2026, les essais positions → croisements → positions, appel de phares,
clignotants gauche puis droit ont identifié `FD 05 7D 01 flags checksum` :

| Bit | Profil C3 | Validation |
|---|---|---|
| `0x80` | Positions | Physique |
| `0x40` | Croisements | Physique |
| `0x20` | Routes | Appel de phares |
| `0x10` / `0x08` | Gauche / droite | Physique et affichage |
| `0x04` / `0x02` | Antibrouillards avant / arrière | Déduit du firmware, à confirmer |

Exemples observés : `80 → C0 → 80`, `A0/E0` pendant les appels, `D0/C0` à gauche,
`88/80` à droite. La synchronisation des voyants et optiques a aussi nécessité de
corriger l’opacité et l’ordre de rendu des matériaux. L’utilisateur a confirmé le résultat.

## CarPlay : exploration, puis simplification

Un prototype avait été construit d’après TLINK 4.0.41.11 et des fixtures locales.
L’autoradio réel embarquait Zlink 5.4.61 : les anciens ports et formats n’étaient pas
compatibles. Une capture locale, poursuivie pendant la coupure Wi-Fi d’ADB, a révélé
le canal vidéo vers le port loopback 1888.

Une seconde capture préservant l’initialisation a permis de décoder **264 images H.264
1280 × 720** et de visualiser la grille de l’iPhone. Cela prouve un décodage hors ligne,
**pas une intégration temps réel ou tactile dans C3 Twin**. La première capture tournante
avait perdu les paramètres de décodage initiaux et ne pouvait pas être décodée seule.

Décision finale de l’utilisateur : ouvrir Zlink directement, ajouter un raccourci de
retour et abandonner la réimplémentation du protocole. Ce parcours a été installé
et confirmé fonctionnel. Le prototype et ses hypothèses restent archivés, hors build.

## Launcher

Le choix HOME Android seul était ignoré par le firmware. Après réglage de
`persist.sys.qf.launcher`, la touche Home a effectivement repris C3 Twin.
Le prochain démarrage après contact reste à vérifier ; veille et démarrage à froid
peuvent avoir des politiques de restauration différentes.

## Méthode pour une nouvelle voiture

1. Relever le matériel et les permissions réels.
2. Capturer une situation stable, puis un seul changement physique à la fois.
3. Comparer les trames et conserver les incertitudes.
4. Ajouter le profil et un test à partir des données capturées.
5. Vérifier les voyants et le modèle en conditions réelles, à l’arrêt.
6. Documenter appareil, version, observation et limites de chaque conclusion.
