# Personnel de maison (Android)

Suivi des **arrivées et départs du personnel de maison** (aide ménagère, gardien, laveur de véhicule, cuisinier(ère), chauffeur…), fiche de chaque personne, sociétés de placement, bilans et évaluations. Tout reste **sur le téléphone, chiffré**.

## Fonctions (version 1.0)
- **Fiche du personnel** : nom, prénoms, fonction (liste + « Autre… »), société, dates de début et de fin, horaires contractuels et jours travaillés, salaire net à payer, téléphone, **copie de la pièce d'identité avec OCR** (le texte est lu sur le téléphone et propose le nom et les prénoms ; appui sur une ligne lue pour la placer dans « Nom » ou « Prénoms »), **notes et remarques horodatées**.
- **Sociétés de placement** : coordonnées et **numéros Orange Money, Moov Money et Telecel Money** (copier / appeler). Ces numéros sont sur la fiche de la société, pas du personnel.
- **Pointage du jour** : actions manuelles *Arrivée*, *Départ*, *Retard* (avec motif), *Absent*, *Modifier* ; retour possible sur un jour passé. Le retard est aussi calculé : arrivée après l'horaire contractuel + tolérance (10 min par défaut, réglable dans « Bilans »).
- **Bilans du mois** : présences, absences, retards (et minutes), heures travaillées par personnel ; partage du bilan par message.
- **Évaluations des sociétés** : journalières ou mensuelles, note de 1 à 5 étoiles et remarque ; chaque évaluation peut être cochée « partageable » ; le bouton **Témoignage** partage un texte (note moyenne + remarques) qui **ne contient aucun nom de personnel**. Base de la future publication sur un site public.

## Sécurité et confidentialité
- Base **chiffrée (SQLCipher)** ; clé aléatoire gardée dans le Keystore Android.
- Copies des pièces d'identité **chiffrées (AES-256)** dans le dossier privé de l'application, absentes de la galerie ; **captures d'écran interdites** pendant l'affichage d'une pièce.
- OCR (ML Kit) **sur le téléphone** : l'image n'est envoyée nulle part. Seul le signal de présence à SAWALI (règle 4) sort : nom de l'application, version, modèle du téléphone, **aucune donnée sur le personnel**.
- Pas de verrouillage par code dans cette version (à ajouter si le téléphone est partagé).

## Compiler
Ouvrir le dossier dans Android Studio, ou `gradle assembleDebug`. Tests : `gradle testDebugUnitTest` (calculs de retards et de bilans, lecture des noms sur la pièce).
