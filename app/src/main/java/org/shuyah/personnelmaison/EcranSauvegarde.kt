package org.shuyah.personnelmaison

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
    var confirmation by remember { mutableStateOf("") }
    var occupe by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmer by remember { mutableStateOf<String?>(null) }        // "restaurer" | "precedente" | "desactiver"
    val cloud = SauvegardeEnLigne.actif(c)

    /** Lance une opération réseau : « en cours » tout de suite, puis le résultat (jamais silencieux). */
    fun lancer(enCours: String, travail: suspend () -> SauvegardeEnLigne.Resultat, apres: (SauvegardeEnLigne.Resultat) -> Unit = {}) {
        if (occupe) return
        occupe = true; message = null; annoncer(c, enCours)
        portee.launch {
            val r = travail(); occupe = false; message = r.message; annoncer(c, r.message, true)
            mode = SauvegardeEnLigne.mode(c); dernier = SauvegardeEnLigne.dernierEnvoi(c); apres(r)
        }
    }

    Carte("Pourquoi sauvegarder ?", Icons.Filled.CloudUpload) {
        Text("Toutes vos données (personnel, pointages, portefeuille, pièces, réglages) sont copiées EN LIGNE, chiffrées sur ce téléphone avec votre phrase secrète. " +
            "Après une désinstallation, un changement de téléphone ou de version, il suffit de retaper la phrase pour tout retrouver. Le serveur ne peut rien lire.", fontSize = 15.sp, color = BleuNuit)
    }

    if (cloud) {
        Carte("Sauvegarde en ligne activée", Icons.Filled.CloudDone) {
            Ligne("Dernière sauvegarde", dateLisible(dernier))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Icon(Icons.Filled.Autorenew, null, tint = Vert, modifier = Modifier.size(20.dp))
                Text("  Enregistrée automatiquement une fois par jour, à l'ouverture de l'application.", fontSize = 14.sp, color = Gris)
            }
        }
        GrosBouton(Icons.Filled.CloudUpload, "Sauvegarder maintenant", Vert, actif = !occupe) { lancer("Sauvegarde en cours…", { SauvegardeEnLigne.sauvegarder(c) }) }
        GrosBouton(Icons.Filled.CloudDownload, "Restaurer la dernière sauvegarde", Bleu, actif = !occupe) { confirmer = "restaurer"; phrase = "" }
        GrosBouton(Icons.Filled.History, "Restaurer la version précédente", Bleu, actif = !occupe, sousTexte = "Si la dernière sauvegarde est abîmée") { confirmer = "precedente"; phrase = "" }
        GrosBouton(Icons.Filled.CloudOff, "Désactiver et effacer en ligne", Rouge, actif = !occupe) { confirmer = "desactiver" }
    } else {
        Carte(if (mode == "local") "Ce téléphone seulement" else "Activer la sauvegarde en ligne", Icons.Filled.Key) {
            Text("Choisissez une phrase secrète d'au moins ${SauvegardeCrypto.LONGUEUR_MIN_PHRASE} caractères (plusieurs mots faciles à retenir). " +
                "ATTENTION : si vous la perdez, personne ne pourra récupérer la sauvegarde. Notez-la sur papier et gardez-la en lieu sûr.", fontSize = 15.sp, color = BleuNuit)
            ChampPhrase("Phrase secrète", phrase) { phrase = it }
            ChampPhrase("Confirmez la phrase secrète", confirmation) { confirmation = it }
            if (phrase.isNotEmpty() && !SauvegardeCrypto.phraseValide(phrase)) Text("Encore ${SauvegardeCrypto.LONGUEUR_MIN_PHRASE - SauvegardeCrypto.normaliser(phrase).length} caractère(s) au moins.", fontSize = 14.sp, color = Rouge)
            if (confirmation.isNotEmpty() && SauvegardeCrypto.normaliser(confirmation) != SauvegardeCrypto.normaliser(phrase)) Text("Les deux phrases ne sont pas identiques.", fontSize = 14.sp, color = Rouge)
        }
        val pret = SauvegardeCrypto.phraseValide(phrase) && SauvegardeCrypto.normaliser(confirmation) == SauvegardeCrypto.normaliser(phrase)
        GrosBouton(Icons.Filled.CloudUpload, "Activer et sauvegarder", Vert, actif = pret && !occupe) {
            lancer("Activation en cours (quelques secondes)…", { SauvegardeEnLigne.activer(c, phrase) }) { if (it.ok) { phrase = ""; confirmation = "" } }
        }
        Carte("Réinstallation ou nouveau téléphone ?", Icons.Filled.Restore) {
            Text("Retapez ci-dessus votre phrase secrète (une seule fois suffit), puis appuyez sur « Restaurer ». Vos données actuelles seront remplacées.", fontSize = 15.sp, color = BleuNuit)
        }
        GrosBouton(Icons.Filled.CloudDownload, "Restaurer ma sauvegarde", Bleu, actif = SauvegardeCrypto.phraseValide(phrase) && !occupe) { confirmer = "restaurer" }
        if (mode != "local") GrosBouton(Icons.Filled.PhoneAndroid, "Garder les données sur ce téléphone seulement", Gris, actif = !occupe) {
            SauvegardeEnLigne.choisirLocal(c); mode = "local"; annoncer(c, "Mode « ce téléphone seulement » : rien n'est envoyé en ligne", true)
        }
    }
    if (occupe) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp)); Text("  Opération en cours…", fontSize = 16.sp, color = Bleu) }
    message?.let { Carte { Text(it, fontSize = 16.sp, color = BleuNuit) } }

    // Confirmations (fenêtres de dialogue) : la restauration REMPLACE les données du téléphone, la désactivation EFFACE la sauvegarde du serveur
    when (confirmer) {
        "restaurer", "precedente" -> AlertDialog(onDismissRequest = { confirmer = null }, title = { Text("Restaurer la sauvegarde ?") },
            text = {
                Column { Text("Toutes les données de ce téléphone seront REMPLACÉES par celles de la sauvegarde, puis l'application redémarrera.")
                    if (cloud) ChampPhrase("Phrase secrète", phrase) { phrase = it } }
            },
            confirmButton = { Tuile(Icons.Filled.CloudDownload, "Restaurer", Vert, actif = SauvegardeCrypto.phraseValide(phrase)) {
                val prec = confirmer == "precedente"; confirmer = null
                lancer("Restauration en cours (ne fermez pas l'application)…", { SauvegardeEnLigne.restaurer(c, phrase, prec) }) { if (it.ok) SauvegardeEnLigne.relancer(c) }
            } },
            dismissButton = { Tuile(Icons.Filled.Close, "Annuler", Orange) { confirmer = null } })
        "desactiver" -> Confirmation("Effacer la sauvegarde en ligne ?", "La sauvegarde du serveur sera DÉFINITIVEMENT effacée. Vos données restent sur ce téléphone.",
            { confirmer = null; lancer("Effacement en cours…", { SauvegardeEnLigne.desactiver(c) }) }, { confirmer = null })
    }
}
