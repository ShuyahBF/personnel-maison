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
}
