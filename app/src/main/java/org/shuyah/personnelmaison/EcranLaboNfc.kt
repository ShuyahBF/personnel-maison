package org.shuyah.personnelmaison

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
    val journal = remember { mutableStateListOf<Pair<String, LaboNfc.Etape>>() }                // (heure, étape)
    val nfc = remember(actif) { FamilleNfc.etatNfc(c) }
    val formatHeure = remember { SimpleDateFormat("HH:mm:ss", Locale.FRANCE) }

    DisposableEffect(Unit) { onDispose { FamilleNfc.activite(c)?.let { LaboNfc.arreter(it) } } }

    /** Ajoute une étape au journal (appelée depuis le fil du lecteur NFC : on repasse par le fil de l'écran). */
    val ajouter: (LaboNfc.Etape) -> Unit = { e -> FamilleNfc.activite(c)?.runOnUiThread { journal.add(formatHeure.format(Date()) to e) } }
    val terminer: (Boolean?) -> Unit = { ok -> FamilleNfc.activite(c)?.runOnUiThread { resultat = ok; actif = false; FamilleNfc.activite(c)?.let { LaboNfc.arreter(it) } } }

    fun demarrer() {
        val a = FamilleNfc.activite(c) ?: return
        journal.clear(); resultat = null; actif = true
        when (mode) {
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
            actif -> {
                Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp); Text("  Test en cours… gardez la carte contre le téléphone.", fontSize = 14.sp, color = BleuNuit) }
                GrosBouton(Icons.Filled.Stop, "Arrêter", Rouge) { FamilleNfc.activite(c)?.let { LaboNfc.arreter(it) }; actif = false; journal.add(formatHeure.format(Date()) to LaboNfc.Etape("Arrêté par l'utilisateur", LaboNfc.Etat.INFO)) }
            }
            else -> {
                Liste("Action", mode, listOf("Écrire une carte", "Lire une carte", "Effacer une carte")) { mode = it }
                if (mode == "Écrire une carte") Liste("Contenu de test", contenu, LaboNfc.CONTENUS) { contenu = it }
                GrosBouton(Icons.Filled.PlayArrow, if (journal.isEmpty()) "Démarrer le test" else "Recommencer", Bleu) { demarrer() }
            }
        }
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
