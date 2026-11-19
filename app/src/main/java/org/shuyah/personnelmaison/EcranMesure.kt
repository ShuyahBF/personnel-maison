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
import java.io.File

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
        else -> ChoixMesure(retour) { mode = it }
    }
}

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
            Text("Précision habituelle : quelques centimètres par mètre. Pour un devis ou un achat important, vérifiez au mètre ruban.", color = Gris, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }
        GrosBouton(Icons.Filled.ViewInAr, "Mesure en direct", Bleu, actif = compatible != false,
            sousTexte = if (compatible == false) "Téléphone non compatible (Google ARCore)" else "Réalité augmentée : visez, posez les points") { choisir("direct") }
        GrosBouton(Icons.Filled.PhotoCamera, "Mesure sur photo", Vert, sousTexte = "Avec une feuille A4 ou un carreau comme repère") { choisir("photo") }
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

    // La caméra de mesure suit la vie de l'écran : démarre quand il est au premier plan, s'arrête quand il est masqué, est libérée quand on le quitte
    val cycle = LocalLifecycleOwner.current
    DisposableEffect(cameraPermise) {
        ar.surEtat = { etat = it }
        val observateur = LifecycleEventObserver { _, evenement ->
            when (evenement) {
                Lifecycle.Event.ON_RESUME -> if (cameraPermise) blocage = ar.demarrer()
                Lifecycle.Event.ON_PAUSE -> ar.suspendre()
                else -> {}
            }
        }
        cycle.lifecycle.addObserver(observateur)
        if (cameraPermise && cycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) blocage = ar.demarrer()
        onDispose { cycle.lifecycle.removeObserver(observateur); ar.surEtat = {}; ar.fermer() }
    }

    val points = etat.points
    val aire = if (points.size >= 3) MesureSurface.aireEspace(points) else null
    val perimetre = if (points.size >= 2) MesureSurface.perimetreEspace(points, ferme = points.size >= 3) else null
    val prochain = if (points.isNotEmpty() && etat.reticule != null) MesureSurface.distance(points.last(), etat.reticule!!) else null

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
                        etiquette(MesureSurface.texteLongueur(MesureSurface.distance(points[i], points[j])), ((a.x + b.x) / 2).toFloat(), ((a.y + b.y) / 2).toFloat(), texte)
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
                })
                if (etat.message.isNotBlank()) Text(etat.message, color = Color.White, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xCCD64545)).padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
        // Actions : chacune a son pictogramme et sa légende
        Row(Modifier.fillMaxWidth().background(Color.White).padding(10.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
            Tuile(Icons.Filled.AddLocationAlt, "Poser un point", Vert, actif = etat.surfaceVisee) { ar.poser() }
            Tuile(Icons.Filled.Undo, "Annuler le dernier", Orange, actif = points.isNotEmpty()) { ar.annulerDernier() }
            Tuile(Icons.Filled.RestartAlt, "Recommencer", Rouge, actif = points.isNotEmpty()) { ar.vider(); Toast.makeText(c, "Points effacés.", Toast.LENGTH_SHORT).show() }
            Tuile(Icons.Filled.ContentCopy, "Copier", Bleu, actif = perimetre != null) {
                copier(c, listOfNotNull(aire?.let { "Surface : ${MesureSurface.texteAire(it)}" }, perimetre?.let { "Périmètre : ${MesureSurface.texteLongueur(it)}" }).joinToString(" · "))
            }
        }
    }
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
