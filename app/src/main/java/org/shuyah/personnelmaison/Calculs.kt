package org.shuyah.personnelmaison

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * CALCULS PURS (sans Android, testés par CalculsTest) : retards, présences du mois, formats d'affichage, textes à partager.
 * Convention : les heures sont des minutes depuis minuit (07:30 = 450) ; les jours de la semaine sont un masque de bits (lundi = bit 0).
 */

/** Ce qui définit les horaires attendus d'un personnel. */
data class ContratInfo(val debutMin: Int, val finMin: Int, val jours: Int, val dateDebut: LocalDate, val dateFin: LocalDate?)

/** Un pointage, réduit à ce que les statistiques utilisent. */
data class JourPointage(val date: LocalDate, val arrivee: Int?, val depart: Int?, val retardSignale: Boolean, val absent: Boolean)

/** Chiffres d'un mois pour un personnel. */
data class StatsMois(val joursAttendus: Int, val presences: Int, val absences: Int, val retards: Int, val retardTotalMin: Int,
                     val minutesTravaillees: Int, val departsAnticipes: Int)

object Calculs {
    const val TOLERANCE_PAR_DEFAUT = 10          // minutes de retard tolérées avant de compter un retard

    /** « 07:05 » */
    fun heure(minutes: Int?): String = if (minutes == null) "—" else "%02d:%02d".format(minutes / 60, minutes % 60)

    /** « 9 h 05 » ou « 45 min » */
    fun duree(minutes: Int): String = if (minutes < 60) "$minutes min" else "${minutes / 60} h %02d".format(minutes % 60)

    /** « 150 000 FCFA » (espaces insécables entre les milliers). */
    fun francs(montant: Long): String = "%,d".format(montant).replace(',', ' ').replace(' ', ' ') + " FCFA"

    private val FORMAT_FR = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    /** « 11/11/2026 » (affichage JJ/MM/AAAA, stockage AAAA-MM-JJ). */
    fun dateFr(d: LocalDate): String = d.format(FORMAT_FR)
    fun dateFr(iso: String?): String = iso?.let { try { dateFr(LocalDate.parse(it)) } catch (e: Exception) { it } } ?: "—"
    /** « mercredi 11 novembre 2026 » */
    fun dateLongue(d: LocalDate): String = d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.FRENCH) + " " + d.dayOfMonth + " " +
        d.month.getDisplayName(TextStyle.FULL, Locale.FRENCH) + " " + d.year
    /** « novembre 2026 » */
    fun moisLong(m: YearMonth): String = m.month.getDisplayName(TextStyle.FULL, Locale.FRENCH) + " " + m.year

    /** Vrai si ce jour de la semaine est travaillé selon le masque. */
    fun jourTravaille(jours: Int, jour: DayOfWeek): Boolean = jours and (1 shl (jour.value - 1)) != 0

    /** « Lun, Mar, Mer » */
    fun joursTexte(jours: Int): String = DayOfWeek.values().filter { jourTravaille(jours, it) }.joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.FRENCH).replace(".", "").replaceFirstChar { c -> c.uppercase() } }

    /**
     * Minutes de retard d'une arrivée : 0 si elle est dans la tolérance. Exemple : début 07:00, tolérance 10 → 07:08 = 0 ; 07:15 = 15.
     */
    fun retardMinutes(arrivee: Int?, debutContrat: Int, tolerance: Int): Int {
        if (arrivee == null) return 0
        val ecart = arrivee - debutContrat
        return if (ecart > tolerance) ecart else 0
    }

    /** Ce pointage est-il un retard ? (arrivée au-delà de la tolérance, OU retard signalé à la main) */
    fun estRetard(p: JourPointage, debutContrat: Int, tolerance: Int): Boolean =
        p.arrivee != null && (retardMinutes(p.arrivee, debutContrat, tolerance) > 0 || p.retardSignale)

    /** Chiffres du mois. Le jour courant sans pointage n'est pas encore une absence (la journée n'est pas finie). */
    fun statsMois(mois: YearMonth, c: ContratInfo, pointages: List<JourPointage>, tolerance: Int, aujourdhui: LocalDate): StatsMois {
        val parDate = pointages.associateBy { it.date }
        var attendus = 0; var presences = 0; var absences = 0; var retards = 0; var retardTotal = 0; var travaille = 0; var anticipes = 0
        for (jour in 1..mois.lengthOfMonth()) {
            val d = mois.atDay(jour)
            if (d.isAfter(aujourdhui) || d.isBefore(c.dateDebut) || (c.dateFin != null && d.isAfter(c.dateFin))) continue
            val p = parDate[d]
            val attendu = jourTravaille(c.jours, d.dayOfWeek)
            if (attendu) attendus++
            if (p?.arrivee != null) {
                presences++
                if (estRetard(p, c.debutMin, tolerance)) { retards++; retardTotal += maxOf(0, p.arrivee - c.debutMin) }
                if (p.depart != null) {
                    travaille += maxOf(0, p.depart - p.arrivee)
                    if (attendu && p.depart < c.finMin - tolerance) anticipes++
                }
            } else if (attendu && (p?.absent == true || d != aujourdhui)) absences++
        }
        return StatsMois(attendus, presences, absences, retards, retardTotal, travaille, anticipes)
    }

    /** Action à journaliser quand une heure passe de `ancien` à `nouveau` : CREATION, MODIFICATION, SUPPRESSION, ou null si rien ne change. */
    fun actionJournal(ancien: Int?, nouveau: Int?): String? = when {
        ancien == nouveau -> null
        ancien == null -> "CREATION"
        nouveau == null -> "SUPPRESSION"
        else -> "MODIFICATION"
    }

    /** Libellé lisible d'une action : « enregistrée », « corrigée », « effacée ». */
    fun actionTexte(action: String): String = when (action) { "CREATION" -> "enregistré"; "MODIFICATION" -> "corrigé"; "SUPPRESSION" -> "effacé"; else -> action.lowercase() }

    /** Note moyenne d'une liste de notes (0 si vide), arrondie au dixième. */
    fun moyenne(notes: List<Int>): Double = if (notes.isEmpty()) 0.0 else Math.round(notes.average() * 10) / 10.0

    /** Période d'une évaluation affichée : « 11/11/2026 » (jour) ou « novembre 2026 » (mois). */
    fun periodeTexte(type: String, periode: String): String = try {
        if (type == "MOIS") moisLong(YearMonth.parse(periode)) else dateFr(LocalDate.parse(periode))
    } catch (e: Exception) { periode }

    /** Texte d'un témoignage à partager (évaluations cochées « partageables » seulement) : aucune donnée sur les personnels. */
    fun temoignage(nomSociete: String, evaluations: List<Evaluation>): String {
        val liste = evaluations.filter { it.partageable }.sortedByDescending { it.periode }
        if (liste.isEmpty()) return ""
        val sb = StringBuilder("Avis sur la société de placement « $nomSociete »\nNote moyenne : ${moyenne(liste.map { it.note })} / 5 (${liste.size} évaluation(s))\n")
        for (e in liste) {
            sb.append("\n• ${periodeTexte(e.type, e.periode)} : ${"★".repeat(e.note)}${"☆".repeat(5 - e.note)}")
            if (e.remarque.isNotBlank()) sb.append("\n  « ${e.remarque.trim()} »")
        }
        return sb.toString()
    }

    /** Bilan du mois à partager (une ligne par personnel). */
    fun bilan(mois: YearMonth, lignes: List<Pair<String, StatsMois>>): String {
        val sb = StringBuilder("Bilan de ${moisLong(mois)}\n")
        for ((nom, s) in lignes) {
            sb.append("\n$nom : ${s.presences}/${s.joursAttendus} jour(s) de présence, ${s.absences} absence(s), ${s.retards} retard(s)")
            if (s.retardTotalMin > 0) sb.append(" (${duree(s.retardTotalMin)} au total)")
            sb.append(", ${duree(s.minutesTravaillees)} travaillées")
        }
        return sb.toString()
    }
}
