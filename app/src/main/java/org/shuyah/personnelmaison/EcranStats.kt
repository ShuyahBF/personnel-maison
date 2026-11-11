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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth

/**
 * BILANS DU MOIS : présences, absences, retards et heures de chaque personnel, note moyenne des sociétés ; partage du bilan par message.
 * Les nombres sont alignés à droite (règle du propriétaire sur les tableaux). La tolérance de retard se règle ici.
 */
@Composable
fun EcranStats(ouvrir: (Ecran) -> Unit) {
    val c = LocalContext.current
    val dao = remember { Base.dao(c) }
    var mois by remember { mutableStateOf(YearMonth.now()) }
    val personnels by dao.personnel().collectAsState(emptyList())
    val societes by dao.societes().collectAsState(emptyList())
    val evaluations by dao.toutesEvaluations().collectAsState(emptyList())
    val pointages by remember(mois) { dao.pointagesPeriode(mois.atDay(1).toString(), mois.atEndOfMonth().toString()) }.collectAsState(emptyList())
    var tolerance by remember { mutableStateOf(Reglages.tolerance(c)) }
    var reglage by remember { mutableStateOf(false) }
    val aujourdhui = LocalDate.now()

    // Une ligne par personnel concerné par ce mois
    val lignes = personnels.mapNotNull { p ->
        val debut = try { LocalDate.parse(p.dateDebut) } catch (e: Exception) { return@mapNotNull null }
        val fin = p.dateFin?.let { try { LocalDate.parse(it) } catch (e: Exception) { null } }
        if (debut.isAfter(mois.atEndOfMonth()) || (fin != null && fin.isBefore(mois.atDay(1)))) return@mapNotNull null
        p to Calculs.statsMois(mois, ContratInfo(p.debutMin, p.finMin, p.jours, debut, fin),
            pointages.filter { it.personnelId == p.id }.map { JourPointage(LocalDate.parse(it.date), it.arrivee, it.depart, it.retardSignale, it.absent) }, tolerance, aujourdhui)
    }

    Page("Bilans", actions = { Tuile(Icons.Filled.Tune, "Tolérance", Orange) { reglage = true } }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Tuile(Icons.Filled.ChevronLeft, "Précédent") { mois = mois.minusMonths(1) }
            Text(Calculs.moisLong(mois), fontSize = 18.sp, color = BleuNuit)
            Tuile(Icons.Filled.ChevronRight, "Suivant", actif = mois.isBefore(YearMonth.now())) { mois = mois.plusMonths(1) }
        }
        Carte("Personnel", Icons.Filled.Groups) {
            if (lignes.isEmpty()) Text("Aucun personnel sous contrat ce mois-là.", color = Gris, fontSize = 13.sp)
            else {
                // En-tête du tableau : nombres alignés à droite, en-têtes compris
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                    Text("Nom", Modifier.weight(1f), fontSize = 11.sp, color = Gris)
                    listOf("Prés.", "Abs.", "Retards", "Heures").forEach { Text(it, Modifier.width(if (it == "Heures") 62.dp else 50.dp), fontSize = 11.sp, color = Gris, textAlign = TextAlign.End) }
                }
                HorizontalDivider()
                lignes.forEach { (p, s) ->
                    Row(Modifier.fillMaxWidth().clickable { ouvrir(Ecran.FichePersonnel(p.id)) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("${p.prenoms} ${p.nom}", fontSize = 13.sp, color = BleuNuit); Text(p.fonction, fontSize = 11.sp, color = Gris) }
                        Text("${s.presences}/${s.joursAttendus}", Modifier.width(50.dp), fontSize = 13.sp, textAlign = TextAlign.End)
                        Text("${s.absences}", Modifier.width(50.dp), fontSize = 13.sp, color = if (s.absences > 0) Rouge else BleuNuit, textAlign = TextAlign.End)
                        Text("${s.retards}", Modifier.width(50.dp), fontSize = 13.sp, color = if (s.retards > 0) Rouge else BleuNuit, textAlign = TextAlign.End)
                        Text(Calculs.duree(s.minutesTravaillees), Modifier.width(62.dp), fontSize = 13.sp, textAlign = TextAlign.End)
                    }
                    HorizontalDivider()
                }
                Text("Retard compté au-delà de $tolerance min de tolérance ou quand il est signalé à la main.", fontSize = 11.sp, color = Gris, modifier = Modifier.padding(top = 6.dp))
            }
        }
        Carte("Sociétés de placement", Icons.Filled.Business) {
            val moisTexte = mois.toString()
            if (societes.isEmpty()) Text("Aucune société.", color = Gris, fontSize = 13.sp)
            societes.forEach { s ->
                val duMois = evaluations.filter { it.societeId == s.id && it.periode.startsWith(moisTexte) }.map { it.note }
                val toutes = evaluations.filter { it.societeId == s.id }.map { it.note }
                Row(Modifier.fillMaxWidth().clickable { ouvrir(Ecran.FicheSociete(s.id)) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.nom, Modifier.weight(1f), fontSize = 14.sp, color = BleuNuit)
                    Column(horizontalAlignment = Alignment.End) {
                        Text(if (duMois.isEmpty()) "Ce mois : —" else "Ce mois : ${Calculs.moyenne(duMois)} / 5", fontSize = 12.sp, color = Gris)
                        Text(if (toutes.isEmpty()) "Global : —" else "Global : ${Calculs.moyenne(toutes)} / 5", fontSize = 12.sp, color = Gris)
                    }
                }
            }
        }
        RangeeTuiles { Tuile(Icons.Filled.Share, "Partager le bilan", Bleu, actif = lignes.isNotEmpty()) { partager(c, "Bilan de ${Calculs.moisLong(mois)}", Calculs.bilan(mois, lignes.map { (p, s) -> "${p.prenoms} ${p.nom}" to s })) } }
    }

    if (reglage) {
        var texte by remember { mutableStateOf(tolerance.toString()) }
        AlertDialog(onDismissRequest = { reglage = false }, title = { Text("Tolérance de retard") },
            text = { Column { Text("Minutes de retard tolérées avant de compter un retard.", fontSize = 13.sp); Champ("Minutes", texte, { texte = it.filter { ch -> ch.isDigit() } }, KeyboardType.Number) } },
            confirmButton = { Tuile(Icons.Filled.Check, "Valider", Vert) { texte.toIntOrNull()?.let { Reglages.tolerance(c, it); tolerance = Reglages.tolerance(c) }; reglage = false } },
            dismissButton = { Tuile(Icons.Filled.Close, "Annuler") { reglage = false } })
    }
}
