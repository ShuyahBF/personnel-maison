package org.shuyah.personnelmaison

import org.junit.Assert.*
import org.junit.Test

/** Tests des images de la caméra partagée : conversion YUV → NV21 et taille de la photo envoyée. */
class FamilleImageTest {
    @Test fun conversionNv21PlansEntrelaces() {
        // Image 4 × 2 : plan Y avec pas de ligne 6 (2 octets de remplissage), plans U et V entrelacés (pas de pixel 2, pas de ligne 4)
        val y = byteArrayOf(1, 2, 3, 4, 99, 99, 5, 6, 7, 8, 99, 99)
        val u = byteArrayOf(10, 0, 11, 0); val v = byteArrayOf(20, 0, 21, 0)
        val nv21 = FamilleImage.versNv21(4, 2, y, 6, u, v, 4, 2)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 20, 10, 21, 11), nv21)         // Y puis V,U entrelacés
    }

    @Test fun conversionNv21PlansSepares() {
        val nv21 = FamilleImage.versNv21(2, 2, byteArrayOf(1, 2, 3, 4), 2, byteArrayOf(10), byteArrayOf(20), 1, 1)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 20, 10), nv21)
    }

    @Test fun tailleDeLaPhotoEnvoyee() {
        assertEquals(640 to 853, FamilleImage.tailleCible(1280, 960, 90))        // paysage 1280×960 tourné de 90° → portrait 960×1280 → réduit à 640 de large
        assertEquals(640 to 360, FamilleImage.tailleCible(1280, 720, 0))
        assertEquals(320 to 240, FamilleImage.tailleCible(320, 240, 0))          // jamais agrandie
        assertEquals(240 to 320, FamilleImage.tailleCible(320, 240, 270))
    }

    @Test fun intervallesProposes() {
        assertTrue(FamilleImage.INTERVALLE_DEFAUT_S in FamilleImage.INTERVALLES_S)
        assertEquals(FamilleImage.INTERVALLES_S.sorted(), FamilleImage.INTERVALLES_S)
    }
}
