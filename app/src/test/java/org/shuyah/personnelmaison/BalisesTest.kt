package org.shuyah.personnelmaison

import org.junit.Assert.*
import org.junit.Test

/** Tests des balises Bluetooth : lecture des annonces iBeacon, Eddystone, Estimote, distance, lissage, suivi de présence. */
class BalisesTest {
    private fun ad(type: Int, vararg d: Int) = byteArrayOf((d.size + 1).toByte(), type.toByte()) + ByteArray(d.size) { d[it].toByte() }
    private fun adOctets(type: Int, d: ByteArray) = byteArrayOf((d.size + 1).toByte(), type.toByte()) + d
    private val flags = ad(0x01, 0x06)

    @Test fun iBeacon() {
        val uuid = ByteArray(16) { (it + 1).toByte() }
        val donnees = byteArrayOf(0x4C, 0x00, 0x02, 0x15) + uuid + byteArrayOf(0x00, 0x07, 0x01, 0x02, 0xC5.toByte())      // major 7, minor 258, puissance −59 dBm
        val a = Balises.analyserAnnonce(flags + adOctets(0xFF, donnees))
        assertEquals("iBeacon", a.format); assertEquals(-59, a.puissance1m)
        assertEquals("ib:01020304-0506-0708-090A-0B0C0D0E0F10:7:258", a.identifiant)
        assertTrue(a.resume.contains("major 7") && a.resume.contains("minor 258"))
    }

    @Test fun eddystoneUid() {
        val p = byteArrayOf(0xAA.toByte(), 0xFE.toByte(), 0x00, (-20).toByte()) + ByteArray(10) { 0x11 } + ByteArray(6) { 0x22 } + byteArrayOf(0, 0)
        val a = Balises.analyserAnnonce(flags + adOctets(0x16, p))
        assertEquals("Eddystone-UID", a.format); assertEquals(-61, a.puissance1m)                                  // −20 dBm à 0 m → −61 dBm à 1 m
        assertEquals("ed:" + "11".repeat(10) + ":" + "22".repeat(6), a.identifiant)
    }

    @Test fun eddystoneUrl() {
        val p = byteArrayOf(0xAA.toByte(), 0xFE.toByte(), 0x10, (-20).toByte(), 0x03) + "exemple".toByteArray() + byteArrayOf(0x00)   // 0x03 = https://, 0x00 = .com/
        val a = Balises.analyserAnnonce(adOctets(0x16, p))
        assertEquals("Eddystone-URL", a.format); assertEquals("https://exemple.com/", a.resume)
    }

    @Test fun estimoteEtNomEtTronque() {
        val p = byteArrayOf(0x9A.toByte(), 0xFE.toByte(), 0x22) + byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8) + byteArrayOf(0x00, 0x10)
        val a = Balises.analyserAnnonce(adOctets(0x16, p) + adOctets(0x09, "Estimote".toByteArray()))
        assertEquals("Estimote", a.format); assertEquals("es:0102030405060708", a.identifiant); assertEquals("Estimote", a.nom)
        assertTrue(a.details.any { it.contains("0102030405060708") })
        // Annonce tronquée ou vide : jamais d'exception
        assertEquals("Autre", Balises.analyserAnnonce(byteArrayOf(0x1A, 0xFF.toByte(), 0x4C)).format)
        assertEquals("Autre", Balises.analyserAnnonce(ByteArray(0)).format)
    }

    @Test fun estimoteParFabricant() {
        val a = Balises.analyserAnnonce(adOctets(0xFF, byteArrayOf(0x5D, 0x01, 0x01, 0x02, 0x03)))
        assertEquals("Estimote", a.format); assertNull(a.identifiant)                                              // sans identifiant lisible : l'adresse sert de clé
    }

    @Test fun distanceEtLibelle() {
        assertEquals(1.0, Balises.distanceM(-59.0, -59)!!, 0.001)
        assertEquals(10.0, Balises.distanceM(-79.0, -59)!!, 0.001)
        assertNull(Balises.distanceM(-70.0, null))
        assertEquals("< 1 m", Balises.libelleDistance(0.4)); assertEquals("≈ 2,5 m", Balises.libelleDistance(2.5)); assertEquals("> 20 m", Balises.libelleDistance(35.0)); assertEquals("distance inconnue", Balises.libelleDistance(null))
    }

    @Test fun lissageDuSignal() {
        val l = Balises.Lissage(3)
        assertEquals(-60.0, l.ajouter(-60), 0.001); assertEquals(-65.0, l.ajouter(-70), 0.001)
        l.ajouter(-80); assertEquals(-76.67, l.ajouter(-80), 0.01)                                                // la 1re mesure (−60) est sortie de la fenêtre de 3
    }

    @Test fun suiviDePresenceArriveeEtDepart() {
        val s = Balises.Suivi(60_000)
        s.vue("awa", 1_000)
        assertEquals(listOf(Balises.Evenement("awa", true, 1_000)), s.mettreAJour(2_000)); assertTrue(s.estPresente("awa"))
        assertTrue(s.mettreAJour(30_000).isEmpty())                                                               // toujours présente : rien de nouveau
        s.vue("awa", 50_000); assertTrue(s.mettreAJour(100_000).isEmpty())                                        // revue à 50 s : présente jusqu'à 110 s
        val depart = s.mettreAJour(120_000)
        assertEquals(1, depart.size); assertFalse(depart[0].arrivee); assertFalse(s.estPresente("awa"))
        s.vue("awa", 130_000); assertTrue(s.mettreAJour(131_000)[0].arrivee)                                      // de retour
    }
}
