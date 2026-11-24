package org.shuyah.personnelmaison

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * SUIVI DES MODIFICATIONS (version 1.64, refonte « synchronisation automatique entre deux appareils », lot 1 — demande du propriétaire).
 *
 * PROBLÈME : chaque fiche porte un numéro automatique LOCAL (« personnel n° 12 »), identique sur aucun autre téléphone, et la plupart des tables n'ont ni date de modification
 * ni trace des suppressions : impossible de dire quelle version d'une fiche est la plus récente, ni si une fiche absente a été supprimée ou ajoutée ailleurs.
 *
 * SOLUTION, SANS TOUCHER AUX ÉCRANS NI AU SCHÉMA DES TABLES (donc sans migration risquée de la base de paie) : deux petites tables à côté, tenues À JOUR PAR LA BASE ELLE-MÊME
 * grâce à des « déclencheurs » (comme les événements « après ajout / modification / suppression » d'un fichier HFSQL) :
 *   - `suivi_fiches`       : pour CHAQUE fiche (table + numéro local) → son IDENTIFIANT UNIQUE mondial (aléatoire, 32 caractères) et sa DATE DE DERNIÈRE MODIFICATION (millisecondes) ;
 *   - `suivi_suppressions` : pierre tombale de chaque fiche supprimée (identifiant unique + date de suppression), pour que la suppression se propage à l'autre appareil au lieu d'être « rétablie ».
 * Les fiches qui existaient déjà reçoivent leur identifiant à l'ouverture (date = date de création quand elle existe). Tout est idempotent : rejouer l'installation ne change rien.
 * Les lots suivants s'en serviront pour fusionner deux appareils automatiquement (« le plus récent gagne », fiche par fiche). Rien ne change à l'écran dans ce lot.
 */
object SuiviModifs {
    /** Table → colonne de DATE DE CRÉATION (millisecondes) utilisée pour dater les fiches déjà présentes ; null = inconnue (date de l'installation). */
    val TABLES: List<Pair<String, String?>> = listOf(
        "societes" to null, "personnels" to "creeLe", "pointages" to null, "notes" to "horodatage", "evaluations" to "creeLe", "journal_pointages" to "horodatage",
        "horaires_personnel" to null, "numeros" to null, "paiements" to "creeLe", "versements" to "creeLe", "taches" to null, "documents_societe" to "ajouteLe",
        "profil" to null, "operations" to "modifieLe", "lignes_operation" to null, "pieces_operation" to "ajouteLe", "medicaments" to null,
        "prises_medicament" to null, "mesures_enregistrees" to "creeLe")

    /** Heure actuelle en millisecondes, calculée par SQLite (utilisable dans les déclencheurs). */
    private const val MAINTENANT = "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"
    private const val UID = "lower(hex(randomblob(16)))"

    /** Toutes les instructions SQL (création des tables de suivi, un jeu de 3 déclencheurs par table, rattrapage des fiches existantes). Fonction pure : testée. */
    fun instructions(): List<String> = buildList {
        add("CREATE TABLE IF NOT EXISTS `suivi_fiches` (`t` TEXT NOT NULL, `id` INTEGER NOT NULL, `uid` TEXT NOT NULL, `maj` INTEGER NOT NULL, PRIMARY KEY(`t`, `id`))")
        add("CREATE UNIQUE INDEX IF NOT EXISTS `index_suivi_fiches_uid` ON `suivi_fiches` (`uid`)")
        add("CREATE TABLE IF NOT EXISTS `suivi_suppressions` (`t` TEXT NOT NULL, `uid` TEXT NOT NULL, `maj` INTEGER NOT NULL, PRIMARY KEY(`t`, `uid`))")
        for ((t, creation) in TABLES) {
            // Après AJOUT : la fiche reçoit son identifiant unique et sa date
            add("CREATE TRIGGER IF NOT EXISTS `suivi_ai_$t` AFTER INSERT ON `$t` BEGIN " +
                "INSERT OR REPLACE INTO `suivi_fiches` (`t`, `id`, `uid`, `maj`) VALUES ('$t', NEW.`id`, $UID, $MAINTENANT); END")
            // Après MODIFICATION : la date est renouvelée (la fiche est créée dans le suivi si elle n'y était pas)
            add("CREATE TRIGGER IF NOT EXISTS `suivi_au_$t` AFTER UPDATE ON `$t` BEGIN " +
                "INSERT OR IGNORE INTO `suivi_fiches` (`t`, `id`, `uid`, `maj`) VALUES ('$t', NEW.`id`, $UID, $MAINTENANT); " +
                "UPDATE `suivi_fiches` SET `maj` = $MAINTENANT WHERE `t` = '$t' AND `id` = NEW.`id`; END")
            // Après SUPPRESSION : pierre tombale (identifiant unique + date) puis la fiche quitte le suivi
            add("CREATE TRIGGER IF NOT EXISTS `suivi_ad_$t` AFTER DELETE ON `$t` BEGIN " +
                "INSERT OR REPLACE INTO `suivi_suppressions` (`t`, `uid`, `maj`) SELECT `t`, `uid`, $MAINTENANT FROM `suivi_fiches` WHERE `t` = '$t' AND `id` = OLD.`id`; " +
                "DELETE FROM `suivi_fiches` WHERE `t` = '$t' AND `id` = OLD.`id`; END")
            // Rattrapage : les fiches déjà présentes reçoivent un identifiant (date = création si connue)
            add("INSERT OR IGNORE INTO `suivi_fiches` (`t`, `id`, `uid`, `maj`) SELECT '$t', `id`, $UID, " +
                (if (creation != null) "COALESCE(`$creation`, $MAINTENANT)" else MAINTENANT) + " FROM `$t`")
            // Nettoyage : une fiche REMPLACÉE par un enregistrement « INSERT OR REPLACE » (pointage du jour, profil) laisse un ancien numéro sans fiche : on l'oublie (pas une suppression)
            add("DELETE FROM `suivi_fiches` WHERE `t` = '$t' AND `id` NOT IN (SELECT `id` FROM `$t`)")
        }
    }

    /**
     * Installe (ou complète) le suivi. Appelé à CHAQUE ouverture de la base : idempotent. Une erreur ici ne doit JAMAIS empêcher l'ouverture de la base de paie :
     * elle est avalée (le suivi sera retenté à la prochaine ouverture).
     */
    fun installer(db: SupportSQLiteDatabase) {
        for (sql in instructions()) {
            try { db.execSQL(sql) } catch (e: Exception) { android.util.Log.w("SuiviModifs", "instruction ignorée : ${e.message}") }
        }
    }
}
