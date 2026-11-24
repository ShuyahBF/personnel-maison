package org.shuyah.personnelmaison

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.shuyah.personnelmaison.FusionAppareils.Fiche
import org.shuyah.personnelmaison.FusionAppareils.Instantane
import org.shuyah.personnelmaison.FusionAppareils.Operation
import org.shuyah.personnelmaison.FusionAppareils.Tombe

/** Fusion de deux appareils (1.67) : « le plus récent gagne » fiche par fiche, suppressions, clés naturelles, convergence. Aucun téléphone ni base : un petit magasin en mémoire rejoue les plans. */
class FusionAppareilsTest {
    /** Un « appareil » en mémoire : mêmes règles d'application que DepotFusion (SQL), sans SQL. */
    class Appareil(fiches: List<Fiche> = emptyList(), tombes: List<Tombe> = emptyList()) {
        val fiches = fiches.associateBy { it.table to it.uid }.toMutableMap()
        val tombes = tombes.associateBy { it.table to it.uid }.toMutableMap()
        fun instantane() = Instantane(fiches.values.toList(), tombes.values.toList())
        fun supprimer(t: String, uid: String, maj: Long) { fiches.remove(t to uid); tombes[t to uid] = Tombe(t, uid, maj) }
        fun fusionnerAvec(autre: Appareil): FusionAppareils.Plan {
            val plan = FusionAppareils.calculer(instantane(), autre.instantane())
            for (o in plan.operations) when (o) {
                is Operation.AdopterUid -> fiches.remove(o.table to o.ancien)?.let { fiches[o.table to o.nouveau] = it.copy(uid = o.nouveau) }
                is Operation.Inserer -> { fiches[o.fiche.table to o.fiche.uid] = o.fiche.copy(champs = o.fiche.champs.mapValues { (k, v) -> if (k in (FusionAppareils.PROTEGEES[o.fiche.table] ?: emptySet())) null else v }); tombes.remove(o.fiche.table to o.fiche.uid) }
                is Operation.Mettre -> { val ici = fiches[o.fiche.table to o.fiche.uid]
                    val protegees = FusionAppareils.PROTEGEES[o.fiche.table] ?: emptySet()
                    fiches[o.fiche.table to o.fiche.uid] = o.fiche.copy(champs = o.fiche.champs.mapValues { (k, v) -> if (k in protegees) ici?.champs?.get(k) else v }) }
                is Operation.Supprimer -> { fiches.remove(o.table to o.uid); tombes[o.table to o.uid] = Tombe(o.table, o.uid, Long.MAX_VALUE) }
            }
            return plan
        }
        fun contenu() = fiches.values.map { Triple(it.table, it.uid, it.champs) }.toSet()
    }
    private fun perso(uid: String, nom: String, maj: Long) = Fiche("personnels", uid, maj, mapOf("nom" to nom, "societeId" to null, "pieceChemin" to null))
    private fun pointage(uid: String, personne: String, date: String, depart: Int, maj: Long) = Fiche("pointages", uid, maj, mapOf("personnelId" to personne, "date" to date, "depart" to depart))
    private fun converge(a: Appareil, b: Appareil) { a.fusionnerAvec(b); b.fusionnerAvec(a); a.fusionnerAvec(b); assertEquals(a.contenu(), b.contenu()) }

    @Test fun unionDesAjoutsDesDeuxCotes() {
        val a = Appareil(listOf(perso("p1", "OUEDRAOGO", 10))); val b = Appareil(listOf(perso("p2", "SAWADOGO", 12)))
        converge(a, b)
        assertEquals(setOf("p1", "p2"), a.fiches.keys.map { it.second }.toSet())
    }
    @Test fun lePlusRecentGagneDesDeuxCotes() {
        val a = Appareil(listOf(perso("p1", "ANCIEN NOM", 10))); val b = Appareil(listOf(perso("p1", "NOUVEAU NOM", 20)))
        converge(a, b); assertEquals("NOUVEAU NOM", a.fiches[("personnels" to "p1")]!!.champs["nom"])
        val c = Appareil(listOf(perso("p1", "RECENT", 30))); val d = Appareil(listOf(perso("p1", "VIEUX", 5)))
        converge(c, d); assertEquals("RECENT", d.fiches[("personnels" to "p1")]!!.champs["nom"])
    }
    @Test fun uneSuppressionSePropageSaufSiLaFicheAEteModifieeApres() {
        val a = Appareil(listOf(perso("p1", "X", 5))); val b = Appareil(listOf(perso("p1", "X", 5)))
        a.supprimer("personnels", "p1", 10); converge(a, b)
        assertTrue(b.fiches.isEmpty())                                                      // supprimée partout
        val c = Appareil(); c.supprimer("personnels", "p1", 10); val d = Appareil(listOf(perso("p1", "MODIFIEE APRES", 20)))
        converge(c, d); assertEquals(1, c.fiches.size)                                      // modifiée APRÈS la suppression : elle revient
    }
    @Test fun deuxPointagesDuMemeJourSontRamenesAUnSeul() {
        val p = perso("p1", "X", 1)
        val a = Appareil(listOf(p, pointage("b", "p1", "2026-11-24", 17 * 60, 5))); val b = Appareil(listOf(p, pointage("a", "p1", "2026-11-24", 18 * 60, 9)))
        converge(a, b)
        val pts = a.fiches.values.filter { it.table == "pointages" }
        assertEquals(1, pts.size); assertEquals("a", pts[0].uid); assertEquals(18 * 60, pts[0].champs["depart"])      // le plus petit identifiant est gardé, le plus récent l'emporte
    }
    @Test fun lePlanAjouteLesParentsAvantLesEnfants() {
        val b = Appareil(listOf(pointage("pt", "p1", "2026-11-24", 1, 3), perso("p1", "X", 2)))
        val plan = FusionAppareils.calculer(Instantane(emptyList(), emptyList()), b.instantane())
        val ordre = plan.operations.filterIsInstance<Operation.Inserer>().map { it.fiche.table }
        assertTrue(ordre.indexOf("personnels") < ordre.indexOf("pointages"))
        assertEquals(2, plan.ajouts)
    }
    @Test fun lesCheminsDeFichiersNeVoyagentPas() {
        val a = Appareil(listOf(perso("p1", "X", 1).let { it.copy(champs = it.champs + ("pieceChemin" to "/data/local.enc")) }))
        val b = Appareil(listOf(perso("p1", "X NOUVEAU", 9).let { it.copy(champs = it.champs + ("pieceChemin" to "/data/autre.enc")) }))
        a.fusionnerAvec(b)
        assertEquals("X NOUVEAU", a.fiches[("personnels" to "p1")]!!.champs["nom"]); assertEquals("/data/local.enc", a.fiches[("personnels" to "p1")]!!.champs["pieceChemin"])   // le chemin local est gardé
        val c = Appareil(); c.fusionnerAvec(b); assertEquals(null, c.fiches[("personnels" to "p1")]!!.champs["pieceChemin"])                                                          // une fiche nouvelle n'emporte pas un chemin qui n'existe pas ici
    }
    @Test fun rienAFaireQuandToutEstIdentique() {
        val a = Appareil(listOf(perso("p1", "X", 5))); val b = Appareil(listOf(perso("p1", "X", 5)))
        val plan = a.fusionnerAvec(b); assertTrue(plan.vide); assertEquals("Aucune différence : les deux appareils sont à jour.", plan.resume())
    }
    @Test fun resumeLisible() {
        val b = Appareil(listOf(perso("p1", "X", 5), pointage("pt", "p1", "2026-11-24", 1, 6)))
        val r = FusionAppareils.calculer(Instantane(emptyList(), emptyList()), b.instantane()).resume()
        assertTrue(r.contains("1 personne(s)") && r.contains("1 pointage(s)") && r.contains("ajouté(s)"))
    }
}

/** 1.68 : cas du terrain (« aucune différence » à tort) : mêmes identifiant et date mais contenus différents, adoptions comptées. */
class FusionAppareilsDiagnosticTest {
    private fun perso(uid: String, nom: String, maj: Long) = FusionAppareils.Fiche("personnels", uid, maj, mapOf("nom" to nom, "societeId" to null, "pieceChemin" to null))
    @Test fun memeDateMemeIdentifiantContenuDifferentEstTranche() {
        val a = FusionAppareilsTest.Appareil(listOf(perso("p1", "A", 10))); val b = FusionAppareilsTest.Appareil(listOf(perso("p1", "B", 10)))
        assertTrue(!a.fusionnerAvec(b).vide || !b.fusionnerAvec(a).vide)                    // l'un des deux sens agit : plus de « aucune différence » à tort
        a.fusionnerAvec(b); b.fusionnerAvec(a); a.fusionnerAvec(b)
        assertEquals(a.contenu(), b.contenu())                                              // et les deux convergent
    }
    @Test fun unRapprochementNEstPasUnePlanVide() {
        val pt = { uid: String -> FusionAppareils.Fiche("pointages", uid, 5, mapOf("personnelId" to "p", "date" to "2026-11-24", "depart" to 1)) }
        val plan = FusionAppareils.calculer(FusionAppareils.Instantane(listOf(pt("b")), emptyList()), FusionAppareils.Instantane(listOf(pt("a")), emptyList()))
        assertTrue(!plan.vide); assertTrue(plan.resume().contains("rapprochée"))
    }
}
