package org.shuyah.personnelmaison

import java.time.LocalDate
import java.time.YearMonth

/** Listes finies du portefeuille (règle 10 : liste déroulante, « Autre… » pour une valeur libre). */
object Listes {
    val CATEGORIES_DEPENSE = listOf("Alimentation", "Courses ménagères", "Transport", "Santé", "Éducation", "Eau et électricité", "Loyer", "Salaires du personnel", "Communication", "Vêtements", "Loisirs")
    val CATEGORIES_RECETTE = listOf("Salaire", "Vente", "Loyer perçu", "Cadeau", "Remboursement", "Prime")
    val PORTEFEUILLES = listOf("Espèces", "Orange Money", "Moov Money", "Telecel Money", "Banque / carte")
    fun categories(genre: String) = if (genre == "RECETTE") CATEGORIES_RECETTE else CATEGORIES_DEPENSE
}

/** Totaux d'une période. */
data class Totaux(val recettes: Long, val depenses: Long) { val solde get() = recettes - depenses }

/** CALCULS PURS du portefeuille (statistiques de suivi), testés par BudgetTest. */
object CalculsBudget {
    fun totaux(ops: List<Operation>) = Totaux(ops.filter { it.genre == "RECETTE" }.sumOf { it.montant }, ops.filter { it.genre == "DEPENSE" }.sumOf { it.montant })

    fun duMois(ops: List<Operation>, mois: YearMonth) = ops.filter { it.date.startsWith(mois.toString()) }

    /** Montant par catégorie (« Sans catégorie » si vide), du plus grand au plus petit. */
    fun parCategorie(ops: List<Operation>, genre: String): List<Pair<String, Long>> =
        ops.filter { it.genre == genre }.groupBy { it.categorie.ifBlank { "Sans catégorie" } }.map { (k, v) -> k to v.sumOf { it.montant } }.sortedByDescending { it.second }

    /** Marchands (dépenses) ou sources (recettes) les plus importants : (nom, total, nombre d'opérations). */
    fun parTiers(ops: List<Operation>, genre: String, n: Int = 5): List<Triple<String, Long, Int>> =
        ops.filter { it.genre == genre }.groupBy { it.tiers.ifBlank { "Non précisé" } }.map { (k, v) -> Triple(k, v.sumOf { it.montant }, v.size) }.sortedByDescending { it.second }.take(n)

    /** Solde de chaque portefeuille (recettes − dépenses), sur toutes les opérations. */
    fun soldeParPortefeuille(ops: List<Operation>): List<Pair<String, Long>> =
        ops.groupBy { it.portefeuille.ifBlank { "Espèces" } }.map { (k, v) -> k to totaux(v).solde }.sortedBy { it.first }

    /** Les `n` derniers mois (le plus ancien d'abord) avec leurs totaux : évolution sur la période. */
    fun serieMois(ops: List<Operation>, fin: YearMonth, n: Int = 6): List<Pair<YearMonth, Totaux>> =
        (n - 1 downTo 0).map { fin.minusMonths(it.toLong()) }.map { m -> m to totaux(duMois(ops, m)) }

    /** Dépense moyenne par jour : sur les jours écoulés du mois (jusqu'à `aujourdhui` pour le mois en cours). */
    fun depenseMoyenneParJour(ops: List<Operation>, mois: YearMonth, aujourdhui: LocalDate): Long {
        val jours = if (mois == YearMonth.from(aujourdhui)) aujourdhui.dayOfMonth else mois.lengthOfMonth()
        return if (jours <= 0) 0 else totaux(duMois(ops, mois)).depenses / jours
    }

    /** Somme des lignes de détail. */
    fun sommeLignes(lignes: List<LigneOperation>) = lignes.sumOf { it.montant }

    /** Pourcentage entier d'une part dans un total (0 si total nul). */
    fun pourcent(part: Long, total: Long): Int = if (total <= 0) 0 else (part * 100 / total).toInt()

    /** Bilan du mois à partager. */
    fun bilanTexte(mois: YearMonth, ops: List<Operation>): String {
        val t = totaux(duMois(ops, mois))
        val sb = StringBuilder("Portefeuille · ${Calculs.moisLong(mois)}\nRecettes : ${Calculs.francs(t.recettes)}\nDépenses : ${Calculs.francs(t.depenses)}\nSolde : ${Calculs.francs(t.solde)}")
        val cats = parCategorie(duMois(ops, mois), "DEPENSE")
        if (cats.isNotEmpty()) sb.append("\n\nDépenses par catégorie :").also { cats.forEach { (c, m) -> sb.append("\n• $c : ${Calculs.francs(m)} (${pourcent(m, t.depenses)} %)") } }
        return sb.toString()
    }
}
