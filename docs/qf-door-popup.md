# Masquer le popup de portière du firmware QF

Sur le K706 de référence, le firmware affichait sa propre fenêtre à l’ouverture d’une
porte, en plus du modèle C3 Twin. Le réglage ci-dessous a été appliqué et validé
physiquement le 4 novembre 2026 : disparition du popup, animation de porte C3 Twin conservée.

Cette procédure est spécifique au firmware QF étudié. Elle ne désactive ni l’application
voiture, ni son service, ni la caméra de recul. Elle n’est pas appliquée automatiquement
par l’installateur C3 Twin.

## Réglage réversible

Relever la valeur précédente avant modification :

```bash
adb -s <SERIAL> shell settings get system KeyDoorNotShow
adb -s <SERIAL> shell settings put system KeyDoorNotShow 1
adb -s <SERIAL> shell settings get system KeyDoorNotShow
```

La valeur précédente était `0` sur notre appareil. Pour rétablir ce comportement :

```bash
adb -s <SERIAL> shell settings put system KeyDoorNotShow 0
```

Si la valeur initiale était absente (`null`), restaurer l’absence avec
`settings delete system KeyDoorNotShow` au lieu d’inventer une valeur par défaut.

Tester à l’arrêt une ouverture/fermeture de porte : le popup ne doit plus apparaître
et le dashboard doit continuer à recevoir l’état. Le choix est enregistré dans les
réglages Android ; une réécriture par le firmware reste possible selon ses versions.

## Pourquoi les données restent disponibles

Dans le firmware analysé, `VehicleSender` partage `ACTION_DATA_SHARE` avant de tester
`VehicleConfigUtil.getCarDoorNotShow()` pour l’affichage `ACTION_DOOR_WINDOW`.
`getCarDoorNotShow()` lit `Settings.System.KeyDoorNotShow`.
Ne pas confondre avec `KeyDoorNotAutoClose`, qui concerne la fermeture automatique
après délai, ni avec les clés de partage `KeyShareDoorWindow`.
