package org.shuyah.personnelmaison

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Tests de la logique de l'enregistrement d'écran de débogage (sans téléphone). */
class EnregistreurEcranLogiqueTest {
    @Test fun tailleReduiteEtPaire() {
        val (l, h) = EnregistreurEcranLogique.tailleVideo(1080, 2400)
        assertEquals(1280, h)                                        // le grand côté est ramené à 1280
        assertTrue(l % 2 == 0 && l in 570..580)                      // rapport conservé (1080 / 2400 × 1280 ≈ 576), largeur paire
    }

    @Test fun petitEcranNonAgrandi() {
        assertEquals(720 to 1280, EnregistreurEcranLogique.tailleVideo(720, 1280))
        assertEquals(480 to 800, EnregistreurEcranLogique.tailleVideo(481, 801))     // côtés impairs ramenés à des nombres pairs
    }

    @Test fun tailleJamaisNulle() {
        val (l, h) = EnregistreurEcranLogique.tailleVideo(0, -5)
        assertTrue(l >= 2 && h >= 2 && l % 2 == 0 && h % 2 == 0)
    }

    @Test fun nomDuFichier() {
        assertEquals("personneldemaison-20261117-194805.mp4", EnregistreurEcranLogique.nomFichier("Personnel de maison", 2026, 11, 17, 19, 48, 5))
        assertEquals("ecran-20260105-000000.mp4", EnregistreurEcranLogique.nomFichier("../..", 2026, 1, 5, 0, 0, 0))       // jamais de caractère de chemin dans le nom
    }

    @Test fun purgeDesVieillesVideosSeulement() {
        val maintenant = 1_800_000_000_000L
        fun f(nom: String, ageJours: Int) = File.createTempFile("enreg-test", nom).apply { setLastModified(maintenant - ageJours * 86_400_000L); deleteOnExit() }
        val ancienne = f(".mp4", 8); val recente = f(".mp4", 2); val autre = f(".txt", 30)
        val aSupprimer = EnregistreurEcranLogique.aPurger(listOf(ancienne, recente, autre), maintenant)
        assertEquals(listOf(ancienne), aSupprimer)
    }

    @Test fun positionJamaisHorsEcran() {
        val (x, y) = EnregistreurEcranLogique.positionBornee(5000, -300, 1080, 2400, 138, margeHaut = 60, margeBas = 90)
        assertEquals(1080 - 138, x)                                  // collé au bord droit
        assertEquals(60, y)                                          // jamais sous la barre d'état
        assertEquals(2400 - 138 - 90, EnregistreurEcranLogique.positionBornee(0, 9999, 1080, 2400, 138, 60, 90).second)   // jamais sur la zone des gestes
    }

    @Test fun positionRetrouveeApresRotation() {
        // Rond placé au milieu à droite d'un écran portrait, puis écran tourné : il reste au milieu à droite.
        val (fx, fy) = EnregistreurEcranLogique.versFractions(1080 - 138, 1100, 1080, 2400, 138, 60, 90)
        val (x, y) = EnregistreurEcranLogique.depuisFractions(fx, fy, 2400, 1080, 138, 60, 90)
        assertEquals(2400 - 138, x)
        assertTrue(y in 60..(1080 - 138 - 90))
    }

    @Test fun fractionsAbsurdesRamenees() {
        val (x, y) = EnregistreurEcranLogique.depuisFractions(7f, -2f, 720, 1280, 100, 40, 50)
        assertEquals(620, x); assertEquals(40, y)
    }

    @Test fun petitEcranSansPlace() {
        val (x, y) = EnregistreurEcranLogique.positionBornee(10, 10, 50, 50, 100, 20, 20)       // écran plus petit que le rond : jamais d'erreur
        assertTrue(x == 0 && y == 20)
    }
}
