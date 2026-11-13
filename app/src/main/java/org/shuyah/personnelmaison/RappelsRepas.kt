package org.shuyah.personnelmaison

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * REPAS PARTAGÉS AVEC LA FAMILLE — logique pure (sans Android), testée par RappelsRepasTest : repas et heures usuelles, prochaine heure de repas,
 * texte des événements « prend son repas », « a fini » et « n'a pas encore pris son repas ».
 */
object RappelsRepas {
    /** Un repas non déclaré au bout de ce délai (minutes) après l'heure prévue est signalé aux proches. */
    const val DELAI_ALERTE_MIN = 60

    /** Un repas : code stable, nom, groupe nominal pour les phrases (« son déjeuner »), heure usuelle (minutes depuis minuit) et activé par défaut ou non. */
    data class Repas(val code: String, val nom: String, val possessif: String, val minutesDefaut: Int, val actifDefaut: Boolean)

    /** Liste finie des repas (liste déroulante / interrupteurs). */
    val REPAS = listOf(
        Repas("petit_dejeuner", "Petit-déjeuner", "son petit-déjeuner", 7 * 60 + 30, true),
        Repas("dejeuner", "Déjeuner", "son déjeuner", 12 * 60 + 30, true),
        Repas("diner", "Dîner", "son dîner", 19 * 60 + 30, true),
        Repas("collation", "Collation", "sa collation", 16 * 60 + 30, false))

    fun repas(code: String) = REPAS.firstOrNull { it.code == code }

    /** Prochaine fois (ms) où l'horloge indique cette heure, strictement après `apresMs` (aujourd'hui si elle n'est pas passée, sinon demain). */
    fun prochaineHeure(minutes: Int, apresMs: Long, zone: ZoneId): Long {
        val jour = Instant.ofEpochMilli(apresMs).atZone(zone).toLocalDate()
        val aujourdhui = jour.atStartOfDay(zone).plusMinutes(minutes.toLong()).toInstant().toEpochMilli()
        return if (aujourdhui > apresMs) aujourdhui else jour.plusDays(1).atStartOfDay(zone).plusMinutes(minutes.toLong()).toInstant().toEpochMilli()
    }

    // ---- Événement partagé (chiffré avant l'envoi) ----
    /** `statut` : « prend » (il commence son repas), « termine » (il a fini), « pas_pris » (non déclaré une heure après l'heure prévue). */
    data class Evenement(val repas: String, val statut: String, val prevuMs: Long, val par: String)

    private fun echapper(s: String) = s.replace("\\", " ").replace("\"", "'")

    fun evenementVersTexte(e: Evenement): String = String.format(Locale.ROOT, "{\"repas\":\"%s\",\"statut\":\"%s\",\"prevu\":%d,\"par\":\"%s\"}", echapper(e.repas), echapper(e.statut), e.prevuMs, echapper(e.par))

    fun evenementDepuisTexte(t: String): Evenement? {
        fun texte(c: String) = Regex("\"$c\":\"([^\"]*)\"").find(t)?.groupValues?.get(1)
        val prevu = Regex("\"prevu\":(\\d+)").find(t)?.groupValues?.get(1)?.toLongOrNull()
        val repas = texte("repas"); val statut = texte("statut")
        return if (repas == null || statut == null || prevu == null) null else Evenement(repas, statut, prevu, texte("par").orEmpty())
    }

    /** « Awa prend son déjeuner » / « Awa a fini son déjeuner » / « Awa n'a pas encore pris son déjeuner (prévu à 12:30) ». */
    fun libelleEvenement(e: Evenement, zone: ZoneId = ZoneId.systemDefault()): String {
        val qui = e.par.ifBlank { "Il/elle" }
        val groupe = repas(e.repas)?.possessif ?: "son repas"
        val h = Instant.ofEpochMilli(e.prevuMs).atZone(zone).let { "%02d:%02d".format(it.hour, it.minute) }
        return when (e.statut) { "prend" -> "$qui prend $groupe"; "termine" -> "$qui a fini $groupe"; else -> "$qui n'a pas encore pris $groupe (prévu à $h)" }
    }
}
