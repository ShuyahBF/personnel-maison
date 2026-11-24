package org.shuyah.personnelmaison

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ÉCRAN « SAUVEGARDE EN LIGNE » (Plus → Sauvegarde en ligne ; version 1.35) : mêmes principes que la sauvegarde en ligne d'auRor.
 *  - Activer : on choisit une PHRASE SECRÈTE (12 caractères au moins, saisie deux fois, avec l'œil pour la voir) ; elle est la seule clé de la sauvegarde et ne peut pas être retrouvée si elle est perdue.
 *  - Sauvegarder maintenant, restaurer (la dernière ou la précédente), désactiver et effacer la sauvegarde du serveur.
 *  - Sur un téléphone neuf (après réinstallation) : « Restaurer » avec la même phrase secrète.
 * Chaque action a son pictogramme et sa légende ; chaque action annonce « en cours » puis son résultat (règles du propriétaire). Le serveur ne peut rien lire : tout est chiffré ici.
 */
private fun annoncer(c: android.content.Context, t: String, long: Boolean = false) =
    android.widget.Toast.makeText(c.applicationContext, t, if (long) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT).show()

private fun dateLisible(ms: Long) = if (ms <= 0) "jamais" else SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date(ms))

/** Champ de phrase secrète avec l'ŒIL pour afficher ou masquer la saisie (règle 22). */
@Composable
private fun ChampPhrase(libelle: String, valeur: String, surChangement: (String) -> Unit) {
    var voir by remember { mutableStateOf(false) }
    OutlinedTextField(value = valeur, onValueChange = surChangement, label = { Text(libelle) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (voir) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { IconButton({ voir = !voir }) { Icon(if (voir) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (voir) "Masquer la saisie" else "Afficher la saisie") } })
}

@Composable
fun EcranSauvegarde(retour: () -> Unit) = Page("Sauvegarde en ligne", retour) {
    val c = LocalContext.current
    val portee = rememberCoroutineScope()
    var mode by remember { mutableStateOf(SauvegardeEnLigne.mode(c)) }
    var dernier by remember { mutableLongStateOf(SauvegardeEnLigne.dernierEnvoi(c)) }
    var phrase by remember { mutableStateOf("") }
    var identifiant by remember { mutableStateOf("") }                                     // e-mail ou téléphone : mélangé à la phrase pour que le casier soit UNIQUE à la personne (1.60)
    // Copie de sécurité sur le téléphone (1.61) : "creer" | "lire" = fenêtre ouverte ; octets en attente d'enregistrement ou de lecture
    var copie by remember { mutableStateOf<String?>(null) }
    var octetsCopie by remember { mutableStateOf<ByteArray?>(null) }
    var octetsLus by remember { mutableStateOf<ByteArray?>(null) }
    var copieFaite by remember { mutableStateOf(SauvegardeEnLigne.copieLocaleRecente(c)) }
    val aMigrer = SauvegardeEnLigne.aMigrer(c)
    var autre by remember { mutableStateOf<Pair<String, SauvegardeEnLigne.Existante>?>(null) }   // « activer » | « sauvegarder » + sauvegarde d'un AUTRE appareil trouvée dans le casier
    var confirmation by remember { mutableStateOf("") }
    var changer by remember { mutableStateOf(false) }                                      // fenêtre « Changer mon e-mail ou téléphone » (1.66)
    var nouvelId by remember { mutableStateOf("") }
    var occupe by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmer by remember { mutableStateOf<String?>(null) }        // "restaurer" | "precedente" | "desactiver"
    var apercu by remember { mutableStateOf<SauvegardeEnLigne.Apercu?>(null) }         // contenu vérifié de la sauvegarde, montré avant de remplacer
    val secours = SauvegardeEnLigne.secoursDisponible(c)
    val cloud = SauvegardeEnLigne.actif(c)

    /** Lance une opération réseau : « en cours » tout de suite, puis le résultat (jamais silencieux). */
    fun lancer(enCours: String, travail: suspend () -> SauvegardeEnLigne.Resultat, apres: (SauvegardeEnLigne.Resultat) -> Unit = {}) {
        if (occupe) return
        occupe = true; message = null; annoncer(c, enCours)
        portee.launch {
            val r = travail(); occupe = false; message = r.message; annoncer(c, r.message, true)
            if (r.autreAppareil != null) autre = Pair(if (SauvegardeEnLigne.actif(c)) "sauvegarder" else "activer", r.autreAppareil)
            mode = SauvegardeEnLigne.mode(c); dernier = SauvegardeEnLigne.dernierEnvoi(c); apres(r)
        }
    }

    // Choix de l'emplacement du fichier de copie (Documents, carte SD…) puis écriture : l'application n'a besoin d'AUCUNE autorisation de stockage
    val creerFichier = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val octets = octetsCopie
        if (uri != null && octets != null) {
            portee.launch {
                val r = try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.contentResolver.openOutputStream(uri)?.use { it.write(octets) } ?: throw java.io.IOException("emplacement illisible") }
                    SauvegardeEnLigne.copieLocaleFaite(c); copieFaite = true; SauvegardeEnLigne.Resultat(true, "Copie de sécurité enregistrée (${octets.size / 1024} Ko, chiffrée). Gardez ce fichier en lieu sûr.") }
                catch (e: Exception) { SauvegardeEnLigne.Resultat(false, "Enregistrement du fichier impossible : ${e.message ?: e.javaClass.simpleName}") }
                message = r.message; annoncer(c, r.message, true); octetsCopie = null
            }
        } else { octetsCopie = null; if (uri == null) annoncer(c, "Enregistrement annulé : aucune copie n'a été faite") }
    }
    val ouvrirFichier = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) portee.launch {
            try { octetsLus = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.contentResolver.openInputStream(uri)?.use { it.readBytes() } }; copie = "lire" }
            catch (e: Exception) { message = "Lecture du fichier impossible : ${e.message ?: e.javaClass.simpleName}"; annoncer(c, message!!, true) }
        }
    }

    // 1.65 : « Enregistrer une copie chiffrée » en UN geste quand la configuration est connue (sauvegarde en ligne active, ou e-mail + phrase déjà saisis une fois) ; sinon la fenêtre de saisie
    fun demanderCopie() {
        if (occupe) return
        if (SauvegardeEnLigne.copieConfiguree(c))
            lancer("Préparation de la copie (vérification des données)…", { val (o, r) = SauvegardeEnLigne.preparerCopieMemorisee(c); octetsCopie = o; r }) { r -> if (r.ok) creerFichier.launch(SauvegardeEnLigne.nomCopieLocale()) }
        else { copie = "creer"; phrase = ""; confirmation = "" }
    }

    Carte("Pourquoi sauvegarder ?", Icons.Filled.CloudUpload) {
        Text("Toutes vos données (personnel, pointages, portefeuille, pièces, réglages) sont copiées EN LIGNE, chiffrées sur ce téléphone avec votre phrase secrète. " +
            "Après une désinstallation, un changement de téléphone ou de version, il suffit de retaper la phrase pour tout retrouver. Le serveur ne peut rien lire.", fontSize = 15.sp, color = BleuNuit)
    }

    // SÉCURISER LA SAUVEGARDE EN LIGNE (1.61) : une sauvegarde d'avant la 1.60 n'a pas d'identifiant. Étape 1 : copie de sécurité sur ce téléphone ; étape 2 : e-mail ou téléphone + phrase.
    if (aMigrer) {
        Carte("Sécuriser ma sauvegarde en ligne", Icons.Filled.Shield) {
            Text("Votre sauvegarde actuelle n'est liée à aucun e-mail ni téléphone : une autre personne ayant la même phrase tomberait sur le même casier. En 2 étapes, vos données passent dans un casier qui n'est qu'à vous. Rien n'est perdu : l'ancienne sauvegarde n'est effacée qu'après vérification de la nouvelle.", fontSize = 15.sp, color = BleuNuit)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Icon(if (copieFaite) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null, tint = if (copieFaite) Vert else Gris, modifier = Modifier.size(22.dp))
                Text("  1. Copie de sécurité sur ce téléphone " + if (copieFaite) "(faite)" else "(à faire d'abord)", fontSize = 16.sp, color = BleuNuit)
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Icon(Icons.Filled.RadioButtonUnchecked, null, tint = Gris, modifier = Modifier.size(22.dp))
                Text("  2. Votre e-mail ou téléphone et votre phrase", fontSize = 16.sp, color = BleuNuit)
            }
        }
        if (!copieFaite) GrosBouton(Icons.Filled.SaveAlt, "1. Faire la copie de sécurité", Orange, actif = !occupe) { demanderCopie() }
        else {
            Carte { OutlinedTextField(value = identifiant, onValueChange = { identifiant = it }, label = { Text("Votre e-mail ou numéro de téléphone") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Avec l'indicatif pour un numéro (ex. +226 70 00 00 00).") })
                ChampPhrase("Phrase secrète (la même ou une nouvelle)", phrase) { phrase = it }
                ChampPhrase("Confirmez la phrase secrète", confirmation) { confirmation = it }
                if (phrase.isNotEmpty()) GardeFouPhrase.verifier(phrase, identifiant).raisons.forEach { Text("• $it", fontSize = 14.sp, color = Rouge) }
                if (confirmation.isNotEmpty() && SauvegardeCrypto.normaliser(confirmation) != SauvegardeCrypto.normaliser(phrase)) Text("Les deux phrases ne sont pas identiques.", fontSize = 14.sp, color = Rouge) }
            val pretMigration = SauvegardeCrypto.identifiantValide(identifiant) && GardeFouPhrase.verifier(phrase, identifiant).acceptable && SauvegardeCrypto.normaliser(confirmation) == SauvegardeCrypto.normaliser(phrase)
            GrosBouton(Icons.Filled.Lock, "2. Passer à mon identifiant", Vert, actif = pretMigration && !occupe) {
                lancer("Passage en cours (envoi puis vérification en ligne)…", { SauvegardeEnLigne.migrer(c, phrase, identifiant) }) { if (it.ok) { phrase = ""; confirmation = "" } }
            }
        }
    }
    // COPIE DE SÉCURITÉ SUR LE TÉLÉPHONE : un fichier chiffré, enregistré où l'on veut, qui survit à une désinstallation
    Carte("Copie de sécurité sur ce téléphone", Icons.Filled.SaveAlt) {
        Text("Enregistre TOUTES vos données dans un fichier chiffré (votre e-mail ou téléphone + votre phrase), à l'endroit que vous choisissez (Documents, carte SD…). Il reste sur le téléphone même si l'application est désinstallée, et se restaure ensuite, sans connexion.", fontSize = 15.sp, color = BleuNuit)
        if (SauvegardeEnLigne.derniereCopieLocale(c) > 0) Ligne("Dernière copie", dateLisible(SauvegardeEnLigne.derniereCopieLocale(c)))
    }
    GrosBouton(Icons.Filled.SaveAlt, "Enregistrer une copie chiffrée", Orange, actif = !occupe,
        sousTexte = if (SauvegardeEnLigne.copieConfiguree(c)) "Sans rien saisir : votre configuration est mémorisée" else "Première fois : e-mail ou téléphone, phrase et confirmation") { demanderCopie() }
    if (!cloud && SauvegardeEnLigne.copieConfiguree(c)) GrosBouton(Icons.Filled.Delete, "Oublier la configuration de la copie", Gris, actif = !occupe) {
        SauvegardeEnLigne.oublierConfigurationCopie(c); message = "Configuration de la copie oubliée : la prochaine copie redemandera l'e-mail ou le téléphone et la phrase."; annoncer(c, message!!, true)
    }
    GrosBouton(Icons.Filled.FolderOpen, "Restaurer depuis un fichier", Bleu, actif = !occupe) { phrase = ""; ouvrirFichier.launch(arrayOf("*/*")) }

    // 1.62 : alerte « autre appareil » (posée à l'ouverture ou à une sauvegarde refusée)
    SauvegardeEnLigne.alerteAutreAppareil(c)?.let { Carte("Un autre appareil a sauvegardé", Icons.Filled.Warning) {
        Text("Une sauvegarde faite depuis un AUTRE appareil date du ${it.take(16).replace('T', ' ')}. Votre sauvegarde automatique est suspendue pour ne rien écraser. Touchez « Restaurer la dernière sauvegarde » : un tableau compare les deux avant tout remplacement. Pour garder CE téléphone et remplacer la sauvegarde en ligne, utilisez « Sauvegarder maintenant » puis « Remplacer ».", fontSize = 15.sp, color = Rouge)
    } }
    // 1.63 : « je suis déjà à jour » : l'alerte tombe jusqu'à la prochaine sauvegarde d'un autre appareil (la règle compare maintenant la DATE de la sauvegarde connue)
    if (SauvegardeEnLigne.alerteAutreAppareil(c) != null) GrosBouton(Icons.Filled.CheckCircle, "Je suis déjà à jour (ignorer l'alerte)", Gris, actif = !occupe) {
        SauvegardeEnLigne.ignorerAlerte(c); message = "Alerte ignorée jusqu'à la prochaine sauvegarde d'un autre appareil"; annoncer(c, message!!, true)
    }
    if (cloud) {
        Carte("Sauvegarde en ligne activée", Icons.Filled.CloudDone) {
            Ligne("Dernière sauvegarde", dateLisible(dernier))
            SauvegardeEnLigne.identifiant(c)?.let { Ligne("Casier lié à", SauvegardeCrypto.masquerIdentifiant(it)) }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Icon(Icons.Filled.Autorenew, null, tint = Vert, modifier = Modifier.size(20.dp))
                Text("  Enregistrée automatiquement une fois par jour, à l'ouverture de l'application.", fontSize = 14.sp, color = Gris)
            }
        }
        GrosBouton(Icons.Filled.CloudUpload, "Sauvegarder maintenant", Vert, actif = !occupe) { lancer("Sauvegarde en cours…", { SauvegardeEnLigne.sauvegarder(c) }) }
        GrosBouton(Icons.Filled.CloudDownload, "Restaurer la dernière sauvegarde", Bleu, actif = !occupe) { confirmer = "restaurer"; phrase = "" }
        GrosBouton(Icons.Filled.History, "Restaurer la version précédente", Bleu, actif = !occupe, sousTexte = "Si la dernière sauvegarde est abîmée") { confirmer = "precedente"; phrase = "" }
        GrosBouton(Icons.Filled.Edit, "Changer mon e-mail ou téléphone", Orange, actif = !occupe, sousTexte = "La phrase secrète est redemandée") { changer = true; nouvelId = ""; phrase = "" }
        GrosBouton(Icons.Filled.CloudOff, "Désactiver et effacer en ligne", Rouge, actif = !occupe) { confirmer = "desactiver" }
    } else {
        Carte(if (mode == "local") "Ce téléphone seulement" else "Activer la sauvegarde en ligne", Icons.Filled.Key) {
            Text("Choisissez une phrase secrète d'au moins ${SauvegardeCrypto.LONGUEUR_MIN_PHRASE} caractères (plusieurs mots faciles à retenir). " +
                "ATTENTION : si vous la perdez, personne ne pourra récupérer la sauvegarde. Notez-la sur papier et gardez-la en lieu sûr.", fontSize = 15.sp, color = BleuNuit)
            // Identifiant (1.60) : l'e-mail OU le téléphone de la personne, mélangé à la phrase : deux personnes ne peuvent plus tomber sur le même casier, même avec la même phrase.
            OutlinedTextField(value = identifiant, onValueChange = { identifiant = it }, label = { Text("Votre e-mail ou numéro de téléphone") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Avec l'indicatif pour un numéro (ex. +226 70 00 00 00). À retaper avec la phrase sur un téléphone neuf ; il ne quitte jamais ce téléphone.") })
            if (identifiant.isNotBlank() && !SauvegardeCrypto.identifiantValide(identifiant)) Text("Saisissez une adresse e-mail valide ou un numéro de téléphone d'au moins 8 chiffres.", fontSize = 14.sp, color = Rouge)
            ChampPhrase("Phrase secrète", phrase) { phrase = it }
            ChampPhrase("Confirmez la phrase secrète", confirmation) { confirmation = it }
            // Garde-fou : chaque raison de refus est écrite (phrase trop courte, trop banale, reprenant l'e-mail ou le téléphone…)
            if (phrase.isNotEmpty()) GardeFouPhrase.verifier(phrase, identifiant).raisons.forEach { Text("• $it", fontSize = 14.sp, color = Rouge) }
            if (confirmation.isNotEmpty() && SauvegardeCrypto.normaliser(confirmation) != SauvegardeCrypto.normaliser(phrase)) Text("Les deux phrases ne sont pas identiques.", fontSize = 14.sp, color = Rouge)
        }
        val pret = SauvegardeCrypto.identifiantValide(identifiant) && GardeFouPhrase.verifier(phrase, identifiant).acceptable && SauvegardeCrypto.normaliser(confirmation) == SauvegardeCrypto.normaliser(phrase)
        GrosBouton(Icons.Filled.CloudUpload, "Activer et sauvegarder", Vert, actif = pret && !occupe) {
            lancer("Activation en cours (quelques secondes)…", { SauvegardeEnLigne.activer(c, phrase, identifiant) }) { if (it.ok) { phrase = ""; confirmation = "" } }
        }
        Carte("Réinstallation ou nouveau téléphone ?", Icons.Filled.Restore) {
            Text("Retapez ci-dessus votre e-mail ou téléphone ET votre phrase secrète (une seule fois suffit), puis appuyez sur « Restaurer ». Pour une sauvegarde faite avant la version 1.60, laissez l'e-mail ou le téléphone vide. Vos données actuelles seront remplacées.", fontSize = 15.sp, color = BleuNuit)
        }
        GrosBouton(Icons.Filled.CloudDownload, "Restaurer ma sauvegarde", Bleu, actif = SauvegardeCrypto.phraseValide(phrase) && (identifiant.isBlank() || SauvegardeCrypto.identifiantValide(identifiant)) && !occupe) { confirmer = "restaurer" }
        if (mode != "local") GrosBouton(Icons.Filled.PhoneAndroid, "Garder les données sur ce téléphone seulement", Gris, actif = !occupe) {
            SauvegardeEnLigne.choisirLocal(c); mode = "local"; annoncer(c, "Mode « ce téléphone seulement » : rien n'est envoyé en ligne", true)
        }
    }
    if (secours) {
        Carte("Copie de secours disponible", Icons.Filled.Shield) {
            Text("Avant la dernière restauration, vos données d'alors ont été mises de côté sur ce téléphone. Vous pouvez les rétablir si la restauration ne vous convient pas.", fontSize = 15.sp, color = BleuNuit)
        }
        GrosBouton(Icons.Filled.Undo, "Rétablir les données d'avant", Orange, actif = !occupe) {
            lancer("Retour en arrière en cours…", { SauvegardeEnLigne.revenirEnArriere(c) }) { if (it.ok) SauvegardeEnLigne.relancer(c) }
        }
    }
    if (occupe) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp)); Text("  Opération en cours…", fontSize = 16.sp, color = Bleu) }
    message?.let { Carte { Text(it, fontSize = 16.sp, color = BleuNuit) } }

    // Fenêtre « copie de sécurité » : identifiant + phrase (avec garde-fou), puis choix de l'emplacement du fichier ; ou lecture d'un fichier choisi
    when (copie) {
        "creer" -> AlertDialog(onDismissRequest = { copie = null }, title = { Text("Copie de sécurité chiffrée") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Retenez bien ces deux informations : sans elles, le fichier est illisible pour tout le monde.", fontSize = 14.sp, color = Gris)
                OutlinedTextField(value = identifiant, onValueChange = { identifiant = it }, label = { Text("E-mail ou téléphone") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ChampPhrase("Phrase secrète", phrase) { phrase = it }
                ChampPhrase("Confirmez la phrase", confirmation) { confirmation = it }
                if (phrase.isNotEmpty()) GardeFouPhrase.verifier(phrase, identifiant).raisons.forEach { Text("• $it", fontSize = 14.sp, color = Rouge) }
            } },
            confirmButton = { Tuile(Icons.Filled.SaveAlt, "Créer", Vert, actif = !occupe && SauvegardeCrypto.identifiantValide(identifiant) && GardeFouPhrase.verifier(phrase, identifiant).acceptable && SauvegardeCrypto.normaliser(confirmation) == SauvegardeCrypto.normaliser(phrase)) {
                copie = null
                lancer("Préparation de la copie (vérification des données)…", { val (o, r) = SauvegardeEnLigne.preparerCopieLocale(c, phrase, identifiant); octetsCopie = o; r }) { r -> if (r.ok) { phrase = ""; confirmation = ""; creerFichier.launch(SauvegardeEnLigne.nomCopieLocale()) } }
            } },
            dismissButton = { Tuile(Icons.Filled.Close, "Annuler", Orange) { copie = null } })
        "lire" -> AlertDialog(onDismissRequest = { copie = null; octetsLus = null }, title = { Text("Lire la copie de sécurité") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Saisissez l'e-mail ou le téléphone ET la phrase utilisés pour créer la copie (copie d'avant la 1.60 : e-mail vide).", fontSize = 14.sp, color = Gris)
                OutlinedTextField(value = identifiant, onValueChange = { identifiant = it }, label = { Text("E-mail ou téléphone") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ChampPhrase("Phrase secrète", phrase) { phrase = it }
            } },
            confirmButton = { Tuile(Icons.Filled.Search, "Vérifier", Vert, actif = !occupe && SauvegardeCrypto.phraseValide(phrase) && (identifiant.isBlank() || SauvegardeCrypto.identifiantValide(identifiant))) {
                val o = octetsLus; copie = null; octetsLus = null
                if (o != null && !occupe) { occupe = true; message = null; annoncer(c, "Lecture et vérification en cours…")
                    portee.launch { val (ap, r) = SauvegardeEnLigne.apercuDepuisFichier(c, o, phrase, identifiant); occupe = false; if (ap != null) apercu = ap else { message = r.message; annoncer(c, r.message, true) } } }
            } },
            dismissButton = { Tuile(Icons.Filled.Close, "Annuler", Orange) { copie = null; octetsLus = null } })
    }

    // CHANGER L'IDENTIFIANT (1.66) : la phrase est redemandée et vérifiée ; avertissement clair avant de continuer
    if (changer) AlertDialog(onDismissRequest = { changer = false }, title = { Text("Changer mon e-mail ou téléphone") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ATTENTION : la sauvegarde passe dans un NOUVEAU casier lié à ce nouvel identifiant, puis l'ancien casier est effacé (seulement après vérification en ligne). " +
                "Sur vos AUTRES appareils, vous devrez saisir le même nouvel identifiant, sinon ils continueront à sauvegarder dans l'ancien. Vos données ne sont pas modifiées.", fontSize = 14.sp, color = Rouge)
            SauvegardeEnLigne.identifiant(c)?.let { Text("Identifiant actuel : ${SauvegardeCrypto.masquerIdentifiant(it)}", fontSize = 14.sp, color = Gris) }
            OutlinedTextField(value = nouvelId, onValueChange = { nouvelId = it }, label = { Text("Nouvel e-mail ou téléphone") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Avec l'indicatif pour un numéro (ex. +226 70 00 00 00).") })
            ChampPhrase("Votre phrase secrète actuelle", phrase) { phrase = it }
        } },
        confirmButton = { Tuile(Icons.Filled.Check, "Changer", Vert, actif = !occupe && SauvegardeCrypto.identifiantValide(nouvelId) && SauvegardeCrypto.phraseValide(phrase)) {
            changer = false
            lancer("Changement en cours (envoi dans le nouveau casier, vérification)…", { SauvegardeEnLigne.changerIdentifiant(c, phrase, nouvelId) }) { if (it.ok) { phrase = ""; nouvelId = "" } }
        } },
        dismissButton = { Tuile(Icons.Filled.Close, "Annuler", Orange) { changer = false } })

    // AUTRE APPAREIL (1.60) : le casier contient déjà une sauvegarde faite par une autre installation. Jamais écrasée en silence.
    autre?.let { (quoi, e) ->
        AlertDialog(onDismissRequest = { autre = null }, title = { Text("Une sauvegarde d'un autre appareil existe") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ce casier (même identifiant et même phrase) contient déjà une sauvegarde faite le ${e.majLe.take(16).replace('T', ' ')} depuis un AUTRE appareil.", fontSize = 15.sp, color = BleuNuit)
                    Text("• Nouveau téléphone : choisissez « Restaurer » pour retrouver vos données.", fontSize = 15.sp, color = Vert)
                    Text("• Quelqu'un d'autre utilise la même phrase : « Remplacer » EFFACERAIT ses données. Choisissez plutôt une autre phrase.", fontSize = 15.sp, color = Rouge)
                }
            },
            confirmButton = { Tuile(Icons.Filled.CloudDownload, "Restaurer", Vert) { autre = null; confirmer = "restaurer" } },
            dismissButton = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tuile(Icons.Filled.Close, "Annuler", Orange) { autre = null }
                Tuile(Icons.Filled.Warning, "Remplacer", Rouge) { autre = null
                    if (quoi == "activer") lancer("Activation en cours (quelques secondes)…", { SauvegardeEnLigne.activer(c, phrase, identifiant, forcer = true) }) { if (it.ok) { phrase = ""; confirmation = "" } }
                    else lancer("Sauvegarde en cours…", { SauvegardeEnLigne.sauvegarder(c, forcer = true) })
                }
            } })
    }

    // Contenu VÉRIFIÉ de la sauvegarde : l'utilisateur compare avec ce que contient ce téléphone avant de remplacer
    apercu?.let { ap ->
        AlertDialog(onDismissRequest = { apercu = null }, title = { Text("Remplacer les données de ce téléphone ?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 1.62 : tableau de COMPARAISON rubrique par rubrique (sauvegarde en ligne / ce téléphone), les différences en couleur
                    val lignes = SauvegardeEnLigne.comparer(ap.sauvegarde, ap.telephone)
                    val perdus = SauvegardeEnLigne.ajoutsLocaux(lignes)
                    Row(Modifier.fillMaxWidth()) { Text("Rubrique", Modifier.weight(2f), fontSize = 14.sp, color = Gris); Text("En ligne", Modifier.weight(1f), fontSize = 14.sp, color = Gris, textAlign = androidx.compose.ui.text.style.TextAlign.End); Text("Ce téléphone", Modifier.weight(1.3f), fontSize = 14.sp, color = Gris, textAlign = androidx.compose.ui.text.style.TextAlign.End) }
                    lignes.forEach { l ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(l.libelle, Modifier.weight(2f), fontSize = 15.sp, color = BleuNuit)
                            Text("${l.sauvegarde}", Modifier.weight(1f), fontSize = 15.sp, color = if (l.enPlusEnLigne > 0) Vert else BleuNuit, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                            Text("${l.telephone}" + if (l.enPlusSurTelephone > 0) " ▲" else "", Modifier.weight(1.3f), fontSize = 15.sp, color = if (l.enPlusSurTelephone > 0) Rouge else BleuNuit, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        }
                    }
                    if (ap.pieces > 0) Text("${ap.pieces} pièce(s) jointe(s) dans la sauvegarde", fontSize = 14.sp, color = Gris)
                    if (perdus > 0) Text("ATTENTION : ce téléphone contient $perdus élément(s) (▲) qui ne sont PAS dans la sauvegarde (faits depuis). Les restaurer les mettrait de côté sur ce téléphone, sans les fusionner. Si vous hésitez, annulez, puis faites d'abord « Enregistrer une copie chiffrée ».", fontSize = 15.sp, color = Rouge)
                    if (ap.vide && !ap.telephoneVide) Text("ATTENTION : la sauvegarde est VIDE alors que ce téléphone contient des données. Ne la restaurez pas.", fontSize = 15.sp, color = Rouge)
                    Text("Vos données actuelles seront mises de côté sur ce téléphone (vous pourrez les rétablir), puis l'application redémarrera.", fontSize = 14.sp, color = Gris)
                }
            },
            confirmButton = { Tuile(Icons.Filled.CloudDownload, "Remplacer", if (ap.vide && !ap.telephoneVide) Gris else Vert) {
                apercu = null
                lancer("Restauration en cours (ne fermez pas l'application)…", { SauvegardeEnLigne.restaurer(c, ap) }) { if (it.ok) SauvegardeEnLigne.relancer(c) }
            } },
            dismissButton = { Tuile(Icons.Filled.Close, "Annuler", Orange) { apercu = null } })
    }

    // Confirmations (fenêtres de dialogue) : la restauration REMPLACE les données du téléphone, la désactivation EFFACE la sauvegarde du serveur
    when (confirmer) {
        "restaurer", "precedente" -> AlertDialog(onDismissRequest = { confirmer = null }, title = { Text("Vérifier la sauvegarde") },
            text = {
                Column { Text("La sauvegarde va d'abord être téléchargée et VÉRIFIÉE. Rien n'est remplacé tant que vous n'avez pas vu son contenu et confirmé.")
                    if (cloud) {
                        OutlinedTextField(value = identifiant, onValueChange = { identifiant = it }, label = { Text("E-mail ou téléphone (vide : sauvegarde d'avant la 1.60)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        ChampPhrase("Phrase secrète", phrase) { phrase = it }
                    } }
            },
            confirmButton = { Tuile(Icons.Filled.Search, "Vérifier", Vert, actif = SauvegardeCrypto.phraseValide(phrase) && (identifiant.isBlank() || SauvegardeCrypto.identifiantValide(identifiant)) && !occupe) {
                val prec = confirmer == "precedente"; confirmer = null
                if (!occupe) { occupe = true; message = null; annoncer(c, "Téléchargement et vérification en cours…")
                    portee.launch { val (ap, r) = SauvegardeEnLigne.apercu(c, phrase, identifiant, prec); occupe = false; if (ap != null) apercu = ap else { message = r.message; annoncer(c, r.message, true) } } }
            } },
            dismissButton = { Tuile(Icons.Filled.Close, "Annuler", Orange) { confirmer = null } })
        "desactiver" -> Confirmation("Effacer la sauvegarde en ligne ?", "La sauvegarde du serveur sera DÉFINITIVEMENT effacée. Vos données restent sur ce téléphone.",
            { confirmer = null; lancer("Effacement en cours…", { SauvegardeEnLigne.desactiver(c) }) }, { confirmer = null })
    }
}
