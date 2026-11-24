package org.shuyah.personnelmaison

/**
 * FUSION DE DEUX APPAREILS — moteur PUR (version 1.67, lot 2 de la refonte « synchronisation automatique », demande du propriétaire).
 *
 * Principe (pour un développeur WinDev) : on compare, fiche par fiche, ce que contient CE téléphone et ce que contient l'AUTRE (sa sauvegarde en ligne). Chaque fiche porte un
 * identifiant unique mondial (`uid`) et la date de sa dernière modification (`maj`), tenus par SuiviModifs (lot 1). Règle : « le plus récent gagne », fiche par fiche.
 *   - fiche présente d'un seul côté            → ajoutée de l'autre côté (union : rien n'est perdu) ;
 *   - fiche des deux côtés, versions différentes → la plus récente l'emporte ;
 *   - fiche SUPPRIMÉE d'un côté (pierre tombale) → supprimée de l'autre côté, SAUF si elle a été modifiée APRÈS la suppression (alors elle revient) ;
 *   - deux fiches « identiques » créées séparément (même personne + même jour de pointage, même horaire…) → ramenées à UNE seule (clé naturelle), la plus récente l'emporte.
 * Les liens entre fiches (le pointage d'une personne…) ne sont PAS des numéros locaux mais l'`uid` de la fiche parente : ils sont donc comparables d'un téléphone à l'autre.
 * Ce fichier ne touche à AUCUNE base de données : il calcule seulement un PLAN d'opérations (testé sans téléphone). L'application du plan est dans DepotFusion.kt.
 */
object FusionAppareils {
    /** Une fiche normalisée : `champs` = colonnes de la table SAUF `id` ; les colonnes de lien (clés étrangères) contiennent l'`uid` du parent (texte) ou null. */
    data class Fiche(val table: String, val uid: String, val maj: Long, val champs: Map<String, Any?>)
    /** Pierre tombale : la fiche `uid` de cette table a été supprimée à la date `maj`. */
    data class Tombe(val table: String, val uid: String, val maj: Long)
    /** Tout ce qui est suivi sur un appareil. */
    class Instantane(val fiches: List<Fiche>, val tombes: List<Tombe>)

    sealed class Operation {
        data class Inserer(val fiche: Fiche) : Operation()
        data class Mettre(val fiche: Fiche) : Operation()                                  // met à jour la fiche locale portant ce uid
        data class Supprimer(val table: String, val uid: String) : Operation()
        data class AdopterUid(val table: String, val ancien: String, val nouveau: String) : Operation()   // deux fiches identiques : on garde le plus petit identifiant des deux
    }
    /** Résultat : opérations à appliquer ici, et compteurs lisibles pour l'aperçu montré avant de confirmer. */
    class Plan(val operations: List<Operation>, val details: List<String> = emptyList()) {
        val ajouts get() = operations.count { it is Operation.Inserer }
        val modifications get() = operations.count { it is Operation.Mettre }
        val suppressions get() = operations.count { it is Operation.Supprimer }
        val rapprochements get() = operations.count { it is Operation.AdopterUid }
        val vide get() = ajouts == 0 && modifications == 0 && suppressions == 0 && rapprochements == 0
        /** Détail par table : « 3 pointages ajoutés, 1 paiement modifié… » (libellés lisibles). */
        fun resume(): String {
            val l = mutableListOf<String>()
            fun compter(nom: String, f: (Operation) -> String?) {
                val par = operations.mapNotNull(f).groupingBy { it }.eachCount()
                if (par.isNotEmpty()) l.add(par.entries.joinToString(", ") { "${it.value} ${LIBELLES[it.key] ?: it.key}" } + " " + nom)
            }
            compter("ajouté(s)") { (it as? Operation.Inserer)?.fiche?.table }
            compter("modifié(s)") { (it as? Operation.Mettre)?.fiche?.table }
            compter("supprimé(s)") { (it as? Operation.Supprimer)?.table }
            if (rapprochements > 0) l.add("$rapprochements fiche(s) identique(s) rapprochée(s)")
            return if (l.isEmpty()) "Aucune différence : les deux appareils sont à jour." else l.joinToString(" ; ")
        }
    }

    /**
     * GARDE-FOU de la synchronisation AUTOMATIQUE (1.71) : un plan n'est appliqué sans confirmation que s'il supprime très peu (3 fiches au plus ET moins d'un cinquième des fiches de ce téléphone).
     * Au-delà, la personne confirme elle-même, avec le détail sous les yeux (fusion manuelle). Fonction pure, testée.
     */
    fun planSur(plan: Plan, nombreDeFiches: Int): Boolean = plan.suppressions <= 3 && plan.suppressions * 5 <= maxOf(nombreDeFiches, 5)

    /** Tables fusionnées, PARENTS AVANT ENFANTS (un lien ne peut viser qu'une fiche déjà présente). Les pièces jointes (fichiers) ne sont pas fusionnées dans ce lot. */
    val ORDRE = listOf("societes", "personnels", "medicaments", "operations", "mesures_enregistrees", "profil", "pointages", "notes", "evaluations", "journal_pointages",
        "horaires_personnel", "numeros", "paiements", "versements", "taches", "lignes_operation", "prises_medicament")
    private val LIBELLES = mapOf("societes" to "société(s)", "personnels" to "personne(s)", "medicaments" to "médicament(s)", "operations" to "opération(s) du portefeuille",
        "mesures_enregistrees" to "mesure(s)", "profil" to "profil", "pointages" to "pointage(s)", "notes" to "note(s)", "evaluations" to "évaluation(s)", "journal_pointages" to "ligne(s) du journal des pointages",
        "horaires_personnel" to "horaire(s)", "numeros" to "numéro(s)", "paiements" to "paiement(s) (bonus, retenues, avances)", "versements" to "versement(s) de salaire", "taches" to "tâche(s)",
        "lignes_operation" to "ligne(s) d'opération", "prises_medicament" to "prise(s) de médicament")

    /** Colonne de lien → table parente. `numeros` est spécial : son parent dépend de la colonne `proprietaire` (PERSONNEL ou SOCIETE). */
    val LIENS: Map<String, Map<String, String>> = mapOf(
        "personnels" to mapOf("societeId" to "societes"), "pointages" to mapOf("personnelId" to "personnels"), "notes" to mapOf("personnelId" to "personnels"),
        "evaluations" to mapOf("societeId" to "societes"), "journal_pointages" to mapOf("personnelId" to "personnels"), "horaires_personnel" to mapOf("personnelId" to "personnels"),
        "paiements" to mapOf("personnelId" to "personnels"), "versements" to mapOf("personnelId" to "personnels"), "taches" to mapOf("personnelId" to "personnels"),
        "lignes_operation" to mapOf("operationId" to "operations"), "prises_medicament" to mapOf("medicamentId" to "medicaments"))
    /** Parent d'un lien de `numeros` (colonne `proprietaireId`). */
    fun parentNumero(champs: Map<String, Any?>): String? = when (champs["proprietaire"]) { "PERSONNEL" -> "personnels"; "SOCIETE" -> "societes"; else -> null }
    /** Colonnes JAMAIS écrasées par une fusion et vidées sur une fiche venue de l'autre appareil : ce sont des chemins de fichiers qui n'existent que sur le téléphone d'origine. */
    val PROTEGEES: Map<String, Set<String>> = mapOf("personnels" to setOf("pieceChemin"))

    /** Clé « naturelle » : ce qui rend deux fiches créées séparément identiques. null = pas de clé (seul l'identifiant unique compte). */
    fun cleNaturelle(f: Fiche): String? {
        val colonnes = when (f.table) {
            "pointages" -> listOf("personnelId", "date"); "horaires_personnel" -> listOf("personnelId", "jour"); "prises_medicament" -> listOf("medicamentId", "prevuMs")
            "profil" -> return "profil"
            else -> return null
        }
        return colonnes.joinToString("|") { "${f.champs[it]}" }
    }

    /**
     * Calcule le plan pour amener CE téléphone (`local`) au niveau de l'AUTRE (`pair`) : « le plus récent gagne ». Fonction pure et déterministe.
     * Convergence : fusionner A avec B puis B avec A donne le même contenu (les égalités de date se départagent par l'identifiant unique).
     */
    fun calculer(local: Instantane, pair: Instantane): Plan {
        val localParUid = local.fiches.associateBy { it.table to it.uid }
        val localParCle = local.fiches.mapNotNull { f -> cleNaturelle(f)?.let { (f.table to it) to f } }.toMap()
        val tombesLocales = local.tombes.associate { (it.table to it.uid) to it.maj }
        val ops = mutableListOf<Operation>()
        val details = mutableListOf<String>()                                               // lignes lisibles montrées dans l'aperçu : « pointage 2026-11-24 : arrivee 06:30 → 06:25 »
        val clesNaturellesPair = pair.fiches.mapNotNull { f -> cleNaturelle(f)?.let { f.table to it } }.toSet()
        val deja = HashSet<Pair<String, String>>()                                          // uids déjà traités dans ce plan (deux fiches de l'autre appareil ne se télescopent pas)

        for (table in ORDRE) for (f in pair.fiches.filter { it.table == table }) {
            val cle = f.table to f.uid
            val ici = localParUid[cle]
            if (ici != null) {                                                                 // même fiche des deux côtés : la plus récente l'emporte
                if (plusRecent(f, ici)) { ops.add(Operation.Mettre(f)); details.add("modification : " + decrire(f) + " (" + differences(ici, f) + ")") }
                continue
            }
            // Même fiche LOGIQUE créée ou remplacée des deux côtés (pointage de la même personne le même jour…) : la clé naturelle passe AVANT la pierre tombale
            // (l'ancienne identité d'une fiche remplacée est en pierre tombale alors que la fiche existe toujours) ; on les ramène à UNE, le plus petit identifiant est gardé.
            val nat = cleNaturelle(f)?.let { localParCle[f.table to it] }
            if (nat != null) {
                if (cle !in deja) {
                    val garde = minOf(nat.uid, f.uid)
                    if (garde != nat.uid) ops.add(Operation.AdopterUid(f.table, nat.uid, garde))
                    if (plusRecent(f, nat)) { ops.add(Operation.Mettre(f.copy(uid = garde))); details.add("modification : " + decrire(f) + " (" + differences(nat, f) + ")") }
                    deja.add(f.table to nat.uid)
                }
                continue
            }
            val tombe = tombesLocales[cle]                                                     // supprimée ici : elle ne revient que si elle a été modifiée ailleurs APRÈS la suppression
            if (tombe == null || f.maj > tombe) { ops.add(Operation.Inserer(f)); details.add("ajout : " + decrire(f)) }
        }
        // Suppressions faites sur l'autre appareil : on les applique ici sauf si la fiche d'ici est plus récente que la suppression
        for (t in pair.tombes) {
            val ici = localParUid[t.table to t.uid] ?: continue
            // Une fiche « remplacée » (pointage enregistré de nouveau) laisse l'ANCIENNE identité en pierre tombale alors que la fiche existe toujours, sous une nouvelle identité, sur l'autre appareil :
            // ce n'est PAS une vraie suppression (clé naturelle encore présente de l'autre côté) : on ne supprime rien.
            val nat = cleNaturelle(ici)
            if (nat != null && (t.table to nat) in clesNaturellesPair) continue
            if (ici.maj <= t.maj) { ops.add(Operation.Supprimer(t.table, t.uid)); details.add("suppression : " + decrire(ici)) }
        }
        // Ordre d'application : adoptions d'identifiants, ajouts et modifications parents d'abord (déjà dans l'ordre), puis suppressions ENFANTS d'abord
        val (suppr, autres) = ops.partition { it is Operation.Supprimer }
        val rang = ORDRE.withIndex().associate { it.value to it.index }
        return Plan(autres.sortedBy { if (it is Operation.AdopterUid) -1 else 0 } + suppr.sortedByDescending { rang[(it as Operation.Supprimer).table] ?: 0 }, details)
    }

    /** Description courte et lisible d'une fiche pour l'aperçu (date d'un pointage, nom d'une personne…). */
    private fun decrire(f: Fiche): String {
        val repere = f.champs["date"] ?: f.champs["nom"] ?: f.champs["mois"] ?: f.champs["libelle"] ?: f.champs["tiers"] ?: ""
        return "${f.table}${if (repere != "") " · $repere" else ""}"
    }
    /** Champs qui diffèrent entre la fiche d'ici et celle de l'autre appareil (3 au plus ; les heures en minutes sont écrites HH:MM). */
    private fun differences(ici: Fiche, autre: Fiche): String {
        fun joli(k: String, v: Any?) = if (v is Long && k in setOf("arrivee", "depart", "debutMin", "finMin")) "%02d:%02d".format(v / 60, v % 60) else if (v == null) "vide" else v.toString().take(30)
        val protegees = PROTEGEES[autre.table] ?: emptySet()
        val d = autre.champs.keys.filter { it !in protegees && ici.champs[it] != autre.champs[it] }.take(3).map { "$it ${joli(it, ici.champs[it])} → ${joli(it, autre.champs[it])}" }
        return if (d.isEmpty()) "contenu différent" else d.joinToString(", ")
    }

    /** La fiche de l'autre appareil est-elle STRICTEMENT plus récente, ou de même date mais de contenu différent avec le plus petit identifiant (départage stable) ? */
    private fun plusRecent(autre: Fiche, ici: Fiche): Boolean {
        val champsIdentiques = autre.champs.filterKeys { k -> k !in (PROTEGEES[autre.table] ?: emptySet()) } == ici.champs.filterKeys { k -> k !in (PROTEGEES[ici.table] ?: emptySet()) }
        if (champsIdentiques) return false
        if (autre.maj != ici.maj) return autre.maj > ici.maj
        // Même date mais contenu différent : on départage de façon STABLE (même résultat sur les deux appareils) : identifiant le plus petit, puis contenu le plus grand
        return if (autre.uid != ici.uid) autre.uid < ici.uid else autre.champs.toString() > ici.champs.toString()
    }
}
