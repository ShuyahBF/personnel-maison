package org.shuyah.personnelmaison

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

// ======================= Suivi de la paie d'un personnel =======================

/** Modes de paiement proposés (liste déroulante ; « Autre… » permet un mode particulier). */
private val MODES_PAIEMENT = listOf("Espèces", "Orange Money", "Moov Money", "Telecel Money", "Virement")

/** Couleur et pictogramme d'un statut : jamais la couleur seule, le texte dit aussi l'état. */
private fun styleStatut(s: StatutPaie): Pair<androidx.compose.ui.graphics.vector.ImageVector, Color> = when (s) {
    StatutPaie.PAYE -> Icons.Filled.CheckCircle to Vert
    StatutPaie.PARTIEL -> Icons.Filled.Timelapse to Orange
    StatutPaie.EN_RETARD -> Icons.Filled.Warning to Rouge
    StatutPaie.A_VENIR -> Icons.Filled.Event to Bleu
    StatutPaie.RIEN -> Icons.Filled.Remove to Gris
}

/**
 * Carte « Suivi de la paie » de la fiche d'un personnel : pour le mois choisi (flèches ◀ ▶), le net à payer, ce qui a été versé, le reste,
 * le reste des mois précédents (report), l'échéance, l'état (payé le…, retard de N jours), les versements, puis l'historique des mois récents.
 * Un mois peut être payé en plusieurs fois ou en retard : on note chaque versement avec sa date, pour le mois qu'il règle.
 */
@Composable
fun BlocSuiviPaie(personnel: Personnel) {
    val c = LocalContext.current
    val dao = remember { Base.dao(c) }
    val scope = rememberCoroutineScope()
    val paiements by dao.paiementsDe(personnel.id).collectAsState(emptyList())
    val versements by dao.versementsDe(personnel.id).collectAsState(emptyList())
    val aujourdhui = remember { LocalDate.now() }
    var mois by remember { mutableStateOf(YearMonth.now()) }
    var dialogue by remember { mutableStateOf(false) }
    val suivi = Calculs.suiviMois(personnel.salaireNet, personnel.dateDebut, personnel.dateFin, personnel.jourPaie, paiements, versements, mois, aujourdhui)
    val (icone, couleur) = styleStatut(suivi.statut)

    Carte("Suivi de la paie", Icons.Filled.EventAvailable) {
        // Navigation d'un mois à l'autre
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ChevronLeft, "Mois précédent", tint = Bleu, modifier = Modifier.size(34.dp).clickable { mois = mois.minusMonths(1) })
            Text(Calculs.moisLong(mois).replaceFirstChar { it.uppercase() }, Modifier.weight(1f), fontSize = 16.sp, color = BleuNuit, textAlign = TextAlign.Center)
            Icon(Icons.Filled.ChevronRight, "Mois suivant", tint = Bleu, modifier = Modifier.size(34.dp).clickable { mois = mois.plusMonths(1) })
        }
        Spacer(Modifier.height(6.dp))
        Ligne("Net du mois", Calculs.francs(suivi.net))
        val avances = Calculs.avancesDuMois(paiements, mois)
        if (avances > 0) Ligne("dont avances déduites", "− " + Calculs.francs(avances))
        Ligne("Déjà versé", Calculs.francs(suivi.verse))
        Ligne(if (suivi.reste < 0) "Payé en trop" else "Reste à payer", Calculs.francs(kotlin.math.abs(suivi.reste)))
        if (suivi.reporte != 0L) Text(
            if (suivi.reporte > 0) "Reste des mois précédents : ${Calculs.francs(suivi.reporte)} (à ajouter)" else "Payé en trop les mois précédents : ${Calculs.francs(-suivi.reporte)} (à déduire)",
            fontSize = 13.sp, color = Orange, modifier = Modifier.padding(top = 4.dp))
        Ligne("Échéance", Calculs.dateFr(suivi.echeance))
        // État du mois : pictogramme + couleur + texte
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icone, null, tint = couleur, modifier = Modifier.size(22.dp)); Text("  " + Calculs.texteStatut(suivi), fontSize = 14.sp, color = couleur)
        }
        // Versements de ce mois (supprimables en cas d'erreur de saisie)
        versements.filter { it.mois == mois.toString() }.forEach { v ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Versé le " + Calculs.dateFr(v.date) + " · " + v.mode, fontSize = 12.sp, color = Gris)
                    if (v.note.isNotBlank()) Text(v.note, fontSize = 14.sp, color = BleuNuit)
                }
                Text(Calculs.francs(v.montant), fontSize = 15.sp, color = Vert, textAlign = TextAlign.End)
                Icon(Icons.Filled.Delete, "Supprimer ce versement", tint = Rouge, modifier = Modifier.padding(start = 10.dp).size(24.dp).clickable {
                    scope.launch { dao.supprimerVersement(v.id); Toast.makeText(c, "Versement supprimé", Toast.LENGTH_SHORT).show() } })
            }
        }
        Spacer(Modifier.height(8.dp))
        RangeeTuiles { Tuile(Icons.Filled.Payments, "Enregistrer un paiement", Vert) { dialogue = true } }

        // Historique : les 6 derniers mois, du plus récent au plus ancien
        Spacer(Modifier.height(10.dp))
        Text("Historique des paiements", fontSize = 13.sp, color = Gris)
        (0..5).map { YearMonth.now().minusMonths(it.toLong()) }.forEach { m ->
            val s = Calculs.suiviMois(personnel.salaireNet, personnel.dateDebut, personnel.dateFin, personnel.jourPaie, paiements, versements, m, aujourdhui)
            if (s.statut == StatutPaie.RIEN) return@forEach
            val (ic, co) = styleStatut(s.statut)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp).clickable { mois = m }, verticalAlignment = Alignment.CenterVertically) {
                Icon(ic, null, tint = co, modifier = Modifier.size(18.dp))
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(Calculs.moisLong(m).replaceFirstChar { it.uppercase() }, fontSize = 14.sp, color = BleuNuit)
                    Text(Calculs.texteStatut(s), fontSize = 12.sp, color = co)
                }
                Text(Calculs.francs(s.net), fontSize = 14.sp, color = BleuNuit, textAlign = TextAlign.End)
            }
        }
    }

    // Fenêtre d'enregistrement d'un versement pour le mois affiché
    if (dialogue) {
        var montant by remember { mutableStateOf(if (suivi.reste > 0) suivi.reste.toString() else "") }
        var date by remember { mutableStateOf(LocalDate.now().toString()) }
        var mode by remember { mutableStateOf(MODES_PAIEMENT.first()) }
        var note by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { dialogue = false }, title = { Text("Paiement de ${Calculs.moisLong(mois)}") },
            text = {
                Column {
                    Champ("Montant versé (FCFA)", montant, { montant = it.filter { ch -> ch.isDigit() } }, KeyboardType.Number)
                    ChampDate("Date du paiement", date, { date = it.toString() })
                    Liste("Mode de paiement", mode, MODES_PAIEMENT, valeurLibre = true) { mode = it }
                    Champ("Note (facultatif)", note, { note = it })
                }
            },
            confirmButton = { Tuile(Icons.Filled.Check, "Valider", Vert, actif = (montant.toLongOrNull() ?: 0) > 0) {
                scope.launch {
                    dao.ajouterVersement(Versement(personnelId = personnel.id, mois = mois.toString(), date = date, montant = montant.toLong(), mode = mode, note = note.trim()))
                    Toast.makeText(c, "Paiement enregistré : ${Calculs.francs(montant.toLong())}", Toast.LENGTH_SHORT).show()
                }; dialogue = false
            } },
            dismissButton = { Tuile(Icons.Filled.Close, "Annuler") { dialogue = false } })
    }
}
