package org.shuyah.personnelmaison

import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * RÉCEPTEUR DE BADGES (Plus → Récepteur de badges) : ce téléphone, posé et branché dans une pièce, écoute les balises Bluetooth (badges) et les signale à SAWALI, qui montre
 * qui est dans quelle zone (SAWALI → Paramètres → « Présence par balises »). Les trois réglages (adresse, identifiant, clé) sont donnés par SAWALI à la création du récepteur.
 */
@Composable
fun EcranRecepteur(retour: () -> Unit) = Page("Récepteur de badges", retour) {
    val c = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var adresse by remember { mutableStateOf(PresenceRecepteur.url(c)) }
    var identifiant by remember { mutableStateOf(PresenceRecepteur.identifiant(c)) }
    var cle by remember { mutableStateOf(PresenceRecepteur.cle(c)) }
    var voirCle by remember { mutableStateOf(false) }
    var actif by remember { mutableStateOf(PresenceRecepteur.actif(c)) }
    val permis = remember(tick) { ScanBalises.permissionsAccordees(c) }
    val bluetooth = remember(tick) { ScanBalises.bluetoothActif(c) }

    // Rafraîchit l'état (envois, badges entendus) deux fois par seconde tant que l'écran est ouvert
    LaunchedEffect(Unit) { while (true) { delay(500); tick++ } }

    val demande = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (ScanBalises.permissionsAccordees(c) && ScanBalises.bluetoothActif(c)) { ServiceRecepteur.demarrer(c); actif = true }
        tick++
    }
    val demarrer = {
        PresenceRecepteur.enregistrer(c, adresse, identifiant, cle)
        val manque = ScanBalises.permissionsRequises().toMutableList().apply { if (Build.VERSION.SDK_INT >= 33) add(android.Manifest.permission.POST_NOTIFICATIONS) }.filter { c.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (manque.isEmpty() && bluetooth) { ServiceRecepteur.demarrer(c); actif = true } else demande.launch(manque.toTypedArray())
    }

    Carte("Principe", Icons.Filled.Sensors) {
        Text("Posez ce téléphone dans la pièce à surveiller et laissez-le BRANCHÉ sur secteur. Il écoute les badges (balises Estimote, iBeacon, Eddystone) et les signale à SAWALI toutes les 5 secondes. " +
            "Les autres appareils Bluetooth (téléphones, écouteurs) ne sont jamais envoyés.", fontSize = 14.sp, color = BleuNuit)
    }

    Carte("Réglages donnés par SAWALI", Icons.Filled.Settings) {
        Champ("Adresse du serveur", adresse, { adresse = it })
        Champ("Identifiant du récepteur (site:récepteur)", identifiant, { identifiant = it })
        OutlinedTextField(cle, { cle = it }, label = { Text("Clé du récepteur") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            visualTransformation = if (voirCle) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation())
        Text(if (voirCle) "Masquer la clé" else "Afficher la clé", fontSize = 13.sp, color = Bleu, modifier = Modifier.padding(top = 2.dp).clickable { voirCle = !voirCle })
        Text("SAWALI → Paramètres → « Présence par balises » → créer le site, les zones, puis le récepteur : l'identifiant et la clé s'affichent UNE SEULE FOIS.", fontSize = 12.sp, color = Gris, modifier = Modifier.padding(top = 4.dp))
    }

    Carte("Écoute et envoi", Icons.Filled.CellTower) {
        if (!bluetooth) Text("Le Bluetooth est désactivé : activez-le dans les réglages du téléphone.", fontSize = 14.sp, color = Orange)
        else if (!permis && !actif) Text("Les autorisations Bluetooth et localisation seront demandées au démarrage.", fontSize = 13.sp, color = Gris)
        if (actif) {
            val s = PresenceRecepteur.Statut
            val maintenant = System.currentTimeMillis()
            Text("Récepteur ACTIF · ${s.envois} envoi(s) · ${ScanBalises.instantane().count { it.annonce.format in PresenceRecepteur.FORMATS_ACCEPTES && maintenant - it.derniereVue < 10_000 }} badge(s) entendu(s) à l'instant",
                fontSize = 15.sp, color = Vert)
            Text("Dernier envoi : " + (if (s.dernierEnvoiMs == 0L) "aucun pour le moment" else "il y a ${(maintenant - s.dernierEnvoiMs) / 1000} s, ${s.dernierMessage}"), fontSize = 13.sp, color = if (s.dernierOk == false) Rouge else BleuNuit)
            GrosBouton(Icons.Filled.Stop, "Arrêter le récepteur", Rouge) { ServiceRecepteur.arreter(c); actif = false }
        } else {
            GrosBouton(Icons.Filled.PlayArrow, "Démarrer le récepteur", Vert, actif = PresenceRecepteur.configure(identifiant, cle)) { demarrer() }
            if (!PresenceRecepteur.configure(identifiant, cle)) Text("Saisissez l'identifiant (avec « : ») et la clé fournis par SAWALI.", fontSize = 12.sp, color = Gris)
        }
        Text("Pour qu'Android ne coupe pas l'écoute : laissez le téléphone branché et, dans les réglages de la batterie, autorisez l'application à fonctionner sans restriction.", fontSize = 12.sp, color = Gris, modifier = Modifier.padding(top = 6.dp))
    }
}
