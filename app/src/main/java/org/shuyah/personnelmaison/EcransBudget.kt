package org.shuyah.personnelmaison

import android.app.Activity
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import java.time.LocalDate
import java.time.YearMonth

private fun sens(genre: String) = if (genre == "RECETTE") "+ " else "− "
private fun couleur(genre: String) = if (genre == "RECETTE") Vert else Rouge

// ======================= Portefeuille : liste du mois =======================

/** Onglet PORTEFEUILLE : recettes et dépenses du mois, solde, ajout d'une dépense / recette ou scan d'un ticket de caisse. */
@Composable
fun EcranBudget(ouvrir: (Ecran) -> Unit) {
    val c = LocalContext.current
    val dao = remember { Base.dao(c) }
    val toutes by dao.operations().collectAsState(emptyList())
    val pieces by dao.nombrePieces().collectAsState(emptyList())
    var mois by remember { mutableStateOf(YearMonth.now()) }
    var filtre by remember { mutableStateOf("TOUT") }                       // TOUT, DEPENSE, RECETTE
    val duMois = CalculsBudget.duMois(toutes, mois)
    val t = CalculsBudget.totaux(duMois)
    val liste = duMois.filter { filtre == "TOUT" || it.genre == filtre }

    Page("Portefeuille") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Tuile(Icons.Filled.ChevronLeft, "Précédent") { mois = mois.minusMonths(1) }
            Text(Calculs.moisLong(mois), fontSize = 18.sp, color = BleuNuit)
            Tuile(Icons.Filled.ChevronRight, "Suivant", actif = mois.isBefore(YearMonth.now())) { mois = mois.plusMonths(1) }
        }
        Carte {
            Row(Modifier.fillMaxWidth()) { Text("Recettes", Modifier.weight(1f), color = Gris, fontSize = 14.sp); Text("+ " + Calculs.francs(t.recettes), color = Vert, fontSize = 16.sp, textAlign = TextAlign.End) }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) { Text("Dépenses", Modifier.weight(1f), color = Gris, fontSize = 14.sp); Text("− " + Calculs.francs(t.depenses), color = Rouge, fontSize = 16.sp, textAlign = TextAlign.End) }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Row(Modifier.fillMaxWidth()) { Text("Solde du mois", Modifier.weight(1f), color = BleuNuit, fontSize = 16.sp); Text(Calculs.francs(t.solde), color = if (t.solde >= 0) Vert else Rouge, fontSize = 20.sp, textAlign = TextAlign.End) }
        }
        RangeeTuiles {
            Tuile(Icons.Filled.DocumentScanner, "Scanner un ticket", Vert) { ouvrir(Ecran.FormOperation(null, "DEPENSE", true)) }
            Tuile(Icons.Filled.RemoveCircle, "Dépense", Rouge) { ouvrir(Ecran.FormOperation(null, "DEPENSE", false)) }
            Tuile(Icons.Filled.AddCircle, "Recette", Vert) { ouvrir(Ecran.FormOperation(null, "RECETTE", false)) }
            Tuile(Icons.Filled.BarChart, "Statistiques", Bleu) { ouvrir(Ecran.BudgetStats) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("TOUT" to "Tout", "DEPENSE" to "Dépenses", "RECETTE" to "Recettes").forEach { (code, libelle) -> FilterChip(filtre == code, { filtre = code }, { Text(libelle) }) }
        }
        if (liste.isEmpty()) Carte { Text("Aucune opération ce mois-ci. Appuyez sur « Scanner un ticket », « Dépense » ou « Recette ».", color = Gris) }
        liste.groupBy { it.date }.forEach { (jour, ops) ->
            Text(Calculs.dateLongue(LocalDate.parse(jour)), fontSize = 13.sp, color = Gris, modifier = Modifier.padding(top = 4.dp))
            ops.forEach { o ->
                Carte(modifier = Modifier.clickable { ouvrir(Ecran.FicheOperation(o.id)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(o.tiers.ifBlank { o.categorie.ifBlank { if (o.genre == "RECETTE") "Recette" else "Dépense" } }, fontSize = 16.sp, color = BleuNuit)
                            Text(listOf(o.categorie, o.portefeuille).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 12.sp, color = Gris)
                        }
                        if ((pieces.find { it.operationId == o.id }?.n ?: 0) > 0) Icon(Icons.Filled.Receipt, "Pièce jointe", tint = Bleu, modifier = Modifier.padding(end = 10.dp).size(20.dp))
                        Text(sens(o.genre) + Calculs.francs(o.montant), fontSize = 16.sp, color = couleur(o.genre), textAlign = TextAlign.End)
                    }
                }
            }
        }
    }
}

// ======================= Fiche d'une opération =======================

@Composable
fun EcranFicheOperation(id: Long, ouvrir: (Ecran) -> Unit, retour: () -> Unit) {
    val c = LocalContext.current
    val dao = remember { Base.dao(c) }
    val scope = rememberCoroutineScope()
    val o by dao.operationFlow(id).collectAsState(null)
    val lignes by dao.lignesOperation(id).collectAsState(emptyList())
    val pieces by dao.piecesOperation(id).collectAsState(emptyList())
    var suppression by remember { mutableStateOf(false) }
    val op = o
    if (op == null) { Page("Opération", retour) { Text("Opération introuvable.") }; return }
    Page(if (op.genre == "RECETTE") "Recette" else "Dépense", retour) {
        Carte {
            Text(sens(op.genre) + Calculs.francs(op.montant), fontSize = 30.sp, color = couleur(op.genre))
            Ligne(if (op.genre == "RECETTE") "Source" else "Marchand", op.tiers); Ligne("Date", Calculs.dateFr(op.date)); Ligne("Catégorie", op.categorie)
            Ligne("Portefeuille", op.portefeuille); Ligne("Note", op.note)
            Text("Enregistrée le ${horodatage(op.creeLe)}" + if (op.modifieLe - op.creeLe > 1000) " · corrigée le ${horodatage(op.modifieLe)}" else "", fontSize = 11.sp, color = Gris, modifier = Modifier.padding(top = 6.dp))
        }
        if (lignes.isNotEmpty()) Carte("Détail", Icons.Filled.ListAlt) {
            lignes.forEach { l ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text((if (l.quantite != 1.0) "${trim(l.quantite)} × " else "") + l.libelle, Modifier.weight(1f), fontSize = 14.sp, color = BleuNuit)
                    Text(Calculs.francs(l.montant), fontSize = 14.sp, textAlign = TextAlign.End)
                }
            }
            val somme = CalculsBudget.sommeLignes(lignes)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(Modifier.fillMaxWidth()) { Text("Somme des lignes", Modifier.weight(1f), fontSize = 13.sp, color = Gris); Text(Calculs.francs(somme), fontSize = 13.sp, color = if (somme == op.montant) Vert else Rouge, textAlign = TextAlign.End) }
            if (somme != op.montant) Text("La somme des lignes diffère du montant total (${Calculs.francs(op.montant)}).", fontSize = 12.sp, color = Rouge)
        }
        Carte("Pièces justificatives", Icons.Filled.Receipt) {
            if (pieces.isEmpty()) Text("Aucune pièce.", color = Gris, fontSize = 13.sp)
            pieces.forEachIndexed { i, p ->
                Row(Modifier.fillMaxWidth().clickable { ouvrir(Ecran.PieceOperation(p.id)) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Miniature(p.fichier)
                    Column(Modifier.padding(start = 10.dp).weight(1f)) {
                        Text("Pièce ${i + 1} · ${horodatage(p.ajouteLe)}", fontSize = 13.sp, color = BleuNuit)
                        Text(p.texte.lines().firstOrNull { it.isNotBlank() } ?: "Pas de texte lu", fontSize = 12.sp, color = Gris, maxLines = 1)
                    }
                }
            }
        }
        RangeeTuiles {
            Tuile(Icons.Filled.Edit, "Modifier") { ouvrir(Ecran.FormOperation(id, op.genre, false)) }
            Tuile(Icons.Filled.Delete, "Supprimer", Rouge) { suppression = true }
        }
    }
    if (suppression) Confirmation("Supprimer cette opération ?", "L'opération, son détail et les photos des pièces seront effacés définitivement.", {
        scope.launch { supprimerOperationComplete(c, dao, id) }; suppression = false; retour()
    }, { suppression = false })
}

/** Supprime une opération avec ses lignes et ses pièces (fichiers chiffrés compris). */
suspend fun supprimerOperationComplete(c: android.content.Context, dao: DaoMaison, id: Long) {
    dao.piecesOperationListe(id).forEach { Pieces.supprimer(c, it.fichier) }
    dao.supprimerPiecesOperation(id); dao.supprimerLignesOperation(id); dao.supprimerOperation(id)
}

private fun trim(d: Double) = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

/** Petite image d'une pièce (déchiffrée puis réduite). */
@Composable
fun Miniature(fichier: String) {
    val c = LocalContext.current
    val image by produceState<Bitmap?>(null, fichier) { value = Pieces.lire(c, fichier)?.let { b -> val f = 160f / maxOf(b.width, b.height); Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true) } }
    Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFE2E8F0)), contentAlignment = Alignment.Center) {
        image?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: Icon(Icons.Filled.Receipt, null, tint = Gris)
    }
}

// ======================= Formulaire d'une opération (avec scan de ticket) =======================

/** Une ligne de détail en cours de saisie. */
data class LigneEdit(val libelle: String, val quantite: String, val montant: String)

/** Une pièce en cours de saisie : `id` null = nouvelle (pas encore enregistrée). */
data class PieceEdit(val id: Long?, val fichier: String, val texte: String)

@Composable
fun EcranFormOperation(id: Long?, genreInitial: String, scanDirect: Boolean, retour: () -> Unit) {
    val c = LocalContext.current
    val dao = remember { Base.dao(c) }
    val scope = rememberCoroutineScope()
    val toutes by dao.operations().collectAsState(emptyList())
    var existante by remember { mutableStateOf<Operation?>(null) }
    var genre by remember { mutableStateOf(genreInitial) }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var montant by remember { mutableStateOf("") }; var tiers by remember { mutableStateOf("") }; var categorie by remember { mutableStateOf("") }
    var portefeuille by remember { mutableStateOf(Listes.PORTEFEUILLES[0]) }; var note by remember { mutableStateOf("") }
    val lignes = remember { mutableStateListOf<LigneEdit>() }
    val pieces = remember { mutableStateListOf<PieceEdit>() }
    val retirees = remember { mutableStateListOf<PieceEdit>() }              // pièces existantes retirées : supprimées seulement à l'enregistrement
    var occupe by remember { mutableStateOf(false) }; var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(id) {
        if (id != null) dao.operation(id)?.let { o ->
            existante = o; genre = o.genre; date = o.date; montant = o.montant.toString(); tiers = o.tiers; categorie = o.categorie; portefeuille = o.portefeuille; note = o.note
            lignes.addAll(dao.lignesOperationListe(id).map { LigneEdit(it.libelle, if (it.quantite == it.quantite.toLong().toDouble()) it.quantite.toLong().toString() else it.quantite.toString(), it.montant.toString()) })
            pieces.addAll(dao.piecesOperationListe(id).map { PieceEdit(it.id, it.fichier, it.texte) })
        }
    }

    // Photo ou image → copie chiffrée + OCR + pré-remplissage (jamais d'écrasement d'une valeur déjà saisie)
    val traiter: (android.net.Uri) -> Unit = { uri ->
        scope.launch {
            occupe = true; message = null
            val image = Pieces.charger(c, uri, 2000)
            if (image == null) message = "Image illisible."
            else {
                val texte = Pieces.lireTexte(image)
                pieces.add(PieceEdit(null, Pieces.enregistrer(c, image), texte))
                val r = AnalyseTicket.analyser(texte)
                if (tiers.isBlank()) r.marchand?.let { tiers = it }
                r.date?.takeIf { !it.isAfter(LocalDate.now()) && (existante == null) }?.let { date = it.toString() }
                if (montant.isBlank()) r.total?.let { montant = it.toString() }
                if (lignes.isEmpty()) r.articles.forEach { lignes.add(LigneEdit(it.libelle, "1", it.montant.toString())) }
                message = if (texte.isBlank()) "Aucun texte reconnu : saisissez les détails à la main." else "Ticket lu. Vérifiez et corrigez si besoin."
            }
            occupe = false
        }
    }
    val fichierPhoto = remember { File(c.cacheDir, "photos").apply { mkdirs() }.let { File(it, "ticket.jpg") } }
    val uriPhoto = remember { FileProvider.getUriForFile(c, c.packageName + ".photos", fichierPhoto) }
    val prendre = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) traiter(uriPhoto) }
    val galerie = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) traiter(uri) }
    LaunchedEffect(Unit) { if (scanDirect && id == null) prendre.launch(uriPhoto) }          // « Scanner un ticket » ouvre directement l'appareil photo

    fun annuler() { pieces.filter { it.id == null }.forEach { Pieces.supprimer(c, it.fichier) }; retour() }
    val somme = lignes.sumOf { it.montant.toLongOrNull() ?: 0 }
    val montantFinal = montant.toLongOrNull() ?: 0
    val suggestionsTiers = remember(toutes, genre) { toutes.filter { it.genre == genre && it.tiers.isNotBlank() }.groupBy { it.tiers }.entries.sortedByDescending { it.value.size }.map { it.key }.take(30) }

    Page(if (id == null) (if (genre == "RECETTE") "Nouvelle recette" else "Nouvelle dépense") else "Corriger l'opération", ::annuler) {
        Carte("Type", Icons.Filled.SwapVert) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tuile(Icons.Filled.RemoveCircle, "Dépense", if (genre == "DEPENSE") Rouge else Color(0xFFCBD5E1)) { genre = "DEPENSE"; categorie = "" }
                Tuile(Icons.Filled.AddCircle, "Recette", if (genre == "RECETTE") Vert else Color(0xFFCBD5E1)) { genre = "RECETTE"; categorie = "" }
            }
        }
        Carte("Pièces justificatives (photo + texte lu)", Icons.Filled.Receipt) {
            Text("Photographiez le ticket ou la facture : la photo est gardée chiffrée comme preuve, le texte est lu et pré-remplit les champs.", fontSize = 12.sp, color = Gris)
            Spacer(Modifier.height(8.dp))
            RangeeTuiles {
                Tuile(Icons.Filled.PhotoCamera, "Photographier", Vert, actif = !occupe) { prendre.launch(uriPhoto) }
                Tuile(Icons.Filled.PhotoLibrary, "Galerie", actif = !occupe) { galerie.launch("image/*") }
            }
            if (occupe) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text("  Lecture du ticket…", fontSize = 13.sp) }
            message?.let { Text(it, fontSize = 13.sp, color = Bleu, modifier = Modifier.padding(top = 6.dp)) }
            pieces.forEachIndexed { i, p ->
                Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Miniature(p.fichier); Text("  Pièce ${i + 1}", fontSize = 14.sp, color = BleuNuit, modifier = Modifier.weight(1f))
                        Icon(Icons.Filled.Delete, "Retirer cette pièce", tint = Rouge, modifier = Modifier.size(26.dp).clickable {
                            if (p.id == null) Pieces.supprimer(c, p.fichier) else retirees.add(p)
                            pieces.removeAt(i)
                        })
                    }
                    Champ("Texte lu (corrigeable)", p.texte, { v -> pieces[i] = p.copy(texte = v) }, lignes = 3)
                }
            }
        }
        Carte("Opération", Icons.Filled.Payments) {
            Champ("Montant total (FCFA)", montant, { montant = it.filter { ch -> ch.isDigit() } }, KeyboardType.Number)
            ChampDate("Date", date, { date = it.toString() })
            Liste(if (genre == "RECETTE") "Source" else "Marchand", tiers.ifBlank { "" }, suggestionsTiers, valeurLibre = true) { tiers = it }
            Liste("Catégorie", categorie, Listes.categories(genre), valeurLibre = true) { categorie = it }
            Liste("Portefeuille", portefeuille, Listes.PORTEFEUILLES, valeurLibre = true) { portefeuille = it }
            Champ("Note", note, { note = it }, lignes = 2)
        }
        Carte("Détail (articles, courses)", Icons.Filled.ListAlt) {
            lignes.forEachIndexed { i, l ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(l.libelle, { v -> lignes[i] = l.copy(libelle = v) }, label = { Text("Article") }, modifier = Modifier.weight(1f).padding(vertical = 2.dp), singleLine = true)
                    OutlinedTextField(l.quantite, { v -> lignes[i] = l.copy(quantite = v.filter { ch -> ch.isDigit() || ch == '.' }) }, label = { Text("Qté") }, modifier = Modifier.width(64.dp).padding(start = 4.dp), singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(l.montant, { v -> lignes[i] = l.copy(montant = v.filter { ch -> ch.isDigit() }) }, label = { Text("Prix") }, modifier = Modifier.width(96.dp).padding(start = 4.dp), singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number))
                    Icon(Icons.Filled.Close, "Retirer la ligne", tint = Rouge, modifier = Modifier.padding(start = 4.dp).size(24.dp).clickable { lignes.removeAt(i) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp).clickable { lignes.add(LigneEdit("", "1", "")) }) { Icon(Icons.Filled.AddCircle, null, tint = Bleu, modifier = Modifier.size(28.dp)); Text("  Ajouter une ligne", color = Bleu, fontSize = 14.sp) }
            if (lignes.isNotEmpty()) {
                Text("Somme des lignes : ${Calculs.francs(somme)}", fontSize = 13.sp, color = if (somme == montantFinal) Vert else Rouge, modifier = Modifier.padding(top = 6.dp))
                if (somme != montantFinal) Text("Utiliser la somme comme montant total", fontSize = 13.sp, color = Bleu, modifier = Modifier.clickable { montant = somme.toString() })
            }
        }
        RangeeTuiles {
            Tuile(Icons.Filled.Check, "Enregistrer", Vert, actif = !occupe && (montantFinal > 0 || somme > 0)) {
                scope.launch {
                    val total = if (montantFinal > 0) montantFinal else somme
                    val maintenant = System.currentTimeMillis()
                    val op = Operation(id = existante?.id ?: 0, genre = genre, date = date, montant = total, tiers = tiers.trim(), categorie = categorie.trim(), portefeuille = portefeuille.trim().ifBlank { "Espèces" },
                        note = note.trim(), creeLe = existante?.creeLe ?: maintenant, modifieLe = maintenant)
                    val oid = if (existante == null) dao.ajouterOperation(op) else { dao.modifierOperation(op); op.id }
                    dao.supprimerLignesOperation(oid)
                    lignes.filter { it.libelle.isNotBlank() || (it.montant.toLongOrNull() ?: 0) > 0 }.forEach { dao.ajouterLigneOperation(LigneOperation(operationId = oid, libelle = it.libelle.trim(), quantite = it.quantite.toDoubleOrNull() ?: 1.0, montant = it.montant.toLongOrNull() ?: 0)) }
                    pieces.forEach { p -> if (p.id == null) dao.ajouterPieceOperation(PieceOperation(operationId = oid, fichier = p.fichier, texte = p.texte)) else dao.modifierPieceOperation(PieceOperation(p.id, oid, p.fichier, p.texte)) }
                    retirees.forEach { dao.supprimerPieceOperation(it.id!!); Pieces.supprimer(c, it.fichier) }
                    retour()
                }
            }
            Tuile(Icons.Filled.Close, "Annuler") { annuler() }
        }
    }
}

// ======================= Pièce justificative : photo + texte lu =======================

/** Photo de la pièce (captures interdites) et son texte lu, corrigeable ; relecture du texte et suppression. */
@Composable
fun EcranPieceOperation(id: Long, retour: () -> Unit) {
    val c = LocalContext.current
    val dao = remember { Base.dao(c) }
    val scope = rememberCoroutineScope()
    var piece by remember { mutableStateOf<PieceOperation?>(null) }
    var image by remember { mutableStateOf<Bitmap?>(null) }
    var texte by remember { mutableStateOf("") }; var message by remember { mutableStateOf<String?>(null) }; var suppression by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val fenetre = (c as? Activity)?.window
        fenetre?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { fenetre?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    LaunchedEffect(id) { dao.pieceOperation(id)?.let { piece = it; texte = it.texte; image = Pieces.lire(c, it.fichier) } }
    Page("Pièce justificative", retour) {
        image?.let { Image(it.asImageBitmap(), "Pièce", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth) } ?: Text("Image introuvable.", color = Rouge)
        Carte("Texte lu (OCR)", Icons.Filled.TextFields) {
            Champ("Texte (corrigeable)", texte, { texte = it; message = null }, lignes = 6)
            message?.let { Text(it, fontSize = 13.sp, color = Vert) }
            Spacer(Modifier.height(6.dp))
            RangeeTuiles {
                Tuile(Icons.Filled.Check, "Enregistrer", Vert, actif = texte != piece?.texte) { scope.launch { piece?.let { p -> dao.modifierPieceOperation(p.copy(texte = texte)); piece = p.copy(texte = texte); message = "Texte enregistré ✓" } } }
                Tuile(Icons.Filled.Refresh, "Relire l'image", Bleu, actif = image != null) { scope.launch { image?.let { texte = Pieces.lireTexte(it); message = "Texte relu : enregistrez pour le garder." } } }
                Tuile(Icons.Filled.Delete, "Supprimer", Rouge) { suppression = true }
            }
        }
    }
    if (suppression) Confirmation("Supprimer cette pièce ?", "La photo chiffrée et son texte seront effacés.", {
        scope.launch { piece?.let { Pieces.supprimer(c, it.fichier); dao.supprimerPieceOperation(it.id) } }; suppression = false; retour()
    }, { suppression = false })
}

// ======================= Statistiques de suivi =======================

/** Une barre horizontale (libellé, valeur, part du maximum). */
@Composable
private fun Barre(libelle: String, valeur: Long, maximum: Long, couleurBarre: Color, droite: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth()) { Text(libelle, Modifier.weight(1f), fontSize = 13.sp, color = BleuNuit); Text(droite, fontSize = 13.sp, color = BleuNuit, textAlign = TextAlign.End) }
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFFE2E8F0))) {
            Box(Modifier.fillMaxWidth(if (maximum <= 0) 0f else (valeur.toFloat() / maximum).coerceIn(0f, 1f)).fillMaxHeight().background(couleurBarre))
        }
    }
}

/** STATISTIQUES DU PORTEFEUILLE : totaux du mois, évolution sur 6 mois, dépenses par catégorie, principaux marchands, soldes par portefeuille. */
@Composable
fun EcranBudgetStats(retour: () -> Unit) {
    val c = LocalContext.current
    val dao = remember { Base.dao(c) }
    val toutes by dao.operations().collectAsState(emptyList())
    var mois by remember { mutableStateOf(YearMonth.now()) }
    val duMois = CalculsBudget.duMois(toutes, mois)
    val t = CalculsBudget.totaux(duMois)
    Page("Statistiques du portefeuille", retour) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Tuile(Icons.Filled.ChevronLeft, "Précédent") { mois = mois.minusMonths(1) }
            Text(Calculs.moisLong(mois), fontSize = 18.sp, color = BleuNuit)
            Tuile(Icons.Filled.ChevronRight, "Suivant", actif = mois.isBefore(YearMonth.now())) { mois = mois.plusMonths(1) }
        }
        Carte("Ce mois-ci", Icons.Filled.Today) {
            Ligne("Recettes", Calculs.francs(t.recettes)); Ligne("Dépenses", Calculs.francs(t.depenses)); Ligne("Solde", Calculs.francs(t.solde))
            Ligne("Dépense par jour", Calculs.francs(CalculsBudget.depenseMoyenneParJour(toutes, mois, LocalDate.now())) + " en moyenne")
            duMois.filter { it.genre == "DEPENSE" }.maxByOrNull { it.montant }?.let { Ligne("Plus grosse dépense", Calculs.francs(it.montant) + " · " + it.tiers.ifBlank { it.categorie.ifBlank { Calculs.dateFr(it.date) } }) }
            Ligne("Opérations", duMois.size.toString())
        }
        val serie = CalculsBudget.serieMois(toutes, mois, 6)
        val maxSerie = serie.maxOfOrNull { maxOf(it.second.recettes, it.second.depenses) } ?: 0
        Carte("Évolution sur 6 mois", Icons.Filled.Timeline) {
            serie.forEach { (m, tt) ->
                Text(Calculs.moisLong(m), fontSize = 12.sp, color = Gris, modifier = Modifier.padding(top = 4.dp))
                Barre("Recettes", tt.recettes, maxSerie, Vert, Calculs.francs(tt.recettes)); Barre("Dépenses", tt.depenses, maxSerie, Rouge, Calculs.francs(tt.depenses))
            }
        }
        val cats = CalculsBudget.parCategorie(duMois, "DEPENSE")
        Carte("Dépenses par catégorie", Icons.Filled.PieChart) {
            if (cats.isEmpty()) Text("Aucune dépense ce mois-ci.", color = Gris, fontSize = 13.sp)
            cats.forEach { (cat, m) -> Barre(cat, m, cats.first().second, Rouge, "${Calculs.francs(m)} · ${CalculsBudget.pourcent(m, t.depenses)} %") }
        }
        val marchands = CalculsBudget.parTiers(duMois, "DEPENSE")
        Carte("Principaux marchands", Icons.Filled.Storefront) {
            if (marchands.isEmpty()) Text("—", color = Gris, fontSize = 13.sp)
            marchands.forEach { (nom, m, n) -> Barre("$nom ($n)", m, marchands.first().second, Bleu, Calculs.francs(m)) }
        }
        val sources = CalculsBudget.parTiers(duMois, "RECETTE")
        if (sources.isNotEmpty()) Carte("Sources de recettes", Icons.Filled.Savings) { sources.forEach { (nom, m, n) -> Barre("$nom ($n)", m, sources.first().second, Vert, Calculs.francs(m)) } }
        Carte("Solde de chaque portefeuille (toutes dates)", Icons.Filled.AccountBalanceWallet) {
            CalculsBudget.soldeParPortefeuille(toutes).ifEmpty { listOf("—" to 0L) }.forEach { (nom, s) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) { Text(nom, Modifier.weight(1f), fontSize = 14.sp, color = BleuNuit); Text(Calculs.francs(s), fontSize = 14.sp, color = if (s >= 0) Vert else Rouge, textAlign = TextAlign.End) }
            }
        }
        RangeeTuiles { Tuile(Icons.Filled.Share, "Partager le bilan", Bleu, actif = duMois.isNotEmpty()) { partager(c, "Portefeuille · ${Calculs.moisLong(mois)}", CalculsBudget.bilanTexte(mois, toutes)) } }
    }
}

// ======================= Onglet « Plus » =======================

/** Raccourcis vers les écrans moins fréquents (bilans du personnel, profil, statistiques du portefeuille). */
@Composable
fun EcranPlus(ouvrir: (Ecran) -> Unit) = Page("Plus") {
    GrosBouton(Icons.Filled.BarChart, "Bilans du personnel", Bleu, sousTexte = "Présences, retards, paie du mois") { ouvrir(Ecran.Stats) }
    GrosBouton(Icons.Filled.PieChart, "Statistiques du portefeuille", Vert, sousTexte = "Recettes, dépenses, catégories") { ouvrir(Ecran.BudgetStats) }
    GrosBouton(Icons.Filled.AccountCircle, "Mon profil", Orange, sousTexte = "Coordonnées, adresse, position") { ouvrir(Ecran.Profil) }
}
