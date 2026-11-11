package org.shuyah.personnelmaison

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

// ======================= Liste des sociétés =======================

@Composable
fun EcranListeSocietes(ouvrir: (Ecran) -> Unit) {
    val c = LocalContext.current
    val dao = remember { Base.dao(c) }
    val societes by dao.societes().collectAsState(emptyList())
    val personnels by dao.personnel().collectAsState(emptyList())
    val evaluations by dao.toutesEvaluations().collectAsState(emptyList())
    Page("Sociétés de placement", actions = { Tuile(Icons.Filled.AddBusiness, "Ajouter", Orange) { ouvrir(Ecran.FormSociete(null)) } }) {
        if (societes.isEmpty()) Carte { Text("Aucune société. Appuyez sur « Ajouter » en haut à droite.", color = Gris) }
        societes.forEach { s ->
            val notes = evaluations.filter { it.societeId == s.id }.map { it.note }
            Carte(modifier = Modifier.clickable { ouvrir(Ecran.FicheSociete(s.id)) }) {
                Text(s.nom, fontSize = 16.sp, color = BleuNuit)
                Text("${personnels.count { it.societeId == s.id }} personnel(s)", fontSize = 12.sp, color = Gris)
                if (notes.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Etoiles(Math.round(Calculs.moyenne(notes)).toInt(), 16); Text("  ${Calculs.moyenne(notes)} / 5 · ${notes.size} évaluation(s)", fontSize = 12.sp, color = Gris)
                } else Text("Pas encore évaluée", fontSize = 12.sp, color = Gris)
            }
        }
    }
}

// ======================= Fiche de la société =======================

@Composable
fun EcranFicheSociete(id: Long, ouvrir: (Ecran) -> Unit, retour: () -> Unit) {
    val c = LocalContext.current
    val dao = remember { Base.dao(c) }
    val scope = rememberCoroutineScope()
    var societe by remember { mutableStateOf<Societe?>(null) }
    val liste by dao.societes().collectAsState(emptyList())
    val personnels by dao.personnel().collectAsState(emptyList())
    val evaluations by dao.evaluations(id).collectAsState(emptyList())
    var suppression by remember { mutableStateOf(false) }
    var controle by remember { mutableStateOf<List<Pair<Evaluation, List<String>>>>(emptyList()) }     // évaluations dont la remarque cite un nom
    societe = liste.find { it.id == id }
    val s = societe
    if (s == null) { Page("Société", retour) { Text("Société introuvable.") }; return }
    val notes = evaluations.map { it.note }
    Page(s.nom, retour) {
        Carte("Coordonnées", Icons.Filled.Business) {
            Ligne("Téléphone", s.telephone); Ligne("Adresse", s.adresse); Ligne("Remarques", s.remarques)
        }
        // Numéros de paiement mobile : copier ou appeler
        Carte("Paiement mobile", Icons.Filled.PhoneAndroid) {
            LigneNumero("Orange Money", s.orange); LigneNumero("Moov Money", s.moov); LigneNumero("Telecel Money", s.telecel)
        }
        Carte("Personnel de cette société", Icons.Filled.Groups) {
            val siens = personnels.filter { it.societeId == id }
            if (siens.isEmpty()) Text("Aucun.", color = Gris, fontSize = 13.sp)
            siens.forEach { p -> Text("• ${p.nom} ${p.prenoms} (${p.fonction})", fontSize = 14.sp, color = Bleu, modifier = Modifier.padding(vertical = 3.dp).clickable { ouvrir(Ecran.FichePersonnel(p.id)) }) }
        }
        Carte("Évaluations", Icons.Filled.Star) {
            if (notes.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) { Etoiles(Math.round(Calculs.moyenne(notes)).toInt()); Text("  ${Calculs.moyenne(notes)} / 5 · ${notes.size} évaluation(s)", fontSize = 13.sp, color = Gris) }
            else Text("Aucune évaluation.", color = Gris, fontSize = 13.sp)
            evaluations.forEach { e ->
                Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Column(Modifier.weight(1f).clickable { ouvrir(Ecran.FormEvaluation(id, e.id)) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Etoiles(e.note, 16); Text("  " + (if (e.type == "MOIS") "Mois : " else "Jour : ") + Calculs.periodeTexte(e.type, e.periode), fontSize = 12.sp, color = Gris)
                            if (e.partageable) Icon(Icons.Filled.Share, "Partageable", tint = Bleu, modifier = Modifier.padding(start = 6.dp).size(14.dp))
                        }
                        if (e.remarque.isNotBlank()) Text(e.remarque, fontSize = 14.sp, color = BleuNuit)
                    }
                    Icon(Icons.Filled.Delete, "Supprimer l'évaluation", tint = Rouge, modifier = Modifier.size(24.dp).clickable { scope.launch { dao.supprimerEvaluation(e.id) } })
                }
            }
        }
        RangeeTuiles {
            Tuile(Icons.Filled.RateReview, "Évaluer", Orange) { ouvrir(Ecran.FormEvaluation(id, null)) }
            Tuile(Icons.Filled.Share, "Témoignage", Bleu, actif = evaluations.any { it.partageable }) {
                // Protection des données : aucune remarque partagée ne doit citer le nom d'un personnel enregistré
                val noms = personnels.map { it.nom to it.prenoms }
                val problemes = evaluations.filter { it.partageable }.map { it to Calculs.nomsCites(it.remarque, noms) }.filter { it.second.isNotEmpty() }
                if (problemes.isEmpty()) partager(c, "Témoignage sur ${s.nom}", Calculs.temoignage(s.nom, evaluations)) else controle = problemes
            }
            Tuile(Icons.Filled.Edit, "Modifier") { ouvrir(Ecran.FormSociete(id)) }
            Tuile(Icons.Filled.Delete, "Supprimer", Rouge) { suppression = true }
        }
        Text("« Témoignage » partage seulement les évaluations cochées « partageables » : le texte ne contient aucune information sur les personnels.", fontSize = 11.sp, color = Gris)
    }
    if (controle.isNotEmpty()) AlertDialog(onDismissRequest = { controle = emptyList() }, title = { Text("Des noms de personnes ?") },
        text = { Text("Ces remarques semblent citer :\n" + controle.joinToString("\n") { (e, mots) -> "• ${Calculs.periodeTexte(e.type, e.periode)} : ${mots.joinToString(", ")}" } + "\n\nCorrigez-les avant de partager.") },
        confirmButton = { Tuile(Icons.Filled.Edit, "Corriger", Orange) { val e = controle.first().first; controle = emptyList(); ouvrir(Ecran.FormEvaluation(id, e.id)) } },
        dismissButton = { Row { Tuile(Icons.Filled.Share, "Partager", Bleu) { controle = emptyList(); partager(c, "Témoignage sur ${s.nom}", Calculs.temoignage(s.nom, evaluations)) }; Tuile(Icons.Filled.Close, "Annuler") { controle = emptyList() } } })
    if (suppression) Confirmation("Supprimer cette société ?", "Ses évaluations seront effacées. Les personnels rattachés restent, sans société.", {
        scope.launch { dao.detacherPersonnel(id); dao.supprimerEvaluationsDe(id); dao.supprimerSociete(id) }; suppression = false; retour()
    }, { suppression = false })
}

/** Ligne « Opérateur : numéro » avec copie et appel. */
@Composable
private fun LigneNumero(libelle: String, numero: String) {
    val c = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(libelle, color = Gris, fontSize = 13.sp, modifier = Modifier.width(120.dp))
        Text(numero.ifBlank { "—" }, color = BleuNuit, fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (numero.isNotBlank()) {
            Icon(Icons.Filled.ContentCopy, "Copier", tint = Bleu, modifier = Modifier.size(24.dp).clickable {
                (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(libelle, numero))
                android.widget.Toast.makeText(c, "Numéro copié", android.widget.Toast.LENGTH_SHORT).show()
            })
            Icon(Icons.Filled.Call, "Appeler", tint = Vert, modifier = Modifier.padding(start = 14.dp).size(24.dp).clickable {
                c.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + numero.filter { it.isDigit() || it == '+' })).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
        }
    }
}

/** Ouvre le partage du système (WhatsApp, e-mail…) avec ce texte. */
fun partager(c: Context, sujet: String, texte: String) {
    if (texte.isBlank()) return
    c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, sujet).putExtra(Intent.EXTRA_TEXT, texte), "Partager").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

// ======================= Formulaire de la société =======================

@Composable
fun EcranFormSociete(id: Long?, retour: () -> Unit) {
    val c = LocalContext.current
    val dao = remember { Base.dao(c) }
    val scope = rememberCoroutineScope()
    var existante by remember { mutableStateOf<Societe?>(null) }
    var nom by remember { mutableStateOf("") }; var telephone by remember { mutableStateOf("") }; var adresse by remember { mutableStateOf("") }
    var orange by remember { mutableStateOf("") }; var moov by remember { mutableStateOf("") }; var telecel by remember { mutableStateOf("") }; var remarques by remember { mutableStateOf("") }
    LaunchedEffect(id) { if (id != null) dao.societe(id)?.let { s -> existante = s; nom = s.nom; telephone = s.telephone; adresse = s.adresse; orange = s.orange; moov = s.moov; telecel = s.telecel; remarques = s.remarques } }
    Page(if (id == null) "Nouvelle société" else "Modifier la société", retour) {
        Carte("Société", Icons.Filled.Business) {
            Champ("Nom de la société", nom, { nom = it }); Champ("Téléphone", telephone, { telephone = it }, KeyboardType.Phone)
            Champ("Adresse", adresse, { adresse = it }); Champ("Remarques", remarques, { remarques = it }, lignes = 2)
        }
        Carte("Numéros de paiement mobile", Icons.Filled.PhoneAndroid) {
            Champ("Orange Money", orange, { orange = it }, KeyboardType.Phone); Champ("Moov Money", moov, { moov = it }, KeyboardType.Phone); Champ("Telecel Money", telecel, { telecel = it }, KeyboardType.Phone)
        }
        RangeeTuiles {
            Tuile(Icons.Filled.Check, "Enregistrer", Vert, actif = nom.isNotBlank()) {
                scope.launch {
                    val s = Societe(id = existante?.id ?: 0, nom = nom.trim(), telephone = telephone.trim(), adresse = adresse.trim(), orange = orange.trim(), moov = moov.trim(), telecel = telecel.trim(), remarques = remarques.trim())
                    if (existante == null) dao.ajouterSociete(s) else dao.modifierSociete(s)
                    retour()
                }
            }
            Tuile(Icons.Filled.Close, "Annuler") { retour() }
        }
    }
}

// ======================= Évaluation d'une société (jour ou mois) =======================

@Composable
fun EcranFormEvaluation(societeId: Long, id: Long?, retour: () -> Unit) {
    val c = LocalContext.current
    val dao = remember { Base.dao(c) }
    val scope = rememberCoroutineScope()
    var existante by remember { mutableStateOf<Evaluation?>(null) }
    var type by remember { mutableStateOf("Jour") }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var note by remember { mutableStateOf(3) }
    var remarque by remember { mutableStateOf("") }
    var partageable by remember { mutableStateOf(false) }
    val personnels by dao.personnel().collectAsState(emptyList())
    var nomsTrouves by remember { mutableStateOf<List<String>>(emptyList()) }     // contrôle de protection des données avant enregistrement
    LaunchedEffect(id) {
        if (id != null) dao.evaluationParId(id)?.let { e ->
            existante = e; type = if (e.type == "MOIS") "Mois" else "Jour"; note = e.note; remarque = e.remarque; partageable = e.partageable
            date = try { if (e.type == "MOIS") YearMonth.parse(e.periode).atDay(1) else LocalDate.parse(e.periode) } catch (ex: Exception) { LocalDate.now() }
        }
    }
    // Enregistrement de l'évaluation (création ou modification)
    fun enregistrer() {
        scope.launch {
            val t = if (type == "Mois") "MOIS" else "JOUR"
            val e = Evaluation(id = existante?.id ?: 0, societeId = societeId, type = t, periode = if (t == "MOIS") YearMonth.from(date).toString() else date.toString(),
                note = note, remarque = remarque.trim(), partageable = partageable, creeLe = existante?.creeLe ?: System.currentTimeMillis())
            if (existante == null) dao.ajouterEvaluation(e) else dao.modifierEvaluation(e)
            retour()
        }
    }
    Page(if (id == null) "Nouvelle évaluation" else "Modifier l'évaluation", retour) {
        Carte("Évaluation", Icons.Filled.Star) {
            Liste("Période", type, listOf("Jour", "Mois")) { type = it }
            ChampDate(if (type == "Mois") "Un jour du mois évalué" else "Jour évalué", date.toString(), { date = it })
            if (type == "Mois") Text("Mois évalué : ${Calculs.moisLong(YearMonth.from(date))}", fontSize = 13.sp, color = Gris)
            Text("Note", fontSize = 13.sp, color = Gris, modifier = Modifier.padding(top = 8.dp)); Etoiles(note, 34) { note = it }
            Champ("Remarque", remarque, { remarque = it }, lignes = 3)
            Text("Ne citez aucun nom de personne dans la remarque.", fontSize = 12.sp, color = Gris)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Switch(partageable, { partageable = it }); Text("  Peut être partagée comme témoignage public", fontSize = 13.sp)
            }
            Text("Un témoignage ne contient jamais de nom de personnel.", fontSize = 11.sp, color = Gris)
        }
        RangeeTuiles {
            Tuile(Icons.Filled.Check, "Enregistrer", Vert) {
                // Évaluation partageable : on vérifie que la remarque ne cite pas un personnel enregistré
                val cites = if (partageable) Calculs.nomsCites(remarque, personnels.map { it.nom to it.prenoms }) else emptyList()
                if (cites.isNotEmpty()) { nomsTrouves = cites; return@Tuile }
                enregistrer()
            }
            Tuile(Icons.Filled.Close, "Annuler") { retour() }
        }
    }
    if (nomsTrouves.isNotEmpty()) AlertDialog(onDismissRequest = { nomsTrouves = emptyList() },
        title = { Text("Un nom de personne ?") },
        text = { Text("La remarque semble citer : ${nomsTrouves.joinToString(", ")}.\nUn témoignage partageable ne doit contenir aucun nom de personne. Corrigez la remarque, ou gardez-la si ce n'est pas un nom.") },
        confirmButton = { Tuile(Icons.Filled.Edit, "Corriger", Orange) { nomsTrouves = emptyList() } },
        dismissButton = { Tuile(Icons.Filled.Check, "Garder", Vert) { nomsTrouves = emptyList(); enregistrer() } })
}
