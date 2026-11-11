package org.shuyah.personnelmaison

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/**
 * ÉCRAN DU JOUR : pour chaque personnel attendu ce jour-là, l'action manuelle de l'utilisateur note l'heure d'ARRIVÉE et de DÉPART,
 * signale un RETARD (avec motif) ou une ABSENCE. On peut revenir sur un jour passé (flèches) pour noter un oubli.
 * Le retard est aussi calculé automatiquement : arrivée au-delà de l'horaire contractuel + la tolérance (réglable dans « Bilans »).
 */
@Composable
fun EcranAujourdhui(ouvrir: (Ecran) -> Unit) {
    val c = LocalContext.current
    val dao = remember { Base.dao(c) }
    val scope = rememberCoroutineScope()
    var jour by remember { mutableStateOf(LocalDate.now()) }
    val personnels by dao.personnel().collectAsState(emptyList())
    val societes by dao.societes().collectAsState(emptyList())
    val pointages by remember(jour) { dao.pointagesDuJour(jour.toString()) }.collectAsState(emptyList())
    var edition by remember { mutableStateOf<Pair<Personnel, Pointage?>?>(null) }
    var retardDirect by remember { mutableStateOf(false) }
    val tolerance = Reglages.tolerance(c)
    // Personnels sous contrat ce jour-là
    val actifs = personnels.filter { p -> !jourAvant(jour, p.dateDebut) && (p.dateFin == null || !jourApres(jour, p.dateFin)) }

    fun enregistrer(p: Pointage) = scope.launch { dao.enregistrerPointage(p) }

    Page("Pointage du jour") {
        // Navigation entre les jours
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Tuile(Icons.Filled.ChevronLeft, "Veille") { jour = jour.minusDays(1) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Calculs.dateLongue(jour), fontSize = 16.sp, color = BleuNuit)
                if (jour != LocalDate.now()) Text("Revenir à aujourd'hui", fontSize = 12.sp, color = Bleu, modifier = Modifier.clickable { jour = LocalDate.now() })
            }
            Tuile(Icons.Filled.ChevronRight, "Lendemain", actif = jour.isBefore(LocalDate.now())) { jour = jour.plusDays(1) }
        }
        if (actifs.isEmpty()) Carte { Text("Aucun personnel sous contrat ce jour-là. Ajoutez-en dans l'onglet « Personnel ».", color = Gris) }
        actifs.forEach { p ->
            val pt = pointages.find { it.personnelId == p.id }
            val societe = societes.find { it.id == p.societeId }?.nom
            val travaille = Calculs.jourTravaille(p.jours, jour.dayOfWeek)
            val retard = Calculs.retardMinutes(pt?.arrivee, p.debutMin, tolerance)
            Carte {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { ouvrir(Ecran.FichePersonnel(p.id)) }) {
                    Column(Modifier.weight(1f)) {
                        Text("${p.nom} ${p.prenoms}", fontSize = 16.sp, color = BleuNuit)
                        Text(listOfNotNull(p.fonction, societe).joinToString(" · ") + "  ·  " + Calculs.heure(p.debutMin) + " → " + Calculs.heure(p.finMin), fontSize = 12.sp, color = Gris)
                    }
                }
                // État du jour
                val (texte, couleur) = when {
                    pt?.absent == true -> ("Absent" + if (pt.motif.isNotBlank()) " : ${pt.motif}" else "") to Rouge
                    pt?.arrivee != null -> {
                        var t = "Arrivé à ${Calculs.heure(pt.arrivee)}"
                        if (retard > 0) t += " · retard de ${Calculs.duree(retard)}" else if (pt.retardSignale) t += " · retard signalé"
                        if (pt.depart != null) t += " · parti à ${Calculs.heure(pt.depart)} (${Calculs.duree(maxOf(0, pt.depart - pt.arrivee))})"
                        t to (if (retard > 0 || pt.retardSignale) Orange.copy(red = 0.85f, green = 0.45f) else Vert)
                    }
                    travaille -> "Attendu à ${Calculs.heure(p.debutMin)}" to Gris
                    else -> "Jour de repos" to Gris
                }
                Text(texte, color = couleur, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp))
                if (pt?.motif?.isNotBlank() == true && pt.absent != true) Text("Motif : ${pt.motif}", fontSize = 12.sp, color = Gris)
                val base = pt ?: Pointage(personnelId = p.id, date = jour.toString())
                val maintenant = LocalTime.now().let { it.hour * 60 + it.minute }
                RangeeTuiles {
                    Tuile(Icons.Filled.Login, "Arrivée", Vert) {
                        choisirHeure(c, "Heure d'arrivée de ${p.prenoms}", pt?.arrivee ?: if (jour == LocalDate.now()) maintenant else p.debutMin) { m -> enregistrer(base.copy(arrivee = m, absent = false)) }
                    }
                    Tuile(Icons.Filled.Logout, "Départ", Bleu, actif = pt?.arrivee != null) {
                        choisirHeure(c, "Heure de départ de ${p.prenoms}", pt?.depart ?: if (jour == LocalDate.now()) maintenant else p.finMin) { m -> enregistrer(base.copy(depart = m)) }
                    }
                    Tuile(Icons.Filled.RunningWithErrors, "Retard", Orange) { retardDirect = true; edition = p to base.copy(retardSignale = true) }
                    Tuile(Icons.Filled.PersonOff, "Absent", Rouge) { edition = p to base.copy(arrivee = null, depart = null, absent = true) }
                    Tuile(Icons.Filled.Edit, "Modifier") { retardDirect = false; edition = p to base }
                }
            }
        }
    }

    edition?.let { (p, pt) ->
        DialogPointage(p, pt!!, jour, surEnregistrement = { enregistrer(it); edition = null },
            surSuppression = { scope.launch { dao.supprimerPointage(p.id, jour.toString()) }; edition = null }, surFermeture = { edition = null })
    }
}

private fun jourAvant(jour: LocalDate, iso: String) = try { jour.isBefore(LocalDate.parse(iso)) } catch (e: Exception) { false }
private fun jourApres(jour: LocalDate, iso: String) = try { jour.isAfter(LocalDate.parse(iso)) } catch (e: Exception) { false }

/** Fenêtre de modification complète d'un pointage : arrivée, départ, retard signalé, absence, motif. */
@Composable
private fun DialogPointage(p: Personnel, depart: Pointage, jour: LocalDate, surEnregistrement: (Pointage) -> Unit, surSuppression: () -> Unit, surFermeture: () -> Unit) {
    var arrivee by remember { mutableStateOf(depart.arrivee) }
    var sortie by remember { mutableStateOf(depart.depart) }
    var retard by remember { mutableStateOf(depart.retardSignale) }
    var absent by remember { mutableStateOf(depart.absent) }
    var motif by remember { mutableStateOf(depart.motif) }
    val c = LocalContext.current
    AlertDialog(
        onDismissRequest = surFermeture,
        title = { Text("${p.prenoms} ${p.nom} · ${Calculs.dateFr(jour)}", fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                HeureFacultative("Arrivée", arrivee, p.debutMin, { arrivee = it; absent = false }) { arrivee = null }
                HeureFacultative("Départ", sortie, p.finMin, { sortie = it }) { sortie = null }
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(retard, { retard = it }); Text("  Retard signalé", fontSize = 14.sp) }
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(absent, { absent = it; if (it) { arrivee = null; sortie = null } }); Text("  Absent", fontSize = 14.sp) }
                Champ("Motif ou remarque", motif, { motif = it })
            }
        },
        confirmButton = { Tuile(Icons.Filled.Check, "Valider", Vert) { surEnregistrement(depart.copy(arrivee = arrivee, depart = sortie, retardSignale = retard, absent = absent, motif = motif.trim())) } },
        dismissButton = { Row { Tuile(Icons.Filled.Delete, "Effacer", Rouge) { surSuppression() }; Tuile(Icons.Filled.Close, "Annuler") { surFermeture() } } }
    )
}

/** Heure facultative : « — » tant qu'elle n'est pas notée ; un appui ouvre le sélecteur, la croix l'efface. */
@Composable
private fun HeureFacultative(libelle: String, valeur: Int?, parDefaut: Int, surChoix: (Int) -> Unit, surEffacement: () -> Unit) {
    val c = LocalContext.current
    Box(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        OutlinedTextField(Calculs.heure(valeur), {}, readOnly = true, label = { Text(libelle) }, modifier = Modifier.fillMaxWidth(),
            trailingIcon = { if (valeur != null) Icon(Icons.Filled.Close, "Effacer", modifier = Modifier.clickable { surEffacement() }) else Icon(Icons.Filled.Schedule, null) })
        Box(Modifier.matchParentSize().padding(end = 48.dp).clickable { choisirHeure(c, libelle, valeur ?: parDefaut, surChoix) })
    }
}
