package org.shuyah.personnelmaison

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * PROFIL DE L'UTILISATEUR : nom, prénoms, téléphones (3 au plus, un par défaut), e-mail, adresse complète avec point de repère,
 * POSITION GPS de la maison (un appui sur « Ma position »), personne à prévenir et remarques. Tout reste sur le téléphone, chiffré.
 */
@Composable
fun EcranProfil() {
    val c = LocalContext.current
    val dao = remember { Base.dao(c) }
    val scope = rememberCoroutineScope()
    val numeros = remember { mutableStateListOf<NumeroEdit>() }
    var charge by remember { mutableStateOf(false) }
    var nom by remember { mutableStateOf("") }; var prenoms by remember { mutableStateOf("") }; var email by remember { mutableStateOf("") }
    var adresse by remember { mutableStateOf("") }; var quartier by remember { mutableStateOf("") }; var ville by remember { mutableStateOf("") }; var repere by remember { mutableStateOf("") }
    var latitude by remember { mutableStateOf<Double?>(null) }; var longitude by remember { mutableStateOf<Double?>(null) }
    var precision by remember { mutableStateOf<Float?>(null) }; var geoLe by remember { mutableStateOf<Long?>(null) }
    var urgenceNom by remember { mutableStateOf("") }; var urgenceTel by remember { mutableStateOf("") }; var remarques by remember { mutableStateOf("") }
    var occupeGeo by remember { mutableStateOf(false) }; var message by remember { mutableStateOf<String?>(null) }; var enregistre by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        dao.profilUneFois()?.let { p ->
            nom = p.nom; prenoms = p.prenoms; email = p.email; adresse = p.adresse; quartier = p.quartier; ville = p.ville; repere = p.repere
            latitude = p.latitude; longitude = p.longitude; precision = p.precisionM; geoLe = p.geolocaliseLe
            urgenceNom = p.urgenceNom; urgenceTel = p.urgenceTelephone; remarques = p.remarques
        }
        numeros.addAll(dao.numerosEdit("UTILISATEUR", 1)); charge = true
    }

    // Relevé de la position (après accord de l'utilisateur dans la fenêtre du système)
    fun releverPosition() {
        if (!Localisation.activee(c)) { message = "Activez la localisation (GPS) dans les réglages du téléphone."; return }
        occupeGeo = true; message = null
        Localisation.obtenir(c) { l ->
            occupeGeo = false
            if (l == null) message = "Position introuvable. Sortez à l'air libre et réessayez."
            else { latitude = l.latitude; longitude = l.longitude; precision = if (l.hasAccuracy()) l.accuracy else null; geoLe = System.currentTimeMillis(); message = "Position relevée ✓ (pensez à enregistrer)" }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> if (r.values.any { it }) releverPosition() else message = "Autorisation de localisation refusée." }

    val completude = Calculs.completudeProfil(ProfilUtilisateur(nom = nom, prenoms = prenoms, email = email, adresse = adresse, ville = ville, repere = repere, latitude = latitude, longitude = longitude,
        urgenceNom = urgenceNom, urgenceTelephone = urgenceTel), numeros.count { it.numero.isNotBlank() })

    Page("Mon profil") {
        Carte {
            Text("Profil complété à $completude %", fontSize = 16.sp, color = BleuNuit)
            LinearProgressIndicator(progress = { completude / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), color = Vert)
            Text("Ces informations restent sur votre téléphone, chiffrées. Elles ne sont jamais envoyées.", fontSize = 12.sp, color = Gris, modifier = Modifier.padding(top = 6.dp))
        }
        Carte("Identité", Icons.Filled.Person) {
            Champ("Nom", nom, { nom = it; enregistre = false }); Champ("Prénoms", prenoms, { prenoms = it; enregistre = false })
            Champ("E-mail", email, { email = it; enregistre = false }, KeyboardType.Email)
        }
        Carte("Téléphones", Icons.Filled.Phone) { EditeurNumeros(numeros) }
        Carte("Adresse", Icons.Filled.Home) {
            Champ("Adresse (rue, porte, n° de parcelle…)", adresse, { adresse = it; enregistre = false })
            Champ("Quartier, secteur ou village", quartier, { quartier = it; enregistre = false }); Champ("Ville", ville, { ville = it; enregistre = false })
            Champ("Point de repère (ex. derrière le marché, portail bleu)", repere, { repere = it; enregistre = false }, lignes = 2)
        }
        Carte("Position GPS de la maison", Icons.Filled.LocationOn) {
            if (latitude != null && longitude != null) {
                Text(Calculs.coordonnees(latitude!!, longitude!!), fontSize = 18.sp, color = BleuNuit)
                Text("Relevée le ${horodatage(geoLe ?: 0)}" + (precision?.let { " · précision ${it.toInt()} m" } ?: ""), fontSize = 12.sp, color = Gris)
            } else Text("Aucune position enregistrée.", fontSize = 14.sp, color = Gris)
            if (occupeGeo) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text("  Recherche de la position…", fontSize = 13.sp) }
            message?.let { Text(it, fontSize = 13.sp, color = if (it.startsWith("Position relevée")) Vert else Rouge, modifier = Modifier.padding(top = 6.dp)) }
            Spacer(Modifier.height(8.dp))
            RangeeTuiles {
                Tuile(Icons.Filled.MyLocation, "Ma position", Vert, actif = !occupeGeo) {
                    if (Localisation.permissionAccordee(c)) releverPosition()
                    else permission.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
                }
                if (latitude != null && longitude != null) {
                    Tuile(Icons.Filled.Map, "Voir sur la carte", Bleu) {
                        try { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude(Ma maison)")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        catch (e: Exception) { message = "Aucune application de cartes trouvée." }
                    }
                    Tuile(Icons.Filled.Share, "Partager", Bleu) { partager(c, "Ma position", "Ma position : https://maps.google.com/?q=$latitude,$longitude") }
                    Tuile(Icons.Filled.LocationOff, "Effacer", Rouge) { latitude = null; longitude = null; precision = null; geoLe = null; enregistre = false }
                }
            }
        }
        Carte("Personne à prévenir en cas d'urgence", Icons.Filled.ContactPhone) {
            Champ("Nom", urgenceNom, { urgenceNom = it; enregistre = false }); Champ("Téléphone", urgenceTel, { urgenceTel = it; enregistre = false }, KeyboardType.Phone)
        }
        Carte("Autres informations utiles", Icons.Filled.Notes) { Champ("Remarques (langue parlée, informations pour les personnels…)", remarques, { remarques = it; enregistre = false }, lignes = 3) }
        RangeeTuiles {
            Tuile(Icons.Filled.Check, "Enregistrer", Vert, actif = charge) {
                scope.launch {
                    dao.enregistrerProfil(ProfilUtilisateur(1, nom.trim(), prenoms.trim(), email.trim(), adresse.trim(), quartier.trim(), ville.trim(), repere.trim(), latitude, longitude, precision, geoLe,
                        urgenceNom.trim(), urgenceTel.trim(), remarques.trim()))
                    dao.enregistrerNumeros("UTILISATEUR", 1, numeros.toList())
                    enregistre = true
                }
            }
        }
        if (enregistre) Text("Profil enregistré ✓", fontSize = 15.sp, color = Vert)
    }
}
