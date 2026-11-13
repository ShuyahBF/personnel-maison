package org.shuyah.personnelmaison

/**
 * IMAGES DE LA CAMÉRA PARTAGÉE — logique pure (sans Android), testée par FamilleImageTest.
 * La caméra fournit des images « YUV » (trois plans : luminosité Y, puis couleurs U et V, avec des pas de lecture propres à chaque téléphone) ;
 * `versNv21` les range dans le format NV21 que sait compresser Android, et `tailleCible` réduit la photo avant l'envoi (économie de données mobiles).
 */
object FamilleImage {
    /** Largeur maximale de la photo envoyée (pixels) ; la hauteur suit. */
    const val LARGEUR_MAX = 640
    /** Intervalles proposés entre deux photos (secondes) : liste finie → liste déroulante. */
    val INTERVALLES_S = listOf(10, 15, 30, 60)
    const val INTERVALLE_DEFAUT_S = 15

    /**
     * Convertit une image YUV_420_888 (plans Y, U, V avec leur « row stride » et « pixel stride ») en NV21 (Y puis V,U entrelacés).
     * `pasLigneY` = octets d'une ligne du plan Y ; `pasPixelUV` = 1 (plans séparés) ou 2 (plans entrelacés, cas fréquent).
     */
    fun versNv21(largeur: Int, hauteur: Int, y: ByteArray, pasLigneY: Int, u: ByteArray, v: ByteArray, pasLigneUV: Int, pasPixelUV: Int): ByteArray {
        val sortie = ByteArray(largeur * hauteur * 3 / 2)
        for (ligne in 0 until hauteur) for (col in 0 until largeur) sortie[ligne * largeur + col] = y[ligne * pasLigneY + col]
        var k = largeur * hauteur
        for (ligne in 0 until hauteur / 2) for (col in 0 until largeur / 2) {
            val i = ligne * pasLigneUV + col * pasPixelUV
            sortie[k++] = v[i]; sortie[k++] = u[i]
        }
        return sortie
    }

    /** Taille de la photo réduite (largeur ≤ `max`, proportions conservées, jamais agrandie) après rotation éventuelle de 90° ou 270°. */
    fun tailleCible(largeur: Int, hauteur: Int, rotation: Int, max: Int = LARGEUR_MAX): Pair<Int, Int> {
        val (l, h) = if (rotation % 180 == 90) hauteur to largeur else largeur to hauteur
        if (l <= max) return l to h
        return max to (h.toLong() * max / l).toInt().coerceAtLeast(1)
    }
}
