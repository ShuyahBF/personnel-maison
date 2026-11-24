package org.shuyah.personnelmaison

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * JOURNAL DE TOUTE MODIFICATION DE DONNÉES (1.74, demande du propriétaire : « met en place ce journal pour toute modification de données dans la base »).
 *
 * BUT : retrouver l'origine d'un plantage ou d'une donnée étrange (écran, synchronisation, fusion, restauration…). La base elle-même écrit une ligne à CHAQUE ajout, modification
 * ou suppression d'une fiche de nos 19 tables (déclencheurs SQLite, comme les événements « après ajout / modification / suppression » d'un fichier HFSQL) :
 *   quand (ms) · table · opération (ajout / modif / suppr) · numéro de la fiche · SOURCE (ecran, synchro, fusion, restauration…).
 * JAMAIS le contenu des fiches (paies, notes…) : seulement qui a touché quoi et quand. La SOURCE vient d'une petite table `journal_contexte` que le code met à jour avant une écriture
 * qui ne vient pas d'un écran (fusion automatique, restauration) puis remet à « ecran ».
 * Les événements de l'application (début / fin / erreur d'une synchronisation, plantage intercepté) sont notés dans le même journal (opération « evt », détail en clair).
 * Le journal garde les 1000 dernières lignes ; il est dans la base chiffrée. Aucun changement du schéma Room : tables créées à côté, comme le suivi des modifications.
 */
object JournalDonnees {
    private const val MAINTENANT = "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"
    private const val SOURCE = "COALESCE((SELECT `source` FROM `journal_contexte` LIMIT 1), 'ecran')"
    const val MAX_LIGNES = 1000

    /** Instructions SQL : tables du journal + 3 déclencheurs par table de données. Fonction pure (testée), idempotente. */
    fun instructions(): List<String> = buildList {
        add("CREATE TABLE IF NOT EXISTS `journal_contexte` (`source` TEXT NOT NULL)")
        add("INSERT INTO `journal_contexte` (`source`) SELECT 'ecran' WHERE NOT EXISTS (SELECT 1 FROM `journal_contexte`)")
        add("CREATE TABLE IF NOT EXISTS `journal_donnees` (`n` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `quand` INTEGER NOT NULL, `t` TEXT NOT NULL, `op` TEXT NOT NULL, `rid` INTEGER, `source` TEXT NOT NULL, `detail` TEXT)")
        for ((t, _) in SuiviModifs.TABLES) {
            for ((suffixe, evenement, op, ligne) in listOf(
                Quad("ai", "INSERT", "ajout", "NEW"), Quad("au", "UPDATE", "modif", "NEW"), Quad("ad", "DELETE", "suppr", "OLD"))) {
                add("CREATE TRIGGER IF NOT EXISTS `jd_${suffixe}_$t` AFTER $evenement ON `$t` BEGIN " +
                    "INSERT INTO `journal_donnees` (`quand`, `t`, `op`, `rid`, `source`) VALUES ($MAINTENANT, '$t', '$op', $ligne.`id`, $SOURCE); END")
            }
        }
    }
    private data class Quad(val a: String, val b: String, val c: String, val d: String)

    /** Installe le journal (appelé à chaque ouverture de la base). Une erreur est avalée : le journal ne doit JAMAIS empêcher l'ouverture de la base de paie. */
    fun installer(db: SupportSQLiteDatabase) {
        for (sql in instructions()) {
            try { db.execSQL(sql) } catch (e: Exception) { android.util.Log.w("JournalDonnees", "instruction ignorée : ${e.message}") }
        }
        elaguer(db)
    }

    /** Garde les 1000 dernières lignes. */
    fun elaguer(db: SupportSQLiteDatabase) {
        try { db.execSQL("DELETE FROM `journal_donnees` WHERE `n` <= (SELECT COALESCE(MAX(`n`), 0) FROM `journal_donnees`) - $MAX_LIGNES") } catch (_: Exception) { }
    }

    /** Indique l'origine des écritures qui suivent (« fusion », « restauration »…). À remettre à « ecran » ensuite : utiliser [avecSource]. */
    fun definirSource(db: SupportSQLiteDatabase, source: String) {
        try { db.execSQL("UPDATE `journal_contexte` SET `source` = ?", arrayOf(source)) } catch (_: Exception) { }
    }

    /** Exécute un bloc d'écriture en marquant sa source, puis la remet à « ecran » (même en cas d'erreur). */
    fun <T> avecSource(db: SupportSQLiteDatabase, source: String, bloc: () -> T): T {
        definirSource(db, source)
        try { return bloc() } finally { definirSource(db, "ecran") }
    }

    /** Note un événement de l'application (synchronisation, erreur…) ; ne lève jamais d'exception. */
    fun evenement(c: Context, nature: String, detail: String) {
        try {
            Base.avecBase(c) { db ->
                db.execSQL("INSERT INTO `journal_donnees` (`quand`, `t`, `op`, `rid`, `source`, `detail`) VALUES (${System.currentTimeMillis()}, ?, 'evt', NULL, 'app', ?)", arrayOf(nature.take(40), detail.take(300)))
                elaguer(db)
            }
        } catch (_: Throwable) { }
    }

    /** Dernières lignes en texte (la plus récente en HAUT), pour l'affichage et la copie au support. */
    fun texte(c: Context, nombre: Int = 200): String = try {
        Base.avecBase(c) { db ->
            val fmt = java.text.SimpleDateFormat("dd/MM HH:mm:ss.SSS", java.util.Locale.FRANCE)
            val sb = StringBuilder()
            db.query("SELECT `quand`, `t`, `op`, `rid`, `source`, `detail` FROM `journal_donnees` ORDER BY `n` DESC LIMIT $nombre").use { cur ->
                while (cur.moveToNext()) {
                    sb.append(fmt.format(java.util.Date(cur.getLong(0)))).append(" · ").append(cur.getString(4)).append(" · ").append(cur.getString(1)).append(" ").append(cur.getString(2))
                    if (!cur.isNull(3)) sb.append(" n°").append(cur.getLong(3))
                    if (!cur.isNull(5)) sb.append(" — ").append(cur.getString(5))
                    sb.append('\n')
                }
            }
            if (sb.isEmpty()) "(journal vide)" else sb.toString()
        }
    } catch (e: Throwable) { "(journal illisible : ${e.message})" }

    /** Nombre de lignes du journal. */
    fun compter(c: Context): Int = try { Base.avecBase(c) { db -> db.query("SELECT COUNT(*) FROM `journal_donnees`").use { it.moveToFirst(); it.getInt(0) } } } catch (_: Throwable) { 0 }

    /** Vide le journal. */
    fun vider(c: Context) { try { Base.avecBase(c) { it.execSQL("DELETE FROM `journal_donnees`") } } catch (_: Throwable) { } }
}
