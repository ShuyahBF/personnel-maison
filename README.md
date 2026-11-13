# Personnel de maison (Android)

Suivi des **arrivées et départs du personnel de maison** (aide ménagère, gardien, laveur de véhicule, cuisinier(ère), chauffeur…), fiche de chaque personne, sociétés de placement, bilans et évaluations. Tout reste **sur le téléphone, chiffré**.

## Version 1.25 — « Où suis-je ? » (SAWALI lot 388, « Vous êtes ici »)
- Nouvel écran **Plus → Où suis-je ?** : le téléphone écoute les balises Bluetooth FIXES posées dans les lieux (rayon, couloir, salle), repère la plus proche dont SAWALI connaît le lieu et propose d'ouvrir la **page publique du lieu** (ou l'ouvre automatiquement si on active l'option).
- Les lieux sont créés dans **SAWALI** par l'administrateur ou le **superviseur** (Paramètres → « Lieux — Vous êtes ici », ou menu « Lieux (balises) ») : nom, texte, liens utiles, identifiant de la balise (visible dans « Test balises Bluetooth »), QR code à imprimer.
- Anti-rebond de 6 dB entre deux lieux voisins ; une balise inconnue de SAWALI n'est redemandée qu'après 2 minutes ; seule la balise la plus proche est demandée à SAWALI.
- Sans l'application, le visiteur scanne simplement le QR code collé près de la balise (tous téléphones, rien à installer).

## Version 1.24 — Test NFC : lien d'ordonnance de test corrigé
- Le contenu « Ordonnance (lien de test) » écrivait un lien vers `api.sawalismartsystems.com`, où la page des ordonnances n'existe pas. Il pointe maintenant vers le **site** (`https://sawalismartsystems.com/officine/ordonnance/TEST-…`), comme les vraies étiquettes d'ordonnance de SAWALI.

## Version 1.23 — Test NFC : « Trier mes cartes » et fin de la lenteur de « Aide et à propos »
- **Correction de la lenteur** (constat du propriétaire : affichage et défilement très lents dans « Aide et à propos ») : l'accès aux préférences chiffrées était **recréé à chaque lecture** (clé maîtresse, jeu de clés : des dizaines à des centaines de millisecondes), et cet écran en relit une quinzaine toutes les 2 secondes. L'accès est maintenant créé **une seule fois** et gardé en mémoire : tous les écrans qui lisent ces réglages en profitent.
- Nouvelle action **« Trier mes cartes »** dans l'écran « Test NFC (cartes vierges) » : le lecteur reste ouvert, on pose les cartes UNE PAR UNE ; pour chacune l'écran affiche son **identifiant** et son **état** en grand et en couleur (VIERGE non formatée, formatée VIDE, contient des données, lecture seule, non inscriptible) avec un résumé du contenu.
- La liste des cartes lues est **filtrable** (ex. n'afficher que les vierges) et on peut **donner un nom** à chaque carte (mémorisé sur le téléphone, retrouvé à la lecture suivante) ; bouton « Copier la liste ». Rien n'est écrit pendant le tri.
- Pour retrouver une carte parmi d'autres sans signe extérieur : poser toutes les cartes, filtrer « VIERGE », nommer chacune (« Carte A »…), puis marquer la carte physiquement.

## Version 1.22 — Récepteur de badges (présence par balises, SAWALI lot 387)
- Nouvel écran **Plus → Récepteur de badges** : ce téléphone, posé et branché dans une pièce, écoute les balises Bluetooth (Estimote, iBeacon, Eddystone-UID) et envoie à SAWALI, toutes les 5 secondes, les badges entendus et leur signal ; SAWALI montre qui est dans quelle zone (Paramètres → « Présence par balises »). Un envoi « à vide » toutes les 30 s indique que le récepteur est vivant.
- **Service au premier plan** avec notification permanente (bouton Arrêter) ; processeur gardé éveillé. Les autres appareils Bluetooth (téléphones, écouteurs) ne sont **jamais** envoyés.
- Réglages (adresse du serveur, identifiant `site:récepteur`, clé) donnés par SAWALI à la création du récepteur ; clé gardée dans les préférences chiffrées.
- Contrat d'échange v1 (le même pour un futur récepteur Windows) : `POST /api/presence-balises/v1/detections`.

## Version 1.21 — Laboratoire NFC : jauge de progression, temps écoulé et conseils
- **Jauge de progression** (0 à 100 %) avec l'étape en cours et le **temps écoulé** pendant un test (lecture, écriture, effacement). Durée habituelle : 1 à 3 secondes une fois la carte détectée.
- **Conseil automatique** : sans carte détectée après 8 s, l'écran explique qu'Android ne détecte qu'une **nouvelle présentation** (éloigner puis reposer la carte, antenne au dos vers le haut, pas d'étui épais) ; au-delà de 20 s en cours d'écriture, il demande de garder la carte immobile.
- Une **erreur inattendue** pendant le test est maintenant écrite dans le journal (avant, elle pouvait être avalée et « rien ne se produisait »).

## Version 1.20 — Laboratoire NFC : carte vierge Mifare Classic vérifiée à la présentation suivante
- Correction : sur une carte VIERGE (ex. Mifare Classic 1K), le test d'écriture affichait « Test échoué » alors que l'écriture avait réussi : Android ne voit la carte comme « NDEF » qu'après le formatage. Maintenant, après « Formatage et écriture », l'écran demande d'**éloigner puis reposer la carte** ; elle est alors **relue et comparée octet par octet** automatiquement (« Test réussi » ou « Test échoué »).
- Correction (constat du 13/11/2026 sur une Mifare Classic 1K vierge) : le laboratoire démarrait la lecture avec l'option « ignorer le contrôle NDEF », si bien qu'Android ne signalait plus la carte comme formatable (« Cette carte ne prend pas en charge NDEF »). L'option est retirée : la carte vierge est reconnue et formatée.
- Les cartes déjà formatées (NTAG…) gardent la vérification immédiate.

## Version 1.19 — Messagerie : le texte tapé reste visible quand le clavier monte
- Correction : quand le clavier s'ouvrait, le panneau de discussion était écrasé (sa hauteur de 60 % était encore réduite par une marge « clavier » en double) et la zone de saisie n'affichait plus le texte tapé (seul le curseur clignotait). Le panneau garde maintenant **au moins 300 dp de hauteur** (ou toute la place disponible), la marge en double est supprimée, la zone de saisie a une hauteur minimale d'une ligne complète et une couleur de texte explicite.

## Version 1.18 — Date/heure et batterie sur une seule ligne
- Dans les barres de titre, la date/heure et le niveau de batterie sont maintenant **sur la même ligne** (« 13/11 16:07 · 🔋 57 % »).

## Version 1.17 — Messagerie permanente : pilule qui clignote et son à l'arrivée d'un message
- La pastille de la messagerie est redessinée en **pilule « Messages »** (bleue) toujours visible en bas à gauche ; elle devient une **pilule rouge qui clignote** (opacité et taille) avec « N nouveau(x) message(s) » tant qu'il y a des messages non lus.
- **Bruit à l'arrivée d'un nouveau message** (son de notification du téléphone) quand l'application est ouverte, et **l'en-tête du panneau clignote en orange** ; **pictogramme haut-parleur** dans le panneau pour couper ou rétablir le son (mémorisé). Les anciens messages non lus au démarrage ne sonnent pas ; vos propres messages jamais.
- **Toujours affichée** : si le serveur est injoignable, la pilule devient grise « Messages · hors ligne » au lieu de disparaître ; le panneau se **réduit** avec la flèche (la discussion est conservée) et se ferme avec la croix. Application fermée : notification du système (veille, toutes les 5 minutes environ).

## Version 1.16 — Diagnostic de la caméra, barre d'état, messagerie permanente, Aide enrichie
- **Caméra partagée : diagnostic en direct** (Ma famille → Ma caméra → Diagnostic) : service démarré, autorisation de la caméra, proches cochés, mode, dernier tour, **étape en cours** (« personne ne regarde », « prise de la photo », « envoi chiffré »…), **dernière erreur avec sa cause** (caméra utilisée par une autre application, désactivée par le système, délai dépassé, envoi refusé…), nombre d'images envoyées. Bouton **Tester la caméra (sans envoi)** : prend une photo ici et l'affiche, pour séparer un problème de caméra d'un problème de réseau. Côté spectateur : signal « je regarde » envoyé, réponse du serveur, et ce que doit vérifier l'autre téléphone. Le démarrage refusé par le système est maintenant signalé au lieu de planter.
- **Barre d'état dans toutes les barres de titre** : **date/heure** et **niveau de batterie** (⚡ en charge, orange à 15 % ou moins), relus toutes les 15 secondes.
- **Messagerie des proches en permanence** (mêmes spécifications que le support SAWALI d'aurOR) : **pastille ronde en bas à gauche** sur tous les écrans, visible seulement **connecté au serveur** avec au moins un proche vérifié ; **pastille rouge** des messages non lus ; un appui ouvre un **panneau (60 % de l'écran)** ; la **flèche** le replie sans perdre la discussion ; la **croix** le ferme et vide l'historique local (la conversation reste chiffrée sur le serveur) ; messages chiffrés de bout en bout, rappel de ne jamais écrire de mot de passe.
- **Aide et à propos** : **version d'Android, niveau d'API et modèle du terminal** affichés (sur chaque appareil), et pictogramme **Rafraîchir** (barre de titre) qui relit tous les réglages de l'écran, serveur compris, sans quitter ni relancer l'application.

## Version 1.15 — Test des balises Bluetooth, et lien « Ma famille » plus clair
- **Plus → Test balises Bluetooth** : détection **en direct** des balises (Estimote, iBeacon, Eddystone ; option « tous les appareils Bluetooth ») avec identifiant (UUID / major / minor, espace / instance, identifiant Estimote), **signal lissé (dBm)**, **distance estimée**, temps depuis la dernière annonce, détails et octets bruts d'une balise (appui sur la ligne), **nom donné à chaque balise** (Awa, Cuisine…). Un suivi « présente / absente » avec un délai réglable (30 s à 5 min) écrit un **journal d'arrivées et de départs** : simulation du pointage automatique du personnel (rien n'est enregistré dans les pointages). L'écoute ne tourne que pendant que l'écran est ouvert ; journal copiable.
- **Lien « Ma famille » plus clair** (retour d'essai) : le code d'invitation affiche un **compte à rebours** (15 minutes) et **disparaît quand il a servi ou expiré** (avant, un code déjà utilisé restait affiché) ; bouton **Nouveau code** ; quand un proche accepte, ce téléphone **ouvre tout seul la vérification du lien** et une **notification** « Nouveau lien à vérifier » est envoyée ; un **bandeau « À vérifier »** en haut de l'écran Ma famille explique où comparer le numéro de sécurité et confirmer ; message d'erreur « code inconnu » plus explicite.

## Version 1.14 — Test NFC : écrire, lire, effacer une carte vierge en suivant chaque étape
- **Plus → Test NFC (cartes vierges)** : simulation sur une carte NFC vierge, avec un **journal horodaté, étape par étape, à l'écran** : carte détectée (identifiant), technologies, famille (SAK), **type et capacité** (NTAG213 / 215 / 216, Ultralight, Mifare Classic, carte sécurisée non inscriptible), état NDEF (vierge, formatée, inscriptible ou lecture seule, capacité), contenu actuel, message préparé (enregistrements et taille), écriture ou formatage avec sa durée, puis **vérification par relecture** (comparaison octet par octet) et résultat final.
- **Trois actions** : écrire (contenus de TEST : lien d'ordonnance factice, carte marchand non signée, étiquette médicament de simulation, texte libre), lire (inspection sans rien écrire), effacer (remise à blanc). **Aucun verrouillage** de carte n'est proposé (irréversible). Bouton **Copier le journal** pour l'envoyer.
- Aucune donnée réelle n'est écrite ; messages d'échec clairs (carte en lecture seule, trop petite, non inscriptible, écriture interrompue).

## Version 1.13 — Étiquette NFC des repas, Aide et à propos
- **Étiquette NFC des repas** (écran Repas) : une étiquette collée sur la table ou le réfrigérateur déclare **« Je prends mon repas »** au 1er contact et **« Repas terminé »** au 2e ; elle peut être liée à un repas précis ou « **selon l'heure** » (le repas suivi le plus proche de l'heure actuelle, de 2 h 30 avant à 1 h 30 après). Les proches choisis sont prévenus. Contenu : un identifiant seulement.
- **Plus → Aide et à propos** : version, et **liste des réglages à faire**, cochés automatiquement quand l'application peut les vérifier (économie de batterie, alarmes exactes, notifications, NFC, caméra, localisation, GPS, compte famille, proche vérifié, domicile) avec un bouton « ▶ » qui ouvre le bon réglage ; la liste est relue toutes les 2 secondes (on peut aller dans les réglages et revenir). Réglages Xiaomi (démarrage automatique, verrouillage en mémoire) à cocher soi-même. Rubrique « À savoir » : téléphone verrouillé, redémarrage, données mobiles, veille, changement de téléphone, étiquettes.

## Version 1.12 — Étiquette NFC des médicaments
- Dans la fiche d'un médicament enregistré : **Étiquette NFC → Écrire une étiquette**. On approche une étiquette NFC vierge (autocollant ou carte NTAG213 ou plus) du dos du téléphone ; elle est écrite (ou formatée) et collée sur la boîte de médicaments.
- **Poser le téléphone sur l'étiquette confirme la prise** (même application fermée, téléphone déverrouillé) : la prise la plus proche de l'heure actuelle (de 6 h avant à 90 minutes après son heure prévue) est confirmée, le rappel disparaît et les proches choisis sont prévenus. Un message indique la prise confirmée.
- **Confidentialité** : l'étiquette ne contient qu'un identifiant (« pm-med », code de l'installation, numéro du médicament) : **aucun nom de médicament ni donnée de santé**. Une étiquette écrite par un autre téléphone est ignorée.
- Messages clairs si l'étiquette est en lecture seule, trop petite ou n'est pas une étiquette inscriptible (carte bancaire, de transport) ; l'application s'ouvre en mode « une seule fenêtre » (singleTask).

## Version 1.11 — Ma famille : repas partagés et repas non pris
- Nouvel écran **Plus → Repas** : petit-déjeuner, déjeuner, dîner (et collation, désactivée par défaut) avec leur **heure** réglable et un interrupteur chacun. Les repas d'aujourd'hui affichent leur état (à venir, à déclarer, en train de manger, terminé, non déclaré) et les boutons **Je mange** et **Terminé**.
- **Rappel à l'heure du repas** avec le bouton « Je prends mon repas » ; **si le repas n'est pas déclaré 60 minutes plus tard**, les proches choisis reçoivent « Awa n'a pas encore pris son déjeuner ».
- **Partage** : « Je prends mon repas », « Repas terminé » et « repas non pris » sont envoyés, **chiffrés de bout en bout**, aux seuls proches cochés (Ma famille → le proche → « Partager mes repas »). La fiche d'un proche affiche ses derniers événements de repas.
- **Veille des proches** : « Awa prend son déjeuner » (notification discrète) et « ⚠ Repas non pris » (alerte). Serveur : événements chiffrés de type « repas » (lot 381, aucun changement côté serveur).

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
