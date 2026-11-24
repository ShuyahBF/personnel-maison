package org.shuyah.personnelmaison

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Suivi des modifications (1.64) : une série de 3 déclencheurs et un rattrapage par table ; le SQL est aussi exporté pour être rejoué dans un vrai SQLite (voir CLAUDE.md). */
class SuiviModifsTest {
    @Test fun troisDeclencheursEtUnRattrapageParTable() {
        val sql = SuiviModifs.instructions()
        val n = SuiviModifs.TABLES.size
        assertEquals(19, n)
        assertEquals(n, sql.count { it.startsWith("CREATE TRIGGER IF NOT EXISTS `suivi_ai_") })
        assertEquals(n, sql.count { it.startsWith("CREATE TRIGGER IF NOT EXISTS `suivi_au_") })
        assertEquals(n, sql.count { it.startsWith("CREATE TRIGGER IF NOT EXISTS `suivi_ad_") })
        assertEquals(n, sql.count { it.startsWith("INSERT OR IGNORE INTO `suivi_fiches`") })
        assertTrue(SuiviModifs.TABLES.map { it.first }.toSet().size == n)
    }
    @Test fun exportPourVerificationSqlite() {
        File("build/suivi_modifs.sql").also { it.parentFile?.mkdirs() }.writeText(SuiviModifs.instructions().joinToString(";\n") + ";\n")
    }
}
