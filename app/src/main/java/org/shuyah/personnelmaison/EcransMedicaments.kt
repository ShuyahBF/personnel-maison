package org.shuyah.personnelmaison

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

// =====================================================================================================================
// MÉDICAMENTS : rappels à heures fixes, suivi des prises du jour, partage avec la famille (prise ou oubli signalés aux proches choisis).
// =====================================================================================================================

/** Liste des médicaments, prises d'aujourd'hui (bouton « Pris ») et accès au formulaire. */
@Composable
fun EcranMedicaments(ouvrir: (Ecran) -> Unit, retour: () -> Unit) = Page("Médicaments", retour) {
    val c = LocalContext.current
    val dao = remember { Base.dao(c) }
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }
    val aujourdhui = remember { LocalDate.now() }
    val medicaments by dao.medicaments().collectAsState(emptyList())
    val debutJour = remember { aujourdhui.atStartOfDay(zone).toInstant().toEpochMilli() }
    val prises by dao.prisesDepuis(debutJour).collectAsState(emptyList())
    var maintenant by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); maintenant = System.currentTimeMillis() } }

    // Prises prévues aujourd'hui, avec leur état (la ligne n'existe qu'une fois le rappel envoyé ou la prise confirmée)
    data class LignePrise(val med: Medicament, val prevuMs: Long, val statut: String)
    val lignes = medicaments.flatMap { m -> RappelsMedicaments.prisesPrevues(m, aujourdhui, zone).map { t ->
        LignePrise(m, t, prises.firstOrNull { it.medicamentId == m.id && it.prevuMs == t }?.statut ?: if (t > maintenant) "A_VENIR" else "ATTENTE") } }.sortedBy { it.prevuMs }

    Carte("Aujourd'hui", Icons.Filled.Today) {
        if (lignes.isEmpty()) Text("Aucune prise prévue aujourd'hui.", fontSize = 14.sp, color = Gris)
        lignes.forEach { l ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val (icone, couleur) = when (l.statut) { "PRIS" -> Icons.Filled.CheckCircle to Vert; "OUBLIE" -> Icons.Filled.Cancel to Rouge; "ATTENTE" -> Icons.Filled.NotificationsActive to Orange; else -> Icons.Filled.Schedule to Gris }
                Icon(icone, null, tint = couleur, modifier = Modifier.size(28.dp))
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(RappelsMedicaments.libelleHeure(((l.prevuMs - debutJour) / 60_000L).toInt()) + " · " + l.med.nom, fontSize = 16.sp, color = BleuNuit)
                    if (l.med.dose.isNotBlank()) Text(l.med.dose, fontSize = 12.sp, color = Gris)
                }
                if (l.statut == "ATTENTE" || l.statut == "OUBLIE" || l.statut == "A_VENIR") Tuile(Icons.Filled.Check, "Pris", Vert) { scope.launch { PlanificateurMedicaments.confirmerPrise(c, l.med.id, l.prevuMs) } }
            }
        }
    }

    Carte("Mes médicaments", Icons.Filled.Medication) {
        if (medicaments.isEmpty()) Text("Aucun médicament. Ajoutez-en un pour recevoir un rappel à l'heure de la prise.", fontSize = 14.sp, color = Gris)
        medicaments.forEach { m ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp)).background(Fond).clickable { ouvrir(Ecran.FormMedicament(m.id)) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.nom + if (m.dose.isNotBlank()) " · " + m.dose else "", fontSize = 16.sp, color = if (m.actif) BleuNuit else Gris)
                    Text(m.heures.replace(",", " · ") + " — " + RappelsMedicaments.libelleJours(m.jours) + if (!m.actif) " (arrêté)" else "", fontSize = 12.sp, color = Gris)
                }
                if (m.partage) Icon(Icons.Filled.FamilyRestroom, "Partagé avec la famille", tint = Bleu, modifier = Modifier.size(22.dp))
                Icon(Icons.Filled.ChevronRight, null, tint = Gris)
            }
        }
        GrosBouton(Icons.Filled.AddCircle, "Ajouter un médicament", Bleu) { ouvrir(Ecran.FormMedicament(null)) }
    }
    Text("Si une prise n'est pas confirmée ${RappelsMedicaments.RETARD_MIN} minutes après l'heure prévue, elle est notée « oubliée » et, si le médicament est partagé, les proches choisis (fiche de chaque proche, dans Ma famille) en sont informés.", fontSize = 12.sp, color = Gris)
}

/** Ajout ou modification d'un médicament. */
@Composable
fun EcranFormMedicament(id: Long?, retour: () -> Unit) = Page(if (id == null) "Nouveau médicament" else "Médicament", retour) {
    val c = LocalContext.current
    val dao = remember { Base.dao(c) }
    val scope = rememberCoroutineScope()
    var existant by remember { mutableStateOf<Medicament?>(null) }
    var nom by remember { mutableStateOf("") }
    var dose by remember { mutableStateOf("") }
    val heures = remember { mutableStateListOf(8 * 60) }
    var jours by remember { mutableIntStateOf(RappelsMedicaments.TOUS_LES_JOURS) }
    var debut by remember { mutableStateOf<String?>(null) }
    var fin by remember { mutableStateOf<String?>(null) }
    var partage by remember { mutableStateOf(true) }
    var actif by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf("") }
    var suppression by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        if (id != null) dao.medicament(id)?.let { m ->
            existant = m; nom = m.nom; dose = m.dose; heures.clear(); heures.addAll(RappelsMedicaments.minutesDepuisTexte(m.heures)); jours = m.jours; debut = m.debut; fin = m.fin; partage = m.partage; actif = m.actif; note = m.note
        }
    }

    Carte("Médicament", Icons.Filled.Medication) {
        Liste("Nom", nom, RappelsMedicaments.USUELS, valeurLibre = true) { nom = it }
        Champ("Dose (ex. 1 comprimé, 5 ml)", dose, { dose = it })
        Champ("Note (avant / après le repas…)", note, { note = it }, lignes = 2)
    }
    Carte("Heures de prise", Icons.Filled.Schedule) {
        heures.forEachIndexed { i, h ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { ChampHeure("Prise ${i + 1}", h) { heures[i] = it } }
                if (heures.size > 1) Icon(Icons.Filled.Close, "Retirer cette heure", tint = Rouge, modifier = Modifier.padding(start = 8.dp).size(26.dp).clickable { heures.removeAt(i) })
            }
        }
        Row(Modifier.padding(top = 6.dp).clickable { heures.add((heures.lastOrNull() ?: 8 * 60).let { (it + 4 * 60) % (24 * 60) }) }, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AddCircle, null, tint = Bleu, modifier = Modifier.size(28.dp)); Text("  Ajouter une heure", color = Bleu, fontSize = 14.sp)
        }
        Text("Jours", fontSize = 15.sp, color = BleuNuit, modifier = Modifier.padding(top = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RappelsMedicaments.INITIALES.forEachIndexed { i, l ->
                val coche = jours and (1 shl i) != 0
                Box(Modifier.size(38.dp).clip(CircleShape).background(if (coche) Bleu else Fond).clickable { jours = jours xor (1 shl i) }, contentAlignment = Alignment.Center) { Text(l, color = if (coche) Color.White else BleuNuit, fontSize = 15.sp) }
            }
        }
        Text(RappelsMedicaments.libelleJours(jours), fontSize = 12.sp, color = Gris)
        ChampDate("Premier jour (facultatif)", debut, { debut = it.toString() }, { debut = null })
        ChampDate("Dernier jour (facultatif)", fin, { fin = it.toString() }, { fin = null })
    }
    Carte("Partage et état", Icons.Filled.FamilyRestroom) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Signaler la prise ou l'oubli à mes proches", Modifier.weight(1f), fontSize = 15.sp, color = BleuNuit); Switch(partage, { partage = it }) }
        Text("Seuls les proches cochés dans leur fiche (Ma famille) reçoivent l'information, chiffrée de bout en bout.", fontSize = 12.sp, color = Gris)
        if (id != null) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text("Traitement en cours (rappels actifs)", Modifier.weight(1f), fontSize = 15.sp, color = BleuNuit); Switch(actif, { actif = it }) }
    }
    val valide = nom.trim().length >= 2 && heures.isNotEmpty() && jours != 0
    GrosBouton(Icons.Filled.Save, "Enregistrer", Vert, actif = valide) {
        scope.launch {
            val m = Medicament(id = existant?.id ?: 0, nom = nom.trim(), dose = dose.trim(), heures = RappelsMedicaments.minutesVersTexte(heures), jours = jours, debut = debut, fin = fin, partage = partage, actif = actif, note = note.trim())
            val mid = if (existant == null) dao.ajouterMedicament(m) else { dao.modifierMedicament(m); m.id }
            PlanificateurMedicaments.planifier(c, m.copy(id = mid))
            retour()
        }
    }
    if (id != null) GrosBouton(Icons.Filled.Delete, "Supprimer ce médicament", Rouge) { suppression = true }
    if (suppression) Confirmation("Supprimer", "Le médicament et l'historique de ses prises seront supprimés. Continuer ?", {
        suppression = false
        scope.launch { if (id != null) { PlanificateurMedicaments.annuler(c, id); dao.supprimerPrisesDe(id); dao.supprimerMedicament(id) }; retour() }
    }, { suppression = false })
}

/**
 * Fiche d'un proche vérifié : interrupteur « Partager mes rappels de médicaments avec lui » et derniers événements reçus de lui (pris / oublié),
 * relus toutes les 30 secondes.
 */
@Composable
fun CarteMedicamentsProche(lien: ClientFamille.Lien) {
    val c = LocalContext.current
    var partage by remember { mutableStateOf(lien.id in ReglagesFamille.partageMedsAvec(c)) }
    var evenements by remember { mutableStateOf<List<RappelsMedicaments.Evenement>>(emptyList()) }
    var charge by remember { mutableStateOf(false) }
    LaunchedEffect(lien.id) {
        while (true) {
            try { evenements = ClientFamille.evenements(c, lien, 0).filter { it.type == "medicament" }.mapNotNull { e -> e.texte?.let { RappelsMedicaments.evenementDepuisTexte(it) } }.takeLast(10).reversed() } catch (e: ClientFamille.Erreur) { /* hors ligne */ }
            charge = true; delay(30_000)
        }
    }
    Carte("Médicaments", Icons.Filled.Medication) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Partager mes rappels de médicaments avec ${lien.nom}", Modifier.weight(1f), fontSize = 15.sp, color = BleuNuit)
            Switch(partage, { partage = it; ReglagesFamille.partagerMeds(c, lien.id, it) })
        }
        Text("Prises de ${lien.nom}", fontSize = 16.sp, color = BleuNuit, modifier = Modifier.padding(top = 10.dp))
        if (evenements.isEmpty()) Text(if (charge) "Rien à signaler pour le moment." else "Chargement…", fontSize = 14.sp, color = Gris)
        evenements.forEach { e ->
            Text((if (e.statut == "pris") "✔ " else "⚠ ") + RappelsMedicaments.libelleEvenement(e.copy(par = lien.nom)), fontSize = 14.sp, color = if (e.statut == "pris") Vert else Rouge, modifier = Modifier.padding(vertical = 2.dp))
        }
    }
}
