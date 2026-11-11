package org.shuyah.personnelmaison

import java.time.LocalDate

/**
 * LECTURE D'UN TICKET DE CAISSE à partir du texte brut de l'OCR : marchand, date, total et lignes d'articles. C'est une SUGGESTION : tout se
 * corrige à la main. Montants en FCFA entiers (« 1 500 », « 1.500 », « 1500 »). Logique pure (sans Android), testée par BudgetTest.
 */
object AnalyseTicket {
    data class Article(val libelle: String, val montant: Long)
    data class Resultat(val marchand: String?, val date: LocalDate?, val total: Long?, val articles: List<Article>)

    private val NOMBRE = Regex("""\d{1,3}(?:[ ., ]\d{3})+|\d+""")
    private val DATE = Regex("""(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{4}|\d{2})""")
    private val LIGNE_TOTAL = Regex("""(?i)\b(net\s*[àa]\s*payer|total\s*(ttc|g[ée]n[ée]ral)?|montant\s*(ttc|total|d[uû])?|[àa]\s*payer)\b""")
    private val SOUS_TOTAL = Regex("""(?i)sous[\s\-]*total|total\s*(tva|ht|remise)|\btva\b|\bht\b""")
    // Lignes qui ne sont pas des articles : paiement, rendu, coordonnées, numéros…
    private val A_IGNORER = Regex("""(?i)\b(total|tva|ht|ttc|esp[èe]ces?|rendu|monnaie|re[çc]u|t[ée]l|tel|date|caisse?|caissier|ticket|facture|n°|ifu|rccm|merci|carte|orange|moov|telecel|paiement|remise|client|heure|bon)\b""")

    /** Montant (entier) écrit dans un fragment de texte : séparateurs de milliers retirés. */
    fun montant(fragment: String): Long? = fragment.replace(Regex("""[ ., ]"""), "").toLongOrNull()

    fun analyser(texte: String): Resultat {
        val lignes = texte.lines().map { it.trim() }.filter { it.isNotEmpty() }
        // Date : première date valide (jour, mois, année à 2 ou 4 chiffres)
        val date = lignes.firstNotNullOfOrNull { l ->
            DATE.find(l)?.let { m ->
                try { var a = m.groupValues[3].toInt(); if (a < 100) a += 2000; LocalDate.of(a, m.groupValues[2].toInt(), m.groupValues[1].toInt()) } catch (e: Exception) { null }
            }
        }
        // Total : dernière ligne « TOTAL / NET À PAYER / MONTANT » (hors sous-totaux, TVA), son dernier nombre
        val candidats = lignes.filter { LIGNE_TOTAL.containsMatchIn(it) && !SOUS_TOTAL.containsMatchIn(it) }.mapNotNull { dernierNombre(it) }
        val total = candidats.lastOrNull()
        // Articles : lignes avec du texte et un montant à la fin
        val articles = lignes.filter { !A_IGNORER.containsMatchIn(it) && !DATE.containsMatchIn(it) }.mapNotNull { l ->
            val n = NOMBRE.findAll(l).lastOrNull() ?: return@mapNotNull null
            val montant = montant(n.value) ?: return@mapNotNull null
            val libelle = l.substring(0, n.range.first).trim(' ', '.', ':', '-', '*', 'x', 'X').trim()
            if (libelle.count { it.isLetter() } >= 3 && n.range.last >= l.length - 2 && montant in 25..10_000_000) Article(libelle.take(60), montant) else null
        }
        // Marchand : première ligne parmi les 4 premières qui ressemble à un nom (surtout des lettres)
        val marchand = lignes.take(4).firstOrNull { l -> l.count { it.isLetter() } >= 3 && l.count { it.isDigit() } <= 3 && !A_IGNORER.containsMatchIn(l) }?.let(::capitaliser)
        return Resultat(marchand, date, total, articles)
    }

    private fun dernierNombre(ligne: String): Long? = NOMBRE.findAll(ligne).lastOrNull()?.let { montant(it.value) }

    /** « SUPERMARCHE  DU CENTRE » → « Supermarche Du Centre » */
    private fun capitaliser(s: String) = s.lowercase().split(Regex("\\s+")).joinToString(" ") { m -> m.replaceFirstChar { it.uppercase() } }
}
