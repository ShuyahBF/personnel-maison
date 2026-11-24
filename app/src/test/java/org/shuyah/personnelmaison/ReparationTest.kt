package org.shuyah.personnelmaison

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reconnaissance des erreurs DÉJÀ CONNUES (réparées et rejouées sans rien dire à l'utilisateur) ; une erreur inconnue n'est jamais « réparée ». */
class ReparationTest {
    @Test fun erreurDuSuiviEstReconnue() {
        assertEquals("suivi", Reparation.cause("SQLiteConstraintException: error code 19: UNIQUE constraint failed: suivi_fiches.t, suivi_fiches.id"))
        assertEquals("suivi", Reparation.cause("SQLiteException: no such table: suivi_suppressions"))
        assertEquals("suivi", Reparation.cause("no such table: journal_donnees"))
    }
    @Test fun baseOccupeeEstReconnue() {
        assertEquals("occupee", Reparation.cause("SQLiteDatabaseLockedException: database is locked (code 5)"))
    }
    @Test fun erreurInconnueNEstPasReparee() {
        assertNull(Reparation.cause("NullPointerException: x"))
        assertNull(Reparation.cause("UNIQUE constraint failed: pointages.personnelId, pointages.date"))
    }
}
