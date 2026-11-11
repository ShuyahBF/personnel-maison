package org.shuyah.personnelmaison

import android.content.Context

/** Réglages de l'application (préférences du téléphone). */
object Reglages {
    private fun p(c: Context) = c.getSharedPreferences("reglages", Context.MODE_PRIVATE)

    /** Texte plus grand dans toute l'application (par défaut : oui, pour les personnes âgées ou à vue réduite). */
    fun grosTexte(c: Context) = p(c).getBoolean("grosTexte", true)
    fun grosTexte(c: Context, v: Boolean) = p(c).edit().putBoolean("grosTexte", v).apply()

    /** Minutes de retard tolérées avant de compter un retard (par défaut 10). */
    fun tolerance(c: Context) = p(c).getInt("tolerance", Calculs.TOLERANCE_PAR_DEFAUT)
    fun tolerance(c: Context, v: Int) = p(c).edit().putInt("tolerance", v.coerceIn(0, 120)).apply()
}
