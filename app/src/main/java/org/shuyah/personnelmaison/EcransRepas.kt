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

    CarteEtiquetteRepas()

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

/** Étiquette NFC des repas : posée sur la table ou le réfrigérateur, elle déclare « Je prends mon repas » (1er contact) puis « Repas terminé » (2e contact). */
@Composable
private fun CarteEtiquetteRepas() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var choix by remember { mutableStateOf("Selon l'heure (recommandé)") }
    var ecriture by remember { mutableStateOf(false) }
    var resultat by remember { mutableStateOf("") }
    val nfc = remember(ecriture) { FamilleNfc.etatNfc(c) }
    DisposableEffect(ecriture) { onDispose { FamilleNfc.activite(c)?.let { FamilleNfc.arreterLecteur(it) } } }
    Carte("Étiquette NFC", Icons.Filled.Nfc) {
        Text("Collez une étiquette NFC sur la table ou le réfrigérateur : poser le téléphone dessus déclare « Je prends mon repas », et un second contact « Repas terminé ». Vos proches sont prévenus. L'étiquette ne contient qu'un identifiant.", fontSize = 13.sp, color = Gris)
        when {
            nfc == 0 -> Text("Ce téléphone n'a pas de NFC.", fontSize = 14.sp, color = Gris)
            nfc == 1 -> Text("Le NFC est désactivé : activez-le dans les réglages du téléphone.", fontSize = 14.sp, color = Orange)
            ecriture -> Text("Approchez maintenant une étiquette NFC vierge du téléphone (dos du téléphone)…", fontSize = 14.sp, color = BleuNuit)
            else -> {
                Liste("Repas de l'étiquette", choix, listOf("Selon l'heure (recommandé)") + RappelsRepas.REPAS.map { it.nom }) { choix = it }
                GrosBouton(Icons.Filled.Nfc, "Écrire une étiquette", Bleu) {
                    val a = FamilleNfc.activite(c)
                    if (a == null) resultat = "Écran indisponible, réessayez."
                    else { resultat = ""; ecriture = true
                        val code = RappelsRepas.REPAS.firstOrNull { it.nom == choix }?.code ?: EtiquetteNfc.REPAS_AUTO
                        EtiquetteNfc.demarrerEcritureRepas(a, code) { ok, message -> scope.launch { resultat = message; if (ok) { ecriture = false; FamilleNfc.arreterLecteur(a) } } } }
                }
            }
        }
        if (resultat.isNotBlank()) Text(resultat, fontSize = 13.sp, color = if (resultat.startsWith("Étiquette")) Vert else Rouge, modifier = Modifier.padding(top = 4.dp))
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