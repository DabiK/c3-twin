> Notes de recherche initiales. Pour l’installation et les résultats validés, lire [le guide actuel](../docs/installation.md) et [la démarche](../docs/demarche.md). Les hypothèses de ce document ne constituent pas une validation matérielle.

# QF001 : ce que l'autoradio expose de la voiture

D'après la documentation de la communauté, sans essai sur ta voiture. Ce qui est marqué *à vérifier* doit être confirmé avec `sonde_qf001.sh`.

## Ton autoradio

- Plateforme **QF** du fabricant Shenzhen Jitu (vendue aussi sous ROCO K706, Hizpo, Ossuret). Ce n'est **pas** un FYT : les outils FYT (`com.syu.ms`) ne s'appliquent pas.
- Android annoncé 12, en réalité Android 10.
- MCU `QF05…001121` : puce son BU32107 et radio TSC4745.

## Le chemin des données

```
Voiture (CAN) → boîtier CAN (Raise/RZC ou Hiworld) → MCU de l'autoradio (UART 38 400 bauds)
             → service Android « mcu_service » → app voiture com.qf.vehicle → autres apps
```

L'autoradio ne lit donc le CAN que si un **boîtier décodeur** est branché sur le faisceau et choisi dans les réglages : *Réglages → Usine (code 8888) → Car → modèle*. Sans boîtier, la vitesse affichée vient du GPS.

## Les apps du constructeur

| Paquet | Rôle |
|---|---|
| `com.qf.vehicle` | App voiture : décode le boîtier CAN, écrans portières, radar, clim, caméra de recul |
| `com.qf.framework` | Système du constructeur : touches, volume, lanceur |
| `com.qf.backcar` | Caméra de recul |
| `com.zjinnova.zlink` | ZLink (CarPlay / Android Auto) |

## Trois façons de lire les données

1. **Le flux du MCU, sans root.** Le service système `mcu_service` (interface `android.qf.mcu.IMcuManager`) accepte qu'une app s'abonne et reçoit tous les messages du MCU. La commande 65 transporte les trames du boîtier CAN. Les commandes 34 et 35 transmettent le fil d'éclairage et le frein à main.
2. **Les messages partagés de l'app voiture.** `com.qf.vehicle` diffuse `com.qf.vehicle.action.DATA_SHARE` aux apps inscrites dans les réglages globaux (`<paquet>KeyShareCarbodyState = 1`, etc.). Ces réglages ne s'écrivent qu'avec une autorisation donnée une fois en adb : `pm grant <paquet> android.permission.WRITE_SECURE_SETTINGS`.
3. **Le journal**, avec root : le MCU écrit chaque valeur dans logcat (`dispatchToClients - cmdId: X - data : [...]`).

Les marches arrière sont aussi diffusées : `com.qf.action.BACKCAR_START` / `BACKCAR_STOP`, et la propriété `sys.qf.backcar_state`.

## Ce qui est confirmé sur une voiture PSA

Le projet [Dashwheel](https://github.com/deviloufr-ai/Dashwheel) tourne au quotidien sur un QF avec une **C4 Picasso**, cousine de ta C3. Sur cette voiture :

| Donnée | Où | État |
|---|---|---|
| Portières, coffre, capot | trame MCU `41 FD 0C 38 <octet>` : 0x80 AVG, 0x40 AVD, 0x20 ARG, 0x10 ARD, 0x08 coffre, 0x04 capot | **confirmé sur la voiture** |
| Marche arrière | diffusion `BACKCAR_START/STOP` | confirmé |
| Angle du volant | diffusion `com.qf.vehicle.action.swa` | confirmé |
| Phares (fil d'éclairage), frein à main | MCU commandes 34 et 35 | fonctionne, ce sont des fils et non le CAN |
| Vitesse, régime, feux de route, antibrouillards, clignotants, ceintures | message partagé « carrosserie » | **vide sur la C4 Picasso** : le boîtier PSA ne le remplit pas |
| Ordinateur de bord, alertes | trames RZC 0x33 à 0x3A | décodé depuis le firmware, pas encore vu sur une voiture |

Pour ta C3, le plus probable est donc :
- **sûr** : portières, coffre, marche arrière, phares allumés ou non, frein à main ;
- **à chercher dans le flux brut** : vitesse, régime, clignotants, antibrouillards, feux stop. Le boîtier les reçoit peut-être, mais l'app voiture ne les partage pas. Il faut les retrouver en comparant le flux pendant qu'on actionne chaque commande, ce que fait la sonde ;
- **sinon** : un adaptateur OBD Bluetooth (ELM327) pour la vitesse, le régime et les températures.

## Accès adb

- Code usine **114477** : active le mode développeur, puis 7 appuis sur le numéro de build.
- Sur les premiers firmwares, adb sans fil était actif par défaut, **en root**, sur le port **9876**. *À vérifier sur ton firmware de novembre 2024.*
- Autres codes : 8888 réglages avancés, 22558800 remise à zéro complète (efface aussi la configuration CAN, à éviter).

## La sonde

`sonde_qf001.sh` se lance depuis le Mac, autoradio sur le même Wi-Fi ou en USB :

```bash
./sonde_qf001.sh <IP_AUTORADIO>
```

Elle ne modifie rien sur l'autoradio. Elle relève les propriétés, les apps, les services et les réglages, copie `com.qf.vehicle` et `com.qf.framework` pour les étudier hors de la voiture, puis enregistre le journal pendant que tu suis 39 étapes : chaque portière, chaque feu, frein, marche arrière, moteur, volant. Un trajet peut être enregistré en option. Le résultat est une archive à me renvoyer.

## Ensuite : l'app jumeau

Une petite app Android qui :
1. affiche la page du jumeau dans une WebView ;
2. s'abonne à `mcu_service` et aux diffusions de `com.qf.vehicle` ;
3. décode les trames et appelle `twin.set({...})` dans la page.

## Sources

- [XDA, discussion générale QF001](https://xdaforums.com/t/general-discussion-qf001-roco-k706-head-units-with-uis-7862s-not-fyt-based-read-first-post-first.4525675/) : codes usine, adb port 9876, firmware de debug.
- [XDA, firmwares QF001](https://xdaforums.com/t/firmware-qf001-roco-k706-head-units-with-uis-7862a-7862s-not-fyt-based.4541261/) : versions de MCU et puces associées.
- [Dashwheel](https://github.com/deviloufr-ai/Dashwheel) : `CarBox.kt`, `CarBoxData.kt`, `McuFeed.kt`, `McuReader.kt`, `PsaTripComputer.kt`, `docs/unit-platforms.md`.
- [canbox-core](https://github.com/fazerxlo/canbox-core) : protocoles Raise et Hiworld pour PSA.
- [QF001CarAppOverlay](https://github.com/patriksh/QF001CarAppOverlay) : nom du paquet `com.qf.vehicle`.
