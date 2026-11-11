package org.shuyah.personnelmaison

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

// ======================= Horaires de tous les personnels =======================

/** Horaires par jour de chaque personnel (lignes de la base ; à défaut, l'ancien horaire uniforme de la fiche). */
@Composable
fun horairesDe(dao: DaoMaison, personnels: List<Personnel>): Map<Long, Horaires> {
    val lignes by dao.tousHoraires().collectAsState(emptyList())
    return remember(lignes, personnels) {
        personnels.associate { p ->
            val siennes = lignes.filter { it.personnelId == p.id }
            p.id to (if (siennes.isNotEmpty()) Horaires(siennes.associate { it.jour to Creneau(it.debutMin, it.finMin) }) else Horaires.uniforme(p.debutMin, p.finMin, p.jours))
        }
    }
}

/** Créneau d'un personnel à une date (null = repos), d'après les lignes d'horaires de la base ou, à défaut, l'ancien horaire uniforme de la fiche. */
fun creneauDuJour(p: Personnel, lignes: List<HorairePersonnel>, d: LocalDate): Creneau? {
    val siennes = lignes.filter { it.personnelId == p.id }
    return if (siennes.isNotEmpty()) siennes.find { it.jour == d.dayOfWeek.value - 1 }?.let { Creneau(it.debutMin, it.finMin) }
    else Horaires.uniforme(p.debutMin, p.finMin, p.jours).pour(d.dayOfWeek)
}

// ======================= Numéros de téléphone (3 au plus, un par défaut, appel) =======================

/** Un numéro en cours de saisie. */
data class NumeroEdit(val numero: String, val parDefaut: Boolean)

/** Charge les numéros d'une fiche pour les modifier. */
suspend fun DaoMaison.numerosEdit(proprietaire: String, id: Long): List<NumeroEdit> = numerosDe(proprietaire, id).map { NumeroEdit(it.numero, it.parDefaut) }

/** Remplace les numéros d'une fiche : 3 au plus, un seul « par défaut » (le premier si aucun n'est choisi). Retourne le numéro par défaut. */
suspend fun DaoMaison.enregistrerNumeros(proprietaire: String, id: Long, liste: List<NumeroEdit>): String {
    val propres = liste.map { it.copy(numero = it.numero.trim()) }.filter { it.numero.isNotBlank() }.take(3)
    val defaut = propres.indexOfFirst { it.parDefaut }.let { if (it < 0) 0 else it }
    supprimerNumeros(proprietaire, id)
    propres.forEachIndexed { i, n -> ajouterNumero(NumeroTel(proprietaire = proprietaire, proprietaireId = id, numero = n.numero, parDefaut = i == defaut)) }
    return propres.getOrNull(defaut)?.numero ?: ""
}

/** Affichage des numéros d'une fiche : le numéro par défaut en premier, avec pictogrammes COPIER et APPELER (ouvre le clavier d'appel du téléphone). */
@Composable
fun BlocNumeros(titre: String, numeros: List<NumeroTel>) {
    val c = LocalContext.current
    Text(titre, color = Gris, fontSize = 13.sp)
    if (numeros.isEmpty()) Text("—", fontSize = 14.sp, color = BleuNuit)
    numeros.sortedByDescending { it.parDefaut }.forEach { n ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(n.numero, fontSize = 17.sp, color = BleuNuit)
                if (n.parDefaut && numeros.size > 1) Text("Numéro par défaut", fontSize = 11.sp, color = Vert)
            }
            Icon(Icons.Filled.ContentCopy, "Copier", tint = Bleu, modifier = Modifier.size(28.dp).clickable {
                (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("numéro", n.numero))
                android.widget.Toast.makeText(c, "Numéro copié", android.widget.Toast.LENGTH_SHORT).show()
            })
            Box(Modifier.padding(start = 16.dp).size(44.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Vert).clickable { appeler(c, n.numero) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Call, "Appeler", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(26.dp))
            }
        }
    }
}

/** Ouvre le clavier d'appel avec ce numéro (l'utilisateur appuie sur « appeler » : aucune autorisation d'appel n'est demandée). */
fun appeler(c: Context, numero: String) =
    c.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + numero.filter { it.isDigit() || it == '+' })).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

/** Saisie de 1 à 3 numéros avec choix du numéro par défaut. */
@Composable
fun EditeurNumeros(liste: SnapshotStateList<NumeroEdit>) {
    Text("Numéros de téléphone (3 au plus)", fontSize = 13.sp, color = Gris)
    liste.forEachIndexed { i, n ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(n.numero, { v -> liste[i] = n.copy(numero = v) }, label = { Text("Numéro ${i + 1}") }, modifier = Modifier.weight(1f).padding(vertical = 3.dp), singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Phone))
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = 6.dp)) {
                RadioButton(n.parDefaut, { for (k in liste.indices) liste[k] = liste[k].copy(parDefaut = k == i) })
                Text("défaut", fontSize = 10.sp, color = Gris)
            }
            Icon(Icons.Filled.Close, "Retirer ce numéro", tint = Rouge, modifier = Modifier.padding(start = 6.dp).size(26.dp).clickable {
                val etaitDefaut = n.parDefaut; liste.removeAt(i)
                if (etaitDefaut && liste.isNotEmpty()) liste[0] = liste[0].copy(parDefaut = true)
            })
        }
    }
    if (liste.size < 3) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp).clickable { liste.add(NumeroEdit("", liste.isEmpty())) }) {
        Icon(Icons.Filled.AddCircle, null, tint = Bleu, modifier = Modifier.size(28.dp)); Text("  Ajouter un numéro", color = Bleu, fontSize = 14.sp)
    }
}

// ======================= Horaires par jour (formulaire) =======================

/** Une heure cliquable dans un cadre. */
@Composable
private fun BoutonHeure(minutes: Int, titre: String, surChoix: (Int) -> Unit) {
    val c = LocalContext.current
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(androidx.compose.ui.graphics.Color(0xFFE2E8F0)).clickable { choisirHeure(c, titre, minutes, surChoix) }.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(Calculs.heure(minutes), fontSize = 16.sp, color = BleuNuit)
    }
}

/** Éditeur des horaires de chaque jour : on coche les jours travaillés et on règle l'heure de début et de fin de CHAQUE jour (samedi plus court, etc.). */
@Composable
fun EditeurHoraires(horaires: SnapshotStateMap<Int, Creneau>) {
    val noms = listOf("Lundi", "Mardi", "Mercredi", "Jeudi", "Vendredi", "Samedi", "Dimanche")
    Text("Horaires de chaque jour", fontSize = 13.sp, color = Gris)
    for (j in 0..6) {
        val c = horaires[j]
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Switch(c != null, { on -> if (on) horaires[j] = horaires.values.firstOrNull() ?: Creneau(7 * 60, 17 * 60) else horaires.remove(j) })
            Text(noms[j], fontSize = 14.sp, color = BleuNuit, modifier = Modifier.width(86.dp).padding(start = 6.dp))
            if (c != null) {
                BoutonHeure(c.debutMin, "${noms[j]} : début") { horaires[j] = c.copy(debutMin = it) }
                Text("  →  ", fontSize = 14.sp)
                BoutonHeure(c.finMin, "${noms[j]} : fin") { horaires[j] = c.copy(finMin = it) }
            } else Text("Repos", fontSize = 13.sp, color = Gris)
        }
        if (c != null && c.finMin <= c.debutMin) Text("L'heure de fin doit être après le début.", color = Rouge, fontSize = 12.sp)
    }
    val premier = (0..6).firstNotNullOfOrNull { horaires[it] }
    if (premier != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp).clickable { for (j in horaires.keys.toList()) horaires[j] = premier }) {
        Icon(Icons.Filled.ContentCopy, null, tint = Bleu, modifier = Modifier.size(22.dp)); Text("  Même horaire que le premier jour pour tous les jours travaillés", color = Bleu, fontSize = 13.sp)
    }
}

// ======================= Bonus et retenues sur salaire =======================

/** Bonus et retraits (retenues) sur le salaire d'un personnel, avec le net à payer du mois. */
@Composable
fun BlocPaiements(personnel: Personnel) {
    val c = LocalContext.current
    val dao = remember { Base.dao(c) }
    val scope = rememberCoroutineScope()
    val paiements by dao.paiementsDe(personnel.id).collectAsState(emptyList())
    var ajout by remember { mutableStateOf<String?>(null) }          // « BONUS » ou « RETENUE » : fenêtre d'ajout ouverte
    val mois = remember { YearMonth.now() }
    Carte("Bonus et retenues sur salaire", Icons.Filled.Payments) {
        val net = Calculs.netAPayer(personnel.salaireNet, paiements, mois)
        Ligne("Salaire net", Calculs.francs(personnel.salaireNet))
        Ligne("Net à payer", Calculs.francs(net) + " (${Calculs.moisLong(mois)})")
        if (paiements.isEmpty()) Text("Aucun bonus ni retenue.", fontSize = 13.sp, color = Gris, modifier = Modifier.padding(top = 6.dp))
        paiements.take(20).forEach { p ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text((if (p.genre == "BONUS") "Bonus" else "Retenue") + " · " + Calculs.dateFr(p.date), fontSize = 12.sp, color = Gris)
                    if (p.motif.isNotBlank()) Text(p.motif, fontSize = 14.sp, color = BleuNuit)
                }
                Text((if (p.genre == "BONUS") "+ " else "− ") + Calculs.francs(p.montant), fontSize = 15.sp, color = if (p.genre == "BONUS") Vert else Rouge, textAlign = TextAlign.End)
                Icon(Icons.Filled.Delete, "Supprimer", tint = Rouge, modifier = Modifier.padding(start = 10.dp).size(24.dp).clickable { scope.launch { dao.supprimerPaiement(p.id) } })
            }
        }
        Spacer(Modifier.height(8.dp))
        RangeeTuiles {
            Tuile(Icons.Filled.AddCircle, "Bonus", Vert) { ajout = "BONUS" }
            Tuile(Icons.Filled.RemoveCircle, "Retenue", Rouge) { ajout = "RETENUE" }
        }
    }
    ajout?.let { genre ->
        var montant by remember { mutableStateOf("") }; var motif by remember { mutableStateOf("") }; var date by remember { mutableStateOf(LocalDate.now().toString()) }
        AlertDialog(onDismissRequest = { ajout = null }, title = { Text(if (genre == "BONUS") "Nouveau bonus" else "Nouvelle retenue (retrait)") },
            text = {
                Column {
                    Champ("Montant (FCFA)", montant, { montant = it.filter { ch -> ch.isDigit() } }, KeyboardType.Number)
                    ChampDate("Date", date, { date = it.toString() })
                    Champ("Motif", motif, { motif = it })
                }
            },
            confirmButton = { Tuile(Icons.Filled.Check, "Valider", Vert, actif = (montant.toLongOrNull() ?: 0) > 0) {
                scope.launch { dao.ajouterPaiement(Paiement(personnelId = personnel.id, date = date, genre = genre, montant = montant.toLong(), motif = motif.trim())) }; ajout = null
            } },
            dismissButton = { Tuile(Icons.Filled.Close, "Annuler") { ajout = null } })
    }
}

// ======================= Tâches par défaut =======================

/** Tâches usuelles proposées selon la fonction (liste déroulante ; « Autre… » pour une tâche libre). */
val TACHES_SUGGEREES: Map<String, List<String>> = mapOf(
    "Aide ménagère" to listOf("Balayer et laver les sols", "Faire la vaisselle", "Laver et repasser le linge", "Nettoyer les sanitaires", "Dépoussiérer les meubles", "Faire les lits", "Faire les courses"),
    "Gardien" to listOf("Ouvrir et fermer le portail", "Surveiller la maison", "Ronde de nuit", "Accueillir et orienter les visiteurs", "Arroser les plantes", "Sortir et rentrer les poubelles"),
    "Laveur de véhicule" to listOf("Laver l'extérieur du véhicule", "Nettoyer l'intérieur", "Aspirer les tapis", "Faire briller la carrosserie", "Nettoyer les vitres"),
    "Cuisinier(ère)" to listOf("Préparer les repas", "Faire les courses de cuisine", "Nettoyer la cuisine", "Ranger les provisions"),
    "Chauffeur" to listOf("Conduire la famille", "Entretenir le véhicule", "Faire les courses et commissions"),
    "Jardinier" to listOf("Arroser les plantes", "Tondre et tailler", "Désherber", "Entretenir les allées"),
    "Nounou" to listOf("Garder les enfants", "Préparer les repas des enfants", "Accompagner à l'école", "Ranger la chambre des enfants"),
    "Lingère" to listOf("Laver le linge", "Repasser", "Plier et ranger le linge")
)

/** Liste des tâches par défaut d'un personnel : ajout depuis les tâches usuelles de sa fonction (ou tâche libre), suppression. */
@Composable
fun BlocTaches(personnel: Personnel) {
    val c = LocalContext.current
    val dao = remember { Base.dao(c) }
    val scope = rememberCoroutineScope()
    val taches by dao.tachesDe(personnel.id).collectAsState(emptyList())
    var ajout by remember { mutableStateOf(false) }
    val suggestions = TACHES_SUGGEREES[personnel.fonction] ?: emptyList()
    Carte("Tâches habituelles", Icons.Filled.Checklist) {
        if (taches.isEmpty()) Text("Aucune tâche définie.", fontSize = 13.sp, color = Gris)
        taches.forEach { t ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircleOutline, null, tint = Bleu, modifier = Modifier.size(20.dp))
                Text(t.libelle, fontSize = 15.sp, color = BleuNuit, modifier = Modifier.weight(1f).padding(start = 8.dp))
                Icon(Icons.Filled.Delete, "Supprimer la tâche", tint = Rouge, modifier = Modifier.size(24.dp).clickable { scope.launch { dao.supprimerTache(t.id) } })
            }
        }
        Spacer(Modifier.height(8.dp))
        RangeeTuiles {
            Tuile(Icons.Filled.Add, "Ajouter", Orange) { ajout = true }
            if (suggestions.any { s -> taches.none { it.libelle == s } })
                Tuile(Icons.Filled.PlaylistAdd, "Tâches usuelles", Bleu) { scope.launch { suggestions.filter { s -> taches.none { it.libelle == s } }.forEach { dao.ajouterTache(Tache(personnelId = personnel.id, libelle = it)) } } }
        }
    }
    if (ajout) {
        var choix by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { ajout = false }, title = { Text("Nouvelle tâche") },
            text = { Liste("Tâche", choix, suggestions, valeurLibre = true) { choix = it } },
            confirmButton = { Tuile(Icons.Filled.Check, "Valider", Vert, actif = choix.isNotBlank()) { scope.launch { dao.ajouterTache(Tache(personnelId = personnel.id, libelle = choix.trim())) }; ajout = false } },
            dismissButton = { Tuile(Icons.Filled.Close, "Annuler") { ajout = false } })
    }
}

// ======================= Documents d'une société (contrat de prestations) =======================

/** Types de documents proposés (liste déroulante, « Autre… » libre). */
val TYPES_DOCUMENT = listOf("Contrat de prestations", "Avenant au contrat", "Facture", "Reçu de paiement")

/** Contrat de prestations et autres documents d'une société : photographier ou joindre une image ou un PDF ; copies chiffrées. */
@Composable
fun BlocDocuments(societeId: Long, ouvrir: (Ecran) -> Unit) {
    val c = LocalContext.current
    val dao = remember { Base.dao(c) }
    val scope = rememberCoroutineScope()
    val documents by dao.documentsDe(societeId).collectAsState(emptyList())
    var enAttente by remember { mutableStateOf<Uri?>(null) }           // fichier choisi, en attente de son nom
    var occupe by remember { mutableStateOf(false) }; var message by remember { mutableStateOf<String?>(null) }
    val fichierPhoto = remember { File(c.cacheDir, "photos").apply { mkdirs() }.let { File(it, "document.jpg") } }
    val uriPhoto = remember { FileProvider.getUriForFile(c, c.packageName + ".photos", fichierPhoto) }
    val prendre = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) enAttente = uriPhoto }
    val choisir = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) enAttente = uri }

    Carte("Contrat de prestations et documents", Icons.Filled.Description) {
        Text("Photographiez le contrat (une photo par page) ou joignez une image ou un PDF. Copies chiffrées, absentes de la galerie.", fontSize = 12.sp, color = Gris)
        if (documents.isEmpty()) Text("Aucun document.", fontSize = 13.sp, color = Gris, modifier = Modifier.padding(top = 6.dp))
        documents.forEach { d ->
            Row(Modifier.fillMaxWidth().clickable { ouvrir(Ecran.Document(d.id)) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (d.type == "PDF") Icons.Filled.PictureAsPdf else Icons.Filled.Image, null, tint = Bleu, modifier = Modifier.size(30.dp))
                Column(Modifier.padding(start = 10.dp)) { Text(d.libelle, fontSize = 15.sp, color = BleuNuit); Text("Ajouté le ${horodatage(d.ajouteLe)}", fontSize = 11.sp, color = Gris) }
            }
        }
        if (occupe) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text("  Enregistrement…", fontSize = 13.sp) }
        message?.let { Text(it, fontSize = 13.sp, color = Rouge) }
        Spacer(Modifier.height(8.dp))
        RangeeTuiles {
            Tuile(Icons.Filled.PhotoCamera, "Photographier", actif = !occupe) { prendre.launch(uriPhoto) }
            Tuile(Icons.Filled.AttachFile, "Joindre un fichier", actif = !occupe) { choisir.launch("*/*") }
        }
    }
    enAttente?.let { uri ->
        var libelle by remember { mutableStateOf(TYPES_DOCUMENT[0]) }
        AlertDialog(onDismissRequest = { enAttente = null }, title = { Text("Quel document ?") },
            text = { Liste("Type de document", libelle, TYPES_DOCUMENT, valeurLibre = true) { libelle = it } },
            confirmButton = { Tuile(Icons.Filled.Check, "Enregistrer", Vert) {
                enAttente = null
                scope.launch {
                    occupe = true; message = null
                    val pdf = c.contentResolver.getType(uri) == "application/pdf"
                    try {
                        val nom = if (pdf) Pieces.octetsDe(c, uri)?.let { Pieces.enregistrerOctets(c, it) } else Pieces.charger(c, uri, 2000)?.let { Pieces.enregistrer(c, it) }
                        if (nom == null) message = "Fichier illisible ou trop gros (20 Mo au plus)."
                        else dao.ajouterDocument(DocumentSociete(societeId = societeId, libelle = libelle.trim(), type = if (pdf) "PDF" else "IMAGE", fichier = nom))
                    } catch (e: Exception) { message = "Enregistrement impossible." }
                    occupe = false
                }
            } },
            dismissButton = { Tuile(Icons.Filled.Close, "Annuler") { enAttente = null } })
    }
}

/** Visionneuse d'un document de société (image ou PDF rendu dans l'application). Captures d'écran interdites pendant l'affichage. */
@Composable
fun EcranDocument(id: Long, retour: () -> Unit) {
    val c = LocalContext.current
    val dao = remember { Base.dao(c) }
    val scope = rememberCoroutineScope()
    var doc by remember { mutableStateOf<DocumentSociete?>(null) }
    var pages by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var chargement by remember { mutableStateOf(true) }; var suppression by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val fenetre = (c as? Activity)?.window
        fenetre?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { fenetre?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    LaunchedEffect(id) {
        val d = dao.documentParId(id); doc = d
        if (d != null) pages = if (d.type == "PDF") Pieces.pagesPdf(c, d.fichier) else listOfNotNull(Pieces.lire(c, d.fichier))
        chargement = false
    }
    Page(doc?.libelle ?: "Document", retour) {
        if (chargement) CircularProgressIndicator()
        else if (pages.isEmpty()) Text("Document illisible.", color = Rouge)
        pages.forEach { Image(it.asImageBitmap(), "Page", Modifier.fillMaxWidth().padding(bottom = 8.dp), contentScale = ContentScale.FillWidth) }
        if (doc != null) Tuile(Icons.Filled.Delete, "Supprimer", Rouge) { suppression = true }
    }
    if (suppression) Confirmation("Supprimer ce document ?", "La copie chiffrée sera effacée définitivement.", {
        scope.launch { doc?.let { Pieces.supprimer(c, it.fichier); dao.supprimerDocument(it.id) } }; suppression = false; retour()
    }, { suppression = false })
}
