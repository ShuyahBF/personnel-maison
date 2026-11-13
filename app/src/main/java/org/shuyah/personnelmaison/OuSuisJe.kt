package org.shuyah.personnelmaison

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * « OÙ SUIS-JE ? » (SAWALI lot 388, « Vous êtes ici ») : le visiteur est près d'une balise FIXE posée dans un lieu (rayon, couloir, salle). Ce téléphone écoute les balises,
 * prend la plus proche (signal le plus fort) dont SAWALI connaît le lieu, puis ouvre la PAGE PUBLIQUE de ce lieu dans le navigateur.
 *
 * Pour un développeur WinDev : c'est l'inverse du « récepteur de badges ». Ici la balise est fixe et c'est le téléphone qui bouge. Le téléphone ne décide de rien : il demande à
 * SAWALI « quel lieu porte cette balise ? » (GET /api/lieux-balises-resolution?balise=…), SAWALI répond {code, nom}, et la page du lieu est  <site>/lieu/<code>?src=app.
 * Les lieux (nom, texte, liens, balise) sont créés par l'administrateur ou le SUPERVISEUR dans SAWALI (rubrique « Lieux — Vous êtes ici »).
 * Cette partie est « logique pure » (testée par OuSuisJeTest) ; l'écran est dans EcranOuSuisJe.
 */
object OuSuisJe {
    const val SITE_PAR_DEFAUT = "https://sawalismartsystems.com"     // site où vivent les pages publiques /lieu/<code>
    const val FRAICHEUR_MS = 6_000L          // une balise non entendue depuis plus longtemps n'est plus « proche »
    const val SIGNAL_MINI = -90.0            // en dessous (dBm), la balise est trop loin pour dire « vous êtes ici »
    const val MARGE_DB = 6.0                 // anti-rebond : on ne change de lieu que si l'autre est nettement plus fort
    const val MAX_CANDIDATS = 4              // balises interrogées au plus par tour (les plus fortes)
    const val MEMOIRE_INCONNUE_MS = 120_000L // une balise inconnue de SAWALI n'est pas redemandée avant 2 minutes

    data class Lieu(val code: String, val nom: String)

    /** Balises entendues récemment et assez fort, de la plus forte à la plus faible, limitées à `max` : ce sont elles qu'on identifie auprès de SAWALI. */
    fun candidats(vues: List<ScanBalises.Vue>, maintenantMs: Long, max: Int = MAX_CANDIDATS): List<Pair<String, Double>> =
        vues.filter { maintenantMs - it.derniereVue <= FRAICHEUR_MS && it.rssiLisse >= SIGNAL_MINI }
            .sortedByDescending { it.rssiLisse }.take(max).map { it.cle to it.rssiLisse }

    /**
     * Le lieu le plus proche : parmi les balises entendues (clé, signal), celles que SAWALI a reconnues (`connus`), la plus forte l'emporte. Anti-rebond : si on était déjà
     * dans `actuel` et qu'il est toujours entendu, on y reste tant que la meilleure balise n'est pas plus forte de MARGE_DB.
     */
    fun plusProche(entendues: List<Pair<String, Double>>, connus: Map<String, Lieu>, actuel: Lieu?): Lieu? {
        val reconnues = entendues.filter { it.first in connus }
        val meilleure = reconnues.maxByOrNull { it.second } ?: return null
        if (actuel != null) {
            val signalActuel = reconnues.firstOrNull { connus[it.first] == actuel }?.second
            if (signalActuel != null && meilleure.second - signalActuel < MARGE_DB) return actuel
        }
        return connus[meilleure.first]
    }

    /** Adresse de la page publique du lieu ; `src=app` la fait compter comme visite « par l'application ». */
    fun adressePage(site: String, code: String) = site.trim().trimEnd('/').ifBlank { SITE_PAR_DEFAUT } + "/lieu/" + code + "?src=app"

    /** Adresse de résolution balise → lieu (la balise est encodée : elle contient des « : »). */
    fun adresseResolution(api: String, balise: String) =
        PresenceRecepteur.adresse(api) + "/api/lieux-balises-resolution?balise=" + URLEncoder.encode(balise, "UTF-8")

    /** Lit la réponse JSON {"code":"abc234","nom":"Rayon frais"} de SAWALI (sans bibliothèque JSON : testable partout). Null si la réponse n'est pas celle d'un lieu. */
    fun lireLieu(json: String): Lieu? {
        val code = Regex("\"code\"\\s*:\\s*\"([a-z0-9]{4,12})\"").find(json)?.groupValues?.get(1) ?: return null
        val brut = Regex("\"nom\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)?.groupValues?.get(1) ?: return null
        return Lieu(code, desechapper(brut))
    }

    /** Défait les échappements JSON usuels (\" \\ \/ \n \t et \uXXXX). */
    fun desechapper(s: String): String {
        val sb = StringBuilder(); var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch != '\\' || i + 1 >= s.length) { sb.append(ch); i++; continue }
            when (val n = s[i + 1]) {
                'n' -> { sb.append('\n'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'u' -> if (i + 6 <= s.length && s.substring(i + 2, i + 6).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { sb.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 6 } else { sb.append(n); i += 2 }
                else -> { sb.append(n); i += 2 }                       // \" \\ \/ : le caractère lui-même
            }
        }
        return sb.toString()
    }

    /** Résultat d'une demande à SAWALI : lieu reconnu, balise inconnue (404), ou erreur réseau (on réessaiera au prochain tour). */
    sealed class Reponse { data class Trouve(val lieu: Lieu) : Reponse(); object Inconnue : Reponse(); data class Echec(val message: String) : Reponse() }

    /** Demande à SAWALI quel lieu porte cette balise. Jamais d'exception. */
    fun resoudre(api: String, balise: String): Reponse = try {
        val cx = URL(adresseResolution(api, balise)).openConnection() as HttpURLConnection
        cx.connectTimeout = 6000; cx.readTimeout = 6000
        val code = cx.responseCode
        val corps = if (code == 200) cx.inputStream.bufferedReader().use { it.readText() } else ""
        cx.disconnect()
        when (code) { 200 -> lireLieu(corps)?.let { Reponse.Trouve(it) } ?: Reponse.Echec("réponse inattendue"); 404 -> Reponse.Inconnue; else -> Reponse.Echec("réponse $code") }
    } catch (e: Exception) { Reponse.Echec("réseau indisponible : " + (e.message ?: e.javaClass.simpleName)) }
}
