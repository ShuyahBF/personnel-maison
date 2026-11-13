package org.shuyah.personnelmaison

import org.junit.Assert.*
import org.junit.Test

/** Tests du récepteur de badges : filtrage des badges, corps JSON du contrat v1, réglages (logique pure). */
class PresenceRecepteurTest {
    private fun vue(cle: String, format: String, rssi: Double, vu: Long) = ScanBalises.Vue(cle, "AA:BB").also {
        it.annonce = Balises.Annonce(format, cle, "", -59, null, emptyList()); it.rssiLisse = rssi; it.derniereVue = vu
    }

    @Test fun seulsLesBadgesRecentsEtReconnusSontEnvoyes() {
        val vues = listOf(
            vue("ib:aaa:1:1", "iBeacon", -62.4, 1000), vue("mac:AA:BB:CC:DD:EE:FF", "Estimote", -70.0, 1000), vue("ed:01:02", "Eddystone-UID", -55.0, 1000),
            vue("mac:11:22:33:44:55:66", "Autre", -40.0, 1000),            // un téléphone ou des écouteurs : jamais envoyé
            vue("ib:ancien:1:1", "iBeacon", -60.0, 100),                     // pas entendu depuis le dernier tour
            vue("ib:mauvais id", "iBeacon", -60.0, 1000))                    // identifiant invalide pour le contrat
        val d = PresenceRecepteur.detections(vues, 500)
        assertEquals(listOf("ib:aaa:1:1", "mac:AA:BB:CC:DD:EE:FF", "ed:01:02"), d.map { it.badge })
        assertEquals(-62, d[0].rssi)
    }

    @Test fun plafondEtSignalBorne() {
        val beaucoup = (1..300).map { vue("ib:b:$it:1", "iBeacon", 5.0, 1000) }
        val d = PresenceRecepteur.detections(beaucoup, 0)
        assertEquals(PresenceRecepteur.MAX_PAR_ENVOI, d.size)
        assertTrue(d.all { it.rssi in -127..0 })                              // un signal aberrant est ramené dans la plage du contrat
    }

    @Test fun corpsJsonDuContratV1() {
        assertEquals("{\"detections\":[]}", PresenceRecepteur.corpsJson(emptyList()))
        assertEquals("{\"detections\":[{\"badge\":\"ib:a:1:2\",\"rssi\":-67},{\"badge\":\"ed:x\",\"rssi\":-50}]}",
            PresenceRecepteur.corpsJson(listOf(PresenceRecepteur.Detection("ib:a:1:2", -67), PresenceRecepteur.Detection("ed:x", -50))))
    }

    @Test fun reglagesEtMessages() {
        assertEquals("https://api.sawalismartsystems.com", PresenceRecepteur.adresse("  "))
        assertEquals("https://exemple.bf", PresenceRecepteur.adresse(" https://exemple.bf/ "))
        assertTrue(PresenceRecepteur.configure("abc123:def456", "cle-assez-longue")); assertFalse(PresenceRecepteur.configure("sans-deux-points", "cle-assez-longue")); assertFalse(PresenceRecepteur.configure("a:b", "court"))
        assertTrue(PresenceRecepteur.messageHttp(401).contains("refusés")); assertEquals("envoi réussi", PresenceRecepteur.messageHttp(200)); assertTrue(PresenceRecepteur.messageHttp(503).contains("indisponible"))
    }
}
