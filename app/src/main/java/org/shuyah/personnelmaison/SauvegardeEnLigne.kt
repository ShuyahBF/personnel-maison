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
    const val URL_PAR_DEFAUT = "https://api.shuyahstore.store"
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
    /** Libellés lisibles des tables pour les messages (« 12 personnes, 340 pointages… »). */
    private val LIBELLES = mapOf("personnels" to "personnes", "pointages" to "pointages", "societes" to "sociétés", "operations" to "opérations du portefeuille",
        "medicaments" to "médicaments", "paiements" to "paiements", "notes" to "notes", "evaluations" to "évaluations", "taches" to "tâches", "documents_societe" to "documents de sociétés")
    fun resumer(comptes: Map<String, Long>, pieces: Int = -1): String {
        val l = comptes.filter { it.value > 0 && it.key in LIBELLES }.map { "${it.value} ${LIBELLES[it.key]}" }.toMutableList()
        if (pieces > 0) l.add("$pieces pièce(s) jointe(s)")
        return if (l.isEmpty()) "aucune donnée" else l.joinToString(", ")
    }
    private fun total(comptes: Map<String, Long>) = comptes.filterKeys { it in LIBELLES }.values.sum()

    /**
     * Photographie de TOUTES les données du téléphone (base + journal WAL, clé de la base, réglages, famille, pièces déchiffrées).
     * IMPORTANT (leçon de la version 1.35 : une sauvegarde incomplète a fait perdre des données) : la copie de la base est ROUVERTE à part et ses lignes sont comptées ;
     * elle n'est acceptée que si elle contient EXACTEMENT les mêmes données que la base en cours d'utilisation. Sinon on réessaie, puis on refuse (jamais de sauvegarde « à peu près »).
     */
    private suspend fun instantane(c: Context): Pair<LinkedHashMap<String, ByteArray>, Map<String, Long>> = withContext(Dispatchers.IO) {
        val entrees = LinkedHashMap<String, ByteArray>()
        val cleBase = Base.secrets(c).getString("cle_base", null) ?: throw Erreur("Clé de la base introuvable")
        val fichier = c.getDatabasePath(Base.NOM_FICHIER); val journal = File(fichier.path + "-wal")
        var comptes: Map<String, Long>? = null
        var dernier = ""
        for (essai in 1..4) {
            try { Base.pointDeControle(c) } catch (e: Exception) { /* le journal est de toute façon copié ci-dessous */ }
            val principal = fichier.readBytes()
            val wal = if (journal.exists() && journal.length() > 0) journal.readBytes() else null
            val live = Base.compterLive(c)
            val copie = try { Base.compterCopie(c, principal, wal, cleBase) } catch (e: Exception) { dernier = "copie illisible (${e.message})"; kotlinx.coroutines.delay(300); continue }
            if (copie == live) {
                entrees["base/${Base.NOM_FICHIER}"] = principal; if (wal != null) entrees["base/${Base.NOM_FICHIER}-wal"] = wal
                comptes = live; break
            }
            dernier = "la copie contient ${resumer(copie)} alors que le téléphone contient ${resumer(live)}"; kotlinx.coroutines.delay(300)
        }
        if (comptes == null) throw Erreur("Sauvegarde REFUSÉE pour ne rien perdre : $dernier. Fermez puis rouvrez l'application et réessayez.")
        entrees["manifeste"] = "personnel-maison;${Version.VERSION};${System.currentTimeMillis()}".toByteArray()
        entrees["secrets/cle_base"] = cleBase.toByteArray()
        entrees["reglages"] = SauvegardeCrypto.prefsVersOctets(reglages(c).all.filterKeys { !it.startsWith(PREFIXE) })
        entrees["famille"] = SauvegardeCrypto.prefsVersOctets(ClientFamille.prefs(c).all)
        for (nom in Pieces.noms(c)) Pieces.lireOctets(c, nom)?.let { entrees["pieces/$nom"] = it }     // déchiffrées avec la clé de CE téléphone : la valise est rechiffrée en bloc
        entrees["comptes"] = SauvegardeCrypto.prefsVersOctets(comptes.mapValues { it.value })
        Pair(entrees, comptes)
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
        val (entrees, comptes) = instantane(c)
        val pieces = entrees.keys.count { it.startsWith("pieces/") }
        val chiffre = withContext(Dispatchers.IO) { SauvegardeCrypto.chiffrer(cles.chiffre, SauvegardeCrypto.archiver(entrees)) }
        if (chiffre.size > TAILLE_MAX) return Resultat(false, "Sauvegarde trop volumineuse (${chiffre.size / (1024 * 1024)} Mo, 55 Mo au plus) : supprimez des pièces volumineuses")
        return withContext(Dispatchers.IO) {
            appel(cles, "PUT", corps = chiffre).use { r ->
                if (!r.isSuccessful) Resultat(false, messageServeur(r, "Envoi refusé"))
                else { reglages(c).edit().putLong("sauv_dernier", System.currentTimeMillis()).apply(); Resultat(true, "Sauvegarde terminée et VÉRIFIÉE : ${resumer(comptes, pieces)} (${chiffre.size / 1024} Ko, chiffrée)") }
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
    /** Ce que contient la sauvegarde (vérifié en rouvrant sa base) et ce que contient ce téléphone : montré AVANT de remplacer quoi que ce soit. */
    class Apercu(val entrees: Map<String, ByteArray>, val cles: SauvegardeCrypto.Cles, val sauvegarde: Map<String, Long>, val pieces: Int, val telephone: Map<String, Long>) {
        val vide get() = total(sauvegarde) == 0L
        val telephoneVide get() = total(telephone) == 0L
    }

    /** Télécharge, déchiffre et VÉRIFIE la sauvegarde sans rien modifier sur le téléphone. */
    suspend fun apercu(c: Context, phrase: String, precedente: Boolean = false): Pair<Apercu?, Resultat> = try {
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
        val (sauv, tel) = withContext(Dispatchers.IO) {
            Pair(Base.compterCopie(c, entrees["base/${Base.NOM_FICHIER}"]!!, entrees["base/${Base.NOM_FICHIER}-wal"], String(entrees["secrets/cle_base"]!!)), Base.compterLive(c))
        }
        Pair(Apercu(entrees, cles, sauv, entrees.keys.count { it.startsWith("pieces/") }, tel), Resultat(true, ""))
    } catch (e: Erreur) { Pair(null, Resultat(false, e.message ?: "Restauration impossible")) } catch (e: Exception) { Pair(null, Resultat(false, "Restauration impossible : ${e.message ?: e.javaClass.simpleName}")) }

    /** Fichier (chiffré par le téléphone) où est gardée la copie de secours d'AVANT la dernière restauration. */
    private fun fichierSecours(c: Context) = File(File(c.filesDir, "avant_restauration").also { it.mkdirs() }, "donnees.enc")
    fun secoursDisponible(c: Context) = fichierSecours(c).exists()
    private fun cleSecours(c: Context) = androidx.security.crypto.MasterKey.Builder(c).setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build()

    /** Remplace les données du téléphone par celles de l'aperçu. D'ABORD une copie de secours chiffrée des données actuelles (jamais effacée automatiquement). */
    suspend fun restaurer(c: Context, a: Apercu): Resultat = try {
        withContext(Dispatchers.IO) {
            val (actuel, _) = instantane(c)
            val f = fichierSecours(c); if (f.exists()) f.delete()
            androidx.security.crypto.EncryptedFile.Builder(c, f, cleSecours(c), androidx.security.crypto.EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB).build()
                .openFileOutput().use { it.write(SauvegardeCrypto.archiver(actuel)) }
            appliquer(c, a.entrees)
            retenir(c, a.cles)                                                          // la sauvegarde en ligne reste active avec la même phrase
            reglages(c).edit().putLong("sauv_dernier", System.currentTimeMillis()).apply()
        }
        Resultat(true, "Données restaurées : ${resumer(a.sauvegarde, a.pieces)}")
    } catch (e: Erreur) { Resultat(false, e.message ?: "Restauration impossible") } catch (e: Exception) { Resultat(false, "Restauration impossible : ${e.message ?: e.javaClass.simpleName}") }

    /** Retour en arrière : rétablit les données d'AVANT la dernière restauration (copie de secours). */
    suspend fun revenirEnArriere(c: Context): Resultat = try {
        withContext(Dispatchers.IO) {
            val f = fichierSecours(c); if (!f.exists()) throw Erreur("Aucune copie de secours")
            val octets = androidx.security.crypto.EncryptedFile.Builder(c, f, cleSecours(c), androidx.security.crypto.EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB).build().openFileInput().use { it.readBytes() }
            appliquer(c, SauvegardeCrypto.ouvrir(octets))
        }
        Resultat(true, "Données d'avant la restauration rétablies")
    } catch (e: Erreur) { Resultat(false, e.message ?: "Retour impossible") } catch (e: Exception) { Resultat(false, "Retour impossible : ${e.message ?: e.javaClass.simpleName}") }

    /** Remet chaque chose à sa place (base + journal, clé, pièces, réglages). Les clés de la sauvegarde en ligne (préfixe « sauv_ ») ne sont pas touchées. */
    private fun appliquer(c: Context, entrees: Map<String, ByteArray>) {
        Base.fermer()
        val fichier = c.getDatabasePath(Base.NOM_FICHIER)
        fichier.parentFile?.mkdirs()
        for (suffixe in listOf("-wal", "-shm", "-journal")) File(fichier.path + suffixe).delete()
        fichier.writeBytes(entrees["base/${Base.NOM_FICHIER}"]!!)
        entrees["base/${Base.NOM_FICHIER}-wal"]?.let { File(fichier.path + "-wal").writeBytes(it) }
        Base.secrets(c).edit().putString("cle_base", String(entrees["secrets/cle_base"]!!)).commit()
        // pièces et documents : on repart d'un dossier propre, puis chaque fichier reprend son nom d'origine
        Pieces.noms(c).forEach { Pieces.supprimer(c, it) }
        entrees.filterKeys { it.startsWith("pieces/") }.forEach { (nom, octets) -> Pieces.ecrireNomme(c, nom.removePrefix("pieces/"), octets) }
        // réglages : on remplace ceux de la sauvegarde, sans toucher aux réglages propres à la sauvegarde (préfixe « sauv_ »)
        ecrirePrefs(reglages(c), entrees["reglages"], garder = PREFIXE)
        ecrirePrefs(ClientFamille.prefs(c), entrees["famille"], garder = null)
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
