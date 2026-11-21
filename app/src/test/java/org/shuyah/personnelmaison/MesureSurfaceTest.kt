package org.shuyah.personnelmaison

import org.junit.Assert.*
import org.junit.Test
import org.shuyah.personnelmaison.MesureSurface.P2
import org.shuyah.personnelmaison.MesureSurface.P3

/** Mesure de surfaces avec la caméra (v1.45) : géométrie pure — polygones, transformation de perspective de la photo, points de la réalité augmentée. */
class MesureSurfaceTest {
    private val a4 = MesureSurface.REFERENCES[0]

    @Test fun aire_et_perimetre_d_un_rectangle_et_d_un_triangle() {
        val rect = listOf(P2(0.0, 0.0), P2(4.0, 0.0), P2(4.0, 3.0), P2(0.0, 3.0))
        assertEquals(12.0, MesureSurface.aire(rect), 1e-9)
        assertEquals(12.0, MesureSurface.aire(rect.reversed()), 1e-9)                 // le sens du tour ne change rien
        assertEquals(14.0, MesureSurface.perimetre(rect), 1e-9)
        assertEquals(11.0, MesureSurface.perimetre(rect, ferme = false), 1e-9)
        assertEquals(6.0, MesureSurface.aire(listOf(P2(0.0, 0.0), P2(4.0, 0.0), P2(0.0, 3.0))), 1e-9)
        assertEquals(0.0, MesureSurface.aire(listOf(P2(0.0, 0.0), P2(1.0, 1.0))), 0.0) // moins de 3 points
    }

    @Test fun piece_en_L() {
        // Pièce en L : carré de 4 m moins un carré de 2 m dans un angle = 12 m²
        val l = listOf(P2(0.0, 0.0), P2(4.0, 0.0), P2(4.0, 2.0), P2(2.0, 2.0), P2(2.0, 4.0), P2(0.0, 4.0))
        assertEquals(12.0, MesureSurface.aire(l), 1e-9)
        assertEquals(16.0, MesureSurface.perimetre(l), 1e-9)
    }

    @Test fun les_coins_touches_dans_le_desordre_sont_remis_en_tour() {
        val coins = listOf(P2(100.0, 100.0), P2(300.0, 250.0), P2(300.0, 100.0), P2(100.0, 250.0))   // touchés « en croix »
        assertEquals(listOf(P2(100.0, 100.0), P2(300.0, 100.0), P2(300.0, 250.0), P2(100.0, 250.0)), MesureSurface.ordonnerCoins(coins))
    }

    /** Fabrique une « photo » : projette des points réels (mètres) avec une vraie perspective (caméra inclinée), pour vérifier qu'on retrouve les mètres. */
    private fun photographier(p: P2): P2 {
        // Perspective arbitraire mais réaliste : mise à l'échelle, rotation légère, fuite vers le haut de l'image
        val x = 420.0 * p.x + 35.0 * p.y + 600.0
        val y = -20.0 * p.x + 300.0 * p.y + 900.0
        val w = 0.02 * p.x + 0.11 * p.y + 1.0
        return P2(x / w, y / w)
    }

    @Test fun mesure_sur_photo_avec_une_feuille_A4_en_perspective() {
        // Feuille A4 posée « en paysage » sur le sol (grand côté le long de x), à 0,5 m de l'origine
        val feuille = listOf(P2(0.5, 0.5), P2(0.797, 0.5), P2(0.797, 0.71), P2(0.5, 0.71)).map(::photographier)
        // Pièce de 3 m × 2 m
        val piece = listOf(P2(0.0, 0.0), P2(3.0, 0.0), P2(3.0, 2.0), P2(0.0, 2.0)).map(::photographier)
        val r = MesureSurface.mesurerSurPhoto(feuille.shuffled(java.util.Random(7)), a4, piece)!!
        assertEquals(6.0, r.aire, 1e-6)
        assertEquals(10.0, r.perimetre, 1e-6)
        assertEquals(listOf(3.0, 2.0, 3.0, 2.0), r.cotes.map { Math.round(it * 1000) / 1000.0 })
    }

    @Test fun tourner_la_reference_ne_change_pas_la_surface_mais_change_les_cotes() {
        // Inverser petit et grand côté de la référence étire un axe et rétrécit l'autre d'autant : la SURFACE reste la même (elle est donc fiable même si
        // l'orientation de la feuille a été mal devinée) ; seules les LONGUEURS des côtés changent, d'où le bouton « Tourner la référence ».
        val feuille = listOf(P2(0.0, 0.0), P2(0.21, 0.0), P2(0.21, 0.297), P2(0.0, 0.297)).map(::photographier)
        val piece = listOf(P2(0.0, 0.0), P2(3.0, 0.0), P2(3.0, 2.0), P2(0.0, 2.0)).map(::photographier)
        val a = MesureSurface.mesurerSurPhoto(feuille, a4, piece)!!
        val b = MesureSurface.mesurerSurPhoto(feuille, a4, piece, tourner = true)!!
        assertEquals(6.0, a.aire, 1e-6); assertEquals(6.0, b.aire, 1e-6)
        assertNotEquals(a.cotes[0], b.cotes[0], 1e-3)
        assertTrue("l'une des deux lectures donne le bon côté (3 m)", Math.abs(a.cotes[0] - 3.0) < 1e-6 || Math.abs(b.cotes[0] - 3.0) < 1e-6)
    }

    @Test fun une_distance_seule_se_mesure_avec_deux_points() {
        val feuille = listOf(P2(0.0, 0.0), P2(0.297, 0.0), P2(0.297, 0.21), P2(0.0, 0.21)).map(::photographier)
        val r = MesureSurface.mesurerSurPhoto(feuille, a4, listOf(P2(0.0, 0.0), P2(1.5, 2.0)).map(::photographier))!!
        assertEquals(2.5, r.cotes.single(), 1e-6)
        assertEquals(0.0, r.aire, 0.0)
    }

    @Test fun reference_inexploitable() {
        val alignes = listOf(P2(0.0, 0.0), P2(1.0, 1.0), P2(2.0, 2.0), P2(3.0, 3.0))
        assertNull(MesureSurface.mesurerSurPhoto(alignes, a4, listOf(P2(0.0, 0.0), P2(5.0, 5.0), P2(9.0, 1.0))))
        assertNull(MesureSurface.mesurerSurPhoto(alignes.take(3), a4, alignes))
    }

    @Test fun aire_dans_l_espace_sol_mur_et_plan_incline() {
        val sol = listOf(P3(0.0, 0.0, 0.0), P3(4.0, 0.0, 0.0), P3(4.0, 0.0, 3.0), P3(0.0, 0.0, 3.0))          // horizontal (y = hauteur dans ARCore)
        val mur = listOf(P3(0.0, 0.0, 1.0), P3(2.0, 0.0, 1.0), P3(2.0, 2.5, 1.0), P3(0.0, 2.5, 1.0))          // vertical
        val incline = listOf(P3(0.0, 0.0, 0.0), P3(1.0, 0.0, 0.0), P3(1.0, 1.0, 1.0), P3(0.0, 1.0, 1.0))      // 1 × √2
        assertEquals(12.0, MesureSurface.aireEspace(sol), 1e-9)
        assertEquals(5.0, MesureSurface.aireEspace(mur), 1e-9)
        assertEquals(Math.sqrt(2.0), MesureSurface.aireEspace(incline), 1e-9)
        assertEquals(14.0, MesureSurface.perimetreEspace(sol), 1e-9)
        assertEquals(5.0, MesureSurface.distance(P3(0.0, 0.0, 0.0), P3(3.0, 0.0, 4.0)), 1e-9)
    }

    @Test fun position_a_l_ecran_d_un_point_de_l_espace() {
        // Caméra à l'origine regardant vers −z (convention OpenGL) : vue = identité ; projection simple de champ 90°
        val identite = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }
        val proj = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = -1f; it[11] = -1f; it[14] = -0.2f }
        val centre = MesureSurface.versEcran(P3(0.0, 0.0, -2.0), identite, proj, 1000, 2000)!!
        assertEquals(500.0, centre.x, 1e-6); assertEquals(1000.0, centre.y, 1e-6)
        val hautDroite = MesureSurface.versEcran(P3(1.0, 1.0, -2.0), identite, proj, 1000, 2000)!!
        assertEquals(750.0, hautDroite.x, 1e-6); assertEquals(500.0, hautDroite.y, 1e-6)      // y de l'espace vers le haut = y de l'écran plus petit
        assertNull(MesureSurface.versEcran(P3(0.0, 0.0, 2.0), identite, proj, 1000, 2000))    // derrière la caméra
    }

    @Test fun textes_et_saisie() {
        assertEquals("12,35 m²", MesureSurface.texteAire(12.345))
        assertEquals("0,062 m² (624 cm²)", MesureSurface.texteAire(0.0624))
        assertEquals("3,42 m", MesureSurface.texteLongueur(3.42))
        assertEquals("42,5 cm", MesureSurface.texteLongueur(0.425))
        assertEquals(0.297, MesureSurface.lireCentimetres(" 29,7 ")!!, 1e-9)
        assertNull(MesureSurface.lireCentimetres("abc")); assertNull(MesureSurface.lireCentimetres("0")); assertNull(MesureSurface.lireCentimetres("5000"))
        val r = MesureSurface.referenceLibre("80", "40")!!
        assertEquals(0.4, r.petit, 1e-9); assertEquals(0.8, r.grand, 1e-9)
        assertNull(MesureSurface.referenceLibre("80", ""))
    }

    @Test fun etalonnage_corrige_les_longueurs_et_les_surfaces() {
        // Constat du 21/11/2026 : 140 cm au mètre ruban, 136 cm mesurés par le téléphone
        val f = MesureSurface.facteurEtalonnage(1.40, 1.36)!!
        assertEquals(1.0294, f, 1e-4)
        assertEquals(1.40, MesureSurface.longueurEtalonnee(1.36, f), 1e-9)
        assertEquals(4.0 * f * f, MesureSurface.aireEtalonnee(4.0, f), 1e-9)               // une surface suit le CARRÉ du facteur
        assertEquals("+2,9 %", MesureSurface.textePourcentage(f))
        assertEquals("−3,1 %", MesureSurface.textePourcentage(0.969))
        assertNull(MesureSurface.facteurEtalonnage(1.40, 0.0))                              // mesure nulle
        assertNull(MesureSurface.facteurEtalonnage(-1.0, 1.0))
        assertNull(MesureSurface.facteurEtalonnage(2.0, 1.0))                               // +100 % : saisie ou points faux, refusé
    }

    @Test fun cotes_d_un_contour_de_l_espace() {
        val carre = listOf(P3(0.0, 0.0, 0.0), P3(1.0, 0.0, 0.0), P3(1.0, 0.0, 2.0), P3(0.0, 0.0, 2.0))
        assertEquals(listOf(1.0, 2.0, 1.0, 2.0), MesureSurface.cotesEspace(carre, ferme = true))
        assertEquals(listOf(1.0, 2.0, 1.0), MesureSurface.cotesEspace(carre, ferme = false))
        assertEquals(listOf(1.0), MesureSurface.cotesEspace(carre.take(2), ferme = true))   // 2 points : une seule longueur
        assertTrue(MesureSurface.cotesEspace(carre.take(1), ferme = true).isEmpty())
    }

    @Test fun points_et_longueurs_se_rangent_en_texte_et_se_relisent() {
        val pts = listOf(P3(0.1234, -2.5, 3.0), P3(1.0, 0.0, -0.0001))
        val relus = MesureSurface.texteVersPoints(MesureSurface.pointsVersTexte(pts))
        assertEquals(2, relus.size)
        assertEquals(0.1234, relus[0].x, 1e-9); assertEquals(-2.5, relus[0].y, 1e-9); assertEquals(-0.0001, relus[1].z, 1e-9)
        assertEquals(listOf(0.85, 1.2), MesureSurface.texteVersNombres(MesureSurface.nombresVersTexte(listOf(0.85, 1.2))))
        assertTrue(MesureSurface.texteVersPoints("n'importe quoi;1,2").isEmpty())             // morceaux illisibles ignorés
        assertTrue(MesureSurface.texteVersPoints("").isEmpty())
    }

    @Test fun unites_d_affichage_et_saisie() {
        assertEquals("1 160 mm", MesureSurface.texteLongueurUnite(1.16, MesureSurface.Unite.MM))
        assertEquals("45,7 po", MesureSurface.texteLongueurUnite(1.16, MesureSurface.Unite.POUCES))
        assertEquals("116,0 cm", MesureSurface.texteLongueurUnite(1.16, MesureSurface.Unite.CM))
        assertEquals("42,5 cm", MesureSurface.texteLongueurUnite(0.425, MesureSurface.Unite.AUTO))
        assertEquals("75,0 mm".replace("75,0", "75,0"), MesureSurface.texteLongueurUnite(0.075, MesureSurface.Unite.MM))   // sous 100 mm : une décimale
        assertEquals("1 162,5 po²", MesureSurface.texteAireUnite(0.75, MesureSurface.Unite.POUCES))
        assertEquals("7 500,0 cm²", MesureSurface.texteAireUnite(0.75, MesureSurface.Unite.CM))
        assertEquals(1.16, MesureSurface.lireLongueur("1160", MesureSurface.Unite.MM)!!, 1e-9)
        assertEquals(1.1684, MesureSurface.lireLongueur("46", MesureSurface.Unite.POUCES)!!, 1e-9)
        assertEquals(1.16, MesureSurface.lireLongueur("116", MesureSurface.Unite.AUTO)!!, 1e-9)       // AUTO : saisie en centimètres
        assertNull(MesureSurface.lireLongueur("abc", MesureSurface.Unite.MM)); assertNull(MesureSurface.lireLongueur("-3", MesureSurface.Unite.CM))
        assertEquals(MesureSurface.Unite.POUCES, MesureSurface.uniteDepuisNom("POUCES")); assertEquals(MesureSurface.Unite.AUTO, MesureSurface.uniteDepuisNom("n'importe quoi"))
    }

    @Test fun television_de_52_pouces_comparee_au_carton() {
        // Écran 16:9 de 52 pouces : 115,1 × 64,7 cm
        val (l, h) = MesureSurface.ecranTelevision(52.0)
        assertEquals(1.1511, l, 1e-3); assertEquals(0.6475, h, 1e-3)
        // Contour mesuré (cadre compris) : 4 côtés presque égaux deux à deux ; diagonale ≈ 53 pouces
        val r = MesureSurface.rectangleDesCotes(listOf(1.172, 0.664, 1.168, 0.668))!!
        assertEquals(1.170, r.grand, 1e-9); assertEquals(0.666, r.petit, 1e-9)
        assertEquals(53.0, r.diagonale / MesureSurface.POUCE_M, 0.1)
        assertNull(MesureSurface.rectangleDesCotes(listOf(1.0, 2.0, 3.0)))
        // Dimensions du carton : 1 160 × 670 mm ; mesuré 1 170 × 666 mm
        val e = MesureSurface.comparer(1.160, r.grand)
        assertEquals(0.010, e.ecartM, 1e-9); assertEquals("Conforme", e.verdict)                       // +0,9 %
        assertEquals("+10,0 mm (+0,9 %)", MesureSurface.texteEcart(e, MesureSurface.Unite.MM))
        assertEquals("Proche (dans la tolérance d'une mesure en réalité augmentée)", MesureSurface.comparer(1.0, 1.02).verdict)
        assertEquals("À vérifier", MesureSurface.comparer(1.0, 1.06).verdict)
        assertEquals("−6,0 mm (−0,6 %)", MesureSurface.texteEcart(MesureSurface.comparer(1.0, 0.994), MesureSurface.Unite.MM))
    }

    @Test fun coherence_de_la_diagonale_annoncee() {
        assertTrue(MesureSurface.coherenceDiagonale(53.1, 52.0).startsWith("Cohérent"))
        assertTrue(MesureSurface.coherenceDiagonale(51.5, 52.0).startsWith("Cohérent"))             // à moins de 1 po sous l'annonce : mesure imprécise tolérée
        assertTrue(MesureSurface.coherenceDiagonale(48.0, 52.0).startsWith("Plus petit"))
        assertTrue(MesureSurface.coherenceDiagonale(58.0, 52.0).startsWith("Plus grand"))
    }

    @Test fun objectif_se_range_en_texte_et_se_relit() {
        val o = MesureSurface.Objectif(true, MesureSurface.Unite.MM, listOf(1.16, 0.67), 0.7772, 1.34)
        val t = MesureSurface.objectifVersTexte(o)
        val r = MesureSurface.texteVersObjectif(t)
        assertTrue(r.verifier); assertEquals(MesureSurface.Unite.MM, r.unite); assertEquals(listOf(1.16, 0.67), r.dimensionsM)
        assertEquals(0.7772, r.surfaceM2!!, 1e-6); assertEquals(1.34, r.diagonaleM!!, 1e-6)
        assertFalse(MesureSurface.texteVersObjectif(MesureSurface.objectifVersTexte(MesureSurface.Objectif())).verifier)         // « Mesurer »
        assertFalse(MesureSurface.texteVersObjectif("").verifier); assertFalse(MesureSurface.texteVersObjectif("n'importe quoi").verifier)
        assertEquals(1.0, MesureSurface.lireSurface("1", MesureSurface.Unite.AUTO)!!, 1e-9)
        assertEquals(0.01, MesureSurface.lireSurface("100", MesureSurface.Unite.CM)!!, 1e-9)         // 100 cm² = 0,01 m²
        assertNull(MesureSurface.lireSurface("0", MesureSurface.Unite.MM))
    }

    @Test fun ecarts_mesure_moins_objectif_pour_un_rectangle() {
        // Objectif : 1 160 × 670 mm ; mesuré (4 côtés) 1 172, 664, 1 168, 668 → grand 1 170, petit 666
        val o = MesureSurface.Objectif(true, MesureSurface.Unite.MM, listOf(0.67, 1.16))              // saisis dans le désordre : le grand est apparié au grand
        val l = MesureSurface.ecartsObjectif(o, listOf(1.172, 0.664, 1.168, 0.668), null)
        assertEquals(2, l.size)
        assertEquals("Grand côté", l[0].libelle); assertEquals(0.010, l[0].ecart.ecartM, 1e-9); assertEquals("Conforme", l[0].ecart.verdict)
        assertEquals("Petit côté", l[1].libelle); assertEquals(-0.004, l[1].ecart.ecartM, 1e-9)
        assertEquals("Grand côté : objectif 1 160 mm · mesuré 1 170 mm → +10,0 mm (+0,9 %) : Conforme", MesureSurface.texteLigneEcart(l[0], MesureSurface.Unite.MM))
    }

    @Test fun ecarts_longueur_unique_surface_et_diagonale() {
        val o1 = MesureSurface.Objectif(true, MesureSurface.Unite.CM, listOf(1.40))
        val l1 = MesureSurface.ecartsObjectif(o1, listOf(1.36), null)                                   // mètre ruban : 140 attendu, 136 mesuré
        assertEquals("Longueur", l1[0].libelle); assertEquals(-0.04, l1[0].ecart.ecartM, 1e-9); assertEquals("À vérifier", l1[0].ecart.verdict)
        assertEquals("Longueur : objectif 140,0 cm · mesuré 136,0 cm → −4,0 cm (−2,9 %) : À vérifier", MesureSurface.texteLigneEcart(l1[0], MesureSurface.Unite.CM))
        val o2 = MesureSurface.Objectif(true, MesureSurface.Unite.AUTO, emptyList(), 12.0, null)
        val l2 = MesureSurface.ecartsObjectif(o2, listOf(3.0, 4.0, 3.0, 4.0), 12.28)
        assertEquals(1, l2.size); assertEquals("Surface", l2[0].libelle); assertEquals(0.28, l2[0].ecart.ecartM, 1e-9)
        assertEquals("Surface : objectif 12,00 m² · mesuré 12,28 m² → +0,280 m² (+2,3 %) : Proche (dans la tolérance d'une mesure en réalité augmentée)", MesureSurface.texteLigneEcart(l2[0], MesureSurface.Unite.AUTO))
        val o3 = MesureSurface.Objectif(true, MesureSurface.Unite.POUCES, emptyList(), null, 52 * MesureSurface.POUCE_M)
        val l3 = MesureSurface.ecartsObjectif(o3, listOf(1.17, 0.666, 1.17, 0.666), null)
        assertEquals("Diagonale", l3[0].libelle); assertEquals(53.0, l3[0].mesure / MesureSurface.POUCE_M, 0.1)
        assertTrue(MesureSurface.ecartsObjectif(MesureSurface.Objectif(), listOf(1.0), 1.0).isEmpty())                // « Mesurer » : aucun écart
        assertTrue(MesureSurface.ecartsObjectif(o1, emptyList(), null).isEmpty())                       // rien de mesuré
    }

    /** Caméra à l'origine regardant vers −z, projection en perspective (colonne par colonne), image 1000 × 2000. */
    private val vueId = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
    private fun perspective(fovY: Double = 1.0, ratio: Double = 0.5, near: Double = 0.1, far: Double = 100.0): FloatArray {
        val f = 1.0 / Math.tan(fovY / 2)
        return floatArrayOf((f / ratio).toFloat(), 0f, 0f, 0f, 0f, f.toFloat(), 0f, 0f, 0f, 0f, ((far + near) / (near - far)).toFloat(), -1f, 0f, 0f, (2 * far * near / (near - far)).toFloat(), 0f)
    }

    @Test fun un_point_deplace_sur_l_image_figee_retrouve_sa_position_dans_l_espace() {
        val proj = perspective()
        val contour = listOf(P3(-0.5, -0.3, -2.0), P3(0.5, -0.3, -2.0), P3(0.5, 0.3, -2.0), P3(-0.5, 0.3, -2.0))        // rectangle dans un plan frontal
        val pixel = MesureSurface.versEcran(contour[1], vueId, proj, 1000, 2000)!!
        // On touche exactement le pixel où se projette le point : on doit le retrouver
        val retrouve = MesureSurface.pointSousPixel(contour[1], contour, pixel.x, pixel.y, 1000, 2000, vueId, proj)!!
        assertEquals(0.5, retrouve.x, 1e-4); assertEquals(-0.3, retrouve.y, 1e-4); assertEquals(-2.0, retrouve.z, 1e-4)
        // On glisse le point de 30 pixels vers la droite : il reste dans le plan du contour (z = −2), seule sa position latérale change
        val deplace = MesureSurface.pointSousPixel(contour[1], contour, pixel.x + 30, pixel.y, 1000, 2000, vueId, proj)!!
        assertEquals(-2.0, deplace.z, 1e-4); assertTrue(deplace.x > 0.5)
        // Avec 2 points seulement : même profondeur que le point d'origine
        val deux = MesureSurface.pointSousPixel(contour[0], contour.take(2), pixel.x - 60, pixel.y, 1000, 2000, vueId, proj)!!
        assertEquals(-2.0, deux.z, 1e-4)
    }

    @Test fun inverse_de_matrice_et_normale_de_contour() {
        val m = DoubleArray(16) { if (it % 5 == 0) 2.0 else 0.0 }                       // 2 × identité
        val inv = MesureSurface.inverse4(m)!!
        assertEquals(0.5, inv[0], 1e-12); assertEquals(0.0, inv[1], 1e-12)
        assertNull(MesureSurface.inverse4(DoubleArray(16)))                                // matrice nulle : pas d'inverse
        val n = MesureSurface.normaleContour(listOf(P3(0.0, 0.0, 0.0), P3(1.0, 0.0, 0.0), P3(1.0, 1.0, 0.0), P3(0.0, 1.0, 0.0)))!!
        assertEquals(1.0, Math.abs(n.z), 1e-9)                                              // contour posé à plat : normale verticale
        assertNull(MesureSurface.normaleContour(listOf(P3(0.0, 0.0, 0.0), P3(1.0, 0.0, 0.0))))
    }
}
