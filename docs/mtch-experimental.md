# APK MTCH expérimentale — analyse puis validation

Version 0.24-mtch-experimental (code 24). Même identifiant et même certificat que
l'APK publique précédente : installer en mise à jour, sans désinstaller C3 Twin.
Aucune installation sur le MTCH réel n'a été effectuée pendant la préparation.

## Utilisation sur l'autoradio

1. Installer l'APK et ouvrir C3 Twin, véhicule à l'arrêt.
2. Ouvrir **Signaux** dans le menu du bas. Les sources disponibles sont détectées.
3. Choisir une porte ou un feu. Laisser les autres éléments immobiles.
4. Capturer fermé/éteint, puis ouvert/allumé, puis le retour fermé/éteint.
5. Choisir un candidat si plusieurs sont proposés. Vérifier dans l'aperçu qu'il suit
   seulement l'élément choisi ; recommencer pour écarter les coïncidences.
6. Pour un clignotant, capturer la phase allumée puis préciser si la valeur suit
   chaque flash ou reste active pendant toute l'activation du commodo.
7. Recommencer pour chaque élément utile, puis **Accepter la configuration** et
   **Valider et enregistrer**. Le profil est appliqué et conservé après fermeture,
   redémarrage Android et mise à jour de l'APK (même signature).

Fermer sans accepter abandonne le brouillon et conserve le profil déjà validé.
Pour corriger, rouvrir Signaux ; retirer un champ ou refaire son apprentissage puis
valider. Accepter un profil vide efface les associations. Désinstaller l'application
ou effacer son stockage supprime naturellement ses préférences.

Les valeurs instantanées ne sont pas sauvegardées : au redémarrage les champs restent
inconnus jusqu'à réception. Une source sync silencieuse pendant dix secondes expire.
La clé de réglage portes est relue toutes les 500 ms ; sa présence ne prouve pas que
le firmware la maintient à jour. Vérifier physiquement avant de valider.

## Si rien ne remonte

Utiliser **Exporter le diagnostic…**, choisir où enregistrer le fichier texte, puis
le transmettre après vérification. Il contient les états de détection des services,
les candidats, les associations et jusqu'à 120 changements bruts, avec une durée
monotone plutôt qu'une heure/localisation. Il ne collecte pas les comptes, le numéro
ADB, le VIN ou la plaque. Les octets bruts restent à examiner avant publication.
Aucun envoi réseau automatique. L'historique brut est en mémoire, perdu à l'arrêt
complet du processus ; exporter avant de quitter si nécessaire.

## Portée et protection du chemin QF

- Si `mcu_service` ou `com.qf.vehicle` est présent : chemin QF existant, aucune
  calibration MTCH appliquée. Le bouton Signaux confirme le mode QF.
- Sinon : mode expérimental passif, y compris sur un appareil non reconnu. Cela ne
  constitue pas une identification certifiée du MCU.
- Sources candidates : broadcast `com.microntek.sync` / octets `syncdata` et clé
  système numérique `com.microntek.controlinfo.door` (représentation little-endian).
- Le prototype ne s'abonne pas au Binder MTC à partir de numéros de transactions
  anciens non confirmés et n'ouvre aucun port série. Aucun réglage CAN constructeur,
  firmware ou launcher n'est modifié.
- Les associations sont des bits calibrés localement, pas un profil C3/MTCH validé.
  Les trames chiffrées, champs multioctets et formats structurés différents peuvent
  nécessiter un nouveau décodeur après analyse du rapport.
- Seuls portes/coffre/capot, position/croisement/route et clignotants sont calibrables.
  Les autres voyants ne sont pas pris en charge par ce prototype.
- Le rendu neutre d'un champ inconnu ne signifie pas fermé/éteint. Le bandeau rappelle
  le mode expérimental ; l'aperçu Signaux distingue explicitement inconnu/inactif/actif.

## Vérification locale

`./tools/check.sh` compile l'APK, rejoue les feux QF et teste les bits de calibration.
`./tools/test-mtch-emulator.sh emulator-PORT` ne cible que les émulateurs : il vérifie
réception synthétique, brouillon séparé, refus de profil invalide, péremption et
restauration du profil dans un nouveau processus. Ces tests ne remplacent pas un essai
sur le firmware MTCH_MX_V4.09_1.
