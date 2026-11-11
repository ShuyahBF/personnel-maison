package org.shuyah.personnelmaison

/**
 * LECTURE DES NOMS SUR UNE PIÈCE D'IDENTITÉ : à partir du texte brut que l'OCR a lu, repère les lignes « Nom » et « Prénom(s) »
 * (cartes bilingues du type « Nom / Surname », « Prénom(s) / Given name(s) »). Ce n'est qu'une SUGGESTION : l'utilisateur corrige dans les champs.
 * Logique pure (sans Android), testée par AnalyseIdentiteTest.
 */
object AnalyseIdentite {
    data class Suggestion(val nom: String?, val prenoms: String?)

    private val ETIQUETTE_NOM = Regex("^(?:nom|surname|last\\s*name)(?:\\s*\\(s\\))?(?:\\s*/\\s*(?:surname|nom|last\\s*name))?\\s*[:\\-]?", RegexOption.IGNORE_CASE)
    private val ETIQUETTE_PRENOMS = Regex("^(?:pr[eé]noms?|given\\s*names?|first\\s*names?)(?:\\s*\\(s\\))?(?:\\s*/\\s*(?:given\\s*names?|pr[eé]noms?|first\\s*names?)(?:\\s*\\(s\\))?)?\\s*[:\\-]?", RegexOption.IGNORE_CASE)
    private val AUTRES_ETIQUETTES = Regex("^(?:date|n[ée]\\(?e?\\)?|lieu|sexe|taille|profession|cnib|n°|no\\.?|nationalit|signature|adresse|expire|d[ée]livr)", RegexOption.IGNORE_CASE)

    fun analyser(texte: String): Suggestion {
        val lignes = texte.lines().map { it.trim() }.filter { it.isNotEmpty() }
        fun valeur(etiquette: Regex): String? {
            for ((i, ligne) in lignes.withIndex()) {
                val m = etiquette.find(ligne) ?: continue
                val reste = ligne.substring(m.range.last + 1).trim(' ', ':', '/', '-')
                if (reste.length >= 2) return reste
                // valeur sur la ligne suivante, sauf si c'est une autre étiquette
                val suivante = lignes.getOrNull(i + 1) ?: continue
                if (!ETIQUETTE_NOM.containsMatchIn(suivante) && !ETIQUETTE_PRENOMS.containsMatchIn(suivante) && !AUTRES_ETIQUETTES.containsMatchIn(suivante)) return suivante
            }
            return null
        }
        return Suggestion(valeur(ETIQUETTE_NOM)?.let(::nettoyer), valeur(ETIQUETTE_PRENOMS)?.let(::nettoyer))
    }

    /** Retire les caractères parasites et met en forme : « OUEDRAOGO » reste en majuscules (nom), « awa marie » devient « Awa Marie ». */
    private fun nettoyer(s: String): String = s.replace(Regex("[^\\p{L}'\\- ]"), "").trim().replace(Regex("\\s+"), " ")
}
