package org.shuyah.personnelmaison

/** Genres d'enregistrement du journal. */
object Genre { const val ARRIVEE = "ARRIVEE"; const val DEPART = "DEPART"; const val ABSENCE = "ABSENCE"; const val RETARD = "RETARD" }

/**
 * ENREGISTRER UNE HEURE (arrivée ou départ) pour un personnel à une date : crée ou modifie le pointage du jour et laisse une ligne HORODATÉE dans le
 * journal (CREATION, MODIFICATION ou SUPPRESSION). `minutes` null = effacer l'heure. Un pointage devenu vide est supprimé.
 */
suspend fun DaoMaison.noterHeure(personnelId: Long, date: String, genre: String, minutes: Int?, retardSignale: Boolean? = null, motif: String? = null, absent: Boolean? = null) {
    val existant = pointage(personnelId, date)
    val base = existant ?: Pointage(personnelId = personnelId, date = date)
    val ancien = if (genre == Genre.ARRIVEE) base.arrivee else base.depart
    var nouveau = if (genre == Genre.ARRIVEE) base.copy(arrivee = minutes, absent = if (minutes != null) false else base.absent) else base.copy(depart = minutes)
    if (retardSignale != null) nouveau = nouveau.copy(retardSignale = retardSignale)
    if (motif != null) nouveau = nouveau.copy(motif = motif)
    if (absent != null) nouveau = nouveau.copy(absent = absent, arrivee = if (absent) null else nouveau.arrivee, depart = if (absent) null else nouveau.depart)
    val vide = nouveau.arrivee == null && nouveau.depart == null && !nouveau.absent && !nouveau.retardSignale && nouveau.motif.isBlank()
    if (vide) { if (existant != null) supprimerPointage(personnelId, date) } else enregistrerPointage(nouveau)
    Calculs.actionJournal(ancien, minutes)?.let { ajouterJournal(JournalPointage(personnelId = personnelId, date = date, genre = genre, heure = minutes, action = it)) }
}

/** Absence déclarée (ou levée) pour un jour, avec journal. */
suspend fun DaoMaison.noterAbsence(personnelId: Long, date: String, absent: Boolean, motif: String) {
    val existant = pointage(personnelId, date)
    if (absent == (existant?.absent == true)) { if (motif != (existant?.motif ?: "")) noterHeure(personnelId, date, Genre.ARRIVEE, existant?.arrivee, motif = motif); return }
    noterHeure(personnelId, date, Genre.ARRIVEE, if (absent) null else existant?.arrivee, motif = motif, absent = absent)
    ajouterJournal(JournalPointage(personnelId = personnelId, date = date, genre = Genre.ABSENCE, heure = null, action = if (absent) "CREATION" else "SUPPRESSION"))
}
