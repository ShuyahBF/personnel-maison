package org.shuyah.personnelmaison

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * SAUVEGARDE EN LIGNE de Personnel de maison (version 1.35) — demande du propriétaire : retrouver TOUTES ses données et réglages après une désinstallation / réinstallation
 * (par exemple en changeant de version depuis ShuyahStore), comme la sauvegarde en ligne d'auRor.
 *
 * Pour un développeur WinDev : on fabrique une « valise » (fichier ZIP) qui contient la base de données (déjà chiffrée), toutes les pièces d'identité et documents (déchiffrés
 * puis rechiffrés pour la valise), et les réglages (ordinaires, secrets, famille, agenda). La valise entière est chiffrée avec la phrase secrète (voir SauvegardeCrypto) puis envoyée au
 * serveur ShuyahStore, qui ne peut RIEN lire. Pour restaurer sur un téléphone neuf : on retape la phrase, on télécharge la valise, on la déchiffre et on remet chaque chose à sa place.
 *
 *  - Le serveur garde la sauvegarde courante ET la précédente (retour arrière si la dernière est abîmée).
 *  - Les clés de la sauvegarde (dérivées de la phrase) sont gardées dans les préférences CHIFFRÉES du téléphone, jamais dans la valise elle-même.
 *  - Mode « cloud » : sauvegarde automatique une fois par jour à l'ouverture (toujours annoncée à l'écran) ; mode « local » : rien n'est envoyé.
 *  - Appels réseau et fichiers : toujours hors du fil principal.
 */
object SauvegardeEnLigne {
    const val URL_PAR_DEFAUT = "https://shuyahstore-api.onrender.com"
    private const val TAILLE_MAX = 55L * 1024 * 1024          // le serveur refuse au-delà de 60 Mo
    private const val INTERVALLE_AUTO_MS = 24L * 3600 * 1000
    private val http = OkHttpClient.Builder().callTimeout(180, TimeUnit.SECONDS).connectTimeout(60, TimeUnit.SECONDS).build()   // 1er appel : le serveur peut se réveiller
    private val OCTETS = "application/octet-stream".toMediaType()

    /** Réglages ordinaires de la sauvegarde (jamais copiés dans la valise). */
    private fun reglages(c: Context) = c.getSharedPreferences("reglages", Context.MODE_PRIVATE)
    private const val PREFIXE = "sauv_"

    /** « cloud », « local » ou null (pas encore choisi). */
    fun mode(c: Context): String? = reglages(c).getString("sauv_mode", null)
    fun dernierEnvoi(c: Context): Long = reglages(c).getLong("sauv_dernier", 0L)
    fun actif(c: Context) = mode(c) == "cloud" && Base.secrets(c).getString("sauv_id", null) != null
    fun choisirLocal(c: Context) = reglages(c).edit().putString("sauv_mode", "local").apply()

    /** Résultat d'une opération : message lisible pour l'écran. */
    class Resultat(val ok: Boolean, val message: String)
    class Erreur(message: String) : Exception(message)

    // ---------------------------------------------------------------- serveur
    private fun appel(cles: SauvegardeCrypto.Cles, methode: String, suffixe: String = "", corps: ByteArray? = null): okhttp3.Response {
        val rb = Request.Builder().url("$URL_PAR_DEFAUT/api/sauvegardes/${cles.id}$suffixe").header("X-Jeton-Sauvegarde", cles.jeton).header("X-ShuyahStore", "1")
        val requete = when (methode) {
            "PUT" -> rb.put(corps!!.toRequestBody(OCTETS)); "DELETE" -> rb.delete(); else -> rb.get()
        }.build()
        try { return http.newCall(requete).execute() } catch (e: java.io.IOException) { throw Erreur("Serveur injoignable : vérifiez la connexion et réessayez") }
    }

    private fun messageServeur(r: okhttp3.Response, repli: String): String {
        val corps = r.body?.string().orEmpty()
        return Regex("\"detail\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(corps)?.groupValues?.get(1)?.replace("\\\"", "\"") ?: "$repli (code ${r.code})"
    }

    /** Une sauvegarde existe-t-elle déjà pour cette phrase ? Rend (date, taille) ou null. */
    suspend fun existante(cles: SauvegardeCrypto.Cles): Pair<String, Long>? = withContext(Dispatchers.IO) {
        appel(cles, "GET", "/info").use { r ->
            when {
                r.code == 404 -> null
                r.isSuccessful -> { val t = r.body?.string().orEmpty()
                    Pair(Regex("\"maj_le\"\\s*:\\s*\"([^\"]+)\"").find(t)?.groupValues?.get(1) ?: "", Regex("\"taille\"\\s*:\\s*(\\d+)").find(t)?.groupValues?.get(1)?.toLong() ?: 0L) }
                else -> throw Erreur(messageServeur(r, "Lecture de la sauvegarde impossible"))
            }
        }
    }

    // ---------------------------------------------------------------- fabrication de la valise
    /** Rassemble toutes les données du téléphone dans un ZIP (non chiffré, en mémoire, jamais écrit sur le disque). */
    private suspend fun fabriquerValise(c: Context): ByteArray = withContext(Dispatchers.IO) {
        Base.pointDeControle(c)                                                      // la base est complète dans son fichier
        val entrees = LinkedHashMap<String, ByteArray>()
        entrees["manifeste"] = "personnel-maison;${Version.VERSION};${System.currentTimeMillis()}".toByteArray()
        entrees["base/${Base.NOM_FICHIER}"] = c.getDatabasePath(Base.NOM_FICHIER).readBytes()
        entrees["secrets/cle_base"] = (Base.secrets(c).getString("cle_base", null) ?: throw Erreur("Clé de la base introuvable")).toByteArray()
        entrees["reglages"] = SauvegardeCrypto.prefsVersOctets(reglages(c).all.filterKeys { !it.startsWith(PREFIXE) })
        entrees["famille"] = SauvegardeCrypto.prefsVersOctets(ClientFamille.prefs(c).all)
        for (nom in Pieces.noms(c)) Pieces.lireOctets(c, nom)?.let { entrees["pieces/$nom"] = it }     // déchiffrées avec la clé de CE téléphone : la valise est rechiffrée en bloc
        SauvegardeCrypto.archiver(entrees)
    }

    /** Envoie une sauvegarde maintenant (mode « cloud » et clés présentes). */
    suspend fun sauvegarder(c: Context): Resultat = try {
        val secrets = Base.secrets(c)
        val id = secrets.getString("sauv_id", null); val jeton = secrets.getString("sauv_jeton", null); val chiffre = secrets.getString("sauv_chiffre", null)
        if (id == null || jeton == null || chiffre == null) Resultat(false, "La sauvegarde en ligne n'est pas activée")
        else {
            val cles = SauvegardeCrypto.Cles(id, jeton, chiffre.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            envoyer(c, cles)
        }
    } catch (e: Erreur) { Resultat(false, e.message ?: "Sauvegarde impossible") } catch (e: Exception) { Resultat(false, "Sauvegarde impossible : ${e.message ?: e.javaClass.simpleName}") }

    private suspend fun envoyer(c: Context, cles: SauvegardeCrypto.Cles): Resultat {
        val chiffre = withContext(Dispatchers.IO) { SauvegardeCrypto.chiffrer(cles.chiffre, fabriquerValise(c)) }
        if (chiffre.size > TAILLE_MAX) return Resultat(false, "Sauvegarde trop volumineuse (${chiffre.size / (1024 * 1024)} Mo, 55 Mo au plus) : supprimez des pièces volumineuses")
        return withContext(Dispatchers.IO) {
            appel(cles, "PUT", corps = chiffre).use { r ->
                if (!r.isSuccessful) Resultat(false, messageServeur(r, "Envoi refusé"))
                else { reglages(c).edit().putLong("sauv_dernier", System.currentTimeMillis()).apply(); Resultat(true, "Sauvegarde terminée (${chiffre.size / 1024} Ko, chiffrée)") }
            }
        }
    }

    /** Active la sauvegarde en ligne avec cette phrase : retient les clés (chiffrées) et envoie la première sauvegarde. */
    suspend fun activer(c: Context, phrase: String): Resultat = try {
        val cles = withContext(Dispatchers.Default) { SauvegardeCrypto.deriver(phrase) }
        retenir(c, cles)
        val r = envoyer(c, cles)
        if (!r.ok) oublier(c)
        r
    } catch (e: Erreur) { oublier(c); Resultat(false, e.message ?: "Activation impossible") } catch (e: Exception) { oublier(c); Resultat(false, "Activation impossible : ${e.message ?: e.javaClass.simpleName}") }

    private fun retenir(c: Context, cles: SauvegardeCrypto.Cles) {
        Base.secrets(c).edit().putString("sauv_id", cles.id).putString("sauv_jeton", cles.jeton).putString("sauv_chiffre", cles.chiffre.joinToString("") { "%02x".format(it) }).apply()
        reglages(c).edit().putString("sauv_mode", "cloud").apply()
    }
    private fun oublier(c: Context) {
        Base.secrets(c).edit().remove("sauv_id").remove("sauv_jeton").remove("sauv_chiffre").apply()
        reglages(c).edit().remove("sauv_mode").remove("sauv_dernier").apply()
    }

    /** Désactive : efface la sauvegarde sur le serveur (jeton exigé) puis oublie les clés. */
    suspend fun desactiver(c: Context): Resultat = try {
        val secrets = Base.secrets(c)
        val id = secrets.getString("sauv_id", null); val jeton = secrets.getString("sauv_jeton", null)
        if (id != null && jeton != null) withContext(Dispatchers.IO) {
            appel(SauvegardeCrypto.Cles(id, jeton, ByteArray(32)), "DELETE").use { r -> if (!r.isSuccessful && r.code != 404) throw Erreur(messageServeur(r, "Effacement impossible")) }
        }
        oublier(c); reglages(c).edit().putString("sauv_mode", "local").apply()
        Resultat(true, "Sauvegarde en ligne effacée et désactivée")
    } catch (e: Erreur) { Resultat(false, e.message ?: "Désactivation impossible") }

    /** Sauvegarde automatique : une fois par jour à l'ouverture, en mode « cloud ». Rend null si rien n'était à faire. */
    suspend fun sauvegardeAutomatique(c: Context): Resultat? =
        if (actif(c) && System.currentTimeMillis() - dernierEnvoi(c) > INTERVALLE_AUTO_MS) sauvegarder(c) else null

    // ---------------------------------------------------------------- restauration
    /** Remplace TOUTES les données de ce téléphone par celles de la sauvegarde. `precedente` : la version d'avant. L'application est ensuite relancée. */
    suspend fun restaurer(c: Context, phrase: String, precedente: Boolean = false): Resultat = try {
        val cles = withContext(Dispatchers.Default) { SauvegardeCrypto.deriver(phrase) }
        val chiffre = withContext(Dispatchers.IO) {
            appel(cles, "GET", if (precedente) "?precedente=1" else "").use { r ->
                if (r.code == 404) throw Erreur("Aucune sauvegarde trouvée pour cette phrase secrète")
                if (!r.isSuccessful) throw Erreur(messageServeur(r, "Téléchargement impossible"))
                r.body!!.bytes()
            }
        }
        val entrees = try { SauvegardeCrypto.ouvrir(SauvegardeCrypto.dechiffrer(cles.chiffre, chiffre)) } catch (e: IllegalArgumentException) { throw Erreur(e.message ?: "Phrase secrète inexacte") }
        if (entrees["manifeste"]?.let { String(it).startsWith("personnel-maison;") } != true || entrees["base/${Base.NOM_FICHIER}"] == null || entrees["secrets/cle_base"] == null)
            throw Erreur("Cette sauvegarde n'est pas celle de Personnel de maison")
        withContext(Dispatchers.IO) { appliquer(c, entrees, cles) }
        Resultat(true, "Données restaurées")
    } catch (e: Erreur) { Resultat(false, e.message ?: "Restauration impossible") } catch (e: Exception) { Resultat(false, "Restauration impossible : ${e.message ?: e.javaClass.simpleName}") }

    /** Remet chaque chose à sa place. La base actuelle est mise de côté et rétablie si quoi que ce soit échoue. */
    private fun appliquer(c: Context, entrees: Map<String, ByteArray>, cles: SauvegardeCrypto.Cles) {
        Base.fermer()
        val fichier = c.getDatabasePath(Base.NOM_FICHIER)
        val avant = File(fichier.path + ".avant")
        val anciennes = Base.secrets(c).getString("cle_base", null)
        fichier.parentFile?.mkdirs()
        if (fichier.exists()) fichier.copyTo(avant, overwrite = true)
        try {
            for (suffixe in listOf("-wal", "-shm", "-journal")) File(fichier.path + suffixe).delete()
            fichier.writeBytes(entrees["base/${Base.NOM_FICHIER}"]!!)
            Base.secrets(c).edit().putString("cle_base", String(entrees["secrets/cle_base"]!!)).apply()
            // pièces et documents : on repart d'un dossier propre, puis chaque fichier reprend son nom d'origine
            Pieces.noms(c).forEach { Pieces.supprimer(c, it) }
            entrees.filterKeys { it.startsWith("pieces/") }.forEach { (nom, octets) -> Pieces.ecrireNomme(c, nom.removePrefix("pieces/"), octets) }
            // réglages : on remplace ceux de la sauvegarde, sans toucher aux réglages propres à la sauvegarde (préfixe « sauv_ »)
            ecrirePrefs(reglages(c), entrees["reglages"], garder = PREFIXE)
            ecrirePrefs(ClientFamille.prefs(c), entrees["famille"], garder = null)
            retenir(c, cles)                                                            // la sauvegarde en ligne reste active avec la même phrase
            reglages(c).edit().putLong("sauv_dernier", System.currentTimeMillis()).apply()
            avant.delete()
        } catch (e: Exception) {
            // échec : on rétablit la base d'origine et sa clé
            if (avant.exists()) avant.copyTo(fichier, overwrite = true)
            if (anciennes != null) Base.secrets(c).edit().putString("cle_base", anciennes).apply()
            throw Erreur("Restauration annulée, vos données actuelles sont conservées : ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun ecrirePrefs(p: android.content.SharedPreferences, octets: ByteArray?, garder: String?) {
        if (octets == null) return
        val valeurs = SauvegardeCrypto.octetsVersPrefs(octets)
        val e = p.edit()
        p.all.keys.filter { garder == null || !it.startsWith(garder) }.forEach { e.remove(it) }
        valeurs.forEach { (k, v) ->
            @Suppress("UNCHECKED_CAST")
            when (v) { is String -> e.putString(k, v); is Int -> e.putInt(k, v); is Long -> e.putLong(k, v); is Float -> e.putFloat(k, v); is Boolean -> e.putBoolean(k, v); is Set<*> -> e.putStringSet(k, v as Set<String>) }
        }
        e.commit()
    }

    /** Relance l'application (les écrans ouverts tenaient l'ancienne base). */
    fun relancer(c: Context) {
        val i = c.packageManager.getLaunchIntentForPackage(c.packageName)!!.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        c.startActivity(i)
        Runtime.getRuntime().exit(0)
    }
}
