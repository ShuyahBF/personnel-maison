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

    /** Proches avec qui je partage mes rappels de médicaments (prise ou oubli). */
    fun partageMedsAvec(c: Context): Set<String> = p(c).getStringSet("partage_meds_avec", emptySet()) ?: emptySet()
    fun partagerMeds(c: Context, lienId: String, oui: Boolean) = p(c).edit().putStringSet("partage_meds_avec", partageMedsAvec(c).toMutableSet().apply { if (oui) add(lienId) else remove(lienId) }).apply()

    /** Curseurs de lecture (événements et messages) par lien : seuls les éléments plus récents sont notifiés. Premier passage = maintenant (rien d'ancien n'est notifié). */
    fun curseur(c: Context, genre: String, lienId: String): Long {
        val cle = "curseur_${genre}_$lienId"
        if (!p(c).contains(cle)) p(c).edit().putLong(cle, System.currentTimeMillis() * 1_000_000L).apply()      // mémorisé dès le premier passage
        return p(c).getLong(cle, 0L)
    }
    fun curseur(c: Context, genre: String, lienId: String, v: Long) = p(c).edit().putLong("curseur_${genre}_$lienId", v).apply()

    // ---- Caméra partagée ----
    /** Proches avec qui je partage ma caméra. */
    fun partageCameraAvec(c: Context): Set<String> = p(c).getStringSet("partage_camera_avec", emptySet()) ?: emptySet()
    fun partagerCamera(c: Context, lienId: String, oui: Boolean) = p(c).edit().putStringSet("partage_camera_avec", partageCameraAvec(c).toMutableSet().apply { if (oui) add(lienId) else remove(lienId) }).apply()
    /** Caméra avant (vrai) ou arrière (faux, par défaut). */
    fun cameraAvant(c: Context) = p(c).getBoolean("camera_avant", false)
    fun cameraAvant(c: Context, v: Boolean) = p(c).edit().putBoolean("camera_avant", v).apply()
    /** Secondes entre deux photos. */
    fun cameraIntervalleS(c: Context) = p(c).getInt("camera_intervalle", FamilleImage.INTERVALLE_DEFAUT_S)
    fun cameraIntervalleS(c: Context, v: Int) = p(c).edit().putInt("camera_intervalle", v).apply()
    /** Vrai : une photo n'est envoyée que si un proche regarde (économie de données, par défaut) ; faux : en continu. */
    fun cameraSeulementSiRegarde(c: Context) = p(c).getBoolean("camera_si_regarde", true)
    fun cameraSeulementSiRegarde(c: Context, v: Boolean) = p(c).edit().putBoolean("camera_si_regarde", v).apply()
    /** Partage de la caméra démarré (service en cours). */
    fun cameraActive(c: Context) = p(c).getBoolean("camera_active", false)
    fun cameraActive(c: Context, v: Boolean) = p(c).edit().putBoolean("camera_active", v).apply()
}
