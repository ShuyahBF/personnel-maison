# Personnel de maison (Android)

Suivi des **arrivées et départs du personnel de maison** (aide ménagère, gardien, laveur de véhicule, cuisinier(ère), chauffeur…), fiche de chaque personne, sociétés de placement, bilans et évaluations. Tout reste **sur le téléphone, chiffré**.

## Version 1.3
- **Téléphones** : jusqu'à **3 numéros** par personnel et par société, un **numéro par défaut**, et un pictogramme **Appeler** (ouvre le clavier d'appel du téléphone avec le numéro) et **Copier**.
- **Horaires par jour** : chaque jour de la semaine a ses propres heures (samedi plus court, jour de repos…) ; retards, départs anticipés et bilans utilisent l'horaire DU JOUR. Les anciennes fiches sont converties automatiquement.
- **Bonus et retenues** sur salaire : saisie datée avec motif, **net à payer du mois** (salaire net + bonus − retenues) sur la fiche et dans « Bilans ».
- **Tâches habituelles** de chaque personnel : liste, ajout depuis les tâches usuelles de sa fonction (liste déroulante, « Autre… » libre).
- **Contrat de prestations des sociétés** : photographier (une photo par page) ou joindre une image ou un PDF ; copies **chiffrées**, lues dans l'application, captures d'écran interdites pendant l'affichage.
- Base de données en **version 3** : migration automatique, données conservées.

## Protection des données dans les témoignages (version 1.2)
- Seuls des **avis sur les sociétés de placement** peuvent être partagés (note, période, remarque), jamais de donnée sur le personnel.
- La remarque étant un texte libre, un **contrôle** repère les mots qui sont le nom ou un prénom d'un personnel enregistré : à l'enregistrement d'une évaluation « partageable » et **avant chaque partage** de témoignage. L'application affiche les mots trouvés et propose de corriger ou de garder (faux positif possible).
- Rappel sous le champ « Remarque » : « Ne citez aucun nom de personne ».

## Pointage très simple (version 1.1, pour personnes âgées ou à vue réduite)
- **Un grand bouton** « Noter une arrivée ou un départ » → **3 questions, une par écran** : *Qui ?* (clic sur le nom) → *Arrivée ou départ ?* (deux gros boutons, le plus probable est « proposé ») → *Quand ?* (date et heure **proposées d'après l'horloge du téléphone**, modifiables : ± 5 min, heure, date) → vérification → **Enregistrer**.
- **Horodatage** : chaque création, modification ou suppression est enregistrée dans un **journal** avec la date et l'heure de l'enregistrement (« corrigé le 11/11/2026 à 14:32 »).
- **Rappel d'un pointage pour correction** : bouton « Corriger un pointage » (60 derniers jours), changer ou effacer l'arrivée et le départ, retard signalé, absence, motif, historique horodaté de la journée.
- **Texte plus grand** (activé par défaut) pour toute l'application ; boutons pleine largeur avec pictogramme ET texte.

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
