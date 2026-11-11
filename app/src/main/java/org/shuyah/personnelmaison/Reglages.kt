package org.shuyah.personnelmaison

import android.content.Context

/** Réglages de l'application (préférences du téléphone). */
object Reglages {
    private fun p(c: Context) = c.getSharedPreferences("reglages", Context.MODE_PRIVATE)

    /** Minutes de retard tolérées avant de compter un retard (par défaut 10). */
    fun tolerance(c: Context) = p(c).getInt("tolerance", Calculs.TOLERANCE_PAR_DEFAUT)
    fun tolerance(c: Context, v: Int) = p(c).edit().putInt("tolerance", v.coerceIn(0, 120)).apply()
}
