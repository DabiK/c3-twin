> **Archive historique, non validée sur le K706.** Les affirmations ci-dessous décrivent le prototype TLINK 4 et ses hypothèses, pas le comportement actuel de C3 Twin. Les ports et formats supposés ne conviennent pas à Zlink 5.4.61. Voir [README](README.md).

# CarPlay dans C3 Twin — les canaux Fox de ZLink

C3 Twin n'embarque pas de moteur CarPlay et ne parle jamais à l'iPhone. Il se branche sur le
moteur déjà présent sur l'autoradio : le démon `z-link` de ZLink (`com.zjinnova.zlink`), qui
fait l'authentification MFi, la session CarPlay et le décodage du lien avec l'iPhone.

```
iPhone ──BT + Wi-Fi (ou USB)──> z-link (root, puce MFi) ──TCP 127.0.0.1──> C3 Twin
                                                          51818 vidéo
                                                          51848 tactile
```

ZLink expose ces flux sur des sockets TCP locaux, sur `127.0.0.1`. C3 Twin s'y connecte comme
un client. Aucun root, aucune permission spéciale, aucun redémarrage de ZLink : il suffit que
le démon tourne (il est lancé au boot par `zlink5.sh`) et que l'iPhone soit connecté.

## Carte des ports

Relevée dans `libzjL10001.so` de TLINK 4.0.41.11 (`lib/armeabi-v7a`), sur les appels à
`FoxServerInit`. À revérifier si l'autoradio embarque une version 5.x.

| Port | Usage | Client C3 Twin |
|---:|---|---|
| 51808 | contrôle | non |
| **51818** | **vidéo CarPlay (H.264)** | **oui** |
| 51828 | contrôle | non |
| 51838 | touches physiques | non |
| **51848** | **tactile CarPlay** | **oui** |
| 51858 | audio principal | non (à faire) |
| 51868 | audio alternatif | non |
| 51878 | audio / night mode | non |
| 51888 | audio système | non |
| 51898 | micro | non |

Les ports 7200/8200/9200/9300 sont ceux de Baidu CarLife, 1051 celui d'Android Auto. Ils ne
concernent pas CarPlay.

## Protocole vidéo (51818)

Le client ouvre la socket, puis envoie une requête de 128 octets, complétée par des zéros :

```
REQUEST FRAME
```

Le serveur répond par un en-tête ASCII de 128 octets, complété par des zéros, suivi des
octets H.264 (Annex-B) :

```
VIDEO#TYPE:H264#WIDTH:1280#HEIGHT:720#LEN:24576#LM:0#TM:0#RM:0#BM:0
```

- `LEN` est la taille exacte du payload qui suit.
- `LM/TM/RM/BM` sont les marges gauche/haut/droite/bas. Elles peuvent être non nulles ; le
  client doit les accepter, pas les supposer à zéro.
- Le flux est continu : une fois la requête envoyée, le serveur pousse les images.
- Le client peut recevoir d'autres lignes d'état sur la même socket ; il doit les ignorer
  sans fermer la connexion.

C3 Twin décode avec `MediaCodec` (`video/avc`) directement dans une `SurfaceView`.

## Protocole tactile (51848)

Le client ouvre la socket et envoie des messages ASCII de 128 octets, complétés par des zéros :

```
STATE:DOWN#x:y#
STATE:MOVE#x:y#
STATE:UP#x:y#
```

`x` et `y` sont dans l'espace de l'image CarPlay (largeur × hauteur du header vidéo), pas dans
celui de l'écran Android. C3 Twin met à l'échelle les coordonnées du `SurfaceView` vers cet
espace.

Le serveur route ensuite vers `CarPlay_Touch_Event`, `AA_touch_event` ou `CarLife_Touch_Event`
selon le mode actif.

## Workflow : afficher et lancer une app

1. C3 Twin ouvre la socket vidéo 51818 et envoie `REQUEST FRAME`.
2. `z-link` reçoit le flux CarPlay de l'iPhone (AirPlay en sans-fil, NCM en filaire) et le
   renvoie en H.264.
3. C3 Twin décode avec `MediaCodec` et affiche la grille CarPlay.
4. Un tap sur une icône envoie `STATE:DOWN#x:y#` puis `STATE:UP#x:y#` sur 51848.
5. `z-link` traduit en rapport HID CarPlay (`CarPlay_Touch_Event` →
   `HIDTouchScreenFillReport`) et l'envoie à l'iPhone.
6. L'iPhone interprète le tap, **lance l'app sur le téléphone**, rend son UI CarPlay et
   renvoie le flux H.264.
7. C3 Twin affiche la nouvelle image.

Il n'y a donc **pas de commande « ouvre l'app X »** : on envoie des coordonnées, et c'est
l'iPhone qui décide. Conséquences :

- seules les apps compatibles CarPlay apparaissent dans la grille et peuvent être lancées ;
- les coordonnées sont dans l'espace de l'image CarPlay (largeur × hauteur du header), pas
  dans celui de l'écran Android ;
- pour ouvrir une app précise depuis un bouton du dashboard, il faudrait connaître la
  position de son icône (fragile) ou utiliser les requêtes UI internes du moteur
  (`CarPlayRequestUI_map/music/...`), qui ne sont pas exposées sur la socket Fox.

## Audio (implémenté)

- `AudioStream` se connecte à **51858** (audio principal : musique, podcasts, appels) et
  **51888** (audio système : Siri, navigation), envoie `REQUEST FRAME`, lit l'en-tête
  `AUDIO#TYPE:PCM#SAMPLE:…#LEN:…#` et joue le PCM 16 bits avec `AudioTrack`.
- Le format est recréé à la volée si le sample rate ou le nombre de canaux change (appel
  8 kHz mono, par exemple).
- `ProjectionActivity` demande le focus audio (`AUDIOFOCUS_GAIN`) et le rend en quittant.

## Ce qui reste à brancher

- **Micro** : port 51898. Le démon `z-link` gère déjà le micro de l'autoradio ; à vérifier
  sur l'unité avant d'ajouter un `AudioRecord`.
- **Audio alternatif** : port 51868 (`AUDIO ALT FRAME LOST#`), à identifier.
- **Touches physiques** : branchées, mais **format non confirmé**. `ZlinkStream.sendKey` ouvre le
  port 51838 et envoie `KEY VALUE:<code>#` (128 octets complétés par des zéros) à chaque appui,
  avec le code de touche Android : 87 suivant, 88 précédent, 85 lecture/pause, 126/127 lecture
  et pause, 79 bouton casque, 5/6 décrocher/raccrocher, 231/84 assistant. Le texte « KEY VALUE: »
  n'apparaît pas en clair dans `libzjL10001.so` (les messages Fox y sont construits à la volée) :
  le format et les codes attendus restent à vérifier sur l'autoradio. Les touches arrivent par
  `dispatchKeyEvent` quand l'écran CarPlay est devant, et par une `MediaSession` active pendant
  la projection pour celles que le firmware envoie à la session média.
- **Validation sur l'autoradio** : les ports et le format viennent de la version 4.0.41.11.
  Les notes de version du firmware QF001 parlent de « zlink5 » : la carte est peut-être
  différente. Le relevé ci-dessous le dit.
- **Pas encore géré** : zoom à deux doigts (le protocole tactile connu ne porte qu'un point),
  mode nuit (`CarPlay_RequestNightMode` existe dans le moteur, mais pas sur une socket Fox connue).

## Fait côté app

- **Baisse de la musique** : quand le canal système (51888 : Siri, GPS) joue autre chose que du
  silence, le canal principal descend à 25 % en 0,3 s, puis remonte doucement quand la voix se
  tait depuis 0,45 s (`ProjectionActivity.duck`, `AudioStream.speaking`).
- **Marges** : l'image est mise à l'échelle sur sa zone utile (cadre moins `LM/TM/RM/BM`), les
  marges sortent de l'écran du côté qui limite. La `SurfaceView` garde le cadre entier, donc le
  tactile reste en coordonnées du cadre. L'app journalise où tombe le cadre :
  `Projection: image 640x360 marges 8/4/8/4 écran 15,40 1222x687`.
- **Panneau voiture** : la WebView est détachée et détruite à la fermeture de l'écran.

## Relevé sur l'autoradio

`autoradio/sonde_qf001.sh` (à la racine du dépôt) relève ZLink à l'étape 4. Pour ne faire
que cette partie, en deux minutes :

```bash
./autoradio/sonde_qf001.sh <IP_AUTORADIO> zlink
```

Dans l'archive, `zlink/` contient :

| Fichier | Contenu |
|---|---|
| `version.txt` | version, chemin de l'APK, ABI |
| `ports_ecoute.txt` | ports TCP en écoute et l'app qui les ouvre ; les ports 518xx sont marqués « Fox ZLink » |
| `processus.txt` | le démon `z-link` et les processus ZLink |
| `fichiers.txt`, `fichiers/` | binaires et scripts ZLink trouvés sur `/system`, `/vendor`… (`zlink5.sh`, `libzj*`) |
| `apk/` | l'APK installé avec ses bibliothèques, pour refaire la carte des ports sur `libzjL10001.so` |
| `fox_sonde.json`, `fox_<port>.bin` | si l'iPhone est connecté et qu'on accepte : 3 à 5 s de chaque canal vidéo et audio, en-têtes et premiers octets |

La lecture du flux passe par `autoradio/sonde_zlink.py`. Elle n'envoie que `REQUEST FRAME`
sur les canaux vidéo et audio, comme l'app, et rien sur le tactile, les touches ou le micro.
Testée contre `tools/fox_fixture.py` sur l'émulateur : 4 ports Fox trouvés, en-têtes vidéo
H.264 et audio PCM lus.

## Test sans autoradio

L'émulateur ne peut pas parler à un iPhone. On simule le démon ZLink avec un serveur local :

```bash
# terminal 1 : mire H.264 + serveur tactile
python3 tools/fox_fixture.py

# terminal 2 : l'APK sur l'émulateur
adb reverse tcp:51818 tcp:52818
adb reverse tcp:51848 tcp:52848
./build.sh
adb install -r build/C3Twin.apk
adb shell am start -n fr.dabi.c3twin/.MainActivity
# puis appuyer sur CarPlay dans le dock
```

Le script `tools/test_projection.sh` enchaîne tout ça et capture l'écran.

`tools/test_robustesse.sh` (émulateur démarré) lance le faux ZLink avec une voix système qui
parle 3 s sur 6, un passage de l'audio en 44,1 kHz mono à 12 s et une coupure vidéo à 22 s,
puis vérifie 13 points : position du doigt avec les marges, canal touches et touches
suivant / lecture-pause, baisse et retour de la musique, changement de format suivi, reconnexion
après coupure, et 8 allers-retours tableau de bord ↔ CarPlay sans redémarrage, plantage, page web
oubliée ni mémoire qui gonfle de plus de 30 %. Dernier passage : 13/13 (pages web 10 → 5,
mémoire 143 → 152 Mo).
