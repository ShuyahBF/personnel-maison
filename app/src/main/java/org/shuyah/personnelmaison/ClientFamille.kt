package org.shuyah.personnelmaison

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * CLIENT DE « MA FAMILLE » : parle au serveur SAWALI (`/api/famille/…`) et chiffre / déchiffre les messages sur le téléphone.
 *
 * Pour un développeur WinDev : c'est l'équivalent d'une « collection de procédures » qui appellent des services web. L'identifiant du compte, le jeton
 * secret et la clé privée sont gardés CHIFFRÉS dans les préférences sécurisées du téléphone (AES-256, clé protégée par le système) ; ils ne sont jamais
 * affichés ni envoyés ailleurs. Le serveur ne reçoit que : le nom choisi, la clé publique, et des messages DÉJÀ chiffrés.
 */
object ClientFamille {
    private const val BASE = "https://api.sawalismartsystems.com/api/famille"
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()

    /** Erreur lisible par l'utilisateur (message du serveur ou « serveur injoignable »). */
    class Erreur(message: String) : Exception(message)

    /** Un lien avec un proche : l'état de vérification de chacun et le numéro de sécurité à comparer. */
    data class Lien(val id: String, val statut: String, val nom: String, val clePublique: String, val verifieParMoi: Boolean, val verifieParLui: Boolean, val numeroSecurite: String) {
        val verifie get() = statut == "verifie"
    }

    /** Un message déchiffré ; `texte` est null si le déchiffrement a échoué (clé ou message altéré). */
    data class Message(val seq: Long, val deMoi: Boolean, val texte: String?, val creeLe: String)

    internal fun prefs(c: Context) = EncryptedSharedPreferences.create(c, "famille_secrets",
        MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)

    fun inscrit(c: Context) = prefs(c).getString("compte_id", null) != null
    fun nom(c: Context) = prefs(c).getString("nom", "") ?: ""
    /** Ma clé publique (base64) et son empreinte (échangée par NFC). */
    fun maClePublique(c: Context) = prefs(c).getString("cle_publique", "") ?: ""
    fun monEmpreinte(c: Context) = FamilleCrypto.empreinte(maClePublique(c))

    /** Appel HTTP : rend le JSON de la réponse ou lève `Erreur` (message du serveur, ou « injoignable »). Jamais sur le fil principal. */
    private fun appel(c: Context, methode: String, chemin: String, corps: JSONObject? = null, authentifie: Boolean = true): JSONObject {
        val p = prefs(c)
        val rb = Request.Builder().url("$BASE$chemin")
        if (authentifie) rb.header("X-Famille-Compte", p.getString("compte_id", "") ?: "").header("X-Famille-Jeton", p.getString("jeton", "") ?: "")
        rb.method(methode, if (methode == "GET" || methode == "DELETE") null else (corps ?: JSONObject()).toString().toRequestBody(JSON))
        try {
            http.newCall(rb.build()).execute().use { r ->
                val texte = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw Erreur(try { JSONObject(texte).optString("detail").ifBlank { "Erreur ${r.code}" } } catch (e: Exception) { "Erreur ${r.code}" })
                return if (texte.isBlank()) JSONObject() else JSONObject(texte)
            }
        } catch (e: Erreur) { throw e
        } catch (e: Exception) { throw Erreur("Serveur injoignable : vérifiez la connexion Internet.") }
    }

    /** Crée le compte famille de ce téléphone (paire de clés + inscription). */
    suspend fun inscrire(c: Context, nom: String) = withContext(Dispatchers.IO) {
        val (publique, privee) = FamilleCrypto.genererPaire()
        val r = appel(c, "POST", "/inscription", JSONObject().put("nom", nom).put("cle_publique", publique), authentifie = false)
        prefs(c).edit().putString("compte_id", r.getString("compte_id")).putString("jeton", r.getString("jeton")).putString("nom", nom.trim())
            .putString("cle_publique", publique).putString("cle_privee", privee).apply()
    }

    /** Nouvelle invitation : le code « ABCD-EFGH » à communiquer au proche (valable 15 minutes, usage unique). */
    suspend fun inviter(c: Context): String = withContext(Dispatchers.IO) { appel(c, "POST", "/invitations").getString("code") }

    /** Accepte le code reçu d'un proche : crée le lien (à vérifier ensuite). */
    suspend fun accepter(c: Context, code: String): String = withContext(Dispatchers.IO) { appel(c, "POST", "/invitations/accepter", JSONObject().put("code", code)).getString("lien_id") }

    /** Mes liens, avec le numéro de sécurité de chacun. */
    suspend fun liens(c: Context): List<Lien> = withContext(Dispatchers.IO) {
        val maPublique = prefs(c).getString("cle_publique", "") ?: ""
        val tab = appel(c, "GET", "/liens").getJSONArray("liens")
        (0 until tab.length()).map { i ->
            val l = tab.getJSONObject(i); val autre = l.getJSONObject("correspondant")
            Lien(l.getString("lien_id"), l.getString("statut"), autre.getString("nom"), autre.getString("cle_publique"), l.getBoolean("verifie_par_moi"), l.getBoolean("verifie_par_lui"),
                FamilleCrypto.numeroSecurite(maPublique, autre.getString("cle_publique")))
        }
    }

    /** Je confirme avoir comparé le numéro de sécurité avec la personne. */
    suspend fun verifier(c: Context, lienId: String) = withContext(Dispatchers.IO) { appel(c, "POST", "/liens/$lienId/verifier"); Unit }

    /** Rompt le lien et efface les messages échangés. */
    suspend fun rompre(c: Context, lienId: String) = withContext(Dispatchers.IO) { appel(c, "DELETE", "/liens/$lienId"); Unit }

    private fun cle(c: Context, lien: Lien): ByteArray {
        val p = prefs(c)
        return FamilleCrypto.cleCommune(p.getString("cle_privee", "") ?: "", lien.clePublique, p.getString("cle_publique", "") ?: "")
    }

    /** Chiffre puis envoie un message à ce lien (lien vérifié seulement : sinon le serveur refuse). */
    suspend fun envoyer(c: Context, lien: Lien, texte: String) = withContext(Dispatchers.IO) {
        val (nonce, chiffre) = FamilleCrypto.chiffrer(cle(c, lien), texte.trim(), lien.id)
        appel(c, "POST", "/liens/${lien.id}/messages", JSONObject().put("nonce", nonce).put("chiffre", chiffre)); Unit
    }

    /** Messages reçus ET envoyés depuis le curseur `apres`, déchiffrés. */
    suspend fun messages(c: Context, lien: Lien, apres: Long = 0): List<Message> = withContext(Dispatchers.IO) {
        val moi = prefs(c).getString("compte_id", "") ?: ""
        val k = cle(c, lien)
        val tab = appel(c, "GET", "/liens/${lien.id}/messages?apres=$apres").getJSONArray("messages")
        (0 until tab.length()).map { i ->
            val m = tab.getJSONObject(i)
            Message(m.getLong("seq"), m.getString("de") == moi, FamilleCrypto.dechiffrer(k, m.getString("nonce"), m.getString("chiffre"), lien.id), m.getString("cree_le"))
        }
    }

    // ---- Position (chiffrée de bout en bout comme les messages ; l'AAD « pos: » empêche de rejouer un message comme une position) ----
    /** Une position reçue d'un proche : contenu déchiffré et heure de dépôt sur le serveur. */
    data class PositionVue(val position: FamillePosition.Position, val majLe: String)

    /** Dépose MA dernière position (texte clair `FamillePosition.versTexte`) pour ce lien : chiffrée sur le téléphone avant l'envoi. */
    suspend fun deposerPosition(c: Context, lien: Lien, texteClair: String) = withContext(Dispatchers.IO) {
        val (nonce, chiffre) = FamilleCrypto.chiffrer(cle(c, lien), texteClair, "pos:" + lien.id)
        appel(c, "PUT", "/liens/${lien.id}/position", JSONObject().put("nonce", nonce).put("chiffre", chiffre)); Unit
    }

    /** La dernière position de ce proche (null s'il ne partage pas ou plus). */
    suspend fun positionDe(c: Context, lien: Lien): PositionVue? = withContext(Dispatchers.IO) {
        val r = appel(c, "GET", "/liens/${lien.id}/position")
        val p = r.optJSONObject("position") ?: return@withContext null
        val texte = FamilleCrypto.dechiffrer(cle(c, lien), p.getString("nonce"), p.getString("chiffre"), "pos:" + lien.id) ?: return@withContext null
        FamillePosition.depuisTexte(texte)?.let { PositionVue(it, p.optString("maj_le")) }
    }

    /** J'arrête de partager avec ce proche : ma position est effacée du serveur. */
    suspend fun effacerPosition(c: Context, lienId: String) = withContext(Dispatchers.IO) { appel(c, "DELETE", "/liens/$lienId/position"); Unit }

    // ---- Événements partagés (rappels de médicaments pris ou oubliés, repas…) : chiffrés comme les messages, AAD « evt: » ----
    /** Un événement reçu : contenu déchiffré (null si illisible). */
    data class EvenementVu(val seq: Long, val type: String, val texte: String?, val creeLe: String)

    /** Envoie un événement chiffré à ce proche (lien vérifié seulement). */
    suspend fun deposerEvenement(c: Context, lien: Lien, type: String, texteClair: String) = withContext(Dispatchers.IO) {
        val (nonce, chiffre) = FamilleCrypto.chiffrer(cle(c, lien), texteClair, "evt:" + lien.id)
        appel(c, "POST", "/liens/${lien.id}/evenements", JSONObject().put("type", type).put("nonce", nonce).put("chiffre", chiffre)); Unit
    }

    /** Événements de ce proche reçus après le curseur `apres`, déchiffrés. */
    suspend fun evenements(c: Context, lien: Lien, apres: Long = 0): List<EvenementVu> = withContext(Dispatchers.IO) {
        val k = cle(c, lien)
        val tab = appel(c, "GET", "/liens/${lien.id}/evenements?apres=$apres").getJSONArray("evenements")
        (0 until tab.length()).map { i ->
            val e = tab.getJSONObject(i)
            EvenementVu(e.getLong("seq"), e.getString("type"), FamilleCrypto.dechiffrer(k, e.getString("nonce"), e.getString("chiffre"), "evt:" + lien.id), e.getString("cree_le"))
        }
    }

    // ---- Caméra partagée : image chiffrée (AAD « img: ») et signal « je regarde » ----
    /** Une image reçue : JPEG déchiffré et heure de dépôt sur le serveur. */
    class ImageVue(val jpeg: ByteArray, val majLe: String)

    /** Dépose MA dernière image (JPEG) pour ce lien : chiffrée sur le téléphone avant l'envoi. */
    suspend fun deposerImage(c: Context, lien: Lien, jpeg: ByteArray) = withContext(Dispatchers.IO) {
        val (nonce, chiffre) = FamilleCrypto.chiffrer(cle(c, lien), java.util.Base64.getEncoder().encodeToString(jpeg), "img:" + lien.id)
        appel(c, "PUT", "/liens/${lien.id}/image", JSONObject().put("nonce", nonce).put("chiffre", chiffre)); Unit
    }

    /** La dernière image de ce proche (null s'il ne partage pas ou plus). */
    suspend fun imageDe(c: Context, lien: Lien): ImageVue? = withContext(Dispatchers.IO) {
        val i = appel(c, "GET", "/liens/${lien.id}/image").optJSONObject("image") ?: return@withContext null
        val texte = FamilleCrypto.dechiffrer(cle(c, lien), i.getString("nonce"), i.getString("chiffre"), "img:" + lien.id) ?: return@withContext null
        try { ImageVue(java.util.Base64.getDecoder().decode(texte), i.optString("maj_le")) } catch (e: Exception) { null }
    }

    /** J'arrête de partager ma caméra avec ce proche : mon image est effacée du serveur. */
    suspend fun effacerImage(c: Context, lienId: String) = withContext(Dispatchers.IO) { appel(c, "DELETE", "/liens/$lienId/image"); Unit }

    /** « Je regarde ta caméra » : à renouveler toutes les ~20 secondes tant que l'écran est ouvert. */
    suspend fun signalerRegard(c: Context, lienId: String) = withContext(Dispatchers.IO) { appel(c, "PUT", "/liens/$lienId/regard"); Unit }

    /** Ce proche regarde-t-il ma caméra en ce moment ? */
    suspend fun quelquUnRegarde(c: Context, lienId: String): Boolean = withContext(Dispatchers.IO) { appel(c, "GET", "/liens/$lienId/regard").optBoolean("regarde", false) }
}
