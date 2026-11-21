package org.shuyah.personnelmaison

import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * MESURE DE SURFACES AVEC LA CAMÉRA (v1.45, propriétaire 19/11/2026) — CALCULS PURS, sans Android : entièrement testés (MesureSurfaceTest).
 *
 * Pour un développeur WinDev : ce fichier ne contient que des « procédures globales » de géométrie. Deux méthodes s'en servent :
 *  1. PHOTO + OBJET DE RÉFÉRENCE : on connaît la taille réelle d'un rectangle posé sur la surface (feuille A4, carreau…). Ses 4 coins sur la photo
 *     donnent la « transformation de perspective » (homographie) qui convertit n'importe quel point de la photo en mètres sur la surface.
 *  2. RÉALITÉ AUGMENTÉE (Google ARCore) : le téléphone donne directement des points en mètres dans l'espace (x, y, z) ; on calcule la surface
 *     du polygone qu'ils forment, et leur position à l'écran pour dessiner les repères.
 */
object MesureSurface {
    /** Point d'un plan (photo en pixels, ou surface en mètres). */
    data class P2(val x: Double, val y: Double)

    /** Point de l'espace en mètres (réalité augmentée). */
    data class P3(val x: Double, val y: Double, val z: Double)

    /** Objet de référence de taille connue (en mètres) : petit côté, grand côté. */
    data class Reference(val nom: String, val petit: Double, val grand: Double)

    /** Références proposées dans la liste déroulante (plus « Autre… » : dimensions saisies en centimètres). */
    val REFERENCES = listOf(
        Reference("Feuille A4 (21 × 29,7 cm)", 0.210, 0.297),
        Reference("Feuille A3 (29,7 × 42 cm)", 0.297, 0.420),
        Reference("Carreau 30 × 30 cm", 0.30, 0.30),
        Reference("Carreau 60 × 60 cm", 0.60, 0.60),
    )

    // =====================================================================================
    // Polygones dans un plan
    // =====================================================================================

    /** Surface d'un polygone (formule « du lacet ») ; l'ordre des points (horaire ou non) n'a pas d'importance. Moins de 3 points : 0. */
    fun aire(points: List<P2>): Double {
        if (points.size < 3) return 0.0
        var s = 0.0
        for (i in points.indices) {
            val a = points[i]; val b = points[(i + 1) % points.size]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2.0
    }

    fun distance(a: P2, b: P2): Double = hypot(b.x - a.x, b.y - a.y)

    /** Périmètre : somme des côtés ; `ferme` ajoute le côté qui revient au premier point. */
    fun perimetre(points: List<P2>, ferme: Boolean = true): Double {
        if (points.size < 2) return 0.0
        var s = 0.0
        for (i in 0 until points.size - 1) s += distance(points[i], points[i + 1])
        if (ferme && points.size > 2) s += distance(points.last(), points.first())
        return s
    }

    // =====================================================================================
    // Photo + objet de référence : transformation de perspective
    // =====================================================================================

    /**
     * Range 4 coins touchés dans n'importe quel ordre en « tour complet » autour de leur centre (sens des aiguilles d'une montre à l'écran,
     * en commençant par le coin le plus en haut à gauche). Sans cela, un rectangle touché « en croix » donnerait une transformation fausse.
     */
    fun ordonnerCoins(coins: List<P2>): List<P2> {
        require(coins.size == 4) { "Il faut exactement 4 coins." }
        val cx = coins.sumOf { it.x } / 4; val cy = coins.sumOf { it.y } / 4
        val tour = coins.sortedBy { atan2(it.y - cy, it.x - cx) }          // y vers le bas à l'écran : angle croissant = sens horaire
        val depart = tour.indices.minByOrNull { tour[it].x + tour[it].y }!!   // coin haut-gauche = plus petite somme x + y
        return List(4) { tour[(depart + it) % 4] }
    }

    /**
     * Le côté « coin 0 → coin 1 » est-il le GRAND côté de la référence ? On compare les longueurs à l'écran des deux paires de côtés opposés.
     * Sous une forte perspective ce choix peut être faux : l'écran propose alors « Tourner la référence » pour l'inverser.
     */
    fun premierCoteEstGrand(coinsOrdonnes: List<P2>): Boolean {
        val c = coinsOrdonnes
        val cotes01 = distance(c[0], c[1]) + distance(c[2], c[3])
        val cotes12 = distance(c[1], c[2]) + distance(c[3], c[0])
        return cotes01 >= cotes12
    }

    /**
     * Transformation de perspective (matrice 3 × 3, 9 nombres ligne par ligne) qui envoie les 4 coins de la photo sur un rectangle réel
     * de `largeur` × `hauteur` mètres : coin 0 → (0, 0), coin 1 → (largeur, 0), coin 2 → (largeur, hauteur), coin 3 → (0, hauteur).
     * Renvoie null si les coins sont alignés ou confondus (aucune solution).
     */
    fun homographie(coinsOrdonnes: List<P2>, largeur: Double, hauteur: Double): DoubleArray? {
        require(coinsOrdonnes.size == 4)
        val reel = listOf(P2(0.0, 0.0), P2(largeur, 0.0), P2(largeur, hauteur), P2(0.0, hauteur))
        // 8 équations à 8 inconnues (h0…h7, h8 = 1) : pour chaque coin, X = (h0 x + h1 y + h2) / (h6 x + h7 y + 1), idem pour Y
        val a = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val (x, y) = coinsOrdonnes[i]; val (gx, gy) = reel[i]
            a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -gx * x, -gx * y, gx)
            a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -gy * x, -gy * y, gy)
        }
        val h = resoudre(a) ?: return null
        return doubleArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0)
    }

    /** Résolution d'un système linéaire par le pivot de Gauss (matrice augmentée n × (n + 1)) ; null si le système n'a pas de solution unique. */
    private fun resoudre(m: Array<DoubleArray>): DoubleArray? {
        val n = m.size
        for (col in 0 until n) {
            val pivot = (col until n).maxByOrNull { abs(m[it][col]) }!!
            if (abs(m[pivot][col]) < 1e-12) return null
            val t = m[col]; m[col] = m[pivot]; m[pivot] = t
            for (l in 0 until n) {
                if (l == col) continue
                val f = m[l][col] / m[col][col]
                if (f != 0.0) for (k in col..n) m[l][k] -= f * m[col][k]
            }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }

    /** Convertit un point de la photo en mètres sur la surface ; null si le point est « à l'infini » (au-delà de la ligne d'horizon de la surface). */
    fun versSurface(h: DoubleArray, p: P2): P2? {
        val w = h[6] * p.x + h[7] * p.y + h[8]
        if (abs(w) < 1e-12) return null
        return P2((h[0] * p.x + h[1] * p.y + h[2]) / w, (h[3] * p.x + h[4] * p.y + h[5]) / w)
    }

    /** Résultat d'une mesure sur photo. */
    data class Resultat(val aire: Double, val perimetre: Double, val cotes: List<Double>)

    /**
     * MESURE SUR PHOTO : `coinsReference` = 4 coins de l'objet de référence (ordre quelconque), `contour` = points du contour à mesurer (dans l'ordre du tour),
     * tous en pixels de la photo. `tourner` inverse petit et grand côté de la référence. Renvoie null si la référence est inexploitable ou si un point est hors de portée.
     */
    fun mesurerSurPhoto(coinsReference: List<P2>, reference: Reference, contour: List<P2>, tourner: Boolean = false): Resultat? {
        if (coinsReference.size != 4 || contour.size < 2) return null
        val coins = ordonnerCoins(coinsReference)
        val grandDabord = premierCoteEstGrand(coins) != tourner
        val h = homographie(coins, if (grandDabord) reference.grand else reference.petit, if (grandDabord) reference.petit else reference.grand) ?: return null
        val reels = contour.map { versSurface(h, it) ?: return null }
        val cotes = reels.indices.map { distance(reels[it], reels[(it + 1) % reels.size]) }.let { if (reels.size == 2) it.take(1) else it }
        return Resultat(aire(reels), perimetre(reels), cotes)
    }

    // =====================================================================================
    // Réalité augmentée : points dans l'espace
    // =====================================================================================

    fun distance(a: P3, b: P3): Double = sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y) + (b.z - a.z) * (b.z - a.z))

    /**
     * Surface d'un polygone de l'espace (sol, mur, plan incliné) : moitié de la longueur de la somme des produits vectoriels des sommets consécutifs
     * (formule de Newell). Exacte pour un polygone plan, quelle que soit son orientation.
     */
    fun aireEspace(points: List<P3>): Double {
        if (points.size < 3) return 0.0
        var sx = 0.0; var sy = 0.0; var sz = 0.0
        for (i in points.indices) {
            val a = points[i]; val b = points[(i + 1) % points.size]
            sx += a.y * b.z - a.z * b.y
            sy += a.z * b.x - a.x * b.z
            sz += a.x * b.y - a.y * b.x
        }
        return sqrt(sx * sx + sy * sy + sz * sz) / 2.0
    }

    fun perimetreEspace(points: List<P3>, ferme: Boolean = true): Double {
        if (points.size < 2) return 0.0
        var s = 0.0
        for (i in 0 until points.size - 1) s += distance(points[i], points[i + 1])
        if (ferme && points.size > 2) s += distance(points.last(), points.first())
        return s
    }

    /**
     * Position À L'ÉCRAN (en pixels, origine en haut à gauche) d'un point de l'espace, à partir des deux matrices 4 × 4 de la caméra données par ARCore
     * (« vue » puis « projection », rangées colonne par colonne comme OpenGL). Renvoie null si le point est derrière la caméra.
     */
    fun versEcran(p: P3, vue: FloatArray, projection: FloatArray, largeur: Int, hauteur: Int): P2? {
        fun appliquer(m: FloatArray, v: DoubleArray) = DoubleArray(4) { l -> (0 until 4).sumOf { c -> m[c * 4 + l] * v[c] } }
        val clip = appliquer(projection, appliquer(vue, doubleArrayOf(p.x, p.y, p.z, 1.0)))
        if (clip[3] <= 1e-6) return null
        val nx = clip[0] / clip[3]; val ny = clip[1] / clip[3]
        return P2((nx + 1) / 2 * largeur, (1 - ny) / 2 * hauteur)
    }

    // =====================================================================================
    // Affichage
    // =====================================================================================

    /** « 12,35 m² » ; en dessous de 1 m², ajoute les cm² (« 0,062 m² (624 cm²) »). */
    fun texteAire(m2: Double): String =
        if (m2 < 1.0) String.format(Locale.FRANCE, "%.3f m² (%.0f cm²)", m2, m2 * 10000) else String.format(Locale.FRANCE, "%.2f m²", m2)

    /** « 3,42 m » ; en dessous de 1 m : « 42,5 cm ». */
    fun texteLongueur(m: Double): String =
        if (m < 1.0) String.format(Locale.FRANCE, "%.1f cm", m * 100) else String.format(Locale.FRANCE, "%.2f m", m)

    /** Lit une dimension saisie en centimètres (« 21 », « 29,7 ») et la rend en mètres ; null si ce n'est pas un nombre positif raisonnable (0,5 cm à 20 m). */
    fun lireCentimetres(texte: String): Double? {
        val v = texte.trim().replace(',', '.').toDoubleOrNull() ?: return null
        return if (v in 0.5..2000.0) v / 100.0 else null
    }

    /** Référence personnalisée à partir de deux dimensions en centimètres (dans n'importe quel ordre). */
    fun referenceLibre(cm1: String, cm2: String): Reference? {
        val a = lireCentimetres(cm1) ?: return null; val b = lireCentimetres(cm2) ?: return null
        return Reference("Autre", minOf(a, b), maxOf(a, b))
    }

    // =====================================================================================
    // Étalonnage et enregistrement (v1.51, constat du propriétaire du 21/11/2026 : mètre ruban à 140 cm, mesure en direct à 136 cm)
    // =====================================================================================

    /** Facteur d'étalonnage limité à ±15 % : au-delà, la mesure d'étalonnage est forcément fausse (mauvais points, mauvaise saisie). */
    const val FACTEUR_MIN = 0.85
    const val FACTEUR_MAX = 1.15

    /**
     * Facteur d'ÉTALONNAGE : vraie longueur ÷ longueur mesurée par le téléphone (ex. 140 cm réels pour 136 cm mesurés → 1,029).
     * Toutes les longueurs affichées sont ensuite multipliées par ce facteur, et les surfaces par son carré. Null si une longueur est invalide
     * ou si le facteur sort de ±15 %.
     */
    fun facteurEtalonnage(reelleM: Double, mesureeM: Double): Double? {
        if (reelleM <= 0.0 || mesureeM <= 0.0 || reelleM.isNaN() || mesureeM.isNaN()) return null
        val f = reelleM / mesureeM
        return if (f in FACTEUR_MIN..FACTEUR_MAX) f else null
    }

    fun longueurEtalonnee(m: Double, facteur: Double): Double = m * facteur
    fun aireEtalonnee(m2: Double, facteur: Double): Double = m2 * facteur * facteur

    /** « +2,9 % » ou « −3,1 % » : écart d'un facteur par rapport à 1. */
    fun textePourcentage(facteur: Double): String = String.format(Locale.FRANCE, "%+.1f %%", (facteur - 1.0) * 100).replace('-', '−')

    /** Côtés d'un contour de l'espace : de chaque point au suivant ; `ferme` ajoute le côté qui revient au premier point (3 points et plus). Longueurs BRUTES (mètres). */
    fun cotesEspace(points: List<P3>, ferme: Boolean): List<Double> {
        if (points.size < 2) return emptyList()
        val l = (0 until points.size - 1).map { distance(points[it], points[it + 1]) }
        return if (ferme && points.size > 2) l + distance(points.last(), points.first()) else l
    }

    /** Points de l'espace en texte « x,y,z;x,y,z » (mètres, 4 décimales) pour l'enregistrement ; format indépendant de la langue du téléphone. */
    fun pointsVersTexte(points: List<P3>): String = points.joinToString(";") { String.format(Locale.US, "%.4f,%.4f,%.4f", it.x, it.y, it.z) }

    /** Relit `pointsVersTexte` ; les morceaux illisibles sont ignorés. */
    fun texteVersPoints(texte: String): List<P3> = texte.split(";").mapNotNull { m ->
        val v = m.split(",").mapNotNull { it.trim().toDoubleOrNull() }
        if (v.size == 3) P3(v[0], v[1], v[2]) else null
    }

    /** Liste de longueurs (mètres) en texte « 0.8500;1.2000 » et retour. */
    fun nombresVersTexte(v: List<Double>): String = v.joinToString(";") { String.format(Locale.US, "%.4f", it) }
    fun texteVersNombres(texte: String): List<Double> = texte.split(";").mapNotNull { it.trim().toDoubleOrNull() }

    // =====================================================================================
    // Unités d'affichage et comparaison avec des dimensions annoncées (v1.54, propriétaire 21/11/2026 : téléviseur de 52 pouces)
    // =====================================================================================

    /** Unité d'AFFICHAGE : les mesures sont toujours gardées en mètres ; seule l'unité montrée change (conversion instantanée, y compris pour les mesures enregistrées). */
    enum class Unite(val libelle: String, val enMetres: Double) {
        AUTO("Automatique (m, cm)", 1.0), MM("Millimètres (mm)", 0.001), CM("Centimètres (cm)", 0.01), POUCES("Pouces (po)", 0.0254)
    }

    const val POUCE_M = 0.0254

    /** Unité retrouvée par son nom enregistré ; AUTO si inconnue. */
    fun uniteDepuisNom(nom: String?): Unite = Unite.values().firstOrNull { it.name == nom } ?: Unite.AUTO

    /** Longueur dans l'unité choisie : « 1 160 mm », « 116,0 cm », « 45,7 po » ; AUTO = affichage habituel (m / cm). */
    fun texteLongueurUnite(m: Double, u: Unite): String = when (u) {
        Unite.AUTO -> texteLongueur(m)
        Unite.MM -> String.format(Locale.FRANCE, if (m * 1000 < 100) "%.1f mm" else "%,.0f mm", m * 1000).replace('\u202f', ' ').replace('\u00a0', ' ')
        Unite.CM -> String.format(Locale.FRANCE, "%,.1f cm", m * 100).replace('\u202f', ' ').replace('\u00a0', ' ')
        Unite.POUCES -> String.format(Locale.FRANCE, "%.1f po", m / POUCE_M)
    }

    /** Surface dans l'unité choisie : « 0,75 m² » (AUTO), « 7 500,0 cm² », « 750 000 mm² », « 1 162,5 po² ». */
    fun texteAireUnite(m2: Double, u: Unite): String = when (u) {
        Unite.AUTO -> texteAire(m2)
        Unite.MM -> String.format(Locale.FRANCE, "%,.0f mm²", m2 * 1_000_000).replace('\u202f', ' ').replace('\u00a0', ' ')
        Unite.CM -> String.format(Locale.FRANCE, "%,.1f cm²", m2 * 10_000).replace('\u202f', ' ').replace('\u00a0', ' ')
        Unite.POUCES -> String.format(Locale.FRANCE, "%,.1f po²", m2 / (POUCE_M * POUCE_M)).replace('\u202f', ' ').replace('\u00a0', ' ')
    }

    /** Longueur saisie dans l'unité choisie (« 1160 » en mm, « 45,7 » en pouces) rendue en mètres ; en AUTO la saisie est en centimètres. Null si ce n'est pas un nombre positif. */
    fun lireLongueur(texte: String, u: Unite): Double? {
        val v = texte.trim().replace(',', '.').toDoubleOrNull() ?: return null
        if (v <= 0.0) return null
        return v * (if (u == Unite.AUTO) 0.01 else u.enMetres)
    }

    /** Rectangle déduit des 4 côtés d'un contour : grand côté, petit côté (moyennes des côtés opposés) et diagonale. Null si le contour n'a pas 4 côtés. */
    data class Rectangle(val grand: Double, val petit: Double, val diagonale: Double)
    fun rectangleDesCotes(cotes: List<Double>): Rectangle? {
        if (cotes.size != 4) return null
        val a = (cotes[0] + cotes[2]) / 2; val b = (cotes[1] + cotes[3]) / 2
        return Rectangle(maxOf(a, b), minOf(a, b), hypot(a, b))
    }

    /** Écart entre une dimension annoncée et la dimension mesurée : en mètres, en pourcentage de l'annoncée, et un verdict (≤ 1 % conforme, ≤ 2,5 % proche, sinon à vérifier). */
    data class Ecart(val ecartM: Double, val pourcent: Double, val verdict: String)
    fun comparer(attendueM: Double, mesureeM: Double): Ecart {
        val e = mesureeM - attendueM
        val p = if (attendueM > 0) e / attendueM * 100 else 0.0
        val v = when { abs(p) <= 1.0 -> "Conforme"; abs(p) <= 2.5 -> "Proche (dans la tolérance d'une mesure en réalité augmentée)"; else -> "À vérifier" }
        return Ecart(e, p, v)
    }

    /** « +3,2 mm (+0,3 %) » : écart signé, dans l'unité choisie. */
    fun texteEcart(e: Ecart, u: Unite): String {
        val unite = if (u == Unite.AUTO) Unite.MM else u
        val valeur = e.ecartM / unite.enMetres
        val sym = when (unite) { Unite.MM -> "mm"; Unite.CM -> "cm"; else -> "po" }
        return String.format(Locale.FRANCE, "%+.1f %s (%+.1f %%)", valeur, sym, e.pourcent).replace('-', '−')
    }

    /** Dimensions de l'ÉCRAN d'un téléviseur annoncé en pouces (diagonale), pour un format donné (16:9 par défaut) : (largeur, hauteur) en mètres. */
    fun ecranTelevision(diagonalePouces: Double, ratioL: Double = 16.0, ratioH: Double = 9.0): Pair<Double, Double> {
        val d = diagonalePouces * POUCE_M
        val h = hypot(ratioL, ratioH)
        return Pair(d * ratioL / h, d * ratioH / h)
    }

    /**
     * Une diagonale de téléviseur annoncée en pouces désigne l'ÉCRAN seul ; le contour mesuré (cadre compris) est un peu plus grand : de 0 à environ 3 pouces de plus est normal.
     * Rend une phrase de verdict ; `mesureePouces` = diagonale du contour mesuré.
     */
    fun coherenceDiagonale(mesureePouces: Double, annoncePouces: Double): String {
        val d = mesureePouces - annoncePouces
        return when {
            d < -1.0 -> "Plus petit qu'un téléviseur de ${String.format(Locale.FRANCE, "%.0f", annoncePouces)} pouces : vérifiez la mesure ou le modèle."
            d <= 3.0 -> "Cohérent avec un téléviseur de ${String.format(Locale.FRANCE, "%.0f", annoncePouces)} pouces (le cadre ajoute normalement 1 à 3 pouces à la diagonale de l'écran)."
            else -> "Plus grand qu'un téléviseur de ${String.format(Locale.FRANCE, "%.0f", annoncePouces)} pouces : il s'agit peut-être d'un modèle plus grand, ou d'un cadre très large."
        }
    }

    // =====================================================================================
    // OBJECTIF d'une mesure (v1.55, propriétaire 21/11/2026 : « mesurer, vérifier… ; à la fin, les écarts : mesures − objectifs »)
    // =====================================================================================

    /**
     * Ce que l'on veut faire de la mesure : la MESURER simplement, ou VÉRIFIER des valeurs attendues (dimensions, surface, diagonale), saisies dans l'unité choisie
     * et gardées en mètres. À la fin, chaque valeur attendue est comparée à ce qui a été mesuré.
     */
    data class Objectif(val verifier: Boolean = false, val unite: Unite = Unite.AUTO, val dimensionsM: List<Double> = emptyList(), val surfaceM2: Double? = null, val diagonaleM: Double? = null) {
        val aDesValeurs: Boolean get() = verifier && (dimensionsM.isNotEmpty() || surfaceM2 != null || diagonaleM != null)
    }

    /** « M » (mesurer) ou « V|UNITÉ|d1;d2|surface|diagonale » (vérifier), pour l'enregistrement avec la mesure. */
    fun objectifVersTexte(o: Objectif): String =
        if (!o.verifier) "M" else "V|${o.unite.name}|${nombresVersTexte(o.dimensionsM)}|${o.surfaceM2?.let { String.format(Locale.US, "%.6f", it) } ?: ""}|${o.diagonaleM?.let { String.format(Locale.US, "%.4f", it) } ?: ""}"

    fun texteVersObjectif(texte: String): Objectif {
        val p = texte.split("|")
        if (p.firstOrNull() != "V" || p.size < 5) return Objectif()
        return Objectif(true, uniteDepuisNom(p[1]), texteVersNombres(p[2]), p[3].toDoubleOrNull(), p[4].toDoubleOrNull())
    }

    /** Surface saisie dans l'unité choisie (m² en Automatique, mm², cm², po²) rendue en m² ; null si ce n'est pas un nombre positif. */
    fun lireSurface(texte: String, u: Unite): Double? {
        val v = texte.trim().replace(',', '.').toDoubleOrNull() ?: return null
        if (v <= 0.0) return null
        return if (u == Unite.AUTO) v else v * u.enMetres * u.enMetres
    }

    /** Une ligne du bilan : libellé, valeur attendue, valeur mesurée (mètres, ou m² si `surface`) et écart = mesuré − attendu. */
    data class LigneEcart(val libelle: String, val objectif: Double, val mesure: Double, val ecart: Ecart, val surface: Boolean)

    /**
     * Bilan « mesuré − objectif ». Les dimensions attendues (de la plus grande à la plus petite) sont associées chacune au côté mesuré le plus proche ; pour un contour de 4 points
     * on compare au GRAND et au PETIT côté du rectangle (moyennes des côtés opposés). La surface se compare à la surface mesurée, la diagonale à celle du rectangle de 4 points.
     */
    fun ecartsObjectif(o: Objectif, cotes: List<Double>, aireM2: Double?): List<LigneEcart> {
        if (!o.aDesValeurs) return emptyList()
        val lignes = ArrayList<LigneEcart>()
        val rect = rectangleDesCotes(cotes)
        val candidats: MutableList<Pair<String, Double>> = when {
            rect != null -> mutableListOf("Grand côté" to rect.grand, "Petit côté" to rect.petit)
            cotes.size == 1 -> mutableListOf("Longueur" to cotes[0])
            else -> cotes.mapIndexed { i, c -> "Côté ${i + 1}" to c }.toMutableList()
        }
        for (attendue in o.dimensionsM.sortedDescending()) {
            val proche = candidats.minByOrNull { abs(it.second - attendue) } ?: break
            candidats.remove(proche)
            lignes += LigneEcart(proche.first, attendue, proche.second, comparer(attendue, proche.second), false)
        }
        if (o.surfaceM2 != null && aireM2 != null) lignes += LigneEcart("Surface", o.surfaceM2, aireM2, comparer(o.surfaceM2, aireM2), true)
        if (o.diagonaleM != null && rect != null) lignes += LigneEcart("Diagonale", o.diagonaleM, rect.diagonale, comparer(o.diagonaleM, rect.diagonale), false)
        return lignes
    }

    /** Écart de surface signé dans l'unité de surface choisie : « +0,012 m² (+1,5 %) ». */
    fun texteEcartAire(e: Ecart, u: Unite): String {
        val (valeur, sym) = when (u) {
            Unite.AUTO -> e.ecartM to "m²"; Unite.MM -> e.ecartM * 1_000_000 to "mm²"; Unite.CM -> e.ecartM * 10_000 to "cm²"; Unite.POUCES -> e.ecartM / (POUCE_M * POUCE_M) to "po²"
        }
        return String.format(Locale.FRANCE, if (u == Unite.AUTO) "%+.3f %s (%+.1f %%)" else "%+.1f %s (%+.1f %%)", valeur, sym, e.pourcent).replace('-', '−')
    }

    /** Ligne du bilan, prête à afficher : « Grand côté : objectif 1 160 mm · mesuré 1 170 mm → +10,0 mm (+0,9 %) : Conforme ». */
    fun texteLigneEcart(l: LigneEcart, u: Unite): String =
        if (l.surface) "${l.libelle} : objectif ${texteAireUnite(l.objectif, u)} · mesuré ${texteAireUnite(l.mesure, u)} → ${texteEcartAire(l.ecart, u)} : ${l.ecart.verdict}"
        else "${l.libelle} : objectif ${texteLongueurUnite(l.objectif, u)} · mesuré ${texteLongueurUnite(l.mesure, u)} → ${texteEcart(l.ecart, u)} : ${l.ecart.verdict}"

    // =====================================================================================
    // Correction des points sur l'IMAGE FIGÉE (v1.56, propriétaire 21/11/2026 : « si la main tremblait, déplacer les points sur l'image gardée au 1er point »)
    // =====================================================================================

    /** Produit de deux matrices 4 × 4 (colonne par colonne, comme OpenGL) : a · b. */
    private fun produit4(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(16) { i -> val col = i / 4; val lig = i % 4; (0 until 4).sumOf { k -> a[k * 4 + lig] * b[col * 4 + k] } }

    /** Inverse d'une matrice 4 × 4 (colonne par colonne) par la méthode des cofacteurs ; null si elle n'est pas inversible. */
    fun inverse4(m: DoubleArray): DoubleArray? {
        val inv = DoubleArray(16)
        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10]
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10]
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9]
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9]
        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10]
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10]
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9]
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9]
        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6]
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6]
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5]
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5]
        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6]
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6]
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5]
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5]
        val det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12]
        if (abs(det) < 1e-12) return null
        return DoubleArray(16) { inv[it] / det }
    }

    /** Rayon de l'espace (origine, direction unitaire) qui passe par le pixel (px, py) d'une image prise avec ces matrices de caméra. Null si les matrices ne sont pas inversibles. */
    fun rayonSousPixel(px: Double, py: Double, largeur: Int, hauteur: Int, vue: FloatArray, projection: FloatArray): Pair<P3, P3>? {
        val inv = inverse4(produit4(DoubleArray(16) { projection[it].toDouble() }, DoubleArray(16) { vue[it].toDouble() })) ?: return null
        val nx = px / largeur * 2 - 1; val ny = 1 - py / hauteur * 2
        fun point(z: Double): P3? {
            val v = DoubleArray(4) { l -> inv[0 * 4 + l] * nx + inv[1 * 4 + l] * ny + inv[2 * 4 + l] * z + inv[3 * 4 + l] }
            return if (abs(v[3]) < 1e-12) null else P3(v[0] / v[3], v[1] / v[3], v[2] / v[3])
        }
        val a = point(-1.0) ?: return null; val b = point(1.0) ?: return null
        val d = P3(b.x - a.x, b.y - a.y, b.z - a.z); val n = sqrt(d.x * d.x + d.y * d.y + d.z * d.z)
        if (n < 1e-12) return null
        return Pair(a, P3(d.x / n, d.y / n, d.z / n))
    }

    /** Normale unitaire du plan d'un contour (formule de Newell) ; null si moins de 3 points ou contour dégénéré. */
    fun normaleContour(points: List<P3>): P3? {
        if (points.size < 3) return null
        var sx = 0.0; var sy = 0.0; var sz = 0.0
        for (i in points.indices) { val a = points[i]; val b = points[(i + 1) % points.size]
            sx += (a.y - b.y) * (a.z + b.z); sy += (a.z - b.z) * (a.x + b.x); sz += (a.x - b.x) * (a.y + b.y) }
        val n = sqrt(sx * sx + sy * sy + sz * sz)
        return if (n < 1e-9) null else P3(sx / n, sy / n, sz / n)
    }

    /**
     * Nouvelle position (mètres) d'un point déplacé sous le doigt sur l'image figée : le rayon du pixel touché est coupé par le plan du contour (3 points et plus) passant par le point d'origine ;
     * avec 2 points, ou si le rayon est presque parallèle au plan, le point reste à la même PROFONDEUR (plan perpendiculaire au regard). Null si le rayon ne coupe pas devant la caméra.
     */
    fun pointSousPixel(initial: P3, contour: List<P3>, px: Double, py: Double, largeur: Int, hauteur: Int, vue: FloatArray, projection: FloatArray): P3? {
        val (o, d) = rayonSousPixel(px, py, largeur, hauteur, vue, projection) ?: return null
        val nContour = normaleContour(contour)
        // Sans plan exploitable : plan perpendiculaire à l'AXE de la caméra (rayon du centre de l'image), c'est-à-dire profondeur constante
        val axe = rayonSousPixel(largeur / 2.0, hauteur / 2.0, largeur, hauteur, vue, projection)?.second ?: d
        val n = if (nContour != null && abs(nContour.x * d.x + nContour.y * d.y + nContour.z * d.z) > 0.1) nContour else axe
        val denom = n.x * d.x + n.y * d.y + n.z * d.z
        if (abs(denom) < 1e-9) return null
        val t = (n.x * (initial.x - o.x) + n.y * (initial.y - o.y) + n.z * (initial.z - o.z)) / denom
        if (t.isNaN() || t <= 0.0) return null
        return P3(o.x + d.x * t, o.y + d.y * t, o.z + d.z * t)
    }
}
