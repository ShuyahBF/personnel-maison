package org.shuyah.personnelmaison

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * RAPPELS DE MÉDICAMENTS — logique pure (sans Android), testée par RappelsMedicamentsTest : heures de prise, jours de la semaine, prochaine prise,
 * et texte des événements partagés avec la famille (« pris » ou « oublié »).
 */
object RappelsMedicaments {
    /** Une prise non confirmée au bout de ce délai (minutes) est considérée comme oubliée et signalée aux proches. */
    const val RETARD_MIN = 30
    /** « Plus tard » : le rappel revient après ce délai (minutes). */
    const val REPORT_MIN = 10
    const val TOUS_LES_JOURS = 127
    /** Initiales des jours, lundi d'abord (bit 0 = lundi). */
    val INITIALES = listOf("L", "M", "M", "J", "V", "S", "D")
    /** Médicaments usuels proposés (liste déroulante, « Autre… » pour un nom libre). */
    val USUELS = listOf("Paracétamol", "Amoxicilline", "Artéméther-luméfantrine", "Metformine", "Amlodipine", "Insuline", "Vitamine C", "Fer / acide folique")

    /** « 08:00,20:00 » → minutes depuis minuit (triées, sans doublon) ; les valeurs illisibles sont ignorées. */
    fun minutesDepuisTexte(t: String): List<Int> = t.split(",").mapNotNull { x ->
        val p = x.trim().split(":")
        val h = p.getOrNull(0)?.toIntOrNull(); val m = p.getOrNull(1)?.toIntOrNull()
        if (p.size == 2 && h != null && m != null && h in 0..23 && m in 0..59) h * 60 + m else null
    }.distinct().sorted()

    fun minutesVersTexte(l: List<Int>) = l.distinct().sorted().joinToString(",") { "%02d:%02d".format(it / 60, it % 60) }
    fun libelleHeure(min: Int) = "%02d:%02d".format(min / 60, min % 60)

    /** Le médicament se prend-il ce jour de la semaine ? */
    fun jourActif(masque: Int, date: LocalDate) = masque and (1 shl (date.dayOfWeek.value - 1)) != 0

    /** La date est-elle dans la période du traitement (début et fin facultatifs, inclus) ? */
    fun dansPeriode(debut: String?, fin: String?, date: LocalDate): Boolean {
        val d = debut?.takeIf { it.isNotBlank() }?.let { try { LocalDate.parse(it) } catch (e: Exception) { null } }
        val f = fin?.takeIf { it.isNotBlank() }?.let { try { LocalDate.parse(it) } catch (e: Exception) { null } }
        return (d == null || !date.isBefore(d)) && (f == null || !date.isAfter(f))
    }

    /** Heures (ms) des prises d'un jour donné. */
    fun prisesDuJour(heures: List<Int>, date: LocalDate, zone: ZoneId): List<Long> = heures.map { date.atStartOfDay(zone).plusMinutes(it.toLong()).toInstant().toEpochMilli() }

    /** Les prises prévues d'un médicament à une date (vide si le jour ou la période ne correspond pas). */
    fun prisesPrevues(m: Medicament, date: LocalDate, zone: ZoneId): List<Long> =
        if (!m.actif || !jourActif(m.jours, date) || !dansPeriode(m.debut, m.fin, date)) emptyList() else prisesDuJour(minutesDepuisTexte(m.heures), date, zone)

    /** Prochaine prise strictement après `apresMs` (jusqu'à 400 jours devant), ou null. */
    fun prochainePrise(m: Medicament, apresMs: Long, zone: ZoneId): Long? {
        var d = Instant.ofEpochMilli(apresMs).atZone(zone).toLocalDate()
        repeat(400) {
            prisesPrevues(m, d, zone).firstOrNull { it > apresMs }?.let { return it }
            d = d.plusDays(1)
        }
        return null
    }

    /** « Tous les jours » ou « L, M, J » ; « Aucun jour » si rien n'est coché. */
    fun libelleJours(masque: Int): String = when {
        masque and TOUS_LES_JOURS == TOUS_LES_JOURS -> "Tous les jours"
        masque and TOUS_LES_JOURS == 0 -> "Aucun jour"
        else -> INITIALES.indices.filter { masque and (1 shl it) != 0 }.joinToString(" ") { listOf("lun", "mar", "mer", "jeu", "ven", "sam", "dim")[it] }
    }

    // ---- Événement partagé avec les proches (chiffré avant l'envoi) ----
    /** `statut` : « pris » ou « oublie ». `par` : nom de la personne qui prend le médicament. */
    data class Evenement(val nom: String, val dose: String, val prevuMs: Long, val statut: String, val par: String)

    private fun echapper(s: String) = s.replace("\\", " ").replace("\"", "'")

    fun evenementVersTexte(e: Evenement): String =
        String.format(Locale.ROOT, "{\"nom\":\"%s\",\"dose\":\"%s\",\"prevu\":%d,\"statut\":\"%s\",\"par\":\"%s\"}", echapper(e.nom), echapper(e.dose), e.prevuMs, e.statut, echapper(e.par))

    fun evenementDepuisTexte(t: String): Evenement? {
        fun texte(c: String) = Regex("\"$c\":\"([^\"]*)\"").find(t)?.groupValues?.get(1)
        val prevu = Regex("\"prevu\":(\\d+)").find(t)?.groupValues?.get(1)?.toLongOrNull()
        val statut = texte("statut")
        return if (texte("nom") == null || prevu == null || statut == null) null else Evenement(texte("nom")!!, texte("dose").orEmpty(), prevu, statut, texte("par").orEmpty())
    }

    /** « Awa a pris Paracétamol 500 mg (prévu à 08:00) » / « Awa n'a pas pris … ». */
    fun libelleEvenement(e: Evenement, zone: ZoneId = ZoneId.systemDefault()): String {
        val h = Instant.ofEpochMilli(e.prevuMs).atZone(zone).let { "%02d:%02d".format(it.hour, it.minute) }
        val med = (e.nom + if (e.dose.isNotBlank()) " " + e.dose else "").trim()
        return if (e.statut == "pris") "${e.par.ifBlank { "Il/elle" }} a pris $med (prévu à $h)" else "${e.par.ifBlank { "Il/elle" }} n'a pas pris $med (prévu à $h)"
    }
}
