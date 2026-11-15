package org.shuyah.personnelmaison

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Messages courts : « en cours » dès l'appui, puis le résultat (jamais d'action silencieuse). */
private object MsgTele {
    fun court(c: android.content.Context, t: String) = android.widget.Toast.makeText(c.applicationContext, t, android.widget.Toast.LENGTH_SHORT).show()
    fun long(c: android.content.Context, t: String) = android.widget.Toast.makeText(c.applicationContext, t, android.widget.Toast.LENGTH_LONG).show()
}

/**
 * ÉCRAN « MA TÉLÉ » (Plus → Ma Télé) : lire sur un téléviseur les vidéos proposées par SAWALI, et les piloter depuis ce téléphone.
 *
 *  1. ABONNEMENT : la projection est réservée aux abonnés ; les formules (prix selon le nombre d'appareils du foyer et de postes TV) s'achètent ici (page de paiement).
 *  2. MES TÉLÉS : on appaire une télé avec le code affiché par sa page /tv ; le nombre de télés dépend de la formule.
 *  3. TÉLÉCOMMANDE : lecture, pause, −10 s, +10 s, curseur de position, arrêt ; la télé renvoie sa position toutes les 2 secondes.
 *  4. VIDÉOS : celles que l'administrateur propose aux familles.
 * SEUL LE DÉTENTEUR de ce téléphone pilote SA télé : le serveur refuse l'administrateur et les autres membres de la famille.
 * Chaque action porte un pictogramme et une légende ; chaque actualisation annonce « en cours » puis son résultat (règles du propriétaire).
 */
@Composable
fun EcranTele(retour: () -> Unit) = Page("Ma Télé", retour) {
    val c = LocalContext.current
    val portee = rememberCoroutineScope()
    if (!ClientFamille.inscrit(c)) {
        Carte("Ma télé", Icons.Filled.Tv) {
            Text("Pour projeter des vidéos sur la télé, ce téléphone doit d'abord avoir son compte « Ma famille » (il identifie son détenteur). " +
                "Ouvrez Plus → Ma famille et créez votre compte, puis revenez ici.", fontSize = 14.sp)
        }
        return@Page
    }

    var abo by remember { mutableStateOf<Tele.Abonnement?>(null) }
    var ecrans by remember { mutableStateOf<List<Tele.Ecran>>(emptyList()) }
    var videos by remember { mutableStateOf<List<Tele.Video>>(emptyList()) }
    var occupe by remember { mutableStateOf(false) }
    var choisi by remember { mutableStateOf("") }                         // télé choisie pour la télécommande
    var code by remember { mutableStateOf("") }; var nomTele by remember { mutableStateOf("") }
    var glisse by remember { mutableStateOf<Float?>(null) }               // position choisie en faisant glisser le curseur (null : il suit la lecture)
    var aRetirer by remember { mutableStateOf<Tele.Ecran?>(null) }

    /** Appel réseau hors écran, puis retour sur l'écran avec la réponse. */
    fun appel(methode: String, chemin: String, corps: String? = null, suite: (Tele.Reponse) -> Unit) {
        occupe = true
        portee.launch { val r = withContext(Dispatchers.IO) { Tele.appeler(c, methode, chemin, corps) }; occupe = false; suite(r) }
    }
    /** Paiement commencé : on garde son numéro ; à chaque ouverture et chaque actualisation on demande à SAWALI s'il est payé (l'abonnement s'active alors). */
    fun verifierAchat(suite: () -> Unit) {
        val depot = ClientFamille.prefs(c).getString("tele_depot", null)
        if (depot == null) { suite(); return }
        appel("POST", "/achat", JSONObject().put("deposit_id", depot).toString()) { r ->
            when (Tele.statutAchat(r)) {
                "paye" -> { ClientFamille.prefs(c).edit().remove("tele_depot").apply(); MsgTele.long(c, "✅ Paiement reçu : votre abonnement est activé.") }
                "echec" -> { ClientFamille.prefs(c).edit().remove("tele_depot").apply(); MsgTele.long(c, "Le paiement n'a pas abouti. Vous pouvez recommencer.") }
            }
            suite()
        }
    }
    fun actualiser(annonce: Boolean) {
        if (annonce) MsgTele.court(c, "Actualisation en cours…")
        verifierAchat {
            appel("GET", "/abonnement") { r ->
                val a = Tele.abonnement(r)
                if (a == null) { MsgTele.long(c, "Abonnement illisible : " + r.message()); return@appel }
                abo = a
                if (!a.actif) { ecrans = emptyList(); videos = emptyList(); if (annonce) MsgTele.long(c, "Pas d'abonnement actif."); return@appel }
                appel("GET", "/ecrans") { re ->
                    ecrans = Tele.ecrans(re)
                    if (choisi.isEmpty() || ecrans.none { it.id == choisi }) choisi = ecrans.firstOrNull()?.id ?: ""
                    appel("GET", "/videos") { rv -> videos = Tele.videos(rv); if (annonce) MsgTele.long(c, "Actualisé : ${ecrans.size} télé(s), ${videos.size} vidéo(s).") }
                }
            }
        }
    }
    LaunchedEffect(Unit) { actualiser(false) }
    // État de la télé relu toutes les 2 secondes tant que l'écran est ouvert (position, lecture / pause, en ligne)
    LaunchedEffect(abo?.actif) {
        while (abo?.actif == true) {
            delay(2000)
            val r = withContext(Dispatchers.IO) { Tele.appeler(c, "GET", "/ecrans") }
            if (r.ok) ecrans = Tele.ecrans(r)
        }
    }
    fun ordre(corps: JSONObject, annonce: String) {
        if (choisi.isEmpty()) { MsgTele.long(c, "Choisissez d'abord une télé."); return }
        MsgTele.court(c, "$annonce en cours…")
        appel("POST", "/commande", corps.put("ecran_id", choisi).toString()) { r -> MsgTele.long(c, if (r.ok) "$annonce : ordre envoyé à la télé." else "Ordre refusé : " + r.message()) }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ---------------------------------------------------------------- 1. abonnement
        Carte("Abonnement télé", Icons.Filled.WorkspacePremium) {
            val a = abo
            when {
                a == null -> Text("Lecture de l'abonnement…", fontSize = 13.sp)
                a.actif -> {
                    Text("✅ ${a.formule} · encore ${a.joursRestants} jour(s)", fontSize = 15.sp, color = Vert)
                    Text(Tele.libelleLimites(a.appareils, a.postesTv) + " · ${a.postesUtilises}/${a.postesTv} télé(s) appairée(s)", fontSize = 13.sp)
                    if (a.role == "couvert") Text("Vous êtes couvert par l'abonnement de ${a.titulaire}.", fontSize = 13.sp, color = Gris)
                    else if (a.appareilsCouverts.size > 1) Text("Appareils couverts : " + a.appareilsCouverts.joinToString(", "), fontSize = 13.sp, color = Gris)
                }
                else -> Text("La projection sur télé est réservée aux abonnés. Le prix dépend du nombre d'appareils du foyer et de postes TV (téléviseurs).", fontSize = 13.sp)
            }
            OutlinedButton({ actualiser(true) }, enabled = !occupe) { Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Actualiser") }
        }

        // Formules proposées : s'abonner (ou étendre le foyer)
        val formules = abo?.formules.orEmpty()
        if (formules.isNotEmpty() && abo?.role != "couvert") Carte(if (abo?.actif == true) "Étendre mon foyer" else "S'abonner", Icons.Filled.ShoppingCart) {
            formules.forEach { f ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(f.nom, fontSize = 15.sp, color = BleuNuit)
                    Text(Tele.libelleLimites(f.appareils, f.postesTv) + " · ${f.jours} jours", fontSize = 12.sp, color = Gris)
                    if (f.description.isNotBlank()) Text(f.description, fontSize = 12.sp, color = Gris)
                    Button({
                        MsgTele.court(c, "Ouverture de la page de paiement en cours…")
                        appel("POST", "/acheter", JSONObject().put("formule_id", f.id).toString()) { r ->
                            val p = Tele.paiement(r)
                            if (p == null) MsgTele.long(c, "Paiement impossible : " + r.message())
                            else {
                                ClientFamille.prefs(c).edit().putString("tele_depot", p.second).apply()
                                c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.first)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                MsgTele.long(c, "Payez sur la page ouverte, puis revenez ici : l'abonnement s'active tout seul (ou touchez « Actualiser »).")
                            }
                        }
                    }, enabled = !occupe) { Icon(Icons.Filled.Payment, null); Spacer(Modifier.width(6.dp)); Text("Choisir · ${Tele.libellePrix(f.prix)}") }
                }
                HorizontalDivider()
            }
        }

        if (abo?.actif == true) {
            // ---------------------------------------------------------------- 2. mes télés
            Carte("Mes télés", Icons.Filled.Tv) {
                if (ecrans.isEmpty()) Text("Aucune télé appairée.", fontSize = 13.sp, color = Gris)
                ecrans.forEach { e ->
                    Row(Modifier.fillMaxWidth().clickable { choisi = e.id }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(choisi == e.id, { choisi = e.id })
                        Column(Modifier.weight(1f)) {
                            Text(e.nom, fontSize = 15.sp, color = BleuNuit)
                            Text(if (e.enLigne) "● en ligne" else "○ hors ligne (page /tv fermée ?)", fontSize = 12.sp, color = if (e.enLigne) Vert else Gris)
                        }
                        IconButton({ aRetirer = e }, enabled = !occupe) { Icon(Icons.Filled.Delete, "Retirer ${e.nom}", tint = Rouge) }
                    }
                }
                Text("Pour ajouter une télé : sur la télé, ouvrez le navigateur internet et allez à l'adresse sawalismartsystems.com/tv — un code de 6 caractères s'affiche. Saisissez-le ici.",
                    fontSize = 12.sp, color = Gris, modifier = Modifier.padding(top = 6.dp))
                OutlinedTextField(code, { code = it.take(12) }, label = { Text("Code affiché par la télé") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
                OutlinedTextField(nomTele, { nomTele = it.take(60) }, label = { Text("Nom de la télé (ex. Salon)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button({
                    if (!Tele.codeValide(code)) { MsgTele.long(c, "Saisissez le code affiché par la télé."); return@Button }
                    MsgTele.court(c, "Appairage de la télé en cours…")
                    appel("POST", "/appairer", JSONObject().put("code", Tele.codeNormalise(code)).put("nom", nomTele.trim()).toString()) { r ->
                        if (r.ok) { code = ""; nomTele = ""; Tele.idEcran(r)?.let { choisi = it }; MsgTele.long(c, "✅ Télé appairée."); actualiser(false) }
                        else MsgTele.long(c, "Appairage impossible : " + r.message())
                    }
                }, enabled = !occupe) { Icon(Icons.Filled.Link, null); Spacer(Modifier.width(6.dp)); Text("Appairer cette télé") }
            }

            // ---------------------------------------------------------------- 3. télécommande
            val e = ecrans.firstOrNull { it.id == choisi }
            if (e != null) Carte("Lecture sur « ${e.nom} »", Icons.Filled.SmartDisplay) {
                val enCours = e.lecture != "arret" && e.videoId.isNotEmpty()
                val titre = videos.firstOrNull { it.id == e.videoId }?.titre ?: if (enCours) "Vidéo en cours" else "Rien en lecture"
                Text(titre + if (e.lecture == "pause") "  ⏸ en pause" else if (e.lecture == "lecture") "  ▶ en lecture" else "", fontSize = 16.sp, color = BleuNuit)
                if (e.erreur.isNotBlank()) Text("⚠ " + e.erreur, fontSize = 12.sp, color = Rouge)
                // UNE seule jauge : elle avance avec la lecture ; on peut la faire glisser (l'ordre part au relâchement)
                Slider(value = glisse ?: Tele.avancement(e.position, e.duree), onValueChange = { glisse = it },
                    onValueChangeFinished = { val g = glisse; glisse = null; if (g != null && e.duree > 0) ordre(JSONObject().put("action", "position").put("position", g * e.duree), "Déplacement") },
                    enabled = e.duree > 0)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(Tele.minutes((glisse ?: Tele.avancement(e.position, e.duree)) * e.duree), fontSize = 12.sp, color = Gris); Text(Tele.minutes(e.duree), fontSize = 12.sp, color = Gris)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton({ ordre(JSONObject().put("action", "reculer").put("delta", 10), "Retour de 10 s") }, Modifier.weight(1f), enabled = !occupe, contentPadding = PaddingValues(4.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Filled.FastRewind, null); Text("−10 s", fontSize = 11.sp) } }
                    Button({ ordre(JSONObject().put("action", if (e.lecture == "lecture") "pause" else "reprendre"), if (e.lecture == "lecture") "Pause" else "Lecture") }, Modifier.weight(1f), enabled = !occupe, contentPadding = PaddingValues(4.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(if (e.lecture == "lecture") Icons.Filled.Pause else Icons.Filled.PlayArrow, null); Text(if (e.lecture == "lecture") "Pause" else "Lecture", fontSize = 11.sp) } }
                    OutlinedButton({ ordre(JSONObject().put("action", "avancer").put("delta", 10), "Avance de 10 s") }, Modifier.weight(1f), enabled = !occupe, contentPadding = PaddingValues(4.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Filled.FastForward, null); Text("+10 s", fontSize = 11.sp) } }
                    OutlinedButton({ ordre(JSONObject().put("action", "stop"), "Arrêt") }, Modifier.weight(1f), enabled = !occupe, contentPadding = PaddingValues(4.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Filled.Stop, null, tint = Rouge); Text("Arrêter", fontSize = 11.sp, color = Rouge) } }
                }
            }

            // ---------------------------------------------------------------- 4. vidéos
            Carte("Vidéos", Icons.Filled.VideoLibrary) {
                if (videos.isEmpty()) Text("Aucune vidéo n'est proposée pour le moment.", fontSize = 13.sp, color = Gris)
                videos.forEach { v ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(v.titre, fontSize = 15.sp, color = BleuNuit); Text(Tele.taille(v.octets), fontSize = 12.sp, color = Gris) }
                        Button({ ordre(JSONObject().put("action", "lire").put("video_id", v.id), "Lecture de « ${v.titre} »") }, enabled = !occupe && choisi.isNotEmpty()) {
                            Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Lire sur la télé") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    aRetirer?.let { t ->
        AlertDialog(onDismissRequest = { aRetirer = null }, icon = { Icon(Icons.Filled.Delete, null, tint = Rouge) }, title = { Text("Retirer « ${t.nom} » ?") },
            text = { Text("La télé ne sera plus associée à ce téléphone ; le poste TV est libéré.", fontSize = 13.sp) },
            confirmButton = { Button({
                aRetirer = null; MsgTele.court(c, "Retrait de la télé en cours…")
                appel("DELETE", "/ecrans/${t.id}") { r -> MsgTele.long(c, if (r.ok) "Télé retirée." else "Retrait impossible : " + r.message()); actualiser(false) }
            }) { Icon(Icons.Filled.Delete, null); Spacer(Modifier.width(6.dp)); Text("Retirer") } },
            dismissButton = { OutlinedButton({ aRetirer = null }) { Icon(Icons.Filled.Close, null); Spacer(Modifier.width(6.dp)); Text("Annuler") } })
    }
}
