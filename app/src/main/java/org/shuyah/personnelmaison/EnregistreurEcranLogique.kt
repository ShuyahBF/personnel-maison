package org.shuyah.personnelmaison

import java.io.File

/**
 * Logique PURE de l'enregistrement d'écran de débogage (testée sur l'ordinateur : EnregistreurEcranLogiqueTest). Aucune dépendance Android.
 * Pour un développeur WinDev : ce sont les petits calculs que l'on isole pour les tester sans téléphone.
 */
object EnregistreurEcranLogique {
    /** Durée maximale d'une vidéo (15 minutes, demande du propriétaire du 19/11/2026 ; 10 minutes avant) : au-delà l'enregistrement s'arrête tout seul (fichier léger, partage facile). */
    const val DUREE_MAX_MS = 900_000
    /** Les vidéos non envoyées sont effacées au bout de ce nombre de jours (elles contiennent ce qui était à l'écran). */
    const val JOURS_CONSERVATION = 7

    /**
     * Taille de la vidéo : le grand côté ne dépasse pas `max` pixels (fichier léger), les deux côtés sont PAIRS (exigence de l'encodeur H.264),
     * le rapport largeur / hauteur de l'écran est conservé. Rend (largeur, hauteur).
     */
    fun tailleVideo(largeurEcran: Int, hauteurEcran: Int, max: Int = 1280): Pair<Int, Int> {
        val l = largeurEcran.coerceAtLeast(2)
        val h = hauteurEcran.coerceAtLeast(2)
        val k = if (maxOf(l, h) > max) max.toDouble() / maxOf(l, h) else 1.0
        fun pair(v: Double) = (v.toInt() / 2 * 2).coerceAtLeast(2)
        return pair(l * k) to pair(h * k)
    }

    /** Nom du fichier : « <prefixe>-AAAAMMJJ-HHMMSS.mp4 » (date locale), sans caractère spécial. */
    fun nomFichier(prefixe: String, annee: Int, mois: Int, jour: Int, heure: Int, minute: Int, seconde: Int): String =
        "%s-%04d%02d%02d-%02d%02d%02d.mp4".format(prefixe.filter { it.isLetterOrDigit() }.ifEmpty { "ecran" }.lowercase(), annee, mois, jour, heure, minute, seconde)

    /** Fichiers à effacer : ceux dont la dernière modification a plus de `jours` jours. Seuls les « .mp4 » sont concernés. */
    fun aPurger(fichiers: List<File>, maintenantMs: Long, jours: Int = JOURS_CONSERVATION): List<File> =
        fichiers.filter { it.name.endsWith(".mp4") && maintenantMs - it.lastModified() > jours * 86_400_000L }

    /** Une vidéo plus courte que cette durée est inutilisable (l'encodeur n'a rien écrit) : elle est supprimée au lieu d'être partagée. */
    const val DUREE_MIN_MS = 800

    // ---- POSITION DU ROND (v1.46) : il se déplace au doigt, et sa place est retenue sous forme de FRACTION de l'écran
    // (0 = bord gauche / haut, 1 = bord droit / bas) pour rester au bon endroit quand l'écran tourne ou sur un autre téléphone.

    /**
     * Ramène la position (coin haut-gauche du rond, en pixels) à l'intérieur de l'écran : jamais hors de l'écran, jamais sous la barre d'état
     * (`margeHaut`) ni sur la zone des gestes du bas (`margeBas`).
     */
    fun positionBornee(x: Int, y: Int, largeurEcran: Int, hauteurEcran: Int, taille: Int, margeHaut: Int = 0, margeBas: Int = 0): Pair<Int, Int> {
        val xMax = (largeurEcran - taille).coerceAtLeast(0)
        val yMax = (hauteurEcran - taille - margeBas).coerceAtLeast(margeHaut)
        return x.coerceIn(0, xMax) to y.coerceIn(margeHaut, yMax)
    }

    /** Position en pixels -> fractions (0..1) de la zone de déplacement possible, pour la mémoriser. */
    fun versFractions(x: Int, y: Int, largeurEcran: Int, hauteurEcran: Int, taille: Int, margeHaut: Int = 0, margeBas: Int = 0): Pair<Float, Float> {
        val largeurLibre = (largeurEcran - taille).coerceAtLeast(1)
        val hauteurLibre = (hauteurEcran - taille - margeBas - margeHaut).coerceAtLeast(1)
        return (x.toFloat() / largeurLibre).coerceIn(0f, 1f) to ((y - margeHaut).toFloat() / hauteurLibre).coerceIn(0f, 1f)
    }

    /** Fractions mémorisées -> position en pixels pour l'écran actuel (opération inverse de versFractions). */
    fun depuisFractions(fx: Float, fy: Float, largeurEcran: Int, hauteurEcran: Int, taille: Int, margeHaut: Int = 0, margeBas: Int = 0): Pair<Int, Int> {
        val largeurLibre = (largeurEcran - taille).coerceAtLeast(0)
        val hauteurLibre = (hauteurEcran - taille - margeBas - margeHaut).coerceAtLeast(0)
        return positionBornee((fx.coerceIn(0f, 1f) * largeurLibre).toInt(), margeHaut + (fy.coerceIn(0f, 1f) * hauteurLibre).toInt(), largeurEcran, hauteurEcran, taille, margeHaut, margeBas)
    }
}
