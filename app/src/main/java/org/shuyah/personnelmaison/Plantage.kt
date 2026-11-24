package org.shuyah.personnelmaison

import android.content.Context
import android.os.Build

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
        } catch (_: Throwable) { /* ne jamais aggraver */ }
    }

    fun dernier(c: Context): String? = c.getSharedPreferences(FICHIER, Context.MODE_PRIVATE).getString("dernier", null)
    fun effacer(c: Context) { c.getSharedPreferences(FICHIER, Context.MODE_PRIVATE).edit().remove("dernier").apply() }
}
