package org.shuyah.personnelmaison

import org.junit.Assert.*
import org.junit.Test

/** Tests de la cryptographie de « Ma famille » : clé commune, chiffrement de bout en bout, numéro de sécurité. */
class FamilleCryptoTest {
    private val awa = FamilleCrypto.genererPaire()      // (publique, privée)
    private val issa = FamilleCrypto.genererPaire()

    @Test fun lesDeuxPersonnesObtiennentLaMemeCleSansL_echanger() {
        val kAwa = FamilleCrypto.cleCommune(awa.second, issa.first, awa.first)
        val kIssa = FamilleCrypto.cleCommune(issa.second, awa.first, issa.first)
        assertEquals(32, kAwa.size); assertArrayEquals(kAwa, kIssa)
    }

    @Test fun chiffrementDeBoutEnBout() {
        val k = FamilleCrypto.cleCommune(awa.second, issa.first, awa.first)
        val (nonce, chiffre) = FamilleCrypto.chiffrer(k, "Médicament pris à 8 h ✔", "lien1")
        assertFalse(chiffre.contains("Médicament"))
        val kIssa = FamilleCrypto.cleCommune(issa.second, awa.first, issa.first)
        assertEquals("Médicament pris à 8 h ✔", FamilleCrypto.dechiffrer(kIssa, nonce, chiffre, "lien1"))
    }

    @Test fun uneMauvaiseCleUnMessageAltereOuUnAutreLienNeSeDechiffrentPas() {
        val k = FamilleCrypto.cleCommune(awa.second, issa.first, awa.first)
        val (nonce, chiffre) = FamilleCrypto.chiffrer(k, "secret", "lien1")
        val intrus = FamilleCrypto.genererPaire()
        assertNull(FamilleCrypto.dechiffrer(FamilleCrypto.cleCommune(intrus.second, awa.first, intrus.first), nonce, chiffre, "lien1"))   // un tiers
        assertNull(FamilleCrypto.dechiffrer(k, nonce, chiffre, "lien2"))                                                                   // rejoué sur un autre lien
        assertNull(FamilleCrypto.dechiffrer(k, nonce, chiffre.dropLast(4) + "AAAA", "lien1"))                                              // altéré
    }

    @Test fun deuxMessagesIdentiquesDonnentDesTextesChiffresDifferents() {
        val k = FamilleCrypto.cleCommune(awa.second, issa.first, awa.first)
        assertNotEquals(FamilleCrypto.chiffrer(k, "ok", "l").second, FamilleCrypto.chiffrer(k, "ok", "l").second)
    }

    @Test fun numeroDeSecuriteIdentiqueDesDeuxCotesEtChangeSiUneCleChange() {
        val n1 = FamilleCrypto.numeroSecurite(awa.first, issa.first)
        assertEquals(n1, FamilleCrypto.numeroSecurite(issa.first, awa.first))
        assertTrue(Regex("(\\d{5} ){5}\\d{5}").matches(n1))
        val intrus = FamilleCrypto.genererPaire()
        assertNotEquals(n1, FamilleCrypto.numeroSecurite(awa.first, intrus.first))      // un intermédiaire qui remplace la clé est détecté
    }
}
