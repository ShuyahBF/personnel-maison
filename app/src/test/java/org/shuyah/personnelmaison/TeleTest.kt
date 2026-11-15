package org.shuyah.personnelmaison

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests des fonctions pures de « Ma télé » (sans téléphone) : code de la télé, libellés, durées, messages du serveur. */
class TeleTest {
    @Test fun codeDeLaTeleNormalise() {
        assertEquals("ABC123", Tele.codeNormalise("abc-123"))
        assertEquals("ABC123", Tele.codeNormalise(" a b c 1 2 3 "))
        assertTrue(Tele.codeValide("abc 123")); assertFalse(Tele.codeValide("ab")); assertFalse(Tele.codeValide("---"))
    }

    @Test fun limitesEtPrixEnClair() {
        assertEquals("1 appareil · 1 poste TV", Tele.libelleLimites(1, 1))
        assertEquals("3 appareils · 2 postes TV", Tele.libelleLimites(3, 2))
        assertEquals("2\u202F500 F CFA", Tele.libellePrix(2500))
    }

    @Test fun dureesEtTailles() {
        assertEquals("0:00", Tele.minutes(0.0)); assertEquals("2:05", Tele.minutes(125.4)); assertEquals("0:00", Tele.minutes(-5.0))
        assertEquals("12 Mo", Tele.taille(12L * 1024 * 1024)); assertEquals("2,5 Mo", Tele.taille((2.5 * 1024 * 1024).toLong()))
    }

    @Test fun avancementDeLaJauge() {
        assertEquals(0f, Tele.avancement(10.0, 0.0), 0.0001f); assertEquals(0.5f, Tele.avancement(30.0, 60.0), 0.0001f)
        assertEquals(1f, Tele.avancement(90.0, 60.0), 0.0001f); assertEquals(0f, Tele.avancement(-3.0, 60.0), 0.0001f)
    }

    @Test fun messagesDuServeur() {
        assertEquals("Limite atteinte : 1 téléviseur.", Tele.Reponse(409, "{\"detail\":\"Limite atteinte : 1 téléviseur.\"}").message())
        // refus d'abonnement : le message lisible (nom et prix de la formule) prime sur le code
        val r402 = Tele.Reponse(402, "{\"detail\":{\"code\":\"abonnement_requis\",\"message\":\"Réservée aux abonnés. Formule « Télé » : 1 000 FCFA.\",\"formule\":null}}")
        assertTrue(r402.abonnementRequis); assertEquals("Réservée aux abonnés. Formule « Télé » : 1 000 FCFA.", r402.message())
        assertEquals("seul le détenteur de l'appareil peut faire cela", Tele.Reponse(403, "").message())
        assertTrue(Tele.Reponse(200, "{}").ok); assertFalse(Tele.Reponse(409, "").ok)
    }
}
