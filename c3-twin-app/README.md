# Application C3 Twin

Le point d’entrée du projet est le [README racine](../README.md).

- [Compiler et installer](../docs/installation.md)
- [Compatibilité matérielle](../docs/compatibilite.md)
- [Architecture et conventions](../docs/architecture.md)
- [Démarche et validations](../docs/demarche.md)
- [Dépannage](../docs/diagnostic.md)
- [Instructions pour agents](../AGENTS.md)

Depuis ce dossier :

```bash
npm ci
./tools/check.sh                         # test feux, syntaxe JS/shell et build APK
./installer_autoradio.sh --help          # aucun accès à un appareil
./installer_autoradio.sh <IP> install    # installation, sans changer le launcher
./installer_autoradio.sh <IP> launcher   # choix explicite du launcher
./installer_autoradio.sh <IP> journal    # journaux privés locaux
```

`build/C3Twin.apk` est signé avec la clé locale `build/debug.keystore`.
Conserver cette clé pour mettre à jour une installation existante sans effacer ses données.
Les anciens tests de projection et les classes TLINK 4 ont été archivés dans
[`experiments/tlink4`](../experiments/tlink4/README.md), hors du code compilé.
