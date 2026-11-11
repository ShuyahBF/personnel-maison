package org.shuyah.personnelmaison

import android.app.Activity
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

// ======================= Liste du personnel =======================

@Composable
fun EcranListePersonnel(ouvrir: (Ecran) -> Unit) {
    val c = LocalContext.current
    val dao = remember { Base.dao(c) }
    val personnels by dao.personnel().collectAsState(emptyList())
    val societes by dao.societes().collectAsState(emptyList())
    var anciens by remember { mutableStateOf(false) }
    val aujourdhui = LocalDate.now()
    // « Sous contrat » : début passé ou présent et fin absente ou à venir
    fun sousContrat(p: Personnel) = try { !LocalDate.parse(p.dateDebut).isAfter(aujourdhui) && (p.dateFin == null || !LocalDate.parse(p.dateFin).isBefore(aujourdhui)) } catch (e: Exception) { true }
    val liste = personnels.filter { anciens || sousContrat(it) }
    Page("Personnel", actions = { Tuile(Icons.Filled.PersonAdd, "Ajouter", Orange) { ouvrir(Ecran.FormPersonnel(null)) } }) {
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(anciens, { anciens = it }); Text("  Afficher aussi les contrats terminés ou à venir", fontSize = 13.sp, color = Gris) }
        if (liste.isEmpty()) Carte { Text("Aucun personnel. Appuyez sur « Ajouter » en haut à droite.", color = Gris) }
        liste.forEach { p ->
            Carte(modifier = Modifier.clickable { ouvrir(Ecran.FichePersonnel(p.id)) }) {
                Text("${p.nom} ${p.prenoms}", fontSize = 16.sp, color = BleuNuit)
                Text(listOfNotNull(p.fonction, societes.find { it.id == p.societeId }?.nom).joinToString(" · "), fontSize = 13.sp, color = Gris)
                Text("Depuis le ${Calculs.dateFr(p.dateDebut)}" + (p.dateFin?.let { " · jusqu'au ${Calculs.dateFr(it)}" } ?: "") + (if (!sousContrat(p)) "  (hors contrat)" else ""), fontSize = 12.sp, color = Gris)
            }
        }
    }
}

// ======================= Fiche du personnel =======================

@Composable
fun EcranFichePersonnel(id: Long, ouvrir: (Ecran) -> Unit, retour: () -> Unit) {
    val c = LocalContext.current
    val dao = remember { Base.dao(c) }
    val scope = rememberCoroutineScope()
    val p by dao.personnelFlow(id).collectAsState(null)
    val societes by dao.societes().collectAsState(emptyList())
    val notes by dao.notes(id).collectAsState(emptyList())
    val tousNumeros by dao.tousNumeros().collectAsState(emptyList())
    val tousPersonnels by dao.personnel().collectAsState(emptyList())
    val horaires = horairesDe(dao, tousPersonnels)
    val mois = remember { YearMonth.now() }
    val pointages by dao.pointagesPersonnel(id, mois.atDay(1).toString(), mois.atEndOfMonth().toString()).collectAsState(emptyList())
    var nouvelleNote by remember { mutableStateOf("") }
    var suppression by remember { mutableStateOf(false) }
    val personnel = p
    if (personnel == null) { Page("Personnel", retour) { Text("Fiche introuvable.") }; return }
    val societe = societes.find { it.id == personnel.societeId }
    val stats = Calculs.statsMois(mois, ContratInfo(horaires[personnel.id] ?: Horaires.uniforme(personnel.debutMin, personnel.finMin, personnel.jours), LocalDate.parse(personnel.dateDebut), personnel.dateFin?.let { LocalDate.parse(it) }),
        pointages.map { JourPointage(LocalDate.parse(it.date), it.arrivee, it.depart, it.retardSignale, it.absent) }, Reglages.tolerance(c), LocalDate.now())

    Page("${personnel.prenoms} ${personnel.nom}", retour) {
        Carte("Contrat", Icons.Filled.Work) {
            Ligne("Fonction", personnel.fonction)
            Ligne("Société", societe?.nom ?: "Aucune")
            Ligne("Début", Calculs.dateFr(personnel.dateDebut)); Ligne("Fin", personnel.dateFin?.let { Calculs.dateFr(it) } ?: "Non définie")
            Ligne("Horaires", horaires[personnel.id]?.resume() ?: "—")
            Ligne("Salaire net", Calculs.francs(personnel.salaireNet))
        }
        Carte("Téléphone", Icons.Filled.Phone) { BlocNumeros("Numéros", tousNumeros.filter { it.proprietaire == "PERSONNEL" && it.proprietaireId == id }) }
        BlocPaiements(personnel)
        BlocTaches(personnel)
        // Numéros de paiement mobile : ils sont sur la fiche de la SOCIÉTÉ d'appartenance
        if (societe != null) Carte("Paiement via ${societe.nom}", Icons.Filled.PhoneAndroid) {
            Ligne("Orange Money", societe.orange); Ligne("Moov Money", societe.moov); Ligne("Telecel Money", societe.telecel)
            Text("Voir la société", color = Bleu, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp).clickable { ouvrir(Ecran.FicheSociete(societe.id)) })
        }
        Carte("Ce mois-ci (${Calculs.moisLong(mois)})", Icons.Filled.BarChart) {
            Ligne("Présences", "${stats.presences} / ${stats.joursAttendus} jour(s)"); Ligne("Absences", stats.absences.toString())
            Ligne("Retards", stats.retards.toString() + if (stats.retardTotalMin > 0) " (${Calculs.duree(stats.retardTotalMin)})" else ""); Ligne("Heures", Calculs.duree(stats.minutesTravaillees))
        }
        Carte("Pièce d'identité", Icons.Filled.Badge) {
            if (personnel.pieceChemin == null) Text("Aucune copie enregistrée.", color = Gris)
            else {
                Text("Copie chiffrée enregistrée.", color = Gris, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Tuile(Icons.Filled.Visibility, "Voir") { ouvrir(Ecran.Piece(id)) }
            }
        }
        Carte("Notes et remarques", Icons.Filled.StickyNote2) {
            Champ("Nouvelle note", nouvelleNote, { nouvelleNote = it }, lignes = 2)
            Tuile(Icons.Filled.Add, "Ajouter", Orange, actif = nouvelleNote.isNotBlank()) {
                scope.launch { dao.ajouterNote(NotePerso(personnelId = id, texte = nouvelleNote.trim())); nouvelleNote = "" }
            }
            if (notes.isEmpty()) Text("Aucune note.", color = Gris, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            notes.forEach { n ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(horodatage(n.horodatage), fontSize = 11.sp, color = Gris)
                        Text(n.texte, fontSize = 14.sp, color = BleuNuit)
                    }
                    Icon(Icons.Filled.Delete, "Supprimer la note", tint = Rouge, modifier = Modifier.size(24.dp).clickable { scope.launch { dao.supprimerNote(n.id) } })
                }
            }
        }
        RangeeTuiles {
            Tuile(Icons.Filled.Edit, "Modifier") { ouvrir(Ecran.FormPersonnel(id)) }
            if (personnel.dateFin == null || personnel.dateFin > LocalDate.now().toString())
                Tuile(Icons.Filled.EventBusy, "Fin de contrat", Rouge) { scope.launch { dao.modifierPersonnel(personnel.copy(dateFin = LocalDate.now().toString())) } }
            Tuile(Icons.Filled.Delete, "Supprimer", Rouge) { suppression = true }
        }
    }
    if (suppression) Confirmation("Supprimer cette fiche ?", "La fiche, ses pointages, ses notes et la copie de la pièce d'identité seront effacés définitivement.", {
        scope.launch {
            Pieces.supprimer(c, personnel.pieceChemin)
            dao.supprimerPointagesDe(id); dao.supprimerJournalDe(id); dao.supprimerNotesDe(id)
            dao.supprimerHoraires(id); dao.supprimerNumeros("PERSONNEL", id); dao.supprimerPaiementsDe(id); dao.supprimerTachesDe(id); dao.supprimerPersonnel(id)
        }
        suppression = false; retour()
    }, { suppression = false })
}

/** « 11/11/2026 à 14:32 » */
fun horodatage(ms: Long): String {
    val d = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), java.time.ZoneId.systemDefault())
    return Calculs.dateFr(d.toLocalDate()) + " à " + "%02d:%02d".format(d.hour, d.minute)
}

// ======================= Visionneuse de la pièce d'identité =======================

/** Affiche la copie déchiffrée. Les captures d'écran sont interdites tant que cet écran est ouvert (document sensible). */
@Composable
fun EcranPiece(id: Long, retour: () -> Unit) {
    val c = LocalContext.current
    val dao = remember { Base.dao(c) }
    var image by remember { mutableStateOf<Bitmap?>(null) }
    var texte by remember { mutableStateOf("") }
    DisposableEffect(Unit) {
        val fenetre = (c as? Activity)?.window
        fenetre?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { fenetre?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    LaunchedEffect(id) { dao.personnelParId(id)?.let { image = Pieces.lire(c, it.pieceChemin); texte = it.pieceTexte } }
    Page("Pièce d'identité", retour) {
        image?.let { Image(it.asImageBitmap(), "Pièce d'identité", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth) } ?: Text("Image introuvable.")
        if (texte.isNotBlank()) Carte("Texte lu (OCR)", Icons.Filled.TextFields) { Text(texte, fontSize = 13.sp, color = BleuNuit) }
    }
}

// ======================= Formulaire du personnel (avec lecture de la pièce) =======================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EcranFormPersonnel(id: Long?, retour: () -> Unit) {
    val c = LocalContext.current
    val dao = remember { Base.dao(c) }
    val scope = rememberCoroutineScope()
    val societes by dao.societes().collectAsState(emptyList())
    var existant by remember { mutableStateOf<Personnel?>(null) }
    var nom by remember { mutableStateOf("") }; var prenoms by remember { mutableStateOf("") }
    var fonction by remember { mutableStateOf(FONCTIONS[0]) }; var societeId by remember { mutableStateOf<Long?>(null) }
    var debut by remember { mutableStateOf(LocalDate.now().toString()) }; var fin by remember { mutableStateOf<String?>(null) }
    // Horaires de chaque jour (jour 0 = lundi) ; par défaut lundi à samedi, 07:00 à 17:00 ; un jour absent = repos
    val horaires = remember { mutableStateMapOf<Int, Creneau>().apply { for (j in 0..5) put(j, Creneau(7 * 60, 17 * 60)) } }
    val numeros = remember { mutableStateListOf<NumeroEdit>() }                 // 3 numéros au plus, un par défaut
    var salaire by remember { mutableStateOf("") }
    var pieceNom by remember { mutableStateOf<String?>(null) }; var pieceTexte by remember { mutableStateOf("") }
    var nouvellePiece by remember { mutableStateOf<String?>(null) }       // copie créée pendant cette saisie (supprimée si on annule)
    var occupe by remember { mutableStateOf(false) }; var message by remember { mutableStateOf<String?>(null) }
    var ligneChoisie by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(id) {
        if (id != null) dao.personnelParId(id)?.let { p ->
            existant = p; nom = p.nom; prenoms = p.prenoms; fonction = p.fonction; societeId = p.societeId; debut = p.dateDebut; fin = p.dateFin
            salaire = if (p.salaireNet > 0) p.salaireNet.toString() else ""
            val h = dao.horairesDe(p.id)
            horaires.clear()
            if (h.isNotEmpty()) h.forEach { horaires[it.jour] = Creneau(it.debutMin, it.finMin) } else horaires.putAll(Horaires.uniforme(p.debutMin, p.finMin, p.jours).parJour)
            numeros.clear(); numeros.addAll(dao.numerosEdit("PERSONNEL", p.id))
            pieceNom = p.pieceChemin; pieceTexte = p.pieceTexte
        }
    }

    // Photo ou image de la galerie → réduction, copie chiffrée, OCR, suggestion des noms
    val traiter: (android.net.Uri) -> Unit = { uri ->
        scope.launch {
            occupe = true; message = null
            val image = Pieces.charger(c, uri)
            if (image == null) message = "Image illisible"
            else {
                val texte = Pieces.lireTexte(image)
                val copie = Pieces.enregistrer(c, image)
                nouvellePiece?.let { Pieces.supprimer(c, it) }
                nouvellePiece = copie; pieceNom = copie; pieceTexte = texte
                val s = AnalyseIdentite.analyser(texte)
                if (nom.isBlank()) s.nom?.let { nom = it }
                if (prenoms.isBlank()) s.prenoms?.let { prenoms = it }
                message = if (texte.isBlank()) "Aucun texte reconnu : saisissez les noms à la main." else "Texte lu. Vérifiez les noms ci-dessous."
            }
            occupe = false
        }
    }
    val fichierPhoto = remember { File(c.cacheDir, "photos").apply { mkdirs() }.let { File(it, "piece.jpg") } }
    val uriPhoto = remember { FileProvider.getUriForFile(c, c.packageName + ".photos", fichierPhoto) }
    val prendrePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) { traiter(uriPhoto) } }
    val galerie = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) traiter(uri) }

    fun annuler() { nouvellePiece?.let { Pieces.supprimer(c, it) }; retour() }
    val valide = nom.isNotBlank() && prenoms.isNotBlank() && horaires.isNotEmpty() && horaires.values.all { it.finMin > it.debutMin }

    Page(if (id == null) "Nouveau personnel" else "Modifier la fiche", ::annuler) {
        Carte("Pièce d'identité (lecture automatique)", Icons.Filled.Badge) {
            Text("Photographiez la pièce : le texte est lu sur le téléphone et propose le nom et les prénoms. La copie est enregistrée chiffrée.", fontSize = 12.sp, color = Gris)
            Spacer(Modifier.height(8.dp))
            RangeeTuiles {
                Tuile(Icons.Filled.PhotoCamera, "Photographier", actif = !occupe) { prendrePhoto.launch(uriPhoto) }
                Tuile(Icons.Filled.PhotoLibrary, "Galerie", actif = !occupe) { galerie.launch("image/*") }
                if (pieceNom != null) Tuile(Icons.Filled.DeleteOutline, "Retirer", Rouge) { if (nouvellePiece == pieceNom) { Pieces.supprimer(c, pieceNom); nouvellePiece = null }; pieceNom = null; pieceTexte = "" }
            }
            if (occupe) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text("  Lecture en cours…", fontSize = 13.sp) }
            message?.let { Text(it, fontSize = 13.sp, color = Bleu, modifier = Modifier.padding(top = 6.dp)) }
            if (pieceNom != null) Text("Copie enregistrée ✓", fontSize = 13.sp, color = Vert, modifier = Modifier.padding(top = 6.dp))
            // Lignes lues : un appui permet de les placer dans « Nom » ou « Prénoms »
            val lignes = pieceTexte.lines().map { it.trim() }.filter { it.length >= 2 }
            if (lignes.isNotEmpty()) {
                Text("Texte lu : appuyez sur une ligne pour l'utiliser", fontSize = 12.sp, color = Gris, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { lignes.take(24).forEach { l -> AssistChip({ ligneChoisie = l }, { Text(l, fontSize = 12.sp) }) } }
            }
        }
        Carte("Identité et poste", Icons.Filled.Person) {
            Champ("Nom", nom, { nom = it }); Champ("Prénoms", prenoms, { prenoms = it })
            Liste("Fonction", fonction, FONCTIONS, valeurLibre = true) { fonction = it }
            Liste("Société de placement", societes.find { it.id == societeId }?.nom ?: "Aucune", listOf("Aucune") + societes.map { it.nom }) { choix -> societeId = societes.find { it.nom == choix }?.id }
            EditeurNumeros(numeros)
        }
        Carte("Contrat", Icons.Filled.Work) {
            ChampDate("Date de début", debut, { debut = it.toString() })
            ChampDate("Date de fin (facultative)", fin, { fin = it.toString() }, { fin = null })
            EditeurHoraires(horaires)
            Champ("Salaire net à payer (FCFA)", salaire, { salaire = it.filter { ch -> ch.isDigit() } }, KeyboardType.Number)
        }
        RangeeTuiles {
            Tuile(Icons.Filled.Check, "Enregistrer", Vert, actif = valide && !occupe) {
                scope.launch {
                    val ancienne = existant?.pieceChemin
                    // Anciennes colonnes (horaire unique, jours) : recopiées du premier jour travaillé, pour compatibilité ; la vérité est dans les horaires par jour
                    val premier = horaires.toSortedMap().values.firstOrNull() ?: Creneau(7 * 60, 17 * 60)
                    val masque = horaires.keys.fold(0) { a, j -> a or (1 shl j) }
                    val p = Personnel(id = existant?.id ?: 0, nom = nom.trim(), prenoms = prenoms.trim(), fonction = fonction, societeId = societeId, dateDebut = debut, dateFin = fin,
                        debutMin = premier.debutMin, finMin = premier.finMin, jours = masque, salaireNet = salaire.toLongOrNull() ?: 0, telephone = "", pieceChemin = pieceNom, pieceTexte = pieceTexte,
                        creeLe = existant?.creeLe ?: System.currentTimeMillis())
                    val pid = if (existant == null) dao.ajouterPersonnel(p) else { dao.modifierPersonnel(p); p.id }
                    dao.supprimerHoraires(pid)
                    horaires.toSortedMap().forEach { (j, cr) -> dao.ajouterHoraire(HorairePersonnel(personnelId = pid, jour = j, debutMin = cr.debutMin, finMin = cr.finMin)) }
                    dao.enregistrerNumeros("PERSONNEL", pid, numeros.toList())
                    if (ancienne != null && ancienne != pieceNom) Pieces.supprimer(c, ancienne)     // ancienne copie remplacée ou retirée
                    nouvellePiece = null; retour()
                }
            }
            Tuile(Icons.Filled.Close, "Annuler") { annuler() }
        }
    }
    ligneChoisie?.let { l ->
        AlertDialog(onDismissRequest = { ligneChoisie = null }, title = { Text(l, fontSize = 15.sp) }, text = { Text("Utiliser cette ligne comme :") },
            confirmButton = { Row { Tuile(Icons.Filled.Person, "Nom") { nom = l; ligneChoisie = null }; Tuile(Icons.Filled.Badge, "Prénoms") { prenoms = l; ligneChoisie = null } } },
            dismissButton = { Tuile(Icons.Filled.Close, "Fermer") { ligneChoisie = null } })
    }
}

/** Initiales des jours pour les cases de choix (lundi → dimanche). */
object Plage { val JOURS_COURTS = listOf("L", "M", "M", "J", "V", "S", "D") }
