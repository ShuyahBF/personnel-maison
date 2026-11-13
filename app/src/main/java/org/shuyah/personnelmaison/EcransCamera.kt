package org.shuyah.personnelmaison

import android.Manifest
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// =====================================================================================================================
// CAMÉRA PARTAGÉE avec la famille : réglages et interrupteur (écran Ma famille), partage et visionnage (fiche d'un proche).
// =====================================================================================================================

/** Réglages de MA caméra partagée : consentement, caméra avant ou arrière, intervalle entre deux photos, mode, et interrupteur (notification permanente). */
@Composable
fun CarteCameraFamille() {
    val c = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val actif = remember(tick) { ReglagesFamille.cameraActive(c) }
    val avant = remember(tick) { ReglagesFamille.cameraAvant(c) }
    val intervalle = remember(tick) { ReglagesFamille.cameraIntervalleS(c) }
    val siRegarde = remember(tick) { ReglagesFamille.cameraSeulementSiRegarde(c) }
    var message by remember { mutableStateOf("") }
    val demande = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (c.checkSelfPermission(Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) { ServiceCamera.demarrer(c); tick++ } else message = "Autorisation de la caméra refusée."
    }

    Carte("Ma caméra", Icons.Filled.Videocam) {
        Text("La caméra de ce téléphone peut être regardée à distance par les proches que vous cochez dans leur fiche : une photo est rafraîchie toutes les $intervalle secondes, chiffrée de bout en bout. Une notification permanente indique que la caméra est partagée, et vous pouvez l'arrêter à tout moment : les images sont alors effacées du serveur.", fontSize = 13.sp, color = Gris)
        Liste("Caméra", if (avant) "Avant" else "Arrière", listOf("Arrière", "Avant")) { ReglagesFamille.cameraAvant(c, it == "Avant"); tick++ }
        Liste("Une photo toutes les", "$intervalle secondes", FamilleImage.INTERVALLES_S.map { "$it secondes" }) { ReglagesFamille.cameraIntervalleS(c, it.removeSuffix(" secondes").toInt()); tick++ }
        Liste("Quand envoyer", if (siRegarde) "Seulement quand un proche regarde" else "En continu", listOf("Seulement quand un proche regarde", "En continu")) { ReglagesFamille.cameraSeulementSiRegarde(c, it.startsWith("Seulement")); tick++ }
        Text(if (siRegarde) "Économise vos données mobiles : aucune image ne part tant que personne ne regarde." else "Une image est envoyée à chaque intervalle, même si personne ne regarde (consomme des données mobiles).", fontSize = 12.sp, color = Gris)
        if (actif) GrosBouton(Icons.Filled.VideocamOff, "Arrêter la caméra partagée", Rouge) { ServiceCamera.arreter(c); tick++ }
        else GrosBouton(Icons.Filled.Videocam, "Démarrer la caméra partagée", Vert) {
            if (c.checkSelfPermission(Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) { ServiceCamera.demarrer(c); tick++ }
            else demande.launch(if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS) else arrayOf(Manifest.permission.CAMERA))
        }
        if (message.isNotBlank()) Text(message, fontSize = 13.sp, color = Gris, modifier = Modifier.padding(top = 4.dp))

        // ---- Diagnostic en direct : pourquoi une image part ou ne part pas ----
        var tic by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) { while (true) { tic++; delay(1_000) } }
        val maintenant = remember(tic) { System.currentTimeMillis() }
        val choisis = remember(tic) { ReglagesFamille.partageCameraAvec(c).size }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Diagnostic", fontSize = 15.sp, color = BleuNuit)
        @Composable fun Ligne(ok: Boolean?, texte: String) = Row(Modifier.padding(vertical = 1.dp)) {
            Icon(when (ok) { true -> Icons.Filled.CheckCircle; false -> Icons.Filled.Cancel; null -> Icons.Filled.Info }, null, tint = when (ok) { true -> Vert; false -> Rouge; null -> Bleu }, modifier = Modifier.padding(end = 6.dp, top = 1.dp).size(18.dp))
            Text(texte, fontSize = 12.sp, color = BleuNuit)
        }
        Ligne(actif, if (actif) "Service de la caméra démarré" else "Service de la caméra arrêté : appuyez sur « Démarrer la caméra partagée »")
        Ligne(c.checkSelfPermission(Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED, "Autorisation de la caméra")
        Ligne(choisis > 0, if (choisis > 0) "$choisis proche(s) coché(s) pour recevoir la caméra" else "Aucun proche coché (fiche du proche → « Partager ma caméra avec lui »)")
        Ligne(null, "Mode : " + if (siRegarde) "seulement quand un proche regarde" else "en continu")
        if (EtatCamera.dernierTourMs > 0) Ligne(null, "Dernier tour : il y a ${(maintenant - EtatCamera.dernierTourMs) / 1000} s (un tour toutes les $intervalle s) · ${EtatCamera.tours} tour(s)")
        Ligne(EtatCamera.erreur?.let { false }, "État : ${EtatCamera.etape}")
        EtatCamera.erreur?.let { Ligne(false, "Dernière erreur : $it") }
        Ligne(if (EtatCamera.imagesEnvoyees > 0) true else null, "Images envoyées : ${EtatCamera.imagesEnvoyees}" + if (EtatCamera.dernierEnvoiMs > 0) " (dernière il y a ${(maintenant - EtatCamera.dernierEnvoiMs) / 1000} s, ${EtatCamera.dernierePhotoKo} Ko)" else "")

        // ---- Test local de la caméra : prend une photo ici, sans rien envoyer ----
        var essai by remember { mutableStateOf("") }
        var apercu by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
        var enCours by remember { mutableStateOf(false) }
        GrosBouton(Icons.Filled.PhotoCamera, if (enCours) "Test en cours…" else "Tester la caméra (sans envoi)", Gris, actif = !enCours) {
            if (c.checkSelfPermission(Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) essai = "Autorisez d'abord la caméra (bouton « Démarrer la caméra partagée »)."
            else { enCours = true; essai = ""; apercu = null; val t0 = System.currentTimeMillis()
                CaptureCamera.photoDetaillee(c, avant) { jpeg, raison ->
                    FamilleNfc.activite(c)?.runOnUiThread {
                        enCours = false
                        if (jpeg != null) { apercu = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size); essai = "✔ Photo prise en ${System.currentTimeMillis() - t0} ms (${jpeg.size / 1024} Ko) : la caméra fonctionne." }
                        else essai = "✖ Échec : $raison"
                    }
                }
            }
        }
        if (essai.isNotBlank()) Text(essai, fontSize = 13.sp, color = if (essai.startsWith("✔")) Vert else Rouge)
        apercu?.let { Image(it.asImageBitmap(), "Test de la caméra", Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.FillWidth) }
    }
}

/**
 * Fiche d'un proche vérifié : interrupteur « Partager ma caméra avec lui », et visionnage de SA caméra : tant que le bouton « Voir » est actif, le signal
 * « je regarde » est renouvelé toutes les 20 secondes et sa dernière image est relue toutes les 5 secondes.
 */
@Composable
fun CarteCameraProche(lien: ClientFamille.Lien) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var partage by remember { mutableStateOf(lien.id in ReglagesFamille.partageCameraAvec(c)) }
    var regarde by remember { mutableStateOf(false) }
    var image by remember { mutableStateOf<ClientFamille.ImageVue?>(null) }
    var maintenant by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var erreur by remember { mutableStateOf("") }
    var dernierSignal by remember { mutableLongStateOf(0L) }          // dernier signal « je regarde » envoyé avec succès
    var reponse by remember { mutableStateOf("") }                    // ce que le serveur a répondu au dernier tour

    // Visionnage : signal « je regarde » toutes les 20 s, image relue toutes les 5 s ; tout s'arrête quand le bouton est relâché ou l'écran quitté
    LaunchedEffect(regarde) {
        if (!regarde) return@LaunchedEffect
        while (true) {
            try {
                if (System.currentTimeMillis() - dernierSignal > 20_000) { ClientFamille.signalerRegard(c, lien.id); dernierSignal = System.currentTimeMillis() }
                image = ClientFamille.imageDe(c, lien); erreur = ""
                reponse = if (image == null) "le serveur n'a encore aucune image de ${lien.nom}" else "image reçue"
            } catch (e: ClientFamille.Erreur) { erreur = e.message.orEmpty() }
            maintenant = System.currentTimeMillis(); delay(5_000)
        }
    }

    Carte("Caméra", Icons.Filled.Videocam) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Partager ma caméra avec ${lien.nom}", Modifier.weight(1f), fontSize = 15.sp, color = BleuNuit)
            Switch(partage, { oui ->
                partage = oui; ReglagesFamille.partagerCamera(c, lien.id, oui)
                if (!oui) scope.launch { try { ClientFamille.effacerImage(c, lien.id) } catch (e: ClientFamille.Erreur) {} }
            })
        }
        if (partage && !ReglagesFamille.cameraActive(c)) Text("La caméra n'est pas démarrée : démarrez-la dans « Ma caméra » (écran Ma famille).", fontSize = 12.sp, color = Orange)
        if (regarde) GrosBouton(Icons.Filled.VisibilityOff, "Arrêter de regarder", Rouge) { regarde = false; image = null }
        else GrosBouton(Icons.Filled.Visibility, "Voir la caméra de ${lien.nom}", Bleu) { regarde = true }
        if (regarde) {
            val bitmap = remember(image) { image?.jpeg?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), "Caméra de ${lien.nom}", Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.FillWidth)
                val age = image?.majLe?.let { try { java.time.Instant.parse(it).toEpochMilli() } catch (e: Exception) { null } }
                Text("Image ${age?.let { FamillePosition.libelleAge(it, maintenant) } ?: ""}", fontSize = 12.sp, color = Gris)
            } else Text("En attente de l'image… ${lien.nom} doit avoir démarré sa caméra et vous avoir coché ; la première image arrive en quelques secondes.", fontSize = 13.sp, color = Gris, modifier = Modifier.padding(top = 8.dp))
            if (erreur.isNotBlank()) Text(erreur, fontSize = 12.sp, color = Rouge)
            // Diagnostic du visionnage : ce qui a été fait de ce côté, et ce que doit faire l'autre téléphone
            Text("Signal « je regarde » envoyé il y a ${if (dernierSignal > 0) ((maintenant - dernierSignal) / 1000).toString() + " s" else "—"} · $reponse", fontSize = 11.sp, color = Gris, modifier = Modifier.padding(top = 4.dp))
            if (image == null) Text("Si rien n'arrive : sur le téléphone de ${lien.nom}, (1) « Démarrer la caméra partagée » doit être actif, (2) vous devez être coché dans sa fiche « Partager ma caméra avec ${ClientFamille.nom(c).ifBlank { "lui" }} », (3) l'écran « Ma caméra » → Diagnostic dit pourquoi. La première image arrive au plus tard à la fin de son intervalle.", fontSize = 11.sp, color = Gris)
        }
    }
}
