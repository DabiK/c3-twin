# Compiler, installer, choisir le launcher

Toutes les manipulations et validations visuelles se font véhicule à l’arrêt.
Les exemples utilisent `<IP>` ou `<SERIAL>` à remplacer par l’appareil identifié.
Ne pas copier l’adresse d’une ancienne session : elle peut changer.

## Avant de commencer : accès privilégié QF

Pour reproduire la configuration complète de notre K706, préparer l’accès ADB/root
via la procédure propre au firmware. L’activation provenait d’une option usine, mais
son code exact n’est pas confirmé. Lire [les prérequis et pistes ADB/root](adb-root.md).
L’installation de l’APK seule ne nécessite pas nécessairement root.

## APK précompilé (sans compilation)

Télécharger `C3Twin.apk` dans [GitHub Releases](https://github.com/DabiK/c3-twin/releases/latest).
Le fichier `SHA256SUMS` de la release permet d’en vérifier l’intégrité.
Installer avec le gestionnaire de fichiers Android ou `adb -s <SERIAL> install -r C3Twin.apk`.
Pour le partage QF, accorder ensuite, si le firmware l’autorise :

```bash
adb -s <SERIAL> shell pm grant fr.dabi.c3twin android.permission.WRITE_SECURE_SETTINGS
adb -s <SERIAL> shell pm grant fr.dabi.c3twin android.permission.ACCESS_FINE_LOCATION
adb -s <SERIAL> shell appops set fr.dabi.c3twin SYSTEM_ALERT_WINDOW allow
```

Continuer à la section launcher après test. Zlink doit déjà être installé, activé et
capable de connecter l’iPhone indépendamment de C3 Twin. Cette APK ne l’installe pas.

## 1. Poste de développement

Build testé sur macOS ; scripts Bash conçus également pour Linux, non validés sur Linux.
Sous Windows, prévoir un environnement compatible et vérifier ADB côté hôte.

Prérequis : Git, Node.js 22 ou plus récent, npm, JDK 17 ou plus récent,
`zip`, Android SDK command-line tools et `adb` dans le PATH. Le SDK doit contenir
`platforms;android-34`, `build-tools;36.0.0` et `platform-tools`.
Avec `sdkmanager` installé :

```bash
sdkmanager 'platform-tools' 'platforms;android-34' 'build-tools;36.0.0'
export ANDROID_HOME=/chemin/vers/Android/sdk
export PATH="$ANDROID_HOME/platform-tools:$PATH"
cd c3-twin-app
npm ci
./tools/check.sh
```

Le build respecte `ANDROID_HOME`, puis `ANDROID_SDK_ROOT`. Le chemin Homebrew
`/opt/homebrew/share/android-commandlinetools` est le dernier repli.
`BUILD_TOOLS_VERSION` et `ANDROID_PLATFORM` peuvent être surchargés ; la combinaison
36.0.0/34 est celle testée. Le manifeste cible API 29, minimum 26.
`VERSION_CODE` et `VERSION_NAME` permettent de versionner un APK ; augmenter le code
pour une nouvelle version distribuée.

Résultat : `c3-twin-app/build/C3Twin.apk`. La première compilation crée une clé
locale de développement `build/debug.keystore`. **Conserver la même clé** pour les mises
à jour. Ne pas la committer. La signature de développement n’est pas une chaîne de release publique.
En cas de signature différente, ne pas désinstaller automatiquement : cela effacerait les préférences.

## 2. Identifier et connecter l’autoradio

Activer ADB selon la procédure du constructeur. Mettre ordinateur et autoradio sur
le même réseau ; le CarPlay sans fil peut quitter ce réseau et couper ADB.

```bash
adb connect <IP>:9876
adb devices -l
adb -s <IP>:9876 shell getprop ro.product.model
adb -s <IP>:9876 shell getprop ro.build.version.sdk
adb -s <IP>:9876 shell id
adb -s <IP>:9876 shell service list
```

Le port 9876 appartient au montage QF de référence. Utiliser `<IP>:<PORT>` si différent.
En USB, passer `usb`, ou le numéro de série explicitement. Avec plusieurs appareils,
choisir toujours la cible ; ne pas utiliser un `adb shell` sans sélection.
L’installateur échoue si l’appareil est absent ou non autorisé ; il ne tente pas de rooter l’autoradio.

## 3. Installer sans changer l’accueil

Depuis `c3-twin-app` :

```bash
./installer_autoradio.sh <IP>:9876 install
# Ou : ./installer_autoradio.sh <SERIAL> install
```

Cette commande installe l’APK avec conservation des données, tente les autorisations
ci-dessous et ouvre C3 Twin. Un refus de permission est affiché ; il n’est pas masqué
comme une installation entièrement validée. Aucun launcher n’est désactivé.

| Autorisation | Usage | Si refusée |
|---|---|---|
| `ACCESS_FINE_LOCATION` | Vitesse GPS de repli | Accepter la demande Android si souhaité ; contrôler les données CAN séparément |
| `WRITE_SECURE_SETTINGS` | Partage de données `com.qf.vehicle` | Certaines données peuvent manquer ; vérifier le firmware et les droits ADB |
| `SYSTEM_ALERT_WINDOW` via appops | Bouton de retour depuis Zlink | Activer « Afficher par-dessus les autres apps » pour C3 Twin ; Zlink reste ouvrable sans bouton |
| `FOREGROUND_SERVICE` | Maintien du bouton flottant | Déclarée dans le manifeste, pas une demande runtime |

Valider le chargement 3D, les feux et clignotants, puis CarPlay → Zlink → pastille C3.
Déplacer la pastille si elle gêne ; sa position est mémorisée. La notification permet de la masquer.
Les apps masquées du tiroir restent installées ; « Organiser » permet de les réafficher.

## 4. Choisir C3 Twin comme launcher

Seulement une fois l’application utilisable :

```bash
./installer_autoradio.sh <IP>:9876 launcher
```

Le script enregistre le HOME résolu, la valeur QF et la cible dans
`c3-twin-app/backups/launcher-<date>-<pid>/`, puis utilise `cmd package set-home-activity`.
Sur QF, si `persist.sys.qf.launcher` existe déjà, il modifie aussi cette propriété :
le choix Android seul ne suffit pas sur le K706 testé. Cette opération peut exiger ADB root.
Un refus QF après succès Android est signalé comme état partiel à traiter, pas comme succès complet.

Vérifier la touche Home et, lors d’un prochain cycle normal, l’allumage du véhicule.
Ne pas confondre démarrage à froid et sortie de veille : le firmware peut restaurer
la dernière application ou lancer CarPlay automatiquement. Ces politiques ne sont pas modifiées.

## 5. Revenir au launcher précédent

Lire la sauvegarde créée **sur cet appareil**. Ne pas réutiliser le package d’un autre modèle.
Si `home-before.txt` contient `ResolverActivity`, ce n’était pas un launcher sélectionné :
choisir une application HOME installée via les réglages Android.

Sur notre K706, le launcher original était `com.android.launcher.city/com.android.launcher3.Launcher` :

```bash
adb -s <SERIAL> shell setprop persist.sys.qf.launcher com.android.launcher.city
adb -s <SERIAL> shell cmd package set-home-activity com.android.launcher.city/com.android.launcher3.Launcher
adb -s <SERIAL> shell input keyevent KEYCODE_HOME
```

Sur un autre appareil, remplacer ces valeurs par les réglages sauvegardés.
Ne pas désinstaller C3 Twin avant d’avoir rétabli et vérifié un autre écran d’accueil.

## Aperçu sans autoradio

Après `npm ci` et `./build.sh`, servir `c3-twin-app/web/` :

```bash
python3 -m http.server 8000 --directory web
```

Ouvrir `http://localhost:8000/index.html?demo`. Le mode démo simule des données ; il ne
valide ni le CAN, ni CarPlay, ni les permissions. Dans l’application, un appui de deux
secondes sur la vitesse bascule la démo : la désactiver avant tout test véhicule.

## Option QF : enlever le popup constructeur des portes

Si le firmware superpose son propre dessin à l’ouverture d’une portière, voir
[le réglage QF réversible validé](qf-door-popup.md). Les données de portes restent
partagées avec C3 Twin. Cette option est indépendante du launcher.
