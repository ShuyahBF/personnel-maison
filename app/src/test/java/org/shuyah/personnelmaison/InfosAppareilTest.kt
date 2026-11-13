package org.shuyah.personnelmaison

import org.junit.Assert.*
import org.junit.Test

/** Tests des libellés de l'appareil : version d'Android, batterie. */
class InfosAppareilTest {
    @Test fun versionAndroidEtModele() {
        assertEquals("Android 14 (API 34) · Xiaomi 23021RAA2Y", InfosAppareil.libelleAndroid("14", 34, "Xiaomi", "23021RAA2Y"))
        assertEquals("Android 13 (API 33) · Samsung SM-A346B", InfosAppareil.libelleAndroid("13", 33, "samsung", "SM-A346B"))
        assertEquals("Android 12 (API 31) · Google Pixel 6", InfosAppareil.libelleAndroid("12", 31, "Google", "Google Pixel 6"))        // le modèle contient déjà la marque : pas de doublon
    }

    @Test fun batterie() {
        assertEquals("🔋 57 %", InfosAppareil.libelleBatterie(57, false))
        assertEquals("⚡ 100 %", InfosAppareil.libelleBatterie(100, true))
        assertEquals("🔋 ?", InfosAppareil.libelleBatterie(null, false))
        assertTrue(InfosAppareil.batterieFaible(15, false)); assertFalse(InfosAppareil.batterieFaible(15, true)); assertFalse(InfosAppareil.batterieFaible(40, false)); assertFalse(InfosAppareil.batterieFaible(null, false))
    }

    @Test fun arriveeDeMessagesDansLeChat() {
        val recus = listOf(10L to false, 11L to true, 12L to false)                                     // (numéro, envoyé par moi ?)
        assertEquals(0, ChatLogique.arrivees(null, recus))                                              // première lecture : référence posée, aucun bruit
        assertEquals(2, ChatLogique.arrivees(9L, recus)); assertEquals(1, ChatLogique.arrivees(10L, recus)); assertEquals(0, ChatLogique.arrivees(12L, recus))
        assertEquals(0, ChatLogique.arrivees(10L, listOf(11L to true)))                                 // mes propres messages ne sonnent jamais
        assertEquals(12L, ChatLogique.plusGrand(null, recus)); assertEquals(20L, ChatLogique.plusGrand(20L, recus)); assertNull(ChatLogique.plusGrand(null, emptyList()))
    }
}
