package org.shuyah.personnelmaison

import android.content.Context

/**
 * Réglages de « Ma famille » (position) gardés dans les préférences CHIFFRÉES du téléphone : domicile et lieux (coordonnées sensibles), mode de partage,
 * proches avec qui la position est partagée, partage actif ou non.
 */
object ReglagesFamille {
    private fun p(c: Context) = ClientFamille.prefs(c)

    /** Domicile : centre et rayon du périmètre (null tant qu'il n'est pas défini). */
    fun domicile(c: Context): FamillePosition.Lieu? = FamillePosition.lieuxDepuisTexte(p(c).getString("domicile", null)).firstOrNull()
    fun domicile(c: Context, v: FamillePosition.Lieu?) = p(c).edit().putString("domicile", if (v == null) null else FamillePosition.lieuxVersTexte(listOf(v.copy(nom = "Domicile")))).apply()

    /** Autres lieux enregistrés (École, Travail…). */
    fun lieux(c: Context) = FamillePosition.lieuxDepuisTexte(p(c).getString("lieux", null))
    fun lieux(c: Context, v: List<FamillePosition.Lieu>) = p(c).edit().putString("lieux", FamillePosition.lieuxVersTexte(v)).apply()

    /** Mode : seulement hors du périmètre du domicile (par défaut) ou en permanence. */
    fun mode(c: Context) = p(c).getString("mode_position", FamillePosition.MODE_HORS_DOMICILE) ?: FamillePosition.MODE_HORS_DOMICILE
    fun mode(c: Context, v: String) = p(c).edit().putString("mode_position", v).apply()

    /** Identifiants des liens (proches) avec qui je partage ma position. */
    fun partageAvec(c: Context): Set<String> = p(c).getStringSet("partage_avec", emptySet()) ?: emptySet()
    fun partager(c: Context, lienId: String, oui: Boolean) = p(c).edit().putStringSet("partage_avec", partageAvec(c).toMutableSet().apply { if (oui) add(lienId) else remove(lienId) }).apply()

    /** Partage en cours (service de suivi démarré) : sert à restaurer l'interrupteur à l'ouverture de l'écran. */
    fun actif(c: Context) = p(c).getBoolean("partage_actif", false)
    fun actif(c: Context, v: Boolean) = p(c).edit().putBoolean("partage_actif", v).apply()
}
