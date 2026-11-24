package org.shuyah.personnelmaison

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * MÉMOIRE DES PLANTAGES (1.73) : quand l'application plante, le détail technique (message + pile d'appels) est écrit dans un petit fichier
 * du téléphone AVANT la fermeture ; à l'ouverture suivante, l'accueil l'affiche avec « Copier » et « Effacer ». Rien n'est envoyé tout seul.
 * Équivalent WinDev : un gestionnaire d'exceptions global de toute l'application.
 */
object Plantage {
    private const val FICHIER = "plantage"
    @Volatile private var installe = false

    /** Installe le gestionnaire (sans effet s'il l'est déjà). */
    fun installer(c: Context) {
        if (installe) return
        installe = true
        val contexte = c.applicationContext
        val ancien = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { fil, erreur ->
            noter(contexte, "plantage", fil.name, erreur)
            ancien?.uncaughtException(fil, erreur)
        }
    }

    /** Note une erreur (plantage ou erreur interceptée) ; ne lève jamais d'exception. */
    fun noter(c: Context, nature: String, endroit: String, erreur: Throwable) {
        try {
            val pile = android.util.Log.getStackTraceString(erreur).take(6000)
            val texte = "Personnel de maison ${Version.VERSION} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                "$nature · $endroit · ${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.FRANCE).format(java.util.Date())}\n\n$pile"
            c.applicationContext.getSharedPreferences(FICHIER, Context.MODE_PRIVATE).edit().putString("dernier", texte).commit()   // commit : écrit avant la fermeture
            if (nature != "plantage") JournalDonnees.evenement(c, nature, "$endroit : ${erreur.javaClass.simpleName} ${erreur.message ?: ""}")   // erreur interceptée : aussi dans le journal des données
        } catch (_: Throwable) { /* ne jamais aggraver */ }
    }

    fun dernier(c: Context): String? = c.getSharedPreferences(FICHIER, Context.MODE_PRIVATE).getString("dernier", null)
    fun effacer(c: Context) { c.getSharedPreferences(FICHIER, Context.MODE_PRIVATE).edit().remove("dernier").apply() }
}

/**
 * FILET DE SÉCURITÉ DES ÉCRANS (1.76) + RÉPARATION AUTOMATIQUE (1.77) : lance une tâche d'écran (enregistrer, supprimer, synchroniser…) en interceptant TOUTE erreur.
 *   - cause DÉJÀ CONNUE (voir `Reparation`) : on répare puis on REJOUE la tâche une fois, SANS rien afficher (seule une ligne du journal le dit) ;
 *   - sinon, ou si l'erreur persiste : notée dans le journal et dans la mémoire des plantages, avec un message tout de suite, au lieu de fermer l'application.
 * L'annulation normale d'une tâche (changement d'écran) n'est pas une erreur.
 */
fun CoroutineScope.lancerSecurise(c: Context, endroit: String = "écran", bloc: suspend CoroutineScope.() -> Unit): Job = launch {
    var reparee = false
    while (true) {
        try { bloc(); return@launch } catch (e: CancellationException) { throw e } catch (e: Throwable) {
            val reparation = if (!reparee) withContext(Dispatchers.IO) { Reparation.tenter(c, e) } else null
            if (reparation != null) {                                    // solution connue : réparée, rejouée, transparente pour l'utilisateur
                reparee = true
                withContext(Dispatchers.IO) { JournalDonnees.evenement(c, "réparation", "$endroit : $reparation (${e.javaClass.simpleName})") }
                continue
            }
            withContext(Dispatchers.IO) { Plantage.noter(c, "erreur d'écran", endroit, e) }
            android.widget.Toast.makeText(c.applicationContext, "Opération impossible : ${e.message ?: e.javaClass.simpleName} (détail gardé dans le journal)", android.widget.Toast.LENGTH_LONG).show()
            return@launch
        }
    }
}
