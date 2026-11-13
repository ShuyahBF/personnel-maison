package org.shuyah.personnelmaison

import org.junit.Assert.*
import org.junit.Test

/** Tests de la position partagée (périmètre du domicile, envoi, texte) et des messages NFC. */
class FamillePositionNfcTest {
    private val domicile = FamillePosition.Lieu("Domicile", 12.371400, -1.519700, 100)
    private val ecole = FamillePosition.Lieu("École", 12.380000, -1.520000, 150)

    @Test fun distanceEnMetres() {
        assertEquals(0.0, FamillePosition.distanceM(12.3714, -1.5197, 12.3714, -1.5197), 0.01)
        assertEquals(111.0, FamillePosition.distanceM(12.3714, -1.5197, 12.3724, -1.5197), 2.0)        // 0,001° de latitude ≈ 111 m
    }

    @Test fun etatDansOuHorsDuPerimetre() {
        assertFalse(FamillePosition.etat(domicile, listOf(ecole), 12.37145, -1.51970).dehors)                 // à la maison
        val aLEcole = FamillePosition.etat(domicile, listOf(ecole), 12.38005, -1.52000)
        assertTrue(aLEcole.dehors); assertEquals("École", aLEcole.lieu)
        val ailleurs = FamillePosition.etat(domicile, listOf(ecole), 12.40, -1.50)
        assertTrue(ailleurs.dehors); assertNull(ailleurs.lieu)
        assertTrue(FamillePosition.etat(null, emptyList(), 12.0, -1.0).dehors)                                // sans domicile : toujours « dehors »
    }

    @Test fun envoiSeulementHorsDuDomicileEtUneFoisAuRetour() {
        val maison = FamillePosition.Etat(false, "Domicile"); val dehors = FamillePosition.Etat(true, null)
        val m = FamillePosition.MODE_HORS_DOMICILE
        assertFalse(FamillePosition.doitEnvoyer(m, domicile, null, maison))          // à la maison depuis le début : rien
        assertFalse(FamillePosition.doitEnvoyer(m, domicile, false, maison))         // toujours à la maison : rien
        assertTrue(FamillePosition.doitEnvoyer(m, domicile, false, dehors))          // vient de sortir : envoi
        assertTrue(FamillePosition.doitEnvoyer(m, domicile, true, dehors))           // reste dehors : envoi chaque minute
        assertTrue(FamillePosition.doitEnvoyer(m, domicile, true, maison))           // vient de rentrer : une dernière position
        assertTrue(FamillePosition.doitEnvoyer(FamillePosition.MODE_TOUJOURS, domicile, false, maison))
        assertTrue(FamillePosition.doitEnvoyer(m, null, null, maison))               // sans domicile défini : envoi
    }

    @Test fun texteDeLaPositionAllerRetour() {
        val t = FamillePosition.versTexte(12.3714, -1.5197, 18, 1_794_000_000_000L, FamillePosition.Etat(true, "École \"A\""))
        val p = FamillePosition.depuisTexte(t)!!
        assertEquals(12.3714, p.lat, 1e-6); assertEquals(-1.5197, p.lon, 1e-6); assertEquals(18, p.precisionM); assertEquals(1_794_000_000_000L, p.ms)
        assertTrue(p.dehors); assertEquals("École 'A'", p.lieu)
        assertNull(FamillePosition.depuisTexte("n'importe quoi"))
        assertEquals("À : École 'A'", FamillePosition.libelleEtat(p)); assertEquals("Hors du périmètre du domicile", FamillePosition.libelleEtat(p.copy(lieu = null)))
        assertEquals("À la maison", FamillePosition.libelleEtat(p.copy(dehors = false, lieu = "Domicile")))
    }

    @Test fun libellesEtLieux() {
        assertEquals("à l'instant", FamillePosition.libelleAge(1000, 30_000)); assertEquals("il y a 5 min", FamillePosition.libelleAge(0, 5 * 60_000L))
        assertEquals("il y a 2 h", FamillePosition.libelleAge(0, 2 * 3_600_000L)); assertEquals("il y a 3 j", FamillePosition.libelleAge(0, 3 * 86_400_000L))
        assertEquals(listOf(domicile, ecole), FamillePosition.lieuxDepuisTexte(FamillePosition.lieuxVersTexte(listOf(domicile, ecole))))
        assertTrue(FamillePosition.lieuxDepuisTexte("n'importe|quoi").isEmpty())
    }

    // ---- NFC ----
    @Test fun echangeNfcInvitationEtEmpreintes() {
        val code = "ABCD-EFGH"; val empA = "a".repeat(64); val empB = "b".repeat(64)
        assertTrue(FamilleNfc.estSelect(FamilleNfc.commandeSelect()))
        assertFalse(FamilleNfc.estSelect(byteArrayOf(0, 0xA4.toByte(), 4, 0, 3, 1, 2, 3)))
        val lu = FamilleNfc.lireReponseInvitation(FamilleNfc.reponseInvitation(code, empA))!!
        assertEquals(code to empA, lu)
        assertEquals(empB, FamilleNfc.lireEmpreinte(FamilleNfc.commandeEmpreinte(empB)))
        assertNull(FamilleNfc.lireReponseInvitation(FamilleNfc.statutInconnu()))                       // pas d'invitation en cours
        assertNull(FamilleNfc.lireReponseInvitation("pas|un code".toByteArray() + FamilleNfc.statutOk()))
        assertNull(FamilleNfc.lireEmpreinte(FamilleNfc.commandeEmpreinte("Z".repeat(64))))             // pas de l'hexadécimal
        assertNull(FamilleNfc.lireEmpreinte(FamilleNfc.commandeSelect()))
    }

    @Test fun empreinteDeCleStableEtDistincte() {
        val a = FamilleCrypto.genererPaire(); val b = FamilleCrypto.genererPaire()
        assertEquals(64, FamilleCrypto.empreinte(a.first).length); assertEquals(FamilleCrypto.empreinte(a.first), FamilleCrypto.empreinte(a.first))
        assertNotEquals(FamilleCrypto.empreinte(a.first), FamilleCrypto.empreinte(b.first))
    }
}
