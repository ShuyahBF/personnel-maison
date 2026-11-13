package org.shuyah.personnelmaison

import org.junit.Assert.*
import org.junit.Test

/** Garde-fou de performance (version 1.23) : l'accès aux préférences chiffrées ne doit plus être recréé à chaque lecture. */
class PrefsChiffreesTest {
    @Test fun lAccesChiffreEstGardeEnMemoire() {
        val source = java.io.File("src/main/java/org/shuyah/personnelmaison/ClientFamille.kt").readText()
        // une seule création, protégée par le cache `prefsEnMemoire` ; aucune création directe ailleurs que dans le cache
        assertEquals(1, Regex("EncryptedSharedPreferences\\.create").findAll(source).count())
        assertTrue(source.contains("prefsEnMemoire ?: synchronized"))
    }
}
