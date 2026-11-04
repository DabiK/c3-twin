# Préparer l’accès ADB / root sur un autoradio QF

## Le prérequis exact

Pour reproduire **la configuration complète du K706 de référence**, prévoir un accès
ADB autorisé avec les privilèges nécessaires aux réglages QF. La session utilisée
pour ce projet était déjà root (`adb shell id` renvoyait `uid=0`).

Cela ne signifie pas que l’APK exige un téléphone/autoradio rooté pour simplement
s’installer et afficher son interface. Le root ADB sert aux opérations privilégiées
propres au firmware, notamment la propriété persistante du launcher QF. Le processus
C3 Twin reste une application Android normale ; il ne lance pas de commande `su`.

## Comment cet accès avait été activé

Sur le montage de référence, l’utilisateur indique avoir activé une **option du menu
usine**, protégé par un code. Le code exact et le nom de l’option n’ont pas été conservés.
Aucune procédure de flash, Magisk ou déverrouillage du bootloader n’a été réalisée
pendant le développement. Ne pas inventer une procédure de root à partir de cet état.

Les souvenirs de codes tels que « 888 » ou « 5555 » ne sont **pas vérifiés** et ne
constituent pas des instructions à essayer. Aucun code universel n’est recommandé.

## Pistes pour son propre appareil

1. Relever modèle, firmware complet, version MCU et API Android réelle avant de chercher
   une procédure. Une marque commerciale seule ne suffit pas à identifier le firmware.
2. Consulter le manuel ou le vendeur pour l’activation ADB / options développeur du
   **firmware exact**, puis identifier si une option usine expose un ADB privilégié.
3. Utiliser les discussions techniques correspondant à la famille matérielle comme
   pistes, pas comme garantie de compatibilité. Exemple : [discussion QF001/K706 sur XDA](https://xdaforums.com/t/general-discussion-qf001-roco-k706-head-units-with-uis-7862s-not-fyt-based-read-first-post-first.4525675/).
4. Après activation, vérifier les droits réels :

```bash
adb devices -l
adb -s <SERIAL> shell id
adb -s <SERIAL> shell getprop ro.build.display.id
adb -s <SERIAL> shell getprop ro.build.version.sdk
adb -s <SERIAL> shell getprop service.adb.tcp.port
```

`uid=0` signifie que cette session shell est root. Une session `uid=2000(shell)`
peut autoriser certaines opérations ADB sans donner accès à tous les réglages QF.
Le port observé sur notre K706 était **9876** ; relever celui de son appareil.

Ne pas utiliser une option de réinitialisation, de flash MCU ou de changement de profil
CAN pour activer ADB. Ne pas supprimer le launcher constructeur. Les scripts du dépôt
n’exploitent aucune faille et ne rootent pas automatiquement l’appareil.

Une fois l’accès préparé, suivre [l’installation](installation.md). Garder ADB sur un
réseau maîtrisé et ne pas exposer son port sur Internet.
