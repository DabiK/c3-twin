# Sources publiques pour un adaptateur MTCH

Recherche du 5 novembre 2026. Cible déclarée : PX5, MCU `MTCH_MX_V4.09_1`.
Analyse statique de projets publics uniquement ; aucun accès à cette cible, aucun
firmware installé et aucune validation physique MTCH.

## Correspondance avec QF

| Couche | C3 Twin QF actuel | Piste MTC publique |
|---|---|---|
| Service Android | `mcu_service` | `carservice` |
| Contrat Binder | `android.qf.mcu.IMcuManager` | `android.microntek.ICarService` |
| Réception | `IMcuListener`, commande CAN 65 | `ICarManageCallback.onStatusChanged(String, Bundle)` |
| Inscription | transaction QF 2 | inscription 15, désinscription 16 dans l'exemple MTC ancien |
| Décodage | Raise/Hiworld dans `CarData` | réutilisation possible si le firmware transmet le même contenu brut |
| Rendu | patches JSON vers le twin | même contrat de sortie à conserver |

Ces numéros Binder documentent les exemples consultés, pas un ABI garanti sur MTCH.
Ne pas remplacer simplement les noms et numéros QF : vérifier le descripteur et la
forme du callback avant d'utiliser une interface. Préférer la classe constructeur
présente sur l'appareil lorsqu'elle expose l'interface requise.

## MtcdAutoVolume : contrat Binder lisible

[Dépôt](https://github.com/f1xpl/MtcdAutoVolume), révision consultée
`6f9d0eedde977f201db00955a371bbc95341fc82`.

- `app/src/main/java/android/microntek/CarManager.java:43-77` : recherche
  `carservice`, attachement et détachement du callback.
- `ICarService.java:12-31` : descripteur, lectures typées et transactions.
- `ICarManageCallback.java:12-43` : callback transportant un nom d'événement et un Bundle.

Il s'agit d'une preuve d'interface MTC, pas d'une validation des portes sur la cible.
L'application utilise le GPS pour sa vitesse ; son nom ne prouve pas une vitesse CAN.
Aucune licence racine trouvée dans ce checkout : ne pas recopier ses classes dans
la distribution comme si leur réutilisation était automatiquement autorisée.

## JunsunPSARemote : corrélation du protocole PSA

[Dépôt](https://github.com/morcibacsi/JunsunPSARemote), révision consultée
`0b5821487df973af1f848aa69c7d6226d4047bfb`, licence GPL-3.0.

Ce projet Arduino documente le protocole entre un boîtier Raise PSA et un autoradio
8227L. Il ne fournit pas l'interface Android MTCH. Son code CAN utilise l'en-tête
`FD`, une longueur et une somme de contrôle, ainsi que `33`, `34`, `35`, `36` pour
les pages trajet/température : mêmes identifiants que notre décodeur Raise.

`JunsunPSACANRemote.cpp:14-22` encode une vitesse sous `7D/03`.
Notre profil de feux utilise `7D/01` : ne pas confondre ces sous-types. Ce projet
ne fournit pas à lui seul le mapping des clignotants de la cible. Ses états de portes
dans la branche VAN ne doivent pas être transposés au protocole CAN de la C3 II.

Conclusion : il existe une corrélation de protocole indépendante de la marque de
l'autoradio. Réutiliser notre décodage devient plausible **si** MTCH expose ces trames.
Le type de MCU ne permet pas de déduire la marque du boîtier CAN.

## Autres implémentations examinées

### AHUCanBus : lecture série, décodeur vide

[Dépôt](https://github.com/TheUnknown12/AHUCanBus), révision
`9250d000b1dea1e8ac3a24780ebe9b609a37a07c`.
`app/src/main/java/android/microntek/canbus/CanBusServer.java:182-195` ouvre
`SerialManager.openPort(4, 38400)` et reçoit des octets par `SerialReceiver`.
`CanBusHandler.java:21-33` les journalise ; `CanBus44.CmdProc` est vide.
Il n'y a donc pas de profil portes/feux à récupérer. L'ouverture de ce port par
C3 Twin pourrait entrer en concurrence avec le service constructeur ; ce n'est
pas notre premier choix. Aucun fichier LICENSE identifié dans le checkout.

### Dashwheel : pistes de réception, pas de mapping MTC validé

[Dépôt](https://github.com/deviloufr-ai/Dashwheel), révision
`06ccba10927b1eaa6357c59db00fc50772448398`.

- `unitproto/src/main/kotlin/com/openauto/unitproto/mtc/MtcProtocol.kt:36-49`
  décrit `com.microntek.sync`, un extra `syncdata` et la clé système
  `com.microntek.controlinfo.door`. Ce sont des candidats à sonder, pas des
  garanties de disponibilité sur la cible.
- `app/src/main/java/com/openauto/dash/unit/mtc/MtcAdapter.kt:174-213`
  reçoit les octets, mais conserve `MtcSync.Layout.UNKNOWN`. Son commentaire
  indique explicitement qu'aucune donnée n'est encore décodée/publiée par ce chemin.
- Le callback `CarManager` du projet est utilisé pour `KeyDown` : il ne prouve
  pas l'accès aux trames CAN brutes par ce callback.

Le README annonce une prise en charge MTC/MTCH en attente de testeurs. Le code
impose une conclusion plus limitée : des points d'écoute existent, mais aucune
table portes/feux/clignotants validée n'a été trouvée dans cette implémentation.
La licence courante est propriétaire et interdit la reprise sans autorisation :
aucun code de ce dépôt n'a été intégré dans C3 Twin.

## ROM et firmwares (distributions, pas SDK complet)

[Hal9k](https://hal9k.ru/) distribue un Mod Android 10 pour cette communauté
(PX5/PX6/PX30 mentionnés par l'auteur). C'est une distribution modifiée, pas une
publication démontrée de toutes les sources MCU et CAN du `MTCH_MX_V4.09_1`.
[M.I.C.](https://www.mic-autoradio.com/updates/autoradio/mcu/) publie des mises à jour
MCU distinguant MTCE et MTCH, avec des variantes propres à son matériel.

Les sources examinées ne fournissent pas un SDK officiel complet pour notre cible.
Une ROM publique pourrait servir à une analyse hors ligne des services et APK ;
il n'est pas nécessaire de flasher l'autoradio pour commencer le portage.

## Étape d'implémentation proposée

Construire un adaptateur MTC passif distinct du QF : détection de service, abonnement
aux événements disponibles, normalisation vers les champs existants du twin.
Ne pas envoyer les requêtes Raise QF automatiquement sur ce nouveau transport.
Ajouter un diagnostic intégré qui affiche service détecté, réception des événements
et champs reconnus. Cela évite de demander au propriétaire de connaître son système.

Tester hors matériel la classification et les décodeurs à partir de fixtures, puis
distribuer une APK expérimentale pour une validation simple des portes et feux.
Un replay de code public ne permet pas d'annoncer la compatibilité physique.
