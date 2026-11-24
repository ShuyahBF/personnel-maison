package org.shuyah.personnelmaison

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/**
 * ÉCRAN D'ACCUEIL, pensé pour être le plus simple possible (y compris pour des personnes âgées ou à vue réduite) :
 *  - UN grand bouton « Noter une arrivée ou un départ » (parcours en 3 questions, voir EcranNoter) ;
 *  - l'état du jour de chaque personnel, en grand ;
 *  - UN grand bouton « Corriger un pointage » (rappel d'un pointage déjà enregistré).
 * Un interrupteur « Texte plus grand » agrandit tout le texte de l'application.
 */
@Composable
fun EcranAujourdhui(ouvrir: (Ecran) -> Unit, grosTexte: Boolean, surGrosTexte: (Boolean) -> Unit) {
    val c = LocalContext.current
    val dao = remember { Base.dao(c) }
    val aujourdhui = remember { LocalDate.now() }
    val personnels by dao.personnel().collectAsState(emptyList())
    val societes by dao.societes().collectAsState(emptyList())
    val pointages by dao.pointagesDuJour(aujourdhui.toString()).collectAsState(emptyList())
    val profil by dao.profil().collectAsState(null)
    val tolerance = Reglages.tolerance(c)
    val horaires = horairesDe(dao, personnels)
    val actifs = personnels.filter { p -> try { !LocalDate.parse(p.dateDebut).isAfter(aujourdhui) && (p.dateFin == null || !LocalDate.parse(p.dateFin).isBefore(aujourdhui)) } catch (e: Exception) { true } }

    // Sauvegarde en ligne : automatique une fois par jour à l'ouverture (si activée) ; toujours annoncée (règle : jamais d'enregistrement silencieux)
    var alerteAutre by remember { mutableStateOf(SauvegardeEnLigne.alerteAutreAppareil(c)) }
    LaunchedEffect(Unit) {
        SauvegardeEnLigne.verifierAutreAppareil(c); alerteAutre = SauvegardeEnLigne.alerteAutreAppareil(c)   // 1.62 : un autre appareil a-t-il sauvegardé depuis ?
        SauvegardeEnLigne.sauvegardeAutomatique(c)?.let { android.widget.Toast.makeText(c.applicationContext, if (it.ok) "Sauvegarde automatique effectuée" else "Sauvegarde automatique impossible : ${it.message}", android.widget.Toast.LENGTH_LONG).show() }
    }
    Page("Pointage") {
        Text(Calculs.dateLongue(aujourdhui), fontSize = 20.sp, color = BleuNuit)
        GrosBouton(Icons.Filled.AccessTime, "Noter une arrivée ou un départ", Vert) { ouvrir(Ecran.Noter) }
        GrosBouton(Icons.Filled.EditNote, "Corriger un pointage", Bleu) { ouvrir(Ecran.Corrections) }
        // 1.73 : dernier plantage / erreur gardé sur le téléphone, à copier pour le support (rien n'est envoyé tout seul)
        var plantage by remember { mutableStateOf(Plantage.dernier(c)) }
        plantage?.let { texte -> Carte {
            Text("⚠ L'application s'est arrêtée la dernière fois. Détail gardé :", fontSize = 14.sp, color = Rouge)
            Text(texte.take(500), fontSize = 11.sp, color = Gris)
            RangeeTuiles {
                Tuile(Icons.Filled.ContentCopy, "Copier") {
                    (c.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Plantage", texte))
                    android.widget.Toast.makeText(c.applicationContext, "Détail copié : collez-le dans un message.", android.widget.Toast.LENGTH_LONG).show()
                }
                Tuile(Icons.Filled.Delete, "Effacer") { Plantage.effacer(c); plantage = null }
            }
        } }
        // 1.62 : une sauvegarde d'un AUTRE appareil existe dans ce casier : rien n'est écrasé, on invite à comparer puis choisir
        alerteAutre?.let { Carte(modifier = Modifier.clickable { ouvrir(Ecran.Sauvegarde) }) {
            Text("⚠ Un autre appareil a sauvegardé le ${it.take(16).replace('T', ' ')}. Rien n'a été écrasé : comparez les deux et choisissez (touchez ici).", fontSize = 15.sp, color = Rouge)
        } }
        if (SauvegardeEnLigne.mode(c) == null) Carte(modifier = Modifier.clickable { ouvrir(Ecran.Sauvegarde) }) {
            Text("Protégez vos données : activez la sauvegarde en ligne (ou restaurez-la après une réinstallation).", fontSize = 15.sp, color = Vert)
        }
        if (SauvegardeEnLigne.aMigrer(c)) Carte(modifier = Modifier.clickable { ouvrir(Ecran.Sauvegarde) }) {
            Text("Sauvegarde à sécuriser : liez-la à votre e-mail ou téléphone (2 minutes, rien n'est perdu).", fontSize = 15.sp, color = Orange)
        }
        if (profil?.nom.isNullOrBlank()) Carte(modifier = Modifier.clickable { ouvrir(Ecran.Profil) }) {
            Text("Complétez votre profil : nom, téléphone, adresse, position…", fontSize = 15.sp, color = Bleu)
        }
        Text("Aujourd'hui", fontSize = 20.sp, color = BleuNuit, modifier = Modifier.padding(top = 6.dp))
        if (actifs.isEmpty()) Carte { Text("Aucun personnel sous contrat. Ajoutez-en dans l'onglet « Personnel ».", fontSize = 16.sp, color = Gris) }
        actifs.forEach { p ->
            val pt = pointages.find { it.personnelId == p.id }
            val creneau = horaires[p.id]?.pour(aujourdhui.dayOfWeek)           // horaire de CE jour (null = repos)
            val retard = creneau?.let { Calculs.retardMinutes(pt?.arrivee, it.debutMin, tolerance) } ?: 0
            val travaille = creneau != null
            Carte(modifier = Modifier.clickable { ouvrir(Ecran.Correction(p.id, aujourdhui.toString())) }) {
                Text("${p.prenoms} ${p.nom}", fontSize = 20.sp, color = BleuNuit)
                Text(listOfNotNull(p.fonction, societes.find { it.id == p.societeId }?.nom).joinToString(" · "), fontSize = 14.sp, color = Gris)
                val (texte, couleur) = when {
                    pt?.absent == true -> "Absent" + (if (pt.motif.isNotBlank()) " : ${pt.motif}" else "") to Rouge
                    pt?.arrivee != null && pt.depart != null -> "Arrivé à ${Calculs.heure(pt.arrivee)}, parti à ${Calculs.heure(pt.depart)}" to Vert
                    pt?.arrivee != null -> "Arrivé à ${Calculs.heure(pt.arrivee)}" + (if (retard > 0) " — en retard de ${Calculs.duree(retard)}" else "") to (if (retard > 0) Rouge else Vert)
                    travaille -> "Pas encore arrivé (attendu à ${Calculs.heure(creneau?.debutMin)})" to Gris
                    else -> "Jour de repos" to Gris
                }
                Text(texte, fontSize = 18.sp, color = couleur, modifier = Modifier.padding(top = 6.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
            Switch(grosTexte, surGrosTexte); Text("   Texte plus grand", fontSize = 16.sp, color = BleuNuit)
        }
    }
}
