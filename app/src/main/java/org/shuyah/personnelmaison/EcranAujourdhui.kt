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

    Page("Pointage") {
        Text(Calculs.dateLongue(aujourdhui), fontSize = 20.sp, color = BleuNuit)
        GrosBouton(Icons.Filled.AccessTime, "Noter une arrivée ou un départ", Vert) { ouvrir(Ecran.Noter) }
        GrosBouton(Icons.Filled.EditNote, "Corriger un pointage", Bleu) { ouvrir(Ecran.Corrections) }
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
