package org.shuyah.personnelmaison

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.shuyah.personnelmaison.MesureSurface.P2
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MESURER UNE SURFACE AVEC LA CAMÉRA (v1.45, propriétaire 19/11/2026 : « la mesure de surfaces […] à partir de la caméra du téléphone »).
 * Écran Plus → « Mesurer une surface ». Deux méthodes, au choix :
 *  - EN DIRECT (réalité augmentée, Google ARCore) : on vise, on pose des points, la surface s'affiche ; pour les téléphones compatibles ;
 *  - SUR PHOTO, avec un objet de taille connue (feuille A4, carreau…) posé sur la surface : fonctionne sur tous les téléphones.
 * Rien n'est enregistré ni envoyé : la photo reste dans le cache de l'application, le résultat se copie dans le presse-papiers.
 */
@Composable
fun EcranMesure(retour: () -> Unit) {
    var mode by remember { mutableStateOf("") }          // "" = choix, "direct", "photo"
    when (mode) {
        "direct" -> MesureEnDirect(retour = { mode = "" }, versPhoto = { mode = "photo" })
        "photo" -> MesureSurPhoto(retour = { mode = "" })
        "mesures" -> MesuresEnregistrees(retour = { mode = "" })
        else -> ChoixMesure(retour) { mode = it }
    }
}

// =====================================================================================
// Étalonnage (v1.51) : facteur de correction gardé sur le téléphone
// =====================================================================================

/** Facteur d'étalonnage enregistré (1,0 = aucun). Gardé dans les préférences de l'application : il corrige TOUTES les mesures en direct suivantes. */
private fun lireFacteur(c: Context): Double = c.getSharedPreferences("mesure", Context.MODE_PRIVATE).getFloat("facteur", 1f).toDouble().takeIf { it in MesureSurface.FACTEUR_MIN..MesureSurface.FACTEUR_MAX } ?: 1.0
private fun ecrireFacteur(c: Context, f: Double) { c.getSharedPreferences("mesure", Context.MODE_PRIVATE).edit().putFloat("facteur", f.toFloat()).apply() }

/** Copie un résultat dans le presse-papiers, avec un message de confirmation (jamais d'action silencieuse). */
private fun copier(c: Context, texte: String) {
    (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Mesure", texte))
    Toast.makeText(c, "Mesure copiée : $texte", Toast.LENGTH_SHORT).show()
}

// =====================================================================================
// Choix de la méthode
// =====================================================================================

@Composable
private fun ChoixMesure(retour: () -> Unit, choisir: (String) -> Unit) {
    val c = LocalContext.current
    val compatible = remember { MesureAr.compatible(c) }      // true, false, ou null (« ne sait pas encore »)
    Page("Mesurer une surface", retour) {
        Carte("Deux façons de mesurer", Icons.Filled.Straighten) {
            Text("En direct : vous visez le sol ou le mur et posez les coins un à un. Sur photo : vous photographiez la surface avec une feuille A4 posée dessus.", color = BleuNuit, fontSize = 15.sp)
            Text("Précision habituelle : 1 à 3 % (quelques centimètres par mètre). Pour corriger l'écart de votre téléphone, mesurez un mètre ruban en direct puis touchez « Étalonner ». Pour un devis ou un achat important, vérifiez au mètre ruban.", color = Gris, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }
        GrosBouton(Icons.Filled.ViewInAr, "Mesure en direct", Bleu, actif = compatible != false,
            sousTexte = if (compatible == false) "Téléphone non compatible (Google ARCore)" else "Réalité augmentée : visez, posez les points") { choisir("direct") }
        GrosBouton(Icons.Filled.PhotoCamera, "Mesure sur photo", Vert, sousTexte = "Avec une feuille A4 ou un carreau comme repère") { choisir("photo") }
        GrosBouton(Icons.Filled.Bookmarks, "Mes mesures enregistrées", Orange, sousTexte = "Table basse du salon, etc. : nom, note et points") { choisir("mesures") }
    }
}

// =====================================================================================
// Dessin commun : étiquette de texte lisible sur une image
// =====================================================================================

/** Écrit un texte sur fond sombre arrondi, centré sur (x, y). */
private fun DrawScope.etiquette(texte: String, x: Float, y: Float, taillePx: Float, fond: Color = Color(0xE60B1F3A)) {
    val peinture = Paint().apply { color = android.graphics.Color.WHITE; textSize = taillePx; isAntiAlias = true; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    val l = peinture.measureText(texte) + taillePx; val h = taillePx * 1.5f
    drawRoundRect(fond, Offset(x - l / 2, y - h / 2), androidx.compose.ui.geometry.Size(l, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
    drawIntoCanvas { it.nativeCanvas.drawText(texte, x, y + taillePx * 0.35f, peinture) }
}

/** Bandeau de résultat : surface en grand, périmètre dessous. */
@Composable
private fun BandeauResultat(aire: Double?, perimetre: Double?, detail: String, modifier: Modifier = Modifier) =
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(BleuNuit).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.SquareFoot, null, tint = Orange, modifier = Modifier.size(30.dp))
            Text(if (aire != null) MesureSurface.texteAire(aire) else "—", color = Orange, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
        }
        if (perimetre != null) Text("Périmètre : ${MesureSurface.texteLongueur(perimetre)}", color = Color.White, fontSize = 16.sp)
        if (detail.isNotBlank()) Text(detail, color = Color(0xFFCBD5E1), fontSize = 13.sp)
    }

// =====================================================================================
// Mesure en direct (réalité augmentée)
// =====================================================================================

@Composable
private fun MesureEnDirect(retour: () -> Unit, versPhoto: () -> Unit) {
    val c = LocalContext.current
    val activite = c as Activity
    val ar = remember { MesureAr(activite) }
    var etat by remember { mutableStateOf(MesureAr.Etat()) }
    var blocage by remember { mutableStateOf<String?>(null) }                  // message qui empêche la mesure (non compatible, caméra refusée…)
    var cameraPermise by remember { mutableStateOf(ContextCompat.checkSelfPermission(c, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val demande = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        cameraPermise = ok
        if (!ok) blocage = "La mesure a besoin de la caméra. Autorisez-la dans les réglages du téléphone (Applications → Personnel de maison → Autorisations)."
    }
    LaunchedEffect(Unit) { if (!cameraPermise) demande.launch(Manifest.permission.CAMERA) }
    // « Terminer » (v1.52) : les points sont FIGÉS (copie), la caméra est arrêtée ; null = mesure en cours
    var termine by remember { mutableStateOf<List<MesureSurface.P3>?>(null) }
    val estTermine by rememberUpdatedState(termine != null)

    // La caméra de mesure suit la vie de l'écran : démarre quand il est au premier plan, s'arrête quand il est masqué, est libérée quand on le quitte
    val cycle = LocalLifecycleOwner.current
    DisposableEffect(cameraPermise) {
        ar.surEtat = { etat = it }
        val observateur = LifecycleEventObserver { _, evenement ->
            when (evenement) {
                Lifecycle.Event.ON_RESUME -> if (cameraPermise && !estTermine) blocage = ar.demarrer()    // mesure terminée : la caméra reste éteinte
                Lifecycle.Event.ON_PAUSE -> ar.suspendre()
                else -> {}
            }
        }
        cycle.lifecycle.addObserver(observateur)
        if (cameraPermise && cycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) blocage = ar.demarrer()
        onDispose { cycle.lifecycle.removeObserver(observateur); ar.surEtat = {}; ar.fermer() }
    }

    // Étalonnage (v1.51) : facteur gardé sur le téléphone ; toutes les longueurs affichées le suivent, les surfaces suivent son carré
    var facteur by remember { mutableStateOf(lireFacteur(c)) }
    var dialogue by remember { mutableStateOf("") }                      // "", "etalonner", "enregistrer"
    val points = termine ?: etat.points
    val aire = if (points.size >= 3) MesureSurface.aireEtalonnee(MesureSurface.aireEspace(points), facteur) else null
    val perimetre = if (points.size >= 2) MesureSurface.longueurEtalonnee(MesureSurface.perimetreEspace(points, ferme = points.size >= 3), facteur) else null
    val prochain = if (points.isNotEmpty() && etat.reticule != null) MesureSurface.longueurEtalonnee(MesureSurface.distance(points.last(), etat.reticule!!), facteur) else null

    Column(Modifier.fillMaxSize()) {
        Titre("Mesure en direct", retour)
        if (blocage != null) {
            // Mesure impossible : on dit pourquoi et on propose tout de suite l'autre méthode
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Carte("Mesure en direct indisponible", Icons.Filled.Warning) { Text(blocage!!, color = BleuNuit, fontSize = 15.sp) }
                GrosBouton(Icons.Filled.PhotoCamera, "Mesure sur photo", Vert, sousTexte = "Fonctionne sur tous les téléphones") { versPhoto() }
                GrosBouton(Icons.Filled.Refresh, "Réessayer", Bleu) { Toast.makeText(c, "Nouvel essai en cours…", Toast.LENGTH_SHORT).show(); blocage = if (cameraPermise) ar.demarrer() else { demande.launch(Manifest.permission.CAMERA); blocage } }
            }
            return@Column
        }
        // clipToBounds : le dessin (côtés, points) est COUPÉ aux bords de l'image de la caméra ; sans cela un côté qui sort de l'image se dessinait par-dessus les boutons du bas (constaté sur la vidéo du 19/11/2026)
        Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black).clipToBounds()) {
            if (cameraPermise) AndroidView(factory = { ar.creerVue(it) }, modifier = Modifier.fillMaxSize())
            // Par-dessus la caméra : points posés, côtés avec leur longueur, viseur au centre
            Canvas(Modifier.fillMaxSize()) {
                val ecran = points.map { MesureSurface.versEcran(it, etat.vue, etat.projection, size.width.toInt(), size.height.toInt()) }
                val texte = 13.sp.toPx()
                // Surface colorée quand le contour a au moins 3 points tous visibles
                if (ecran.size >= 3 && ecran.all { it != null }) {
                    val chemin = Path().apply { moveTo(ecran[0]!!.x.toFloat(), ecran[0]!!.y.toFloat()); ecran.drop(1).forEach { lineTo(it!!.x.toFloat(), it.y.toFloat()) }; close() }
                    drawPath(chemin, Orange.copy(alpha = 0.28f))
                }
                for (i in ecran.indices) {
                    val a = ecran[i] ?: continue
                    val j = (i + 1) % ecran.size
                    val fermeture = j == 0
                    if (ecran.size >= 2 && (!fermeture || ecran.size >= 3)) ecran[j]?.let { b ->
                        drawLine(if (fermeture) Color.White.copy(alpha = 0.7f) else Orange, Offset(a.x.toFloat(), a.y.toFloat()), Offset(b.x.toFloat(), b.y.toFloat()), strokeWidth = 3.dp.toPx())
                        etiquette(MesureSurface.texteLongueur(MesureSurface.longueurEtalonnee(MesureSurface.distance(points[i], points[j]), facteur)), ((a.x + b.x) / 2).toFloat(), ((a.y + b.y) / 2).toFloat(), texte)
                    }
                }
                ecran.forEachIndexed { i, p -> if (p != null) { drawCircle(Color.White, 9.dp.toPx(), Offset(p.x.toFloat(), p.y.toFloat())); drawCircle(if (i == 0) Vert else Orange, 6.dp.toPx(), Offset(p.x.toFloat(), p.y.toFloat())) } }
                // Trait du dernier point vers le viseur : longueur du côté en cours
                val centre = Offset(size.width / 2, size.height / 2)
                ecran.lastOrNull()?.let { d -> if (prochain != null) {
                    drawLine(Color.White, Offset(d.x.toFloat(), d.y.toFloat()), centre, strokeWidth = 2.dp.toPx())
                    etiquette(MesureSurface.texteLongueur(prochain), (d.x.toFloat() + centre.x) / 2, (d.y.toFloat() + centre.y) / 2, texte, Color(0xE62E9E6B))
                } }
                // Viseur : vert quand une surface est repérée (on peut poser), rouge sinon
                val couleur = if (etat.surfaceVisee) Vert else Rouge
                drawCircle(couleur, 16.dp.toPx(), centre, style = Stroke(3.dp.toPx()))
                drawCircle(couleur, 3.dp.toPx(), centre)
            }
            Column(Modifier.align(Alignment.TopCenter).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BandeauResultat(aire, perimetre, when {
                    points.isEmpty() -> "Visez un coin de la surface puis appuyez sur « Poser un point »."
                    points.size < 3 -> "${points.size} point(s) posé(s) : il en faut au moins 3 pour une surface."
                    else -> "${points.size} points. Ajoutez les autres coins dans l'ordre du tour."
                } + if (facteur != 1.0) " · Étalonné ${MesureSurface.textePourcentage(facteur)}" else "")
                if (etat.message.isNotBlank()) Text(etat.message, color = Color.White, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xCCD64545)).padding(horizontal = 10.dp, vertical = 6.dp))
            }
            // MESURE TERMINÉE (v1.52) : résultat figé, lisible tranquillement ; la caméra est arrêtée (batterie)
            if (termine != null) Column(Modifier.fillMaxSize().background(BleuNuit).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Mesure terminée", color = Orange, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                BandeauResultat(aire, perimetre, "${points.size} points" + if (facteur != 1.0) " · Étalonné ${MesureSurface.textePourcentage(facteur)}" else "")
                val cotes = MesureSurface.cotesEspace(points, points.size >= 3).map { MesureSurface.longueurEtalonnee(it, facteur) }
                cotes.forEachIndexed { i, l -> Text(if (cotes.size == 1) "Longueur : ${MesureSurface.texteLongueur(l)}" else "Côté ${i + 1} : ${MesureSurface.texteLongueur(l)}", color = Color.White, fontSize = 16.sp) }
                Text("Touchez « Enregistrer » pour la garder, « Copier » pour la coller ailleurs, ou « Reprendre » pour continuer à mesurer.", color = Color(0xFFCBD5E1), fontSize = 13.sp)
            }
        }
        // Actions : chacune a son pictogramme et sa légende
        Column(Modifier.fillMaxWidth().background(Color.White).padding(10.dp)) { RangeeTuiles {
            if (termine == null) {
                Tuile(Icons.Filled.AddLocationAlt, "Poser un point", Vert, actif = etat.surfaceVisee) { ar.poser() }
                Tuile(Icons.Filled.Undo, "Annuler le dernier", Orange, actif = points.isNotEmpty()) { ar.annulerDernier() }
                Tuile(Icons.Filled.RestartAlt, "Recommencer", Rouge, actif = points.isNotEmpty()) { ar.vider(); Toast.makeText(c, "Points effacés.", Toast.LENGTH_SHORT).show() }
                Tuile(Icons.Filled.Straighten, "Étalonner", Bleu, actif = points.size >= 2 || facteur != 1.0) { dialogue = "etalonner" }
                Tuile(Icons.Filled.BookmarkAdd, "Enregistrer", Vert, actif = points.size >= 2) { dialogue = "enregistrer" }
                // TERMINER : fige les points, arrête la caméra et affiche le résultat en grand
                Tuile(Icons.Filled.Stop, "Terminer", Rouge, actif = points.size >= 2) {
                    termine = etat.points; ar.suspendre(); Toast.makeText(c, "Mesure terminée : caméra arrêtée.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Tuile(Icons.Filled.PlayArrow, "Reprendre", Vert) { termine = null; blocage = ar.demarrer(); Toast.makeText(c, "Mesure reprise : caméra relancée.", Toast.LENGTH_SHORT).show() }
                Tuile(Icons.Filled.BookmarkAdd, "Enregistrer", Vert) { dialogue = "enregistrer" }
                Tuile(Icons.Filled.ContentCopy, "Copier", Bleu, actif = perimetre != null) {
                    copier(c, listOfNotNull(aire?.let { "Surface : ${MesureSurface.texteAire(it)}" }, perimetre?.let { "Périmètre : ${MesureSurface.texteLongueur(it)}" }).joinToString(" · "))
                }
                Tuile(Icons.Filled.Straighten, "Étalonner", Bleu) { dialogue = "etalonner" }
                Tuile(Icons.Filled.Close, "Quitter", Orange) { retour() }
            }
        } }
    }
    if (dialogue == "etalonner") DialogueEtalonnage(points, facteur, surFacteur = { facteur = it; ecrireFacteur(c, it) }, fermer = { dialogue = "" })
    if (dialogue == "enregistrer") DialogueEnregistrer(
        titre = "Enregistrer cette mesure", resume = listOfNotNull(aire?.let { "Surface : ${MesureSurface.texteAire(it)}" }, perimetre?.let { "Périmètre : ${MesureSurface.texteLongueur(it)}" }).joinToString(" · "),
        nombrePoints = points.size, fermer = { dialogue = "" }) { nom, note ->
        val ferme = points.size >= 3
        Enregistrement.ajouter(c, MesureEnregistree(nom = nom, note = note, type = "direct", points = MesureSurface.pointsVersTexte(points),
            cotes = MesureSurface.nombresVersTexte(MesureSurface.cotesEspace(points, ferme).map { MesureSurface.longueurEtalonnee(it, facteur) }),
            aire = aire, perimetre = perimetre, facteur = facteur, creeLe = System.currentTimeMillis()))
        dialogue = ""
    }
}

// =====================================================================================
// Étalonnage et enregistrement : fenêtres
// =====================================================================================

/** Accès à la base pour les mesures enregistrées (le toast confirme toujours : jamais d'enregistrement silencieux). */
private object Enregistrement {
    fun ajouter(c: Context, m: MesureEnregistree) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            Base.dao(c).ajouterMesure(m)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { Toast.makeText(c, "Mesure « ${m.nom} » enregistrée (Mes mesures enregistrées).", Toast.LENGTH_LONG).show() }
        }
    }
}

/**
 * ÉTALONNAGE : on pose 2 points aux deux bouts d'une longueur CONNUE (un mètre ruban ouvert à 140 cm…), on saisit la vraie longueur, et l'application
 * garde le facteur de correction (vraie longueur ÷ longueur mesurée). Utilise les 2 DERNIERS points posés.
 */
@Composable
private fun DialogueEtalonnage(points: List<MesureSurface.P3>, facteur: Double, surFacteur: (Double) -> Unit, fermer: () -> Unit) {
    val c = LocalContext.current
    var cm by remember { mutableStateOf("") }
    val brute = if (points.size >= 2) MesureSurface.distance(points[points.size - 2], points.last()) else null
    AlertDialog(onDismissRequest = fermer, title = { Text("Étalonner la mesure") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (brute != null) {
                Text("Les 2 derniers points mesurent ${MesureSurface.texteLongueur(brute)} (sans correction).", color = BleuNuit, fontSize = 15.sp)
                Champ("Vraie longueur entre ces 2 points (cm)", cm, { cm = it }, KeyboardType.Decimal)
                Text("Posez les 2 points aux deux extrémités d'une longueur connue (mètre ruban ouvert à 100 ou 140 cm), puis saisissez cette longueur. Toutes les mesures suivantes seront corrigées.", color = Gris, fontSize = 13.sp)
            } else Text("Posez d'abord 2 points aux deux extrémités d'une longueur connue (mètre ruban).", color = BleuNuit, fontSize = 15.sp)
            if (facteur != 1.0) Text("Étalonnage actuel : ${MesureSurface.textePourcentage(facteur)}.", color = Gris, fontSize = 13.sp)
        } },
        confirmButton = { if (brute != null) Tuile(Icons.Filled.Check, "Étalonner", Vert, actif = MesureSurface.lireCentimetres(cm) != null) {
            val reelle = MesureSurface.lireCentimetres(cm)
            val f = reelle?.let { MesureSurface.facteurEtalonnage(it, brute) }
            if (f == null) Toast.makeText(c, "Étalonnage refusé : l'écart dépasse 15 %. Vérifiez la longueur saisie et la position des 2 points.", Toast.LENGTH_LONG).show()
            else { surFacteur(f); Toast.makeText(c, "Étalonnage enregistré : ${MesureSurface.textePourcentage(f)}.", Toast.LENGTH_LONG).show(); fermer() }
        } },
        dismissButton = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (facteur != 1.0) Tuile(Icons.Filled.RestartAlt, "Retirer", Rouge) { surFacteur(1.0); Toast.makeText(c, "Étalonnage retiré.", Toast.LENGTH_SHORT).show(); fermer() }
            Tuile(Icons.Filled.Close, "Fermer", Orange) { fermer() }
        } })
}

/** Fenêtre « Enregistrer cette mesure » : un nom (obligatoire) et une note ; au moins 2 points. */
@Composable
private fun DialogueEnregistrer(titre: String, resume: String, nombrePoints: Int, fermer: () -> Unit, enregistrer: (String, String) -> Unit) {
    var nom by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = fermer, title = { Text(titre) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$nombrePoints points${if (resume.isNotBlank()) " · $resume" else ""}", color = BleuNuit, fontSize = 15.sp)
            Champ("Nom (ex. Table basse du salon)", nom, { nom = it })
            Champ("Note (facultative)", note, { note = it }, lignes = 3)
            if (nom.isNotBlank()) Text("Modifié, non enregistré : touchez « Enregistrer ».", color = Orange, fontSize = 12.sp)
        } },
        confirmButton = { Tuile(Icons.Filled.Save, "Enregistrer", Vert, actif = nom.isNotBlank()) { enregistrer(nom.trim(), note.trim()) } },
        dismissButton = { Tuile(Icons.Filled.Close, "Annuler", Orange) { fermer() } })
}

// =====================================================================================
// Mes mesures enregistrées
// =====================================================================================

@Composable
private fun MesuresEnregistrees(retour: () -> Unit) {
    val c = LocalContext.current
    val dao = remember { Base.dao(c) }
    val scope = rememberCoroutineScope()
    val liste by dao.mesuresEnregistrees().collectAsState(emptyList())
    var ouverte by remember { mutableStateOf<MesureEnregistree?>(null) }
    var aSupprimer by remember { mutableStateOf<MesureEnregistree?>(null) }
    val date = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE) }
    Page("Mes mesures enregistrées", retour) {
        if (liste.isEmpty()) Carte("Aucune mesure enregistrée", Icons.Filled.Bookmarks) {
            Text("Faites une mesure en direct (2 points au moins : une longueur ; 3 points et plus : une surface), puis touchez « Enregistrer » : donnez-lui un nom (« Table basse du salon ») et une note.", color = Gris, fontSize = 14.sp)
        }
        liste.forEach { m ->
            Carte(m.nom, Icons.Filled.Straighten) {
                val points = MesureSurface.texteVersPoints(m.points)
                Text(listOfNotNull(m.aire?.let { "Surface : ${MesureSurface.texteAire(it)}" }, m.perimetre?.let { "Périmètre : ${MesureSurface.texteLongueur(it)}" },
                    if (m.aire == null) MesureSurface.texteVersNombres(m.cotes).firstOrNull()?.let { "Longueur : ${MesureSurface.texteLongueur(it)}" } else null).joinToString(" · "), color = BleuNuit, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("${if (m.type == "direct") "En direct" else "Sur photo"} · ${points.size.takeIf { it > 0 } ?: MesureSurface.texteVersNombres(m.cotes).size} points/côtés · ${date.format(Date(m.creeLe))}", color = Gris, fontSize = 13.sp)
                if (m.note.isNotBlank()) Text(m.note, color = BleuNuit, fontSize = 14.sp)
                RangeeTuiles {
                    Tuile(Icons.Filled.Visibility, "Détail", Bleu) { ouverte = m }
                    Tuile(Icons.Filled.ContentCopy, "Copier", Bleu) { copier(c, "${m.nom} : " + listOfNotNull(m.aire?.let { "Surface ${MesureSurface.texteAire(it)}" }, m.perimetre?.let { "Périmètre ${MesureSurface.texteLongueur(it)}" }).joinToString(" · ").ifBlank { MesureSurface.texteVersNombres(m.cotes).firstOrNull()?.let { MesureSurface.texteLongueur(it) } ?: "" }) }
                    Tuile(Icons.Filled.Delete, "Supprimer", Rouge) { aSupprimer = m }
                }
            }
        }
    }
    ouverte?.let { m -> DetailMesure(m, fermer = { ouverte = null }) { nom, note -> scope.launch { dao.modifierMesure(m.id, nom, note) }; Toast.makeText(c, "Mesure modifiée.", Toast.LENGTH_SHORT).show(); ouverte = null } }
    aSupprimer?.let { m -> Confirmation("Supprimer cette mesure ?", "« ${m.nom} » sera supprimée définitivement.", { scope.launch { dao.supprimerMesure(m.id) }; Toast.makeText(c, "Mesure supprimée.", Toast.LENGTH_SHORT).show(); aSupprimer = null }, { aSupprimer = null }) }
}

/** Détail d'une mesure enregistrée : nom et note modifiables, côtés, et coordonnées des points (en cm depuis le premier point). */
@Composable
private fun DetailMesure(m: MesureEnregistree, fermer: () -> Unit, enregistrer: (String, String) -> Unit) {
    var nom by remember { mutableStateOf(m.nom) }
    var note by remember { mutableStateOf(m.note) }
    val points = MesureSurface.texteVersPoints(m.points)
    val cotes = MesureSurface.texteVersNombres(m.cotes)
    val modifie = nom != m.nom || note != m.note
    AlertDialog(onDismissRequest = fermer, title = { Text("Mesure enregistrée") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Champ("Nom", nom, { nom = it })
            Champ("Note", note, { note = it }, lignes = 3)
            if (modifie) Text("Modifié, non enregistré : touchez « Enregistrer ».", color = Orange, fontSize = 12.sp)
            m.aire?.let { Text("Surface : ${MesureSurface.texteAire(it)}", color = BleuNuit, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            m.perimetre?.let { Text("Périmètre : ${MesureSurface.texteLongueur(it)}", color = BleuNuit, fontSize = 15.sp) }
            if (m.facteur != 1.0) Text("Étalonnage appliqué : ${MesureSurface.textePourcentage(m.facteur)}", color = Gris, fontSize = 13.sp)
            Text("Côtés", fontWeight = FontWeight.Bold, color = BleuNuit)
            cotes.forEachIndexed { i, l -> Text(if (cotes.size == 1) "Longueur : ${MesureSurface.texteLongueur(l)}" else "Côté ${i + 1} : ${MesureSurface.texteLongueur(l)}", fontSize = 14.sp) }
            if (points.isNotEmpty()) {
                Text("Points (en cm, par rapport au point 1)", fontWeight = FontWeight.Bold, color = BleuNuit)
                points.forEachIndexed { i, p -> val o = points[0]
                    Text("Point ${i + 1} : x ${"%.1f".format(Locale.FRANCE, (p.x - o.x) * 100)} · y ${"%.1f".format(Locale.FRANCE, (p.y - o.y) * 100)} · z ${"%.1f".format(Locale.FRANCE, (p.z - o.z) * 100)}", fontSize = 13.sp, color = Gris) }
            }
        } },
        confirmButton = { Tuile(Icons.Filled.Save, "Enregistrer", Vert, actif = modifie && nom.isNotBlank()) { enregistrer(nom.trim(), note.trim()) } },
        dismissButton = { Tuile(Icons.Filled.Close, "Fermer", Orange) { fermer() } })
}

// =====================================================================================
// Mesure sur photo, avec un objet de référence
// =====================================================================================

/** Charge une photo réduite (2 000 pixels au plus) et remise dans le bon sens (orientation notée par l'appareil photo). */
private fun chargerPhoto(c: Context, uri: Uri): Bitmap? = try {
    val taille = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, taille) }
    var reduction = 1
    while (maxOf(taille.outWidth, taille.outHeight) / reduction > 2000) reduction *= 2
    val image = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = reduction }) }
    val orientation = c.contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } ?: ExifInterface.ORIENTATION_NORMAL
    val degres = when (orientation) { ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f }
    if (image != null && degres != 0f) Bitmap.createBitmap(image, 0, 0, image.width, image.height, Matrix().apply { postRotate(degres) }, true) else image
} catch (e: Exception) { null }

private const val AUTRE = "Autre… (dimensions en cm)"

@Composable
private fun MesureSurPhoto(retour: () -> Unit) {
    val c = LocalContext.current
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    val coins = remember { mutableStateListOf<P2>() }          // 4 coins de l'objet de référence (pixels de la photo)
    val contour = remember { mutableStateListOf<P2>() }        // points de la surface à mesurer
    var nomReference by remember { mutableStateOf(MesureSurface.REFERENCES[0].nom) }
    var cm1 by remember { mutableStateOf("") }; var cm2 by remember { mutableStateOf("") }
    var tourner by remember { mutableStateOf(false) }

    fun recevoir(uri: Uri?) {
        if (uri == null) return
        Toast.makeText(c, "Chargement de la photo en cours…", Toast.LENGTH_SHORT).show()
        val image = chargerPhoto(c, uri)
        if (image == null) { Toast.makeText(c, "Photo illisible : réessayez.", Toast.LENGTH_LONG).show(); return }
        photo = image; coins.clear(); contour.clear(); tourner = false
        Toast.makeText(c, "Photo chargée : touchez les 4 coins du repère.", Toast.LENGTH_SHORT).show()
    }
    val fichier = remember { File(c.cacheDir, "photos").apply { mkdirs() }.let { File(it, "mesure.jpg") } }
    val uriPhoto = remember { FileProvider.getUriForFile(c, c.packageName + ".photos", fichier) }
    val prendre = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) recevoir(uriPhoto) }
    val galerie = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { recevoir(it) }

    val reference = if (nomReference == AUTRE) MesureSurface.referenceLibre(cm1, cm2) else MesureSurface.REFERENCES.firstOrNull { it.nom == nomReference }
    val resultat = if (reference != null && coins.size == 4 && contour.size >= 2) MesureSurface.mesurerSurPhoto(coins.toList(), reference, contour.toList(), tourner) else null
    val etape = when { photo == null -> 0; coins.size < 4 -> 1; else -> 2 }
    var enregistrerOuvert by remember { mutableStateOf(false) }

    Page("Mesure sur photo", retour) {
        Carte("Le repère de taille connue", Icons.Filled.CropPortrait) {
            Liste("Objet posé sur la surface", nomReference, MesureSurface.REFERENCES.map { it.nom } + AUTRE) { nomReference = it }
            if (nomReference == AUTRE) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Champ("Côté 1 (cm)", cm1, { cm1 = it }, KeyboardType.Decimal, modifier = Modifier.weight(1f))
                Champ("Côté 2 (cm)", cm2, { cm2 = it }, KeyboardType.Decimal, modifier = Modifier.weight(1f))
            }
            if (nomReference == AUTRE && reference == null) Text("Saisissez les deux côtés du repère en centimètres (ex. 21 et 29,7).", color = Rouge, fontSize = 13.sp)
            Text("Posez le repère À PLAT sur la surface à mesurer, près de son milieu, et photographiez de façon à voir toute la surface.", color = Gris, fontSize = 13.sp)
        }
        RangeeTuiles {
            Tuile(Icons.Filled.PhotoCamera, "Photographier", Vert) { prendre.launch(uriPhoto) }
            Tuile(Icons.Filled.PhotoLibrary, "Galerie", Bleu) { galerie.launch("image/*") }
            Tuile(Icons.Filled.Undo, "Annuler le dernier", Orange, actif = coins.isNotEmpty()) { if (contour.isNotEmpty()) contour.removeAt(contour.size - 1) else coins.removeAt(coins.size - 1) }
            Tuile(Icons.Filled.RestartAlt, "Recommencer", Rouge, actif = coins.isNotEmpty()) { coins.clear(); contour.clear(); tourner = false; Toast.makeText(c, "Points effacés.", Toast.LENGTH_SHORT).show() }
            Tuile(Icons.Filled.Rotate90DegreesCw, "Tourner le repère", Bleu, actif = coins.size == 4 && reference != null && reference.petit != reference.grand) {
                tourner = !tourner; Toast.makeText(c, "Petit et grand côté du repère inversés : les longueurs sont recalculées.", Toast.LENGTH_SHORT).show()
            }
            Tuile(Icons.Filled.ContentCopy, "Copier", Bleu, actif = resultat != null) {
                resultat?.let { r -> copier(c, if (contour.size >= 3) "Surface : ${MesureSurface.texteAire(r.aire)} · Périmètre : ${MesureSurface.texteLongueur(r.perimetre)}" else "Longueur : ${MesureSurface.texteLongueur(r.cotes.first())}") }
            }
            Tuile(Icons.Filled.BookmarkAdd, "Enregistrer", Vert, actif = resultat != null) { enregistrerOuvert = true }
        }
        if (enregistrerOuvert && resultat != null) DialogueEnregistrer("Enregistrer cette mesure",
            if (contour.size >= 3) "Surface : ${MesureSurface.texteAire(resultat.aire)} · Périmètre : ${MesureSurface.texteLongueur(resultat.perimetre)}" else "Longueur : ${MesureSurface.texteLongueur(resultat.cotes.first())}",
            contour.size, fermer = { enregistrerOuvert = false }) { nom, note ->
            // Sur photo, les points sont des pixels : on garde les côtés (mètres) calculés avec le repère, pas les pixels
            Enregistrement.ajouter(c, MesureEnregistree(nom = nom, note = note, type = "photo", points = "", cotes = MesureSurface.nombresVersTexte(resultat.cotes),
                aire = if (contour.size >= 3) resultat.aire else null, perimetre = if (contour.size >= 3) resultat.perimetre else null, facteur = 1.0, creeLe = System.currentTimeMillis()))
            enregistrerOuvert = false
        }
        // Consigne de l'étape en cours (une seule chose à faire à la fois)
        Text(when (etape) {
            0 -> "① Photographiez la surface avec le repère posé dessus."
            1 -> "② Touchez les 4 coins du repère (${coins.size} sur 4). Appui long sur un point pour le déplacer."
            else -> "③ Touchez les coins de la surface à mesurer, dans l'ordre du tour (2 points = une longueur, 3 et plus = une surface)."
        }, color = BleuNuit, fontSize = 16.sp, fontWeight = FontWeight.Bold)

        if (etape == 2) BandeauResultat(resultat?.takeIf { contour.size >= 3 }?.aire, resultat?.takeIf { contour.size >= 3 }?.perimetre, when {
            reference == null -> "Dimensions du repère à saisir."
            contour.size < 2 -> "Touchez au moins 2 points."
            resultat == null -> "Mesure impossible : un point est trop loin (au-delà de l'horizon de la surface) ou les coins du repère sont mal placés."
            contour.size == 2 -> "Longueur : ${MesureSurface.texteLongueur(resultat.cotes.first())}"
            else -> "Côtés : " + resultat.cotes.joinToString(" · ") { MesureSurface.texteLongueur(it) }
        })

        photo?.let { image ->
            // La photo occupe toute la largeur ; `echelle` convertit un appui (pixels de l'écran) en pixels de la photo
            Box(Modifier.fillMaxWidth().aspectRatio(image.width.toFloat() / image.height).clip(RoundedCornerShape(12.dp))) {
                Image(image.asImageBitmap(), "Photo de la surface à mesurer", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize()
                    .pointerInput(image) {
                        detectTapGestures { o ->
                            val p = P2(o.x.toDouble() * image.width / size.width, o.y.toDouble() * image.height / size.height)
                            if (coins.size < 4) coins.add(p) else if (contour.size < 60) contour.add(p)
                        }
                    }
                    .pointerInput(image) {
                        // Appui long puis glisser : déplace le point le plus proche (coin du repère ou point du contour)
                        var liste: MutableList<P2>? = null; var indice = -1
                        detectDragGesturesAfterLongPress(
                            onDragStart = { o ->
                                val p = P2(o.x.toDouble() * image.width / size.width, o.y.toDouble() * image.height / size.height)
                                val candidats = coins.indices.map { Triple(coins as MutableList<P2>, it, MesureSurface.distance(coins[it], p)) } +
                                    contour.indices.map { Triple(contour as MutableList<P2>, it, MesureSurface.distance(contour[it], p)) }
                                val proche = candidats.minByOrNull { it.third }
                                // On n'attrape un point que si le doigt est à moins de 8 % de la largeur de la photo
                                if (proche != null && proche.third < image.width * 0.08) { liste = proche.first; indice = proche.second } else { liste = null; indice = -1 }
                            },
                            onDrag = { changement, d ->
                                changement.consume()
                                liste?.let { l -> if (indice in l.indices) l[indice] = P2(
                                    (l[indice].x + d.x.toDouble() * image.width / size.width).coerceIn(0.0, image.width.toDouble()),
                                    (l[indice].y + d.y.toDouble() * image.height / size.height).coerceIn(0.0, image.height.toDouble())) }
                            },
                            onDragEnd = { liste = null; indice = -1 }, onDragCancel = { liste = null; indice = -1 })
                    }) {
                    fun ecran(p: P2) = Offset((p.x * size.width / image.width).toFloat(), (p.y * size.height / image.height).toFloat())
                    val texte = 12.sp.toPx()
                    // Repère : contour vert (remis en « tour » quand les 4 coins sont posés)
                    val repere = if (coins.size == 4) MesureSurface.ordonnerCoins(coins.toList()) else coins.toList()
                    for (i in repere.indices) if (repere.size == 4 || i < repere.size - 1)
                        drawLine(Vert, ecran(repere[i]), ecran(repere[(i + 1) % repere.size]), strokeWidth = 3.dp.toPx())
                    repere.forEach { drawCircle(Color.White, 8.dp.toPx(), ecran(it)); drawCircle(Vert, 5.dp.toPx(), ecran(it)) }
                    // Surface à mesurer : contour orange, longueur sur chaque côté
                    if (contour.size >= 3) drawPath(Path().apply { ecran(contour[0]).let { moveTo(it.x, it.y) }; contour.drop(1).forEach { p -> ecran(p).let { lineTo(it.x, it.y) } }; close() }, Orange.copy(alpha = 0.28f))
                    for (i in contour.indices) {
                        val j = (i + 1) % contour.size
                        if (contour.size < 2 || (j == 0 && contour.size < 3)) continue
                        val a = ecran(contour[i]); val b = ecran(contour[j])
                        drawLine(Orange, a, b, strokeWidth = 3.dp.toPx())
                        resultat?.cotes?.getOrNull(i)?.let { etiquette(MesureSurface.texteLongueur(it), (a.x + b.x) / 2, (a.y + b.y) / 2, texte) }
                    }
                    contour.forEach { drawCircle(Color.White, 8.dp.toPx(), ecran(it)); drawCircle(Orange, 5.dp.toPx(), ecran(it)) }
                }
            }
        }
        Carte("Pour une bonne mesure", Icons.Filled.Info) {
            Text("• La surface doit être plane (sol, mur, table) et le repère posé à plat dessus.\n• Plus le repère est grand et proche du milieu de la surface, plus la mesure est juste : pour une pièce entière, préférez un grand carreau ou une feuille A3 à une feuille A4.\n• Touchez les coins avec soin : un appui long sur un point permet de le déplacer.\n• La surface reste juste même si le repère est « tourné » ; seules les longueurs des côtés en dépendent.", color = Gris, fontSize = 13.sp)
        }
    }
}
