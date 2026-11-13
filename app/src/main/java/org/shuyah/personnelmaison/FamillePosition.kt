package org.shuyah.personnelmaison

import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * POSITION PARTAGÉE AVEC « MA FAMILLE » — logique pure (sans Android), testée par FamillePositionTest.
 *
 * Règles : le partage ne concerne que les proches cochés (liens vérifiés) ; par défaut la position n'est envoyée QUE lorsque la personne est SORTIE du
 * périmètre de son domicile (une dernière position « de retour à la maison » est envoyée une fois, puis plus rien) ; l'autre mode envoie en permanence.
 * La position est chiffrée de bout en bout avant l'envoi (voir ClientFamille).
 */
object FamillePosition {
    /** Un lieu : nom, centre (latitude, longitude) et rayon du périmètre en mètres. */
    data class Lieu(val nom: String, val lat: Double, val lon: Double, val rayonM: Int)

    /** Où est la personne : hors du périmètre du domicile ? dans quel lieu enregistré (Domicile, École…) ? */
    data class Etat(val dehors: Boolean, val lieu: String?)

    /** Position lue par le proche : coordonnées, précision (m), heure de la mesure, état. */
    data class Position(val lat: Double, val lon: Double, val precisionM: Int, val ms: Long, val dehors: Boolean, val lieu: String?)

    const val MODE_HORS_DOMICILE = "hors_domicile"
    const val MODE_TOUJOURS = "toujours"
    /** Rayons proposés pour le périmètre (liste finie → liste déroulante). */
    val RAYONS_M = listOf(50, 100, 200, 500, 1000)
    /** Lieux usuels proposés à l'enregistrement (« Autre… » pour un nom libre). */
    val LIEUX_USUELS = listOf("École", "Travail", "Marché", "Église / Mosquée", "Famille", "Clinique")
    /** Intervalle de suivi : toutes les minutes. */
    const val INTERVALLE_MS = 60_000L

    /** Distance en mètres entre deux points (formule de haversine). */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    /** État d'une position : `dehors` = hors du périmètre du domicile (sans domicile défini, on considère toujours « dehors ») ; `lieu` = premier lieu qui la contient. */
    fun etat(domicile: Lieu?, lieux: List<Lieu>, lat: Double, lon: Double): Etat {
        val dansDomicile = domicile != null && distanceM(lat, lon, domicile.lat, domicile.lon) <= domicile.rayonM
        val lieu = (listOfNotNull(domicile) + lieux).firstOrNull { distanceM(lat, lon, it.lat, it.lon) <= it.rayonM }?.nom
        return Etat(dehors = !dansDomicile, lieu = lieu)
    }

    /**
     * Faut-il envoyer cette position ? Mode « toujours » ou sans domicile : oui. Mode « hors du domicile » : oui quand la personne est dehors, et UNE fois
     * quand elle vient de rentrer (pour que ses proches voient qu'elle est arrivée) ; ensuite plus rien tant qu'elle reste à la maison.
     */
    fun doitEnvoyer(mode: String, domicile: Lieu?, dehorsPrecedent: Boolean?, etat: Etat): Boolean =
        mode == MODE_TOUJOURS || domicile == null || etat.dehors || dehorsPrecedent == true

    private fun echapper(s: String) = s.replace("\\", " ").replace("\"", "'")

    /** Corps (clair) de la position, avant chiffrement : JSON simple. */
    fun versTexte(lat: Double, lon: Double, precisionM: Int, ms: Long, etat: Etat): String =
        String.format(Locale.ROOT, "{\"lat\":%.6f,\"lon\":%.6f,\"prec\":%d,\"ts\":%d,\"dehors\":%b,\"lieu\":\"%s\"}", lat, lon, precisionM, ms, etat.dehors, echapper(etat.lieu.orEmpty()))

    /** Relit le texte clair d'une position ; null s'il est illisible. */
    fun depuisTexte(texte: String): Position? {
        fun nombre(cle: String) = Regex("\"$cle\":(-?[0-9.]+)").find(texte)?.groupValues?.get(1)
        return try {
            Position(nombre("lat")!!.toDouble(), nombre("lon")!!.toDouble(), nombre("prec")?.toDouble()?.toInt() ?: 0, nombre("ts")!!.toLong(),
                Regex("\"dehors\":(true|false)").find(texte)?.groupValues?.get(1) == "true", Regex("\"lieu\":\"([^\"]*)\"").find(texte)?.groupValues?.get(1)?.ifBlank { null })
        } catch (e: Exception) { null }
    }

    /** « à l'instant », « il y a 3 min », « il y a 2 h », « il y a 2 j ». */
    fun libelleAge(ms: Long, maintenant: Long): String {
        val min = ((maintenant - ms) / 60_000L).coerceAtLeast(0)
        return when { min < 1 -> "à l'instant"; min < 60 -> "il y a $min min"; min < 1440 -> "il y a ${min / 60} h"; else -> "il y a ${min / 1440} j" }
    }

    /** « À l'école », « Hors du périmètre du domicile », « À la maison ». */
    fun libelleEtat(p: Position): String = when { p.lieu != null && !p.dehors -> "À la maison"; p.lieu != null -> "À : ${p.lieu}"; p.dehors -> "Hors du périmètre du domicile"; else -> "À la maison" }

    /** Lieux ↔ texte (une ligne « nom|lat|lon|rayon » par lieu) pour les préférences chiffrées. */
    fun lieuxVersTexte(l: List<Lieu>) = l.joinToString("\n") { "${it.nom.replace("|", " ").replace("\n", " ")}|${it.lat}|${it.lon}|${it.rayonM}" }
    fun lieuxDepuisTexte(t: String?): List<Lieu> = (t ?: "").lines().mapNotNull { ligne ->
        val p = ligne.split("|"); if (p.size != 4) null else try { Lieu(p[0], p[1].toDouble(), p[2].toDouble(), p[3].toInt()) } catch (e: Exception) { null }
    }
}
