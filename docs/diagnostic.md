# Diagnostic

| Symptôme | Vérifications |
|---|---|
| ADB hors ligne après ouverture de Zlink | Le CarPlay sans fil peut changer le Wi-Fi. Remettre l’autoradio sur le réseau de l’ordinateur, relever l’IP, puis `adb disconnect <IP>:<PORT>` et `adb connect <IP>:<PORT>` |
| APK refusé | Vérifier version et signature. Conserver la clé existante ; ne pas désinstaller par réflexe |
| Pas de données voiture | Vérifier `mcu_service`, `com.qf.vehicle`, permissions et configuration CAN constructeur ; le dashboard peut fonctionner sans source matérielle |
| Feux inversés ou absents | Sortir du mode démo, relever le profil/les trames ; ne pas recopier le mapping C3 sur un autre véhicule sans essai |
| Retour flottant absent | Autoriser C3 Twin à afficher par-dessus les autres apps ; ouvrir Zlink depuis C3 Twin pour lancer le service |
| Home ouvre le launcher constructeur | Vérifier le HOME Android et `persist.sys.qf.launcher` ; suivre le guide, sans désactiver le launcher d’origine |
| Le prochain allumage ouvre Zlink | Distinguer le launcher par défaut de l’autolancement CarPlay ou de la restauration après veille |
| 3D lente | Vérifier `car-mobile.glb`, mode Android, absence de démo en arrière-plan ; mesurer avant de changer le rendu |

## Données de diagnostic

Depuis `c3-twin-app` :

```bash
./installer_autoradio.sh <SERIAL> journal
adb -s <SERIAL> shell dumpsys activity activities
adb -s <SERIAL> shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb -s <SERIAL> shell getprop persist.sys.qf.launcher
```

`CarData` écrit `mcu.log` dans `/sdcard/Android/data/fr.dabi.c3twin/files/`, avec rotation
à 8 MiB vers `mcu.old.log`. L’absence de fichier n’est pas masquée par le script de
collecte : examiner les messages `adb pull` et le dossier produit.

`autoradio/sonde_qf001.sh` est une sonde approfondie historique qui copie aussi des
APK constructeur et des réglages. Lire son contenu avant emploi. Ne pas publier ses
résultats bruts. Les outils de capture vidéo ne sont pas nécessaires à l’installation.

Les journaux, captures et snapshots peuvent révéler téléphone, trajet, coordonnées
ou réglages privés. Ils sont exclus par `.gitignore` ; vérifier aussi `git diff --cached`
avant toute publication. Le débogage WebView est désactivé dans la release publique ; le contrôle de cadence
via DevTools nécessite une compilation de développement explicitement débogable.
