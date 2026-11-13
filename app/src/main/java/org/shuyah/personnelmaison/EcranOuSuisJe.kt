package org.shuyah.personnelmaison

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * OÙ SUIS-JE ? (Plus → Où suis-je ?) : le téléphone écoute les balises Bluetooth fixes posées dans les lieux (rayon, couloir, salle), repère la plus proche dont SAWALI connaît le
 * lieu et propose d'ouvrir la page publique de ce lieu (ou l'ouvre tout seul si on le demande). Les lieux sont créés par l'administrateur ou le superviseur dans SAWALI
 * (« Lieux — Vous êtes ici »). L'écoute ne tourne que pendant que l'écran est ouvert ; seule la balise la plus proche est demandée à SAWALI, rien d'autre n'est envoyé.
 */
@Composable
fun EcranOuSuisJe(retour: () -> Unit) = Page("Où suis-je ?", retour) {
    val c = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var lieu by remember { mutableStateOf<OuSuisJe.Lieu?>(null) }          // lieu le plus proche en ce moment
    var message by remember { mutableStateOf("Approchez-vous d'une balise.") }
    var ouvrirSeul by remember { mutableStateOf(false) }                  // ouvre la page dès qu'on arrive dans un nouveau lieu
    var dernierOuvert by remember { mutableStateOf<String?>(null) }       // évite d'ouvrir deux fois la même page
    val permis = remember(tick) { ScanBalises.permissionsAccordees(c) }
    val bluetooth = remember(tick) { ScanBalises.bluetoothActif(c) }
    val connus = remember { HashMap<String, OuSuisJe.Lieu>() }            // balise → lieu (réponses de SAWALI)
    val inconnues = remember { HashMap<String, Long>() }                    // balise → heure de la réponse « inconnue » (redemandée après 2 min)

    fun ouvrirPage(l: OuSuisJe.Lieu) { dernierOuvert = l.code; c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OuSuisJe.adressePage(OuSuisJe.SITE_PAR_DEFAUT, l.code))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    val demande = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        if (ScanBalises.permissionsAccordees(c) && ScanBalises.bluetoothActif(c)) { ScanBalises.vider(); ScanBalises.demarrer(c) }
        tick++
    }
    // L'écoute démarre dès que l'écran s'ouvre (si déjà autorisée) et s'arrête quand on le quitte
    DisposableEffect(Unit) {
        if (ScanBalises.permissionsAccordees(c) && ScanBalises.bluetoothActif(c)) { ScanBalises.vider(); ScanBalises.demarrer(c) }
        onDispose { ScanBalises.arreter(c) }
    }

    // Toutes les 2 secondes : balises proches → on interroge SAWALI pour celles qu'il ne connaît pas encore → lieu le plus proche
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            if (!ScanBalises.enCours()) { tick++; continue }
            val maintenant = System.currentTimeMillis()
            val entendues = OuSuisJe.candidats(ScanBalises.instantane(), maintenant)
            var erreurReseau: String? = null
            for ((cle, _) in entendues) {
                if (cle in connus || (inconnues[cle]?.let { maintenant - it < OuSuisJe.MEMOIRE_INCONNUE_MS } == true)) continue
                when (val r = withContext(Dispatchers.IO) { OuSuisJe.resoudre(PresenceRecepteur.url(c), cle) }) {
                    is OuSuisJe.Reponse.Trouve -> connus[cle] = r.lieu
                    OuSuisJe.Reponse.Inconnue -> inconnues[cle] = maintenant
                    is OuSuisJe.Reponse.Echec -> erreurReseau = r.message
                }
            }
            lieu = OuSuisJe.plusProche(entendues, connus, lieu)
            message = when {
                lieu != null -> ""
                erreurReseau != null -> "SAWALI injoignable : $erreurReseau"
                entendues.isEmpty() -> "Aucune balise proche pour le moment. Approchez-vous d'une balise."
                else -> "${entendues.size} balise(s) proche(s), mais aucune n'est associée à un lieu dans SAWALI."
            }
            lieu?.let { if (ouvrirSeul && it.code != dernierOuvert) ouvrirPage(it) }
            tick++
        }
    }

    Carte("Principe", Icons.Filled.Place) {
        Text("Les balises posées dans les lieux (rayon, couloir, salle) vous disent où vous êtes : le téléphone repère la plus proche et ouvre la page de ce lieu, avec ce que vous pouvez y faire ou y chercher. " +
            "Les lieux sont créés dans SAWALI par l'administrateur ou le superviseur.", fontSize = 14.sp, color = BleuNuit)
    }

    Carte("Vous êtes ici", Icons.Filled.MyLocation) {
        when {
            ScanBalises.adaptateur(c) == null -> Text("Ce téléphone n'a pas de Bluetooth.", fontSize = 14.sp, color = Gris)
            !bluetooth -> { Text("Le Bluetooth est désactivé.", fontSize = 14.sp, color = Orange); GrosBouton(Icons.Filled.Bluetooth, "Ouvrir les réglages Bluetooth", Orange) { c.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            !permis -> { Text("Autorisation nécessaire (Bluetooth et localisation : Android l'exige pour voir les balises ; votre position n'est pas utilisée).", fontSize = 14.sp, color = Orange); GrosBouton(Icons.Filled.Lock, "Autoriser la détection", Bleu) { demande.launch(ScanBalises.permissionsRequises()) } }
            else -> {
                val l = lieu
                if (l != null) {
                    Text(l.nom, fontSize = 22.sp, color = BleuNuit)
                    GrosBouton(Icons.Filled.OpenInBrowser, "Ouvrir la page du lieu", Vert) { ouvrirPage(l) }
                } else Text(message, fontSize = 14.sp, color = Gris)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Switch(ouvrirSeul, { ouvrirSeul = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Ouvrir la page automatiquement en arrivant dans un lieu", fontSize = 13.sp, color = BleuNuit)
                }
            }
        }
    }
}
