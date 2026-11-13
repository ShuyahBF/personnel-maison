package org.shuyah.personnelmaison

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * LABORATOIRE NFC (Plus → Test NFC) : lire, écrire ou effacer une carte NFC VIERGE en suivant chaque étape à l'écran, avec un journal horodaté et un bouton « Copier le journal ».
 * Simulation : les contenus écrits sont de TEST (lien d'ordonnance factice, carte marchand non signée…), sans donnée réelle. Aucun verrouillage de carte n'est proposé.
 */
@Composable
fun EcranLaboNfc(retour: () -> Unit) = Page("Test NFC", retour) {
    val c = LocalContext.current
    var mode by remember { mutableStateOf("Écrire une carte") }
    var contenu by remember { mutableStateOf(LaboNfc.CONTENUS[0]) }
    var actif by remember { mutableStateOf(false) }
    var resultat by remember { mutableStateOf<Boolean?>(null) }
    var cartes by remember { mutableStateOf<List<LaboNfc.CarteVue>>(emptyList()) }          // mode « Trier mes cartes »
    var nommerCarte by remember { mutableStateOf<LaboNfc.CarteVue?>(null) }
    var filtre by remember { mutableStateOf("Toutes") }
    val journal = remember { mutableStateListOf<Pair<String, LaboNfc.Etape>>() }                // (heure, étape)
    val nfc = remember(actif) { FamilleNfc.etatNfc(c) }
    val formatHeure = remember { SimpleDateFormat("HH:mm:ss", Locale.FRANCE) }
    // Temps écoulé depuis le démarrage du test (mis à jour deux fois par seconde tant que le test est actif)
    var debutMs by remember { mutableStateOf(0L) }
    var ecouleS by remember { mutableStateOf(0) }
    LaunchedEffect(actif) { while (actif) { ecouleS = ((System.currentTimeMillis() - debutMs) / 1000).toInt(); delay(500) } }

    DisposableEffect(Unit) { onDispose { FamilleNfc.activite(c)?.let { LaboNfc.arreter(it) } } }

    /** Ajoute une étape au journal (appelée depuis le fil du lecteur NFC : on repasse par le fil de l'écran). */
    val ajouter: (LaboNfc.Etape) -> Unit = { e -> FamilleNfc.activite(c)?.runOnUiThread { journal.add(formatHeure.format(Date()) to e) } }
    val terminer: (Boolean?) -> Unit = { ok -> FamilleNfc.activite(c)?.runOnUiThread { resultat = ok; actif = false; FamilleNfc.activite(c)?.let { LaboNfc.arreter(it) } } }

    fun demarrer() {
        val a = FamilleNfc.activite(c) ?: return
        journal.clear(); resultat = null; actif = true; debutMs = System.currentTimeMillis(); ecouleS = 0
        when (mode) {
            "Trier mes cartes" -> {
                cartes = emptyList()
                LaboNfc.demarrerTri(a, { carte -> FamilleNfc.activite(c)?.runOnUiThread { cartes = LaboNfc.fusionnerCarte(cartes, carte) } }) { terminer(false) }
            }
            "Lire une carte" -> { ajouter(LaboNfc.Etape("En attente d'une carte", LaboNfc.Etat.EN_COURS, "approchez la carte du dos du téléphone")); LaboNfc.demarrerLecture(a, ajouter) { terminer(null) } }
            "Effacer une carte" -> { ajouter(LaboNfc.Etape("En attente d'une carte", LaboNfc.Etat.EN_COURS, "approchez la carte à effacer")); LaboNfc.demarrerEcriture(a, null, ajouter) { terminer(it) } }
            else -> {
                val jeton = LaboNfc.jetonDeTest()
                ajouter(LaboNfc.Etape("Contenu de test choisi", LaboNfc.Etat.INFO, "$contenu · jeton $jeton"))
                ajouter(LaboNfc.Etape("En attente d'une carte vierge", LaboNfc.Etat.EN_COURS, "approchez la carte du dos du téléphone"))
                LaboNfc.demarrerEcriture(a, LaboNfc.messageDeTest(contenu, jeton, c.packageName), ajouter) { terminer(it) }
            }
        }
    }

    Carte("Simulation sur carte vierge", Icons.Filled.Nfc) {
        Text("Écrit un contenu de TEST sur une carte NFC vierge et vérifie le résultat en la relisant, en montrant chaque étape. Rien de réel n'est écrit, et la carte n'est jamais verrouillée : on peut l'effacer et recommencer.", fontSize = 13.sp, color = Gris)
        when {
            nfc == 0 -> Text("Ce téléphone n'a pas de NFC.", fontSize = 14.sp, color = Gris)
            nfc == 1 -> Text("Le NFC est désactivé : activez-le dans les réglages du téléphone (Plus → Aide et à propos).", fontSize = 14.sp, color = Orange)
            actif && mode == "Trier mes cartes" -> {
                // Tri : le lecteur reste ouvert ; la dernière carte posée s'affiche en grand avec son état
                val derniere = cartes.firstOrNull()
                Text("Posez les cartes UNE PAR UNE contre le dos du téléphone : le résultat de chacune s'affiche ci-dessous.", fontSize = 14.sp, color = BleuNuit)
                if (derniere != null) {
                    val couleur = when (derniere.genre) { LaboNfc.GenreCarte.VIERGE -> Vert; LaboNfc.GenreCarte.AVEC_CONTENU -> Orange; LaboNfc.GenreCarte.FORMATEE_VIDE -> Bleu; else -> Rouge }
                    Text("Dernière carte : ${derniere.id}", fontSize = 16.sp, color = BleuNuit)
                    Text(derniere.genre.libelle, fontSize = 22.sp, color = couleur)
                    val nom = Reglages.nomCarte(c, derniere.id)
                    if (nom.isNotBlank()) Text("Nom : $nom", fontSize = 14.sp, color = BleuNuit)
                }
                Text(LaboNfc.bilanTri(cartes), fontSize = 14.sp, color = Gris)
                GrosBouton(Icons.Filled.Stop, "Terminer le tri", Rouge) { FamilleNfc.activite(c)?.let { LaboNfc.arreter(it) }; actif = false }
            }
            actif -> {
                // Jauge de progression : pourcentage et étape déduits du journal, temps écoulé, conseil si la carte tarde
                val (pourcent, etape) = LaboNfc.progression(journal.map { it.second })
                LinearProgressIndicator(progress = { pourcent / 100f }, modifier = Modifier.fillMaxWidth().height(10.dp))
                Text("$pourcent % · $etape · ${ecouleS} s", fontSize = 14.sp, color = BleuNuit)
                Text("Durée habituelle : 1 à 3 secondes une fois la carte détectée.", fontSize = 12.sp, color = Gris)
                if (pourcent == 0 && ecouleS >= 8) Text("Aucune carte détectée depuis $ecouleS s. Si la carte est DÉJÀ contre le téléphone, ÉLOIGNEZ-LA puis REPOSEZ-LA : Android ne détecte qu'une nouvelle présentation. Placez-la au dos du téléphone, vers le haut (antenne NFC), sans étui épais.", fontSize = 13.sp, color = Orange)
                if (pourcent in 1..79 && ecouleS >= 20) Text("Le test prend plus de temps que prévu : gardez la carte IMMOBILE contre le téléphone. Au-delà d'une minute, appuyez sur « Arrêter » et recommencez.", fontSize = 13.sp, color = Orange)
                GrosBouton(Icons.Filled.Stop, "Arrêter", Rouge) { FamilleNfc.activite(c)?.let { LaboNfc.arreter(it) }; actif = false; journal.add(formatHeure.format(Date()) to LaboNfc.Etape("Arrêté par l'utilisateur", LaboNfc.Etat.INFO)) }
            }
            else -> {
                Liste("Action", mode, listOf("Écrire une carte", "Lire une carte", "Effacer une carte", "Trier mes cartes")) { mode = it }
                if (mode == "Écrire une carte") Liste("Contenu de test", contenu, LaboNfc.CONTENUS) { contenu = it }
                GrosBouton(Icons.Filled.PlayArrow, if (mode == "Trier mes cartes") "Commencer le tri" else if (journal.isEmpty()) "Démarrer le test" else "Recommencer", Bleu) { demarrer() }
            }
        }
    }

    // Liste des cartes triées : filtre, genre en couleur, nom modifiable (mémorisé sur le téléphone), copie de la liste
    if (cartes.isNotEmpty()) Carte("Cartes lues (${cartes.size})", Icons.Filled.Checklist) {
        Text(LaboNfc.bilanTri(cartes), fontSize = 13.sp, color = Gris)
        Liste("Afficher", filtre, listOf("Toutes") + LaboNfc.GenreCarte.values().map { it.libelle }) { filtre = it }
        cartes.filter { filtre == "Toutes" || it.genre.libelle == filtre }.forEach { k ->
            val couleur = when (k.genre) { LaboNfc.GenreCarte.VIERGE -> Vert; LaboNfc.GenreCarte.AVEC_CONTENU -> Orange; LaboNfc.GenreCarte.FORMATEE_VIDE -> Bleu; else -> Rouge }
            val nom = Reglages.nomCarte(c, k.id)
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { nommerCarte = k }) {
                Text(k.id + if (nom.isNotBlank()) "  ·  $nom" else "", fontSize = 16.sp, color = BleuNuit)
                Text(k.genre.libelle + " · " + k.famille, fontSize = 13.sp, color = couleur)
                Text("Contenu : ${k.resume} · lue ${k.passages} fois · ${formatHeure.format(Date(k.vueLe))}", fontSize = 12.sp, color = Gris)
                Text("✎ Donner un nom à cette carte", fontSize = 12.sp, color = Bleu)
            }
            HorizontalDivider()
        }
        GrosBouton(Icons.Filled.ContentCopy, "Copier la liste", Gris) {
            val texte = cartes.joinToString("\n") { k -> k.id + " · " + k.genre.libelle + Reglages.nomCarte(c, k.id).let { if (it.isBlank()) "" else " · $it" } + " · " + k.resume }
            (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Cartes NFC", texte))
        }
    }

    nommerCarte?.let { k ->
        var texte by remember(k.id) { mutableStateOf(Reglages.nomCarte(c, k.id)) }
        AlertDialog(onDismissRequest = { nommerCarte = null }, title = { Text("Nom de la carte") },
            text = { Column { Text(k.id + " · " + k.genre.libelle, fontSize = 12.sp, color = Gris); Champ("Nom (ex. Carte A, Pharmacie 1)", texte, { texte = it }) } },
            confirmButton = { Tuile(Icons.Filled.Check, "Valider", Orange) { Reglages.nomCarte(c, k.id, texte.trim()); nommerCarte = null } },
            dismissButton = { Tuile(Icons.Filled.Close, "Annuler", Orange) { nommerCarte = null } })
    }

    if (resultat != null) Carte(null, null) {
        Text(if (resultat == true) "✔ Test réussi : le contenu relu est identique." else "✖ Test échoué : voir le journal ci-dessous.", fontSize = 18.sp, color = if (resultat == true) Vert else Rouge)
    }

    if (journal.isNotEmpty()) Carte("Journal", Icons.Filled.Checklist) {
        journal.forEach { (heure, e) ->
            val (icone, couleur) = when (e.etat) { LaboNfc.Etat.OK -> Icons.Filled.CheckCircle to Vert; LaboNfc.Etat.ECHEC -> Icons.Filled.Cancel to Rouge; LaboNfc.Etat.EN_COURS -> Icons.Filled.HourglassTop to Orange; LaboNfc.Etat.INFO -> Icons.Filled.Info to Bleu }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                Icon(icone, null, tint = couleur, modifier = Modifier.padding(top = 2.dp, end = 8.dp).size(22.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.libelle + (e.dureeMs?.let { " · $it ms" } ?: ""), fontSize = 14.sp, color = BleuNuit)
                    if (e.detail.isNotBlank()) Text(e.detail, fontSize = 12.sp, color = Gris)
                    Text(heure, fontSize = 10.sp, color = Gris)
                }
            }
        }
        GrosBouton(Icons.Filled.ContentCopy, "Copier le journal", Gris) {
            val texte = journal.joinToString("\n") { (h, e) -> "$h  ${e.etat}  ${e.libelle}" + (e.dureeMs?.let { " ($it ms)" } ?: "") + (if (e.detail.isNotBlank()) " — ${e.detail}" else "") }
            (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Journal NFC", texte))
        }
    }
}
