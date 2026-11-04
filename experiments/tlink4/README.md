# Archive TLINK 4 — abandonnée

Ces sources et fixtures appartiennent au prototype de projection CarPlay construit
avant identification du Zlink réel. Elles ne sont **ni compilées dans l’APK actuel,
ni une méthode d’installation**, ni validées sur le K706 équipé de Zlink 5.4.61.

- `android/` : anciennes classes ProjectionActivity, ZlinkStream, AudioStream.
- `tools/` : fixtures et scripts historiques. Les scripts d’installation/test sont
  volontairement arrêtés à leur entrée ; leurs chemins reflètent l’ancienne organisation.
- `protocol-notes.md` : hypothèses et observations TLINK 4 historiques, conservées telles
  quelles sous cet avertissement. Certaines affirmations étaient trop catégoriques.

Décision actuelle : lancer Zlink directement et fournir une pastille retour. Voir
[la démarche](../../docs/demarche.md). Réactiver ces sources nécessiterait une nouvelle
validation du protocole et ne fait pas partie de l’installation de C3 Twin.
