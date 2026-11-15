package org.shuyah.personnelmaison

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * « MA TÉLÉ » : projeter des vidéos sur un téléviseur depuis ce téléphone, par SAWALI (`/api/famille/tv/…`).
 *
 * Règles du propriétaire :
 *  - SEUL LE DÉTENTEUR DE L'APPAREIL lance sa projection : les ordres partent avec l'identité de CE téléphone (compte « Ma famille » et jeton secret, gardés chiffrés) ;
 *  - ABONNEMENT NÉCESSAIRE, prix selon le nombre d'appareils du foyer et de postes TV (téléviseurs) ; la télé est appairée avec le code affiché par sa page /tv.
 *
 * Pour un développeur WinDev : comme `Agenda.kt`, c'est une « collection de procédures » qui appellent des services web ; les fonctions pures en bas (libellés, code, durée)
 * sont testables sur ordinateur sans téléphone.
 */
object Tele {
    private const val BASE = "https://api.sawalismartsystems.com/api/famille/tv"
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()

    /** Réponse brute du serveur ; `message()` donne le « detail » de SAWALI (ou, pour un refus d'abonnement, son message lisible). */
    data class Reponse(val code: Int, val corps: String) {
        val ok get() = code in 200..299
        val abonnementRequis get() = code == 402
        /** Refus 403 « approbation_requise » : l'appareil doit d'abord demander l'accès à SAWALI. */
        val approbationRequise get() = code == 403 && corps.contains("approbation_requise")
        fun message(): String {
            val brut = Regex("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(corps)?.groupValues?.get(1)
                ?: Regex("\"detail\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(corps)?.groupValues?.get(1) ?: return messageHttp(code)
            return brut.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\").ifBlank { messageHttp(code) }
        }
    }
    data class Formule(val id: String, val nom: String, val prix: Int, val jours: Int, val appareils: Int, val postesTv: Int, val description: String)
    data class Abonnement(val actif: Boolean, val role: String, val formule: String, val joursRestants: Int, val titulaire: String, val appareilsCouverts: List<String>,
                          val appareils: Int, val postesTv: Int, val postesUtilises: Int, val formules: List<Formule>,
                          val approbation: String, val approbationRequise: Boolean, val postesEffectifs: Int, val postesEffectifsUtilises: Int)
    data class Ecran(val id: String, val nom: String, val enLigne: Boolean, val lecture: String, val videoId: String, val position: Double, val duree: Double, val erreur: String, val deplacee: Boolean = false)
    data class Video(val id: String, val titre: String, val description: String, val octets: Long)

    fun messageHttp(code: Int) = when (code) {
        401 -> "ce téléphone n'a pas de compte « Ma famille » : créez-le d'abord dans Ma famille"
        402 -> "abonnement requis"
        403 -> "accès refusé (seul le détenteur de l'appareil, et avec l'approbation de SAWALI)"
        404 -> "introuvable (code de télé expiré ? relancez la page /tv sur la télé)"
        409 -> "limite de l'abonnement atteinte"
        503 -> "réseau indisponible"
        else -> "erreur $code"
    }

    /** Appel hors écran : l'identité du téléphone (compte famille + jeton) accompagne chaque demande. */
    fun appeler(c: Context, methode: String, chemin: String, corps: String? = null): Reponse {
        val p = ClientFamille.prefs(c)
        val requete = Request.Builder().url(BASE + chemin)
            .header("X-Famille-Compte", p.getString("compte_id", "") ?: "").header("X-Famille-Jeton", p.getString("jeton", "") ?: "")
            .method(methode, if (methode == "GET" || methode == "DELETE") null else (corps ?: "{}").toRequestBody(JSON)).build()
        return try { http.newCall(requete).execute().use { Reponse(it.code, it.body?.string() ?: "") } }
        catch (e: Exception) { Reponse(503, "{\"detail\":\"réseau indisponible\"}") }
    }

    fun abonnement(r: Reponse): Abonnement? = try {
        val o = JSONObject(r.corps); val f = o.optJSONArray("formules"); val lim = o.optJSONObject("limites"); val couv = o.optJSONArray("appareils_couverts")
        Abonnement(o.optBoolean("actif"), o.optString("role"), o.optString("formule"), o.optInt("jours_restants"), o.optString("titulaire"),
            (0 until (couv?.length() ?: 0)).map { couv!!.getString(it) }, lim?.optInt("appareils") ?: 0, lim?.optInt("postes_tv") ?: 0, o.optInt("postes_utilises"),
            (0 until (f?.length() ?: 0)).map { i -> f!!.getJSONObject(i).let { x -> val l = x.optJSONObject("limites")
                Formule(x.getString("id"), x.optString("nom"), x.optInt("prix"), x.optInt("duree_jours", 30), l?.optInt("appareils") ?: 1, l?.optInt("postes_tv") ?: 1, x.optString("description")) } },
            o.optString("approbation", "aucune"), o.optBoolean("approbation_requise", true), o.optJSONObject("effectif")?.optInt("postes_tv", 1) ?: 1, o.optJSONObject("effectif")?.optInt("postes_utilises") ?: 0)
    } catch (e: Exception) { null }

    fun ecrans(r: Reponse): List<Ecran> = try {
        val t = JSONObject(r.corps).getJSONArray("ecrans")
        (0 until t.length()).map { i -> t.getJSONObject(i).let { x -> val e = x.optJSONObject("etat")
            Ecran(x.getString("id"), x.optString("nom"), x.optBoolean("en_ligne"), e?.optString("lecture") ?: "arret", e?.optString("video_id") ?: "", e?.optDouble("position", 0.0) ?: 0.0,
                e?.optDouble("duree", 0.0) ?: 0.0, e?.optString("erreur") ?: "", x.optBoolean("deplacee")) } }
    } catch (e: Exception) { emptyList() }

    fun videos(r: Reponse): List<Video> = try {
        val t = JSONObject(r.corps).getJSONArray("videos")
        (0 until t.length()).map { i -> t.getJSONObject(i).let { Video(it.getString("id"), it.optString("title"), it.optString("description"), it.optLong("size")) } }
    } catch (e: Exception) { emptyList() }

    /** Adresse de la page de paiement (PawaPay) et numéro du paiement, ou null. */
    fun paiement(r: Reponse): Pair<String, String>? = try { JSONObject(r.corps).let { it.getString("redirect_url") to it.getString("deposit_id") } } catch (e: Exception) { null }
    fun statutAchat(r: Reponse): String = try { JSONObject(r.corps).optString("statut") } catch (e: Exception) { "" }
    /** Défi anti-robots reçu de SAWALI : jeton signé + image (octets PNG décodés de « data:image/png;base64,… »). */
    class Defi(val jeton: String, val png: ByteArray)
    fun defi(r: Reponse): Defi? = try {
        val o = JSONObject(r.corps)
        Defi(o.getString("jeton"), android.util.Base64.decode(o.getString("image").substringAfter("base64,"), android.util.Base64.DEFAULT))
    } catch (e: Exception) { null }

    /** Réponse à la demande de code : adresse masquée où le code a été envoyé (ex. « aw•••@gmail.com »), ou null. */
    fun emailMasque(r: Reponse): String? = try { JSONObject(r.corps).getString("email_masque") } catch (e: Exception) { null }

    /** Détails du téléphone joints à la demande (aident SAWALI à juger de son authenticité) : jamais d'identifiant personnel (ni IMEI, ni numéro de série). */
    fun infosAppareil(c: Context): JSONObject {
        val m = c.resources.displayMetrics
        return JSONObject().put("marque", android.os.Build.MANUFACTURER).put("modele", android.os.Build.MODEL).put("android", android.os.Build.VERSION.RELEASE)
            .put("sdk", android.os.Build.VERSION.SDK_INT.toString()).put("langue", java.util.Locale.getDefault().toLanguageTag())
            .put("fuseau", java.util.TimeZone.getDefault().id).put("ecran", "${m.widthPixels}x${m.heightPixels}")
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("emulateur", estEmulateur(android.os.Build.FINGERPRINT, android.os.Build.MODEL, android.os.Build.PRODUCT, android.os.Build.HARDWARE))
    }

    /** Vrai si les marques techniques du téléphone sont celles d'un émulateur (pure, testable). */
    fun estEmulateur(empreinte: String, modele: String, produit: String, materiel: String): Boolean {
        val t = listOf(empreinte, modele, produit, materiel).joinToString(" ").lowercase()
        return listOf("generic", "emulator", "sdk_gphone", "ranchu", "goldfish", "vbox", "genymotion", "android sdk built for").any { it in t }
    }

    fun idEcran(r: Reponse): String? = try { JSONObject(r.corps).getJSONObject("ecran").getString("id") } catch (e: Exception) { null }

    // ------------------------------------------------------------------ fonctions pures (testées sans téléphone)
    /** Code affiché par la télé : majuscules, sans espace ni tiret (« abc-123 » → « ABC123 »). */
    fun codeNormalise(saisie: String) = saisie.uppercase().filter { it.isLetterOrDigit() }

    /** Un code de télé est exploitable à partir de 4 caractères (la télé en affiche 6). */
    fun codeValide(saisie: String) = codeNormalise(saisie).length in 4..12

    /** Numéro plausible : 8 à 15 chiffres (espaces, points et « + » tolérés). */
    fun telephoneValide(saisie: String) = saisie.filter { it.isDigit() }.length in 8..15

    /** Adresse e-mail plausible (SAWALI refait la vérification complète). */
    fun emailValide(saisie: String) = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$").matches(saisie.trim())

    /** Code reçu par e-mail : exactement 6 chiffres. */
    fun codeEmailValide(saisie: String) = saisie.trim().length == 6 && saisie.trim().all { it.isDigit() }

    /** « 2 appareils · 1 poste TV » : limites d'une formule en clair. */
    fun libelleLimites(appareils: Int, postesTv: Int) =
        "$appareils appareil${if (appareils > 1) "s" else ""} · $postesTv poste${if (postesTv > 1) "s" else ""} TV"

    /** « 4 700 F CFA » : prix lisible (espace insécable fin entre les milliers). */
    fun libellePrix(fcfa: Int) = "%,d".format(fcfa).replace(',', ' ') + " F CFA"

    /** « 2:05 » : durée ou position en minutes et secondes. */
    fun minutes(secondes: Double): String { val n = secondes.toLong().coerceAtLeast(0); return "${n / 60}:${(n % 60).toString().padStart(2, '0')}" }

    /** « 12,5 Mo » : taille d'une vidéo. */
    fun taille(octets: Long): String { val mo = octets / (1024.0 * 1024.0); return if (mo >= 10) "%.0f Mo".format(mo) else "%.1f Mo".format(mo).replace('.', ',') }

    /** Fraction (0 à 1) de la lecture déjà faite, pour la jauge. */
    fun avancement(position: Double, duree: Double) = if (duree <= 0.0) 0f else (position / duree).coerceIn(0.0, 1.0).toFloat()
}
