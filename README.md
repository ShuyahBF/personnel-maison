# Personnel de maison (Android)

Suivi des **arrivées et départs du personnel de maison** (aide ménagère, gardien, laveur de véhicule, cuisinier(ère), chauffeur…), fiche de chaque personne, sociétés de placement, bilans et évaluations. Tout reste **sur le téléphone, chiffré**.

## Version 1.10 — Ma famille : caméra partagée
- **Ma caméra** (Plus → Ma famille) : caméra **avant ou arrière**, **une photo toutes les 10 / 15 / 30 / 60 secondes**, mode « **seulement quand un proche regarde** » (par défaut : aucune image ne part tant que personne ne regarde, ce qui économise les données mobiles) ou « en continu ». Démarrage et arrêt par un bouton ; une **notification permanente** (« Arrêter la caméra ») indique que la caméra est partagée.
- **Fiche d'un proche** : interrupteur « Partager ma caméra avec lui » et bouton **Voir la caméra de X** : tant qu'il est actif, le signal « je regarde » est renouvelé toutes les 20 secondes et l'image est relue toutes les 5 secondes (avec son âge).
- Photos **réduites (640 px de large, 30 à 60 Ko), chiffrées de bout en bout** avant l'envoi, jamais écrites sur le téléphone ; le serveur ne garde que la dernière image (effacée à l'arrêt, au retrait du partage, ou après 10 minutes).
- Serveur : SAWALI lot 382. À venir : notifications de prise de repas.

## Version 1.9 — Rappels de médicaments partagés avec la famille
- Nouvel écran **Plus → Médicaments** : nom (liste + « Autre… »), dose, **heures de prise** (plusieurs par jour), **jours** de la semaine, période facultative, note, traitement actif ou arrêté. Les prises d'aujourd'hui s'affichent avec leur état (à venir, rappel envoyé, pris, oublié) et un bouton **Pris**.
- **Rappel à l'heure** (alarme du téléphone, exacte si le système l'autorise) avec les boutons **Pris** et **Plus tard** (retour dans 10 minutes). Les alarmes sont reprogrammées à l'ouverture de l'application et au redémarrage du téléphone.
- **Oubli** : une prise non confirmée **30 minutes** après l'heure prévue est notée « oubliée ».
- **Partage avec la famille** : si le médicament est partagé, la prise ou l'oubli est envoyé, **chiffré de bout en bout**, aux seuls proches cochés dans leur fiche (Ma famille → le proche → « Partager mes rappels de médicaments »). La fiche d'un proche affiche aussi ses dernières prises ou oublis.
- **Veille des proches** (toutes les 5 minutes environ, application fermée) : notification d'alerte quand un proche n'a pas pris un médicament, notification discrète quand il l'a pris, et **nouveaux messages** (« Nouveau message de Awa », sans le contenu sur l'écran verrouillé). Seuls les éléments arrivés après l'activation sont notifiés.
- Base en version 7 (migration automatique) ; serveur : SAWALI lot 381 (événements chiffrés, conservés 30 jours).
- À venir : photo de caméra partagée, notifications de prise de repas.

## Version 1.8 — Ma famille : ajout par NFC et partage de position
- **Ajout par contact NFC** (deux téléphones avec NFC, dos contre dos) : l'un appuie sur « J'invite », l'autre sur « J'ajoute ». Le code d'invitation passe par NFC, ainsi que l'**empreinte de la clé** de chacun ; si elle concorde avec la clé annoncée par le serveur, le lien est **vérifié automatiquement** des deux côtés (le contact physique remplace la comparaison du numéro de sécurité). Sinon, la vérification manuelle reste nécessaire. Le code reste utilisable à la place du NFC.
- **Ma position** : domicile (position actuelle + **rayon du périmètre** 50 m à 1 km), autres lieux (École, Travail… avec nom libre), mode « seulement hors du domicile » (par défaut : envoi **chaque minute** tant que la personne est sortie du périmètre, plus une dernière position au retour) ou « en permanence ».
- **Consentement et visibilité** : la position n'est envoyée qu'aux proches **cochés** dans leur fiche (liens vérifiés), **chiffrée de bout en bout** ; une **notification permanente** signale le partage, avec « Arrêter » ; l'arrêt **efface** la position sur le serveur (elle disparaît aussi après 24 h sans mise à jour).
- **Fiche d'un proche** : interrupteur « Partager ma position avec lui », sa dernière position (« À la maison », « Hors du périmètre du domicile », « À : École », heure, précision) et « Voir sur la carte ».
- Serveur : SAWALI lot 380. À venir : rappels de médicaments partagés, photo de caméra partagée, notifications de repas. Le suivi ne redémarre pas seul après un redémarrage du téléphone (à relancer).

## Version 1.7 — Ma famille (fondations) : liens vérifiés et messagerie chiffrée
- Nouvel écran **Plus → Ma famille** : créer son compte famille, **inviter** un proche (code à usage unique valable 15 minutes, partageable), **saisir un code** reçu.
- **Vérification du lien** : chaque lien affiche un **numéro de sécurité** (30 chiffres) calculé à partir des deux clés publiques ; les deux personnes le comparent de vive voix et le confirment. Tant que les DEUX n'ont pas confirmé, la messagerie reste fermée. Un intermédiaire (même le serveur) qui remplacerait une clé changerait le numéro.
- **Messagerie chiffrée de bout en bout** (courbe P-256 + AES-256-GCM) : le serveur SAWALI ne stocke que du texte chiffré. La clé privée ne quitte jamais le téléphone (préférences chiffrées).
- Rien de ce qui est dans l'application (personnel, portefeuille, profil) n'est envoyé : seulement le nom choisi, la clé publique et les messages chiffrés.
- Serveur : SAWALI lot 379 (`/api/famille/…`). À venir : position et périmètre du domicile, rappels de médicaments partagés, photo de caméra partagée, notifications de repas.

## Version 1.6 — Liste de courses cochable
- Dans le **détail d'une dépense**, chaque élément se **coche** comme une liste de courses (type Google Keep) : un appui sur la ligne ou la case ; l'élément coché est grisé et barré.
- **Deux totaux** : le **total de la liste** et le **total des éléments cochés** (avec le nombre d'éléments et le reste à prendre).
- La coche est conservée quand on corrige l'opération. Base en version 6, migration automatique (lignes existantes décochées).

## Version 1.5 — Portefeuille (dépenses et recettes)
- Nouvel onglet **Portefeuille** : recettes et dépenses du mois, solde, liste par jour, filtres Tout / Dépenses / Recettes.
- **Scanner un ticket de caisse** : la photo est gardée **chiffrée comme preuve**, le texte est lu par l'OCR (sur le téléphone) et **pré-remplit** marchand, date, montant total et lignes d'articles ; rien n'écrase ce qui est déjà saisi.
- **Détail manuel** des courses (article, quantité, prix) avec contrôle de la somme des lignes ; plusieurs pièces par opération.
- **Corriger ou supprimer** : opération, lignes, pièces ; le texte lu de chaque pièce est **modifiable** à côté de la photo, avec « Relire l'image ».
- Marchand ou source (liste des précédents + « Autre… »), catégorie, **portefeuille** (Espèces, Orange Money, Moov Money, Telecel Money, Banque / carte), note.
- **Statistiques** : totaux du mois, évolution sur 6 mois, dépenses par catégorie, principaux marchands, sources de recettes, solde de chaque portefeuille, dépense moyenne par jour, partage du bilan.
- Onglets : Aujourd'hui · Personnel · Portefeuille · Sociétés · Plus (bilans du personnel, statistiques du portefeuille, profil). Base en version 5, migration automatique.

## Version 1.4 — Mon profil
- Nouvel onglet **Profil** : nom, prénoms, **3 numéros de téléphone** (un par défaut), e-mail, **adresse** (rue, quartier, ville, **point de repère**), **position GPS** de la maison, **personne à prévenir en cas d'urgence** et remarques.
- **Géolocalisation** : bouton « Ma position » (autorisation demandée seulement à ce moment, GPS ou réseau, sans Google Play Services) ; affichage des coordonnées et de la précision, **Voir sur la carte**, **Partager** le lien, **Effacer**.
- Jauge « Profil complété à X % » ; l'accueil invite à compléter le profil tant que le nom est vide.
- Le profil reste sur le téléphone (base chiffrée) et n'est **jamais envoyé** ; base en version 4, migration automatique.

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
