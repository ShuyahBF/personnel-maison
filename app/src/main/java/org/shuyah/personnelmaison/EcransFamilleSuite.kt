package org.shuyah.personnelmaison

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// =====================================================================================================================
// « MA FAMILLE » (suite) : ajout d'un proche par contact NFC, partage de la position, position d'un proche.
// =====================================================================================================================

/**
 * Ajout d'un proche par CONTACT NFC (deux téléphones dos à dos, si les deux ont le NFC). Le téléphone qui invite propose un code (valable 15 minutes) ;
 * l'autre le lit en l'approchant. Les deux téléphones échangent aussi l'empreinte de leur clé : si elle concorde avec la clé annoncée par le serveur,
 * le lien est vérifié AUTOMATIQUEMENT (le contact physique remplace la comparaison du numéro de sécurité).
 */
@Composable
fun CarteNfcFamille(surNouveauLien: (String) -> Unit) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf<String?>(null) }           // « inviter », « lire » ou null
    var message by remember { mutableStateOf("") }
    val nfc = remember(mode) { FamilleNfc.etatNfc(c) }                // 0 absent, 1 désactivé, 2 prêt

    // Quitter l'écran ou changer de mode arrête le mode lecteur et retire l'invitation NFC
    DisposableEffect(mode) {
        onDispose { FamilleNfc.activite(c)?.let { FamilleNfc.arreterLecteur(it) }; FamilleNfc.Etat.arreter() }
    }

    Carte("Ajouter un proche par NFC", Icons.Filled.Nfc) {
        when (nfc) {
            0 -> Text("Ce téléphone n'a pas de NFC : utilisez le code d'invitation ci-dessous.", fontSize = 14.sp, color = Gris)
            1 -> {
                Text("Le NFC est désactivé sur ce téléphone.", fontSize = 14.sp, color = Gris)
                GrosBouton(Icons.Filled.Settings, "Activer le NFC", Orange) { c.startActivity(Intent(Settings.ACTION_NFC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            else -> {
                Text("Si les deux téléphones ont le NFC : l'un invite, l'autre ajoute, dos contre dos. Le lien est alors vérifié automatiquement.", fontSize = 13.sp, color = Gris)
                GrosBouton(Icons.Filled.NearMe, "J'invite (ce téléphone est la carte)", Bleu) {
                    message = ""
                    scope.launch {
                        try { FamilleNfc.Etat.proposer(ClientFamille.inviter(c).filter { it.isLetterOrDigit() }.let { it.take(4) + "-" + it.drop(4) }, ClientFamille.monEmpreinte(c)); mode = "inviter"; message = "Approchez maintenant le téléphone de votre proche (dos contre dos). Invitation valable 15 minutes." }
                        catch (e: ClientFamille.Erreur) { message = e.message.orEmpty() }
                    }
                }
                GrosBouton(Icons.Filled.Sensors, "J'ajoute (ce téléphone lit)", Vert) {
                    val a = FamilleNfc.activite(c)
                    if (a == null) message = "Écran indisponible, réessayez."
                    else {
                        mode = "lire"; message = "Approchez ce téléphone de celui de votre proche, qui doit avoir appuyé sur « J'invite »."
                        FamilleNfc.demarrerLecteur(a, ClientFamille.monEmpreinte(c), { code, empreinteA ->
                            scope.launch {
                                try {
                                    val id = ClientFamille.accepter(c, code)
                                    val lien = ClientFamille.liens(c).firstOrNull { it.id == id }
                                    // L'empreinte reçue par NFC doit être celle de la clé que le serveur annonce : sinon, vérification manuelle
                                    if (lien != null && FamilleCrypto.empreinte(lien.clePublique) == empreinteA) { ClientFamille.verifier(c, id); message = "Lien avec ${lien.nom} créé et vérifié par NFC." }
                                    else message = "Lien créé, mais l'empreinte NFC ne concorde pas : comparez le numéro de sécurité."
                                    mode = null; surNouveauLien(id)
                                } catch (e: ClientFamille.Erreur) { message = e.message.orEmpty(); mode = null }
                            }
                        }, { erreur -> scope.launch { message = erreur } })
                    }
                }
            }
        }
        if (message.isNotBlank()) Text(message, fontSize = 13.sp, color = if (message.startsWith("Lien")) Vert else Gris, modifier = Modifier.padding(top = 6.dp))
    }
}

/**
 * Partage de MA position : consentement, domicile et périmètre, mode, lieux, et interrupteur du suivi (notification permanente). La position n'est envoyée,
 * chiffrée, qu'aux proches cochés dans la fiche de chacun.
 */
@Composable
fun CartePositionFamille() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val domicile = remember(tick) { ReglagesFamille.domicile(c) }
    val lieux = remember(tick) { ReglagesFamille.lieux(c) }
    val mode = remember(tick) { ReglagesFamille.mode(c) }
    val actif = remember(tick) { ReglagesFamille.actif(c) }
    var message by remember { mutableStateOf("") }
    var nouveauLieu by remember { mutableStateOf("") }
    var rayonTexte by remember { mutableStateOf((domicile?.rayonM ?: 100).toString()) }
    var enAttente by remember { mutableStateOf<String?>(null) }       // action à relancer après l'autorisation : « suivi », « domicile », « lieu »

    fun position(apres: (android.location.Location) -> Unit) {
        message = "Recherche de votre position…"
        Localisation.obtenir(c) { l -> if (l == null) message = "Position introuvable : activez la localisation du téléphone." else { message = ""; apres(l) } }
    }
    fun executer(action: String) {
        when (action) {
            "suivi" -> if (!Localisation.activee(c)) message = "Activez la localisation du téléphone." else { ServicePosition.demarrer(c); tick++ }
            "domicile" -> position { l -> ReglagesFamille.domicile(c, FamillePosition.Lieu("Domicile", l.latitude, l.longitude, rayonTexte.toIntOrNull() ?: 100)); tick++ }
            "lieu" -> position { l -> ReglagesFamille.lieux(c, lieux + FamillePosition.Lieu(nouveauLieu.trim(), l.latitude, l.longitude, 100)); nouveauLieu = ""; tick++ }
        }
    }
    val demande = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val a = enAttente; enAttente = null
        if (Localisation.permissionAccordee(c) && a != null) executer(a) else message = "Autorisation de localisation refusée."
    }
    fun avecAutorisation(action: String) {
        if (Localisation.permissionAccordee(c)) executer(action)
        else { enAttente = action; demande.launch(if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
    }

    Carte("Ma position", Icons.Filled.MyLocation) {
        Text("Votre position n'est envoyée, chiffrée de bout en bout, qu'aux proches que vous cochez dans leur fiche. Une notification permanente indique que le partage est actif, et vous pouvez l'arrêter à tout moment : votre position est alors effacée du serveur.", fontSize = 13.sp, color = Gris)
        Liste("Quand partager", if (mode == FamillePosition.MODE_TOUJOURS) "En permanence" else "Seulement hors du domicile", listOf("Seulement hors du domicile", "En permanence")) {
            ReglagesFamille.mode(c, if (it == "En permanence") FamillePosition.MODE_TOUJOURS else FamillePosition.MODE_HORS_DOMICILE); tick++
        }
        Text(if (domicile == null) "Domicile : non défini." else "Domicile défini (périmètre de ${domicile.rayonM} m).", fontSize = 14.sp, color = BleuNuit, modifier = Modifier.padding(top = 6.dp))
        Liste("Rayon du périmètre du domicile", "$rayonTexte m", FamillePosition.RAYONS_M.map { "$it m" }) {
            rayonTexte = it.removeSuffix(" m"); domicile?.let { d -> ReglagesFamille.domicile(c, d.copy(rayonM = rayonTexte.toInt())); tick++ }
        }
        GrosBouton(Icons.Filled.Home, if (domicile == null) "Définir le domicile ici" else "Redéfinir le domicile ici", Bleu) { avecAutorisation("domicile") }

        Text("Autres lieux", fontSize = 16.sp, color = BleuNuit, modifier = Modifier.padding(top = 10.dp))
        lieux.forEach { l ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("${l.nom} · ${l.rayonM} m", Modifier.weight(1f), fontSize = 14.sp, color = BleuNuit)
                Icon(Icons.Filled.Close, "Retirer ce lieu", tint = Rouge, modifier = Modifier.size(24.dp).clickableSimple { ReglagesFamille.lieux(c, lieux - l); tick++ })
            }
        }
        Liste("Nom du lieu à enregistrer ici", nouveauLieu, FamillePosition.LIEUX_USUELS, valeurLibre = true) { nouveauLieu = it }
        GrosBouton(Icons.Filled.AddLocation, "Enregistrer ce lieu (ma position actuelle)", Vert, actif = nouveauLieu.trim().length >= 2) { avecAutorisation("lieu") }

        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        if (actif) GrosBouton(Icons.Filled.LocationOff, "Arrêter le partage de ma position", Rouge) { ServicePosition.arreter(c); tick++ }
        else GrosBouton(Icons.Filled.LocationOn, "Démarrer le partage de ma position", Vert, actif = domicile != null || mode == FamillePosition.MODE_TOUJOURS) { avecAutorisation("suivi") }
        if (!actif && domicile == null && mode == FamillePosition.MODE_HORS_DOMICILE) Text("Définissez d'abord votre domicile (ou choisissez « En permanence »).", fontSize = 12.sp, color = Gris)
        if (message.isNotBlank()) Text(message, fontSize = 13.sp, color = Gris, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Un élément cliquable sans importer `clickable` partout. */
private fun Modifier.clickableSimple(surAppui: () -> Unit) = this.then(Modifier.clickable { surAppui() })

/**
 * Fiche d'un proche vérifié : interrupteur « Partager ma position avec lui » et dernière position qu'il partage (relue toutes les 15 secondes), avec
 * « Voir sur la carte ».
 */
@Composable
fun CartePositionProche(lien: ClientFamille.Lien) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var partage by remember { mutableStateOf(lien.id in ReglagesFamille.partageAvec(c)) }
    var vue by remember { mutableStateOf<ClientFamille.PositionVue?>(null) }
    var charge by remember { mutableStateOf(false) }
    var maintenant by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lien.id) {
        while (true) {
            try { vue = ClientFamille.positionDe(c, lien) } catch (e: ClientFamille.Erreur) { /* hors ligne : on garde la dernière position */ }
            charge = true; maintenant = System.currentTimeMillis(); delay(15_000)
        }
    }
    Carte("Position", Icons.Filled.Place) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Partager ma position avec ${lien.nom}", Modifier.weight(1f), fontSize = 15.sp, color = BleuNuit)
            Switch(partage, { oui ->
                partage = oui; ReglagesFamille.partager(c, lien.id, oui)
                if (!oui) scope.launch { try { ClientFamille.effacerPosition(c, lien.id) } catch (e: ClientFamille.Erreur) {} }
            })
        }
        if (partage && !ReglagesFamille.actif(c)) Text("Le partage n'est pas démarré : démarrez-le dans « Ma position » (écran Ma famille).", fontSize = 12.sp, color = Orange)
        Text("Position de ${lien.nom}", fontSize = 16.sp, color = BleuNuit, modifier = Modifier.padding(top = 10.dp))
        val v = vue
        if (v == null) Text(if (charge) "${lien.nom} ne partage pas sa position pour le moment." else "Chargement…", fontSize = 14.sp, color = Gris)
        else {
            val p = v.position
            Text(FamillePosition.libelleEtat(p), fontSize = 18.sp, color = BleuNuit)
            Text("Mise à jour ${FamillePosition.libelleAge(p.ms, maintenant)} · précision ${p.precisionM} m", fontSize = 12.sp, color = Gris)
            GrosBouton(Icons.Filled.Map, "Voir sur la carte", Bleu) {
                try { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${p.lat},${p.lon}?q=${p.lat},${p.lon}(${Uri.encode(lien.nom)})")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
            }
        }
    }
}
