package org.shuyah.personnelmaison

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Journal des modifications (1.74) : 3 déclencheurs par table de données ; le SQL est exporté pour être rejoué dans un vrai SQLite. */
class JournalDonneesTest {
    @Test fun troisDeclencheursParTable() {
        val sql = JournalDonnees.instructions()
        val n = SuiviModifs.TABLES.size
        for (p in listOf("jd_ai_", "jd_au_", "jd_ad_")) assertEquals(n, sql.count { it.startsWith("CREATE TRIGGER IF NOT EXISTS `$p") })
    }
    @Test fun exportPourVerificationSqlite() {
        File("build/journal_donnees.sql").also { it.parentFile?.mkdirs() }.writeText(JournalDonnees.instructions().joinToString(";\n") + ";\n")
    }
}
