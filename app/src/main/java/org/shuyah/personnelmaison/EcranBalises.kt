package org.shuyah.personnelmaison

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TEST DES BALISES BLUETOOTH (Plus → Test balises) : liste en direct des balises détectées (Estimote, iBeacon, Eddystone, ou tous les appareils Bluetooth), avec leur
 * identifiant, le signal lissé, la distance estimée, le temps depuis la dernière annonce, et un nom que l'on peut leur donner. Un suivi « présente / absente » avec un délai
 * réglable écrit un journal d'arrivées et de départs : c'est la SIMULATION du pointage automatique du personnel (rien n'est enregistré dans les pointages).
 */
@Composable
fun EcranBalises(retour: () -> Unit) = Page("Test balises", retour) {
    val c = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var enCours by remember { mutableStateOf(ScanBalises.enCours()) }
    var toutesLesAnnonces by remember { mutableStateOf(false) }
    var delai by remember { mutableStateOf("2 minutes") }
    var nommer by remember { mutableStateOf<ScanBalises.Vue?>(null) }
    var detail by remember { mutableStateOf<String?>(null) }
    val journal = remember { mutableStateListOf<String>() }
    val suivi = remember { Balises.Suivi(120_000L) }
    val heure = remember { SimpleDateFormat("HH:mm:ss", Locale.FRANCE) }
    val permis = remember(tick) { ScanBalises.permissionsAccordees(c) }
    val bluetooth = remember(tick) { ScanBalises.bluetoothActif(c) }

    val demande = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it } && ScanBalises.bluetoothActif(c)) { ScanBalises.vider(); enCours = ScanBalises.demarrer(c) }
        tick++
    }
    DisposableEffect(Unit) { onDispose { ScanBalises.arreter(c) } }
    LaunchedEffect(delai) { suivi.delaiAbsenceMs = when (delai) { "30 secondes" -> 30_000L; "1 minute" -> 60_000L; "5 minutes" -> 300_000L; else -> 120_000L } }

    // Relecture toutes les secondes : état des balises, présence, journal d'arrivées et de départs
    LaunchedEffect(Unit) {
        while (true) {
            val maintenant = System.currentTimeMillis()
            ScanBalises.instantane().forEach { suivi.vue(it.cle, it.derniereVue) }
            suivi.mettreAJour(maintenant).forEach { e ->
                val nom = Reglages.nomBalise(c, e.identifiant).ifBlank { e.identifiant.take(28) }
                journal.add(0, heure.format(Date(e.ms)) + (if (e.arrivee) "  ➜ ARRIVÉE de " else "  ⇠ DÉPART de ") + nom)
            }
            enCours = ScanBalises.enCours(); tick++; delay(1_000)
        }
    }

    Carte("Détection de balises", Icons.Filled.Sensors) {
        Text("Écoute les balises Bluetooth autour de vous (Estimote, iBeacon, Eddystone) et montre en direct ce que le téléphone reçoit. Test seulement : rien n'est enregistré ni envoyé, et l'écoute s'arrête quand vous quittez l'écran.", fontSize = 13.sp, color = Gris)
        when {
            ScanBalises.adaptateur(c) == null -> Text("Ce téléphone n'a pas de Bluetooth.", fontSize = 14.sp, color = Gris)
            !bluetooth -> { Text("Le Bluetooth est désactivé.", fontSize = 14.sp, color = Orange); GrosBouton(Icons.Filled.Bluetooth, "Ouvrir les réglages Bluetooth", Orange) { c.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            !permis -> { Text("Autorisation nécessaire (Bluetooth et localisation : Android l'exige pour voir les balises ; votre position n'est pas utilisée).", fontSize = 14.sp, color = Orange); GrosBouton(Icons.Filled.Lock, "Autoriser la détection", Bleu) { demande.launch(ScanBalises.permissionsRequises()) } }
            enCours -> GrosBouton(Icons.Filled.Stop, "Arrêter la détection", Rouge) { ScanBalises.arreter(c); enCours = false }
            else -> GrosBouton(Icons.Filled.PlayArrow, "Démarrer la détection", Vert) { ScanBalises.vider(); enCours = ScanBalises.demarrer(c) }
        }
        ScanBalises.erreur?.let { Text(it, fontSize = 13.sp, color = Rouge) }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Montrer tous les appareils Bluetooth", Modifier.weight(1f), fontSize = 14.sp, color = BleuNuit); Switch(toutesLesAnnonces, { toutesLesAnnonces = it })
        }
        Liste("Une balise est absente après", delai, listOf("30 secondes", "1 minute", "2 minutes", "5 minutes")) { delai = it }
    }

    val maintenant = System.currentTimeMillis()
    val liste = ScanBalises.instantane().filter { toutesLesAnnonces || it.annonce.format != "Autre" }
        .sortedWith(compareByDescending<ScanBalises.Vue> { suivi.estPresente(it.cle) }.thenByDescending { it.rssiLisse })
    Carte(if (liste.isEmpty()) "Balises détectées" else "Balises détectées (${liste.size})", Icons.Filled.Bluetooth) {
        if (liste.isEmpty()) Text(if (enCours) "Aucune balise pour le moment. Approchez-vous d'une balise ; elles émettent chaque seconde environ." else "Démarrez la détection.", fontSize = 14.sp, color = Gris)
        liste.forEach { v ->
            val present = suivi.estPresente(v.cle)
            val nom = Reglages.nomBalise(c, v.cle)
            val dist = Balises.libelleDistance(Balises.distanceM(v.rssiLisse, v.annonce.puissance1m))
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { detail = if (detail == v.cle) null else v.cle }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (present) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked, null, tint = if (present) Vert else Gris, modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(nom.ifBlank { v.annonce.nom ?: v.annonce.format + " " + v.cle.takeLast(8) }, fontSize = 16.sp, color = BleuNuit)
                        Text("${v.annonce.format} · ${if (present) "présente" else "absente"} · vue il y a ${(maintenant - v.derniereVue) / 1000} s", fontSize = 12.sp, color = if (present) Vert else Gris)
                    }
                    Column(horizontalAlignment = Alignment.End) { Text("${v.rssiLisse.toInt()} dBm", fontSize = 15.sp, color = BleuNuit); Text(dist, fontSize = 12.sp, color = Gris) }
                }
                if (detail == v.cle) Column(Modifier.padding(start = 32.dp, top = 4.dp)) {
                    Text(v.annonce.resume, fontSize = 12.sp, color = Gris)
                    Text("Adresse ${v.adresse} · ${v.nbAnnonces} annonces · signal brut ${v.rssiBrut} dBm", fontSize = 12.sp, color = Gris)
                    v.annonce.details.forEach { Text(it, fontSize = 11.sp, color = Gris) }
                    Text("✎ Donner un nom à cette balise", fontSize = 13.sp, color = Bleu, modifier = Modifier.padding(top = 4.dp).clickable { nommer = v })
                }
            }
            HorizontalDivider()
        }
        Text("La distance est un ordre de grandeur : murs, corps humain et orientation du téléphone font varier le signal.", fontSize = 11.sp, color = Gris, modifier = Modifier.padding(top = 6.dp))
    }

    if (journal.isNotEmpty()) Carte("Arrivées et départs (simulation du pointage)", Icons.Filled.Checklist) {
        journal.take(40).forEach { Text(it, fontSize = 13.sp, color = BleuNuit, modifier = Modifier.padding(vertical = 2.dp)) }
        GrosBouton(Icons.Filled.ContentCopy, "Copier le journal", Gris) {
            (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Journal balises", journal.reversed().joinToString("\n")))
        }
    }

    nommer?.let { v ->
        var texte by remember(v.cle) { mutableStateOf(Reglages.nomBalise(c, v.cle)) }
        AlertDialog(onDismissRequest = { nommer = null }, title = { Text("Nom de la balise") },
            text = { Column { Text(v.annonce.resume, fontSize = 12.sp, color = Gris); Champ("Nom (ex. Awa, Cuisine, Porte)", texte, { texte = it }) } },
            confirmButton = { Tuile(Icons.Filled.Check, "Valider", Orange) { Reglages.nomBalise(c, v.cle, texte.trim()); nommer = null } },
            dismissButton = { Tuile(Icons.Filled.Close, "Annuler", Orange) { nommer = null } })
    }
}
