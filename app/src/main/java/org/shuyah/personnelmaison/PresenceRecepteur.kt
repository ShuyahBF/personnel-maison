package org.shuyah.personnelmaison

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/**
 * RÉCEPTEUR DE BADGES (présence par balises, SAWALI lot 387) : ce téléphone, posé dans une pièce, écoute les balises Bluetooth (badges portés par les visiteurs) et envoie
 * toutes les 5 secondes à SAWALI les badges entendus et leur signal. SAWALI en déduit qui est dans quelle zone. Le téléphone ne décide de rien : il n'est qu'un récepteur.
 *
 * Contrat d'échange v1 (le même pour tout autre récepteur, Windows ou boîtier) :
 *   POST <adresse>/api/presence-balises/v1/detections   en-têtes X-Recepteur: <site>:<récepteur>, X-Cle: <clé>
 *   corps {"detections":[{"badge":"ib:<uuid>:<major>:<minor>","rssi":-67}, …]}
 * Cette partie est « logique pure » (testée par PresenceRecepteurTest) ; le service Android est dans ServiceRecepteur.
 */
object PresenceRecepteur {
    const val URL_PAR_DEFAUT = "https://api.sawalismartsystems.com"
    const val INTERVALLE_MS = 5_000L
    const val BATTEMENT_MS = 30_000L        // envoi à vide au moins toutes les 30 s : SAWALI sait que le récepteur est vivant
    const val MAX_PAR_ENVOI = 200           // plafond du contrat v1

    /** Seuls ces formats sont des BADGES : on n'envoie jamais les téléphones, écouteurs ou montres qui passent. */
    val FORMATS_ACCEPTES = setOf("iBeacon", "Eddystone-UID", "Estimote")
    private val BADGE_VALIDE = Regex("^[A-Za-z0-9:_\\-.]{3,80}$")

    data class Detection(val badge: String, val rssi: Int)

    /** Badges entendus depuis `depuisMs` : format reconnu, identifiant valide, un seul signal par badge (le plus récent lissé), au plus MAX_PAR_ENVOI. */
    fun detections(vues: List<ScanBalises.Vue>, depuisMs: Long): List<Detection> =
        vues.filter { it.annonce.format in FORMATS_ACCEPTES && it.derniereVue >= depuisMs && BADGE_VALIDE.matches(it.cle) }
            .map { Detection(it.cle, it.rssiLisse.toInt().coerceIn(-127, 0)) }
            .take(MAX_PAR_ENVOI)

    /** Corps JSON du contrat v1 (les identifiants ne contiennent que des caractères sûrs : aucun échappement nécessaire). */
    fun corpsJson(d: List<Detection>): String = "{\"detections\":[" + d.joinToString(",") { "{\"badge\":\"${it.badge}\",\"rssi\":${it.rssi}}" } + "]}"

    /** Adresse du serveur nettoyée (sans barre finale) ; vide = adresse par défaut. */
    fun adresse(brut: String): String = brut.trim().trimEnd('/').ifBlank { URL_PAR_DEFAUT }

    /** Les trois réglages sont-ils complets ? (adresse facultative) */
    fun configure(identifiant: String, cle: String) = identifiant.trim().contains(":") && cle.trim().length >= 8

    /** Résultat lisible d'un code HTTP renvoyé par SAWALI. */
    fun messageHttp(code: Int): String = when (code) {
        200 -> "envoi réussi"
        401 -> "identifiant ou clé refusés par SAWALI : vérifiez-les (clé montrée une seule fois à la création)"
        422 -> "envoi mal formé"
        in 500..599 -> "serveur SAWALI momentanément indisponible"
        else -> "réponse inattendue ($code)"
    }

    /** Envoie les détections ; rend (réussi, message). Jamais d'exception : le service réessaie au prochain tour. */
    fun envoyer(adresse: String, identifiant: String, cle: String, detections: List<Detection>): Pair<Boolean, String> = try {
        val cx = URL(adresse(adresse) + "/api/presence-balises/v1/detections").openConnection() as HttpURLConnection
        cx.requestMethod = "POST"; cx.connectTimeout = 8000; cx.readTimeout = 8000; cx.doOutput = true
        cx.setRequestProperty("Content-Type", "application/json"); cx.setRequestProperty("X-Recepteur", identifiant.trim()); cx.setRequestProperty("X-Cle", cle.trim())
        cx.outputStream.use { it.write(corpsJson(detections).toByteArray(Charsets.UTF_8)) }
        val code = cx.responseCode
        cx.disconnect()
        (code == 200) to messageHttp(code)
    } catch (e: Exception) { false to "réseau indisponible : " + (e.message ?: e.javaClass.simpleName) }

    /** Statut affiché à l'écran (mis à jour par le service). */
    object Statut {
        @Volatile var envois = 0
        @Volatile var badgesDernierEnvoi = 0
        @Volatile var dernierOk: Boolean? = null
        @Volatile var dernierMessage = "pas encore d'envoi"
        @Volatile var dernierEnvoiMs = 0L
    }

    // --- Réglages (préférences chiffrées du téléphone : la clé est un secret) ---
    private fun p(c: Context) = ClientFamille.prefs(c)
    fun url(c: Context) = p(c).getString("recepteur_url", URL_PAR_DEFAUT) ?: URL_PAR_DEFAUT
    fun identifiant(c: Context) = p(c).getString("recepteur_id", "") ?: ""
    fun cle(c: Context) = p(c).getString("recepteur_cle", "") ?: ""
    fun enregistrer(c: Context, url: String, identifiant: String, cle: String) =
        p(c).edit().putString("recepteur_url", adresse(url)).putString("recepteur_id", identifiant.trim()).putString("recepteur_cle", cle.trim()).apply()
    fun actif(c: Context) = p(c).getBoolean("recepteur_actif", false)
    fun actif(c: Context, v: Boolean) = p(c).edit().putBoolean("recepteur_actif", v).apply()
}
