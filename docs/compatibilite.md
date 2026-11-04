# Compatibilité

## Montage de référence

- Citroën C3 II ; le profil des feux a été établi sur ce véhicule.
- Autoradio identifié par ADB comme K706, firmware QF/Jitu (famille QF001),
  plateforme UIS7862S, GPU Mali-G52.
- Runtime observé : Android 10 / API 29. L’étiquette commerciale Android ne suffit pas.
- Service `mcu_service`, interface binder `android.qf.mcu.IMcuManager` et application
  `com.qf.vehicle` présents. Ces interfaces sont propres au firmware, pas au SDK Android.
- Boîtier Raise configuré pour le véhicule dans le firmware.
- Zlink `com.zjinnova.zlink`, version 5.4.61, déjà capable de connecter l’iPhone.
- ADB réseau root accessible sur le port 9876 dans cette configuration.
  Ce port et cet accès ne sont pas garantis sur un autre autoradio.

## Ce qui est portable et ce qui ne l’est pas

L’interface Android exige au minimum API 26 et une WebView capable de rendre WebGL.
Le dashboard peut se charger sans MCU ; cela ne prouve pas que les données voiture fonctionnent.
Le code inclut des branches Raise et Hiworld, mais seule une partie du profil Raise
est validée sur cette C3. Ne pas présenter Hiworld ou FYT comme compatibles sur cette base.

Les positions/croisements/routes/clignotants ont été identifiés par essais physiques.
Les antibrouillards, toutes les alertes, tous les états de portes et la vitesse CAN
nécessitent encore une validation dédiée. Le GPS peut fournir la vitesse en repli.
Les données expirées deviennent indisponibles ; un champ inconnu n’est pas une preuve d’état éteint.

Zlink reste responsable du Wi-Fi/Bluetooth, de la session, du tactile et du son.
Sans Zlink, l’application ne crée pas de session CarPlay. Les applications iPhone
restent dans Zlink ; le tiroir C3 Twin liste les applications Android installées.

## Avant de porter sur un autre matériel

Relever modèle, API réelle, services MCU, boîtier CAN et launcher d’origine.
Vérifier les données en lecture avant de modifier le profil. Ne pas écrire dans
un port série ni remplacer les applications constructeur pour obtenir un signal.
Les opérations sur le launcher doivent rester réversibles ; conserver ADB et le launcher d’origine.
