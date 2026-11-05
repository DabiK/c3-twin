# Portage vers un autoradio identifié PX5

État au 5 novembre 2026 : investigation, aucun APK PX5 validé.

Voir [la comparaison technique et les projets publics examinés](recherche-mtch.md).

## Diagnostic initial

PX5 désigne une plateforme matérielle, pas une identification suffisante du firmware.
La seconde photo identifie le MCU : **MTCH_MX_V4.09_1**, daté du
22 décembre 2023 à 19:07:46. La famille à investiguer est donc MTCH, variante MX,
et non QF. La marque commerciale et les interfaces Android effectivement présentes
restent à relever. Le noyau affiché est `4.19.111+`, compilation du 26 mars 2024 ;
ces informations ne valident pas à elles seules le niveau API Android annoncé.
La même C3 II peut utiliser un autre boîtier CAN, un autre profil et une autre interface
Android. L'absence simultanée de portes et de feux suggère de vérifier d'abord la
chaîne de réception avant de changer le décodage des bits.

Le lecteur actuel utilise `mcu_service`, `android.qf.mcu.IMcuManager` et
`com.qf.vehicle.action.DATA_SHARE`. Ces interfaces sont spécifiques QF.
Installer l'APK sur un autre Android ne rend pas ces interfaces disponibles.

## Informations à obtenir du propriétaire

- Version du firmware Android ; MCU déjà identifié ci-dessus. Masquer le numéro de série.
- Marque/référence du boîtier CAN et profil véhicule sélectionné, si accessibles.
- À l'arrêt, vérifier si l'application constructeur indique correctement une porte
  ouverte, puis les feux et clignotants séparément. Un popup désactivé ne prouve pas
  l'absence de données : vérifier aussi l'écran véhicule constructeur.
- Si nécessaire, inventaire en lecture seule via ADB sur une cible explicitement
  identifiée : API Android réelle, services MCU et applications constructeur.
  Ne pas publier de journaux complets, VIN, numéros de série ou APK constructeur.

Si les portes apparaissent dans l'application constructeur, leur chemin matériel
fonctionne au moins jusque-là : rechercher alors l'interface utilisable par C3 Twin.
Cela ne garantit pas que le firmware expose aussi les clignotants à une application tierce.
Si aucune donnée n'apparaît côté constructeur, vérifier boîtier, raccordement et
configuration avant de développer un adaptateur.

## Pistes documentées, à confirmer sur l'appareil

Le fabricant M.I.C. répertorie des appareils PX5 avec MCU MTCE et demande d'identifier
la famille à partir de la ligne « MCU-Version ». Cela ne signifie pas que l'appareil
signalé est un M.I.C. La seconde photo confirme ici MTCH, pas MTCE ; ne pas supposer
que leurs interfaces sont identiques sans inspection de l'appareil.

Un code de firmware Microntek décompilé montre `CanBusServer`, un objet `Door` et
des broadcasts comme `com.canbus.temperature`. C'est une piste d'interface distincte
de QF, pas un contrat garanti sur tous les PX5. Le profil `dd` consulté n'est pas
identifié ici comme un profil C3 et ses bits ne doivent pas être repris à l'aveugle.

Sources consultées :

- [Identification MCU chez M.I.C.](https://www.mic-autoradio.com/updates/autoradio/mcu/)
- [Exemple de décodeur Microntek](https://github.com/xbenjii/mtccanbus-decompiled/blob/master/android/microntek/canbus/c/dd.java)

## Architecture proposée

Conserver le dashboard et son contrat JSON communs ; séparer les sources matérielles
en adaptateurs QF et, si confirmé, MTC. Détecter les services réellement disponibles,
avec sélection explicite en cas d'ambiguïté. Un matériel non reconnu doit rester
signalé comme tel ; ne pas transformer une donnée inconnue en état fermé/éteint.

Privilégier un APK commun lorsque les adaptateurs peuvent coexister. Des APK par
famille de firmware restent possibles si les dépendances ou permissions l'imposent,
avec une matrice indiquant firmware, boîtier CAN et fonctions testées. Ne pas nommer
un binaire « compatible PX5 » sur la seule base du processeur.

Avant toute release : capturer les événements sur l'appareil avec des manipulations
isolées à l'arrêt, établir des fixtures de décodage, vérifier chaque porte et chaque
type de feu, puis vérifier l'absence de régression QF. Ne pas appliquer les propriétés
launcher QF, réglages de popup QF ou une mise à jour MCU générique à ce nouveau matériel.
