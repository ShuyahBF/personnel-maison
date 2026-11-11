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
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/**
 * NOTER UNE ARRIVÉE OU UN DÉPART — parcours très simple, UNE question par écran, de gros boutons :
 *   1. Qui ?  (clic sur le nom du personnel)
 *   2. Arrivée ou départ ?
 *   3. Quand ?  Date et heure PROPOSÉES d'après l'horloge du téléphone, modifiables (+/− 5 minutes, heure, date)
 *   4. Vérification et enregistrement (horodaté dans le journal : création ou modification)
 * Un retard est détecté tout seul à l'arrivée ; un motif peut être ajouté.
 */
@Composable
fun EcranNoter(retour: () -> Unit) {
    val c = LocalContext.current
    val dao = remember { Base.dao(c) }
    val scope = rememberCoroutineScope()
    val personnels by dao.personnel().collectAsState(emptyList())
    var etape by remember { mutableStateOf(0) }                        // 0 qui, 1 genre, 2 quand, 3 vérification, 4 terminé
    var agent by remember { mutableStateOf<Personnel?>(null) }
    var genre by remember { mutableStateOf(Genre.ARRIVEE) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var minutes by remember { mutableStateOf(maintenant()) }
    var motif by remember { mutableStateOf("") }
    var existant by remember { mutableStateOf<Pointage?>(null) }
    var resultat by remember { mutableStateOf("") }
    val aujourdhui = LocalDate.now()
    val horaires = horairesDe(dao, personnels)
    val actifs = personnels.filter { p -> try { !LocalDate.parse(p.dateDebut).isAfter(aujourdhui) && (p.dateFin == null || !LocalDate.parse(p.dateFin).isBefore(aujourdhui)) } catch (e: Exception) { true } }

    // Retour d'une étape ; à la première étape, on quitte
    fun precedent() { if (etape == 0 || etape == 4) retour() else etape-- }
    // Heure déjà notée pour ce personnel, ce jour, ce genre : sert à avertir d'un remplacement (modification)
    LaunchedEffect(agent, date) { existant = agent?.let { dao.pointage(it.id, date.toString()) } }
    val dejaNote = if (genre == Genre.ARRIVEE) existant?.arrivee else existant?.depart
    val p = agent

    Page(when (etape) { 0 -> "Noter · 1 sur 3"; 1 -> "Noter · 2 sur 3"; 2, 3 -> "Noter · 3 sur 3"; else -> "Noter" }, ::precedent) {
        when (etape) {
            0 -> {
                Question("Qui ?")
                if (actifs.isEmpty()) Text("Aucun personnel sous contrat. Ajoutez-en dans l'onglet « Personnel ».", fontSize = 18.sp, color = Gris)
                actifs.forEach { a ->
                    GrosBouton(Icons.Filled.Person, "${a.prenoms} ${a.nom}", Bleu, sousTexte = a.fonction) {
                        agent = a; date = LocalDate.now(); minutes = maintenant(); motif = ""
                        etape = 1
                    }
                }
            }
            1 -> {
                Question("${p?.prenoms} : arrivée ou départ ?")
                // Suggestion : si l'arrivée du jour est déjà notée sans départ, c'est probablement un départ
                val suggere = if (existant?.arrivee != null && existant?.depart == null) Genre.DEPART else Genre.ARRIVEE
                GrosBouton(Icons.Filled.Login, "ARRIVÉE", Vert, sousTexte = if (suggere == Genre.ARRIVEE) "Proposé" else null) { genre = Genre.ARRIVEE; minutes = maintenant(); etape = 2 }
                GrosBouton(Icons.Filled.Logout, "DÉPART", Bleu, sousTexte = if (suggere == Genre.DEPART) "Proposé" else null) { genre = Genre.DEPART; minutes = maintenant(); etape = 2 }
            }
            2 -> {
                Question(if (genre == Genre.ARRIVEE) "Quand est-il(elle) arrivé(e) ?" else "Quand est-il(elle) parti(e) ?")
                Carte {
                    Text(Calculs.dateLongue(date), fontSize = 22.sp, color = BleuNuit)
                    Text(Calculs.heure(minutes), fontSize = 56.sp, color = BleuNuit, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text("Heure du téléphone proposée : modifiez si besoin.", fontSize = 14.sp, color = Gris)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { GrosBouton(Icons.Filled.Remove, "5 min", Orange) { minutes = (minutes - 5).coerceAtLeast(0) } }
                    Box(Modifier.weight(1f)) { GrosBouton(Icons.Filled.Add, "5 min", Orange) { minutes = (minutes + 5).coerceAtMost(23 * 60 + 59) } }
                }
                GrosBouton(Icons.Filled.Schedule, "Changer l'heure", Bleu) { choisirHeure(c, "Heure", minutes) { minutes = it } }
                GrosBouton(Icons.Filled.CalendarMonth, "Changer la date", Bleu) { choisirDate(c, date) { date = it } }
                if (date != LocalDate.now() || minutes != maintenant()) Text("Revenir à l'heure du téléphone", fontSize = 16.sp, color = Bleu, modifier = Modifier.padding(vertical = 6.dp).clickable { date = LocalDate.now(); minutes = maintenant() })
                if (genre == Genre.ARRIVEE && p != null) {
                    val prevu = horaires[p.id]?.pour(date.dayOfWeek)             // horaire prévu CE jour-là (le samedi peut différer)
                    val retard = prevu?.let { Calculs.retardMinutes(minutes, it.debutMin, Reglages.tolerance(c)) } ?: 0
                    if (retard > 0) { Text("Retard de ${Calculs.duree(retard)} (horaire prévu ${Calculs.heure(prevu?.debutMin)})", fontSize = 18.sp, color = Rouge); Champ("Motif du retard (facultatif)", motif, { motif = it }) }
                }
                GrosBouton(Icons.Filled.ArrowForward, "Continuer", Vert) { etape = 3 }
            }
            3 -> {
                Question("C'est bien cela ?")
                Carte {
                    Text("${p?.prenoms} ${p?.nom}", fontSize = 24.sp, color = BleuNuit)
                    Text(if (genre == Genre.ARRIVEE) "ARRIVÉE" else "DÉPART", fontSize = 30.sp, color = if (genre == Genre.ARRIVEE) Vert else Bleu, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text(Calculs.dateLongue(date), fontSize = 20.sp, color = BleuNuit)
                    Text("à ${Calculs.heure(minutes)}", fontSize = 40.sp, color = BleuNuit, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
                if (dejaNote != null) Text("Attention : ${if (genre == Genre.ARRIVEE) "une arrivée" else "un départ"} est déjà noté(e) à ${Calculs.heure(dejaNote)}. L'enregistrement le/la remplace (correction).", fontSize = 16.sp, color = Rouge)
                GrosBouton(Icons.Filled.Check, "ENREGISTRER", Vert) {
                    scope.launch {
                        if (p != null) {
                            val retard = genre == Genre.ARRIVEE && (horaires[p.id]?.pour(date.dayOfWeek)?.let { Calculs.retardMinutes(minutes, it.debutMin, Reglages.tolerance(c)) } ?: 0) > 0
                            dao.noterHeure(p.id, date.toString(), genre, minutes, retardSignale = if (genre == Genre.ARRIVEE) retard else null, motif = if (retard && motif.isNotBlank()) motif.trim() else null)
                            resultat = "${p.prenoms} ${p.nom}\n${if (genre == Genre.ARRIVEE) "Arrivée" else "Départ"} à ${Calculs.heure(minutes)}\n${Calculs.dateFr(date)}" + if (dejaNote != null) "\n(correction)" else ""
                            etape = 4
                        }
                    }
                }
                GrosBouton(Icons.Filled.ArrowBack, "Modifier", Orange) { etape = 2 }
            }
            else -> {
                Carte { Text("✔ Enregistré", fontSize = 30.sp, color = Vert, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold); Text(resultat, fontSize = 20.sp, color = BleuNuit, modifier = Modifier.padding(top = 6.dp)) }
                Text("Enregistré le ${horodatage(System.currentTimeMillis())}", fontSize = 14.sp, color = Gris)
                GrosBouton(Icons.Filled.AddCircle, "Noter un autre pointage", Vert) { etape = 0; agent = null }
                GrosBouton(Icons.Filled.Home, "Terminé", Bleu) { retour() }
            }
        }
        if (etape in 0..3) Text("Retour : flèche en haut à gauche", fontSize = 12.sp, color = Gris)
    }
}

private fun maintenant(): Int = LocalTime.now().let { it.hour * 60 + it.minute }

// ======================= Rappel d'un pointage pour correction =======================

/** Liste des derniers pointages (60 jours) : un appui rappelle le pointage pour le corriger. */
@Composable
fun EcranCorrections(ouvrir: (Ecran) -> Unit, retour: () -> Unit) {
    val c = LocalContext.current
    val dao = remember { Base.dao(c) }
    val depuis = remember { LocalDate.now().minusDays(60).toString() }
    val pointages by dao.derniersPointages(depuis).collectAsState(emptyList())
    val journal by dao.journalDepuis(depuis).collectAsState(emptyList())
    val personnels by dao.personnel().collectAsState(emptyList())
    Page("Corriger un pointage", retour) {
        Question("Quel pointage corriger ?")
        if (pointages.isEmpty()) Text("Aucun pointage enregistré pour le moment.", fontSize = 18.sp, color = Gris)
        pointages.forEach { pt ->
            val p = personnels.find { it.id == pt.personnelId } ?: return@forEach
            // Dernière action sur ce pointage (journal) : « corrigé le … »
            val derniere = journal.filter { it.personnelId == pt.personnelId && it.date == pt.date }.maxByOrNull { it.horodatage }
            Carte(modifier = Modifier.clickable { ouvrir(Ecran.Correction(pt.personnelId, pt.date)) }) {
                Text("${p.prenoms} ${p.nom}", fontSize = 20.sp, color = BleuNuit)
                Text(Calculs.dateLongue(LocalDate.parse(pt.date)), fontSize = 16.sp, color = Gris)
                Text(if (pt.absent) "Absent" else "Arrivée ${Calculs.heure(pt.arrivee)}  ·  Départ ${Calculs.heure(pt.depart)}", fontSize = 18.sp, color = if (pt.absent) Rouge else BleuNuit)
                if (derniere != null) Text("Dernière action : ${Calculs.actionTexte(derniere.action)} le ${horodatage(derniere.horodatage)}", fontSize = 12.sp, color = Gris)
            }
        }
    }
}

/**
 * CORRECTION D'UN POINTAGE : arrivée et départ en grand avec « Changer » et « Effacer », retard, absence, motif ; historique horodaté de toutes les
 * créations et modifications de ce jour. Chaque correction est journalisée.
 */
@Composable
fun EcranCorrection(personnelId: Long, date: String, retour: () -> Unit) {
    val c = LocalContext.current
    val dao = remember { Base.dao(c) }
    val scope = rememberCoroutineScope()
    val p by dao.personnelFlow(personnelId).collectAsState(null)
    val pointages by dao.pointagesDuJour(date).collectAsState(emptyList())
    val journal by dao.journal(personnelId, date).collectAsState(emptyList())
    val lignesHoraires by dao.tousHoraires().collectAsState(emptyList())
    val pt = pointages.find { it.personnelId == personnelId }
    var motif by remember(pt?.motif) { mutableStateOf(pt?.motif ?: "") }
    var suppression by remember { mutableStateOf(false) }
    val agent = p ?: run { Page("Pointage", retour) { Text("Fiche introuvable.") }; return }
    val jour = LocalDate.parse(date)
    val prevu = creneauDuJour(agent, lignesHoraires, jour)                       // horaire prévu ce jour-là (null = repos)

    @Composable fun Bloc(titre: String, genre: String, valeur: Int?, couleur: androidx.compose.ui.graphics.Color, parDefaut: Int) {
        Carte {
            Text(titre, fontSize = 18.sp, color = Gris)
            Text(Calculs.heure(valeur), fontSize = 44.sp, color = couleur, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { GrosBouton(Icons.Filled.Edit, if (valeur == null) "Noter" else "Changer", Orange) { choisirHeure(c, titre, valeur ?: parDefaut) { m -> scope.launch { dao.noterHeure(personnelId, date, genre, m) } } } }
                if (valeur != null) Box(Modifier.weight(1f)) { GrosBouton(Icons.Filled.Delete, "Effacer", Rouge) { scope.launch { dao.noterHeure(personnelId, date, genre, null) } } }
            }
        }
    }

    Page("Corriger", retour) {
        Text("${agent.prenoms} ${agent.nom}", fontSize = 24.sp, color = BleuNuit)
        Text(Calculs.dateLongue(jour), fontSize = 18.sp, color = Gris)
        Bloc("Arrivée", Genre.ARRIVEE, pt?.arrivee, Vert, prevu?.debutMin ?: 7 * 60)
        Bloc("Départ", Genre.DEPART, pt?.depart, Bleu, prevu?.finMin ?: 17 * 60)
        val retard = prevu?.let { Calculs.retardMinutes(pt?.arrivee, it.debutMin, Reglages.tolerance(c)) } ?: 0
        if (retard > 0) Text("Retard de ${Calculs.duree(retard)}", fontSize = 18.sp, color = Rouge)
        Carte {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(pt?.retardSignale == true, { v -> scope.launch { dao.noterHeure(personnelId, date, Genre.ARRIVEE, pt?.arrivee, retardSignale = v) } }); Text("   Retard signalé", fontSize = 18.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                Switch(pt?.absent == true, { v -> scope.launch { dao.noterAbsence(personnelId, date, v, motif.trim()) } }); Text("   Absent ce jour-là", fontSize = 18.sp)
            }
            Champ("Motif ou remarque", motif, { motif = it })
            GrosBouton(Icons.Filled.Check, "Enregistrer le motif", Vert, actif = motif.trim() != (pt?.motif ?: "")) { scope.launch { dao.noterHeure(personnelId, date, Genre.ARRIVEE, pt?.arrivee, motif = motif.trim()) } }
        }
        Carte("Historique de ce jour", Icons.Filled.History) {
            if (journal.isEmpty()) Text("Rien d'enregistré.", fontSize = 15.sp, color = Gris)
            journal.forEach { j ->
                val quoi = when (j.genre) { Genre.ARRIVEE -> "Arrivée"; Genre.DEPART -> "Départ"; Genre.ABSENCE -> "Absence"; else -> "Retard" }
                Text("${quoi} ${Calculs.actionTexte(j.action)}" + (if (j.heure != null) " : ${Calculs.heure(j.heure)}" else "") + "\nle ${horodatage(j.horodatage)}", fontSize = 15.sp, color = BleuNuit, modifier = Modifier.padding(vertical = 5.dp))
            }
        }
        if (pt != null) GrosBouton(Icons.Filled.DeleteForever, "Supprimer ce pointage", Rouge) { suppression = true }
    }
    if (suppression) Confirmation("Supprimer ce pointage ?", "L'arrivée, le départ et le motif de ce jour seront effacés. L'historique garde la trace de la suppression.", {
        scope.launch {
            pt?.arrivee?.let { dao.noterHeure(personnelId, date, Genre.ARRIVEE, null) }
            dao.noterHeure(personnelId, date, Genre.DEPART, null)
            dao.noterAbsence(personnelId, date, false, ""); dao.noterHeure(personnelId, date, Genre.ARRIVEE, null, retardSignale = false, motif = "")
        }
        suppression = false; retour()
    }, { suppression = false })
}
