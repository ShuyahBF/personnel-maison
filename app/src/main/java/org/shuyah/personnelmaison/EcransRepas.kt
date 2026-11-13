package org.shuyah.personnelmaison

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// =====================================================================================================================
// REPAS : déclaration (« Je prends mon repas », « Repas terminé »), heures suivies, partage avec les proches (début, fin, repas non pris).
// =====================================================================================================================

/** Écran Repas : état d'aujourd'hui avec les boutons de déclaration, et réglage des heures de repas suivies. */
@Composable
fun EcranRepas(retour: () -> Unit) = Page("Repas", retour) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var maintenant by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); maintenant = System.currentTimeMillis(); tick++ } }
    val jour = remember(maintenant) { PlanificateurRepas.jour(maintenant) }
    val debutJour = remember(maintenant) { java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }

    Carte("Aujourd'hui", Icons.Filled.Today) {
        val suivis = RappelsRepas.REPAS.filter { ReglagesFamille.repasActif(c, it.code) }
        if (suivis.isEmpty()) Text("Aucun repas suivi. Activez-en un ci-dessous.", fontSize = 14.sp, color = Gris)
        suivis.forEach { r ->
            val etat = remember(tick, jour) { ReglagesFamille.repasEtat(c, r.code, jour) }
            val minutes = ReglagesFamille.repasMinutes(c, r.code)
            val passe = maintenant >= debutJour + minutes * 60_000L
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val (icone, couleur, texte) = when (etat) {
                    "prend" -> Triple(Icons.Filled.Restaurant, Bleu, "En train de manger")
                    "termine" -> Triple(Icons.Filled.CheckCircle, Vert, "Repas terminé")
                    "alerte" -> Triple(Icons.Filled.Warning, Rouge, "Non déclaré : proches prévenus")
                    else -> if (passe) Triple(Icons.Filled.NotificationsActive, Orange, "À déclarer") else Triple(Icons.Filled.Schedule, Gris, "À venir")
                }
                Icon(icone, null, tint = couleur, modifier = Modifier.size(28.dp))
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text("${RappelsRepas.REPAS.first { it.code == r.code }.nom} · ${RappelsMedicaments.libelleHeure(minutes)}", fontSize = 16.sp, color = BleuNuit)
                    Text(texte, fontSize = 12.sp, color = couleur)
                }
                if (etat == null || etat == "alerte") Tuile(Icons.Filled.Restaurant, "Je mange", Bleu) { scope.launch { PlanificateurRepas.declarer(c, r.code, "prend"); tick++ } }
                else if (etat == "prend") Tuile(Icons.Filled.Check, "Terminé", Vert) { scope.launch { PlanificateurRepas.declarer(c, r.code, "termine"); tick++ } }
            }
        }
    }

    Carte("Mes heures de repas", Icons.Filled.Schedule) {
        RappelsRepas.REPAS.forEach { r ->
            var actif by remember { mutableStateOf(ReglagesFamille.repasActif(c, r.code)) }
            var minutes by remember { mutableIntStateOf(ReglagesFamille.repasMinutes(c, r.code)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.nom, Modifier.weight(1f), fontSize = 16.sp, color = BleuNuit)
                Switch(actif, { actif = it; ReglagesFamille.repasActif(c, r.code, it); PlanificateurRepas.planifier(c, r.code) })
            }
            if (actif) ChampHeure("Heure du ${r.nom.lowercase()}", minutes) { minutes = it; ReglagesFamille.repasMinutes(c, r.code, it); PlanificateurRepas.planifier(c, r.code) }
        }
        Text("À l'heure prévue, un rappel s'affiche. Si le repas n'est pas déclaré ${RappelsRepas.DELAI_ALERTE_MIN} minutes plus tard, les proches choisis (fiche de chaque proche, dans Ma famille) reçoivent « n'a pas encore pris son repas ».", fontSize = 12.sp, color = Gris, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Fiche d'un proche vérifié : « Partager mes repas avec lui » et ses derniers événements de repas (relus toutes les 30 secondes). */
@Composable
fun CarteRepasProche(lien: ClientFamille.Lien) {
    val c = LocalContext.current
    var partage by remember { mutableStateOf(lien.id in ReglagesFamille.partageRepasAvec(c)) }
    var evenements by remember { mutableStateOf<List<RappelsRepas.Evenement>>(emptyList()) }
    var charge by remember { mutableStateOf(false) }
    LaunchedEffect(lien.id) {
        while (true) {
            try { evenements = ClientFamille.evenements(c, lien, 0).filter { it.type == "repas" }.mapNotNull { e -> e.texte?.let { RappelsRepas.evenementDepuisTexte(it) } }.takeLast(8).reversed() } catch (e: ClientFamille.Erreur) { /* hors ligne */ }
            charge = true; delay(30_000)
        }
    }
    Carte("Repas", Icons.Filled.Restaurant) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Partager mes repas avec ${lien.nom}", Modifier.weight(1f), fontSize = 15.sp, color = BleuNuit)
            Switch(partage, { partage = it; ReglagesFamille.partagerRepas(c, lien.id, it) })
        }
        Text("Repas de ${lien.nom}", fontSize = 16.sp, color = BleuNuit, modifier = Modifier.padding(top = 10.dp))
        if (evenements.isEmpty()) Text(if (charge) "Rien à signaler pour le moment." else "Chargement…", fontSize = 14.sp, color = Gris)
        evenements.forEach { e ->
            Text((if (e.statut == "pas_pris") "⚠ " else "🍽 ") + RappelsRepas.libelleEvenement(e.copy(par = lien.nom)), fontSize = 14.sp, color = if (e.statut == "pas_pris") Rouge else BleuNuit, modifier = Modifier.padding(vertical = 2.dp))
        }
    }
}
