package org.shuyah.personnelmaison

import android.content.Context

/**
 * RÉPARATION AUTOMATIQUE ET TRANSPARENTE (1.77, demande du propriétaire : « si la solution est déjà connue, rattrape l'erreur de manière transparente pour l'utilisateur »).
 *
 * Quand une écriture échoue pour une cause DÉJÀ CONNUE, on répare puis on REJOUE l'opération une fois, sans rien dire à l'utilisateur (la réparation est seulement notée dans le journal).
 * Une erreur inconnue, ou qui persiste après la réparation, reste signalée (message + journal) : on ne cache jamais ce qu'on ne sait pas réparer.
 *
 * Causes connues :
 *   1. Le suivi / journal de la base est abîmé (déclencheur ancien ou absent, ligne de suivi périmée : « UNIQUE constraint failed: suivi_fiches », « no such table: suivi_… ») :
 *      on réinstalle les déclencheurs (anciens remplacés) et on nettoie les lignes de suivi sans fiche.
 *   2. La base est momentanément occupée (« database is locked », « busy ») : courte attente, puis nouvel essai.
 * Les blocs rejoués doivent être « tout ou rien » (`Base.atomique`) ou sans effet répété : c'est le cas des écrans qui écrivent plusieurs tables.
 */
object Reparation {
    /** Chaîne des messages de l'erreur et de ses causes (pour la reconnaître). */
    private fun texte(e: Throwable): String = generateSequence(e) { it.cause }.joinToString(" | ") { "${it.javaClass.simpleName}: ${it.message ?: ""}" }

    /** Nature de la réparation reconnue pour ce message (null = erreur inconnue). Fonction pure : testée. */
    fun cause(message: String): String? = when {
        Regex("suivi_(fiches|suppressions|base)|journal_(donnees|contexte)", RegexOption.IGNORE_CASE).containsMatchIn(message) -> "suivi"
        Regex("database is locked|SQLiteDatabaseLockedException|SQLITE_BUSY|database table is locked", RegexOption.IGNORE_CASE).containsMatchIn(message) -> "occupee"
        else -> null
    }

    /** Tente la réparation. Rend sa description (pour le journal) ou null si l'erreur est inconnue. À appeler HORS du fil principal. */
    fun tenter(c: Context, e: Throwable): String? = when (cause(texte(e))) {
        "suivi" -> try {
            Base.avecBase(c) { db -> SuiviModifs.installer(db); JournalDonnees.installer(db) }       // déclencheurs réinstallés, suivi nettoyé et rattrapé
            "suivi de la base réinstallé"
        } catch (_: Throwable) { null }
        "occupee" -> { try { Thread.sleep(500) } catch (_: InterruptedException) { }; "base occupée : nouvel essai" }
        else -> null
    }
}
