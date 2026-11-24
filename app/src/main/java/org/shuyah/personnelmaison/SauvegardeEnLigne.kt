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
    class Resultat(val ok: Boolean, val message: String, val autreAppareil: Existante? = null)

    /** Ce que le serveur sait de la sauvegarde d'un casier : date, taille et l'installation (identifiant ALÉATOIRE, rien de personnel) qui l'a faite en dernier (null : sauvegarde ancienne). */
    class Existante(val majLe: String, val taille: Long, val appareil: String?)

    /** Identifiant aléatoire de CETTE installation (créé une fois, gardé dans les réglages propres à la sauvegarde : jamais copié dans la valise). */
    fun monAppareil(c: Context): String {
        reglages(c).getString("sauv_appareil", null)?.let { return it }
        val id = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        reglages(c).edit().putString("sauv_appareil", id).apply(); return id
    }
    /** Installations déjà « connues » pour ce casier (la mienne, ou celle dont je viens de restaurer la sauvegarde) : elles ne déclenchent pas d'avertissement. */
    private fun connu(c: Context) = reglages(c).getString("sauv_connu", null)
    private fun retenirConnu(c: Context, appareil: String?) { if (appareil != null) reglages(c).edit().putString("sauv_connu", appareil).apply() }
    /** Vrai si le casier a été mis à jour en dernier par une AUTRE installation que celles que je connais : l'écraser ferait perdre ses données. */
    // 1.63 : on compare la DATE de la sauvegarde (et non plus seulement l'appareil) : si l'autre appareil a sauvegardé de nouveau APRÈS que j'ai restauré sa sauvegarde, l'alerte revient.
    private fun connuMaj(c: Context) = reglages(c).getString("sauv_connu_maj", null)
    fun estAutreAppareil(c: Context, e: Existante?): Boolean = e?.appareil != null && e.appareil != monAppareil(c) && e.majLe != connuMaj(c)
    /** « Je suis à jour » : la sauvegarde actuelle de l'autre appareil est connue (comparée ou ignorée en connaissance de cause) : l'alerte tombe jusqu'à sa prochaine sauvegarde. */
    fun ignorerAlerte(c: Context) { alerteAutreAppareil(c)?.let { reglages(c).edit().putString("sauv_connu_maj", it).apply() }; effacerAlerte(c) }

    /** Identifiant de sauvegarde (e-mail ou téléphone) de cette installation, gardé en clair sur le téléphone seulement (pour l'afficher masqué). */
    fun identifiant(c: Context): String? = reglages(c).getString("sauv_identifiant", null)
    class Erreur(message: String) : Exception(message)

    // ---------------------------------------------------------------- serveur
    private fun appel(c: Context, cles: SauvegardeCrypto.Cles, methode: String, suffixe: String = "", corps: ByteArray? = null): okhttp3.Response {
        val appareilEnvoi = monAppareil(c)
        val rb = Request.Builder().url("$URL_PAR_DEFAUT/api/sauvegardes/${cles.id}$suffixe").header("X-Jeton-Sauvegarde", cles.jeton).header("X-ShuyahStore", "1").header("X-Appareil", appareilEnvoi)
        val requete = when (methode) {
            "PUT" -> rb.put(corps!!.toRequestBody(OCTETS)); "DELETE" -> rb.delete(); else -> rb.get()
        }.build()
        try { return http.newCall(requete).execute() } catch (e: java.io.IOException) { throw Erreur("Serveur injoignable : vérifiez la connexion et réessayez") }
    }

    private fun messageServeur(r: okhttp3.Response, repli: String): String {
        val corps = r.body?.string().orEmpty()
        return Regex("\"detail\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(corps)?.groupValues?.get(1)?.replace("\\\"", "\"") ?: "$repli (code ${r.code})"
    }

    /** Le casier de ces clés contient-il déjà une sauvegarde ? Rend ses informations ou null. */
    suspend fun existante(c: Context, cles: SauvegardeCrypto.Cles): Existante? = withContext(Dispatchers.IO) {
        appel(c, cles, "GET", "/info").use { r ->
            when {
                r.code == 404 -> null
                r.isSuccessful -> { val t = r.body?.string().orEmpty()
                    Existante(Regex("\"maj_le\"\\s*:\\s*\"([^\"]+)\"").find(t)?.groupValues?.get(1) ?: "", Regex("\"taille\"\\s*:\\s*(\\d+)").find(t)?.groupValues?.get(1)?.toLong() ?: 0L,
                        Regex("\"appareil\"\\s*:\\s*\"([0-9a-f]+)\"").find(t)?.groupValues?.get(1)) }
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
    suspend fun sauvegarder(c: Context, forcer: Boolean = false): Resultat = try {
        val secrets = Base.secrets(c)
        val id = secrets.getString("sauv_id", null); val jeton = secrets.getString("sauv_jeton", null); val chiffre = secrets.getString("sauv_chiffre", null)
        if (id == null || jeton == null || chiffre == null) Resultat(false, "La sauvegarde en ligne n'est pas activée")
        else {
            val cles = SauvegardeCrypto.Cles(id, jeton, chiffre.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            envoyer(c, cles, forcer)
        }
    } catch (e: Erreur) { Resultat(false, e.message ?: "Sauvegarde impossible") } catch (e: Exception) { Resultat(false, "Sauvegarde impossible : ${e.message ?: e.javaClass.simpleName}") }

    private suspend fun envoyer(c: Context, cles: SauvegardeCrypto.Cles, forcer: Boolean = false): Resultat {
        // GARDE-FOU (1.60) : si une AUTRE installation a sauvegardé en dernier dans ce casier, on ne l'écrase JAMAIS en silence (sauvegarde automatique comprise) : l'écran propose de restaurer ou de remplacer.
        if (!forcer) {
            val e = existante(c, cles)
            if (estAutreAppareil(c, e)) { poserAlerte(c, e!!); return Resultat(false, "Une sauvegarde faite depuis un AUTRE appareil (le ${e!!.majLe.take(16).replace('T', ' ')}) existe déjà pour cet identifiant et cette phrase. " +
                "La remplacer ferait perdre ses données : restaurez-la d'abord, ou remplacez-la en connaissance de cause.", autreAppareil = e) }
        }
        val (entrees, comptes) = instantane(c)
        val pieces = entrees.keys.count { it.startsWith("pieces/") }
        val chiffre = withContext(Dispatchers.IO) { SauvegardeCrypto.chiffrer(cles.chiffre, SauvegardeCrypto.archiver(entrees)) }
        if (chiffre.size > TAILLE_MAX) return Resultat(false, "Sauvegarde trop volumineuse (${chiffre.size / (1024 * 1024)} Mo, 55 Mo au plus) : supprimez des pièces volumineuses")
        return withContext(Dispatchers.IO) {
            appel(c, cles, "PUT", corps = chiffre).use { r ->
                if (!r.isSuccessful) Resultat(false, messageServeur(r, "Envoi refusé"))
                else { effacerAlerte(c); reglages(c).edit().putLong("sauv_dernier", System.currentTimeMillis()).putString("sauv_connu", monAppareil(c)).apply(); Resultat(true, "Sauvegarde terminée et VÉRIFIÉE : ${resumer(comptes, pieces)} (${chiffre.size / 1024} Ko, chiffrée)") }
            }
        }
    }

    /**
     * Active la sauvegarde en ligne avec cet IDENTIFIANT (e-mail ou téléphone) et cette phrase : retient les clés (chiffrées) et envoie la première sauvegarde.
     * Si le casier contient déjà une sauvegarde d'un autre appareil, rien n'est écrasé : le résultat porte `autreAppareil` et l'écran propose de restaurer ou de remplacer (`forcer`).
     */
    suspend fun activer(c: Context, phrase: String, identifiant: String, forcer: Boolean = false): Resultat = try {
        val cles = withContext(Dispatchers.Default) { SauvegardeCrypto.deriver(phrase, identifiant) }
        retenir(c, cles, identifiant)
        val r = envoyer(c, cles, forcer)
        if (!r.ok) oublier(c)
        r
    } catch (e: Erreur) { oublier(c); Resultat(false, e.message ?: "Activation impossible") } catch (e: Exception) { oublier(c); Resultat(false, "Activation impossible : ${e.message ?: e.javaClass.simpleName}") }

    private fun retenir(c: Context, cles: SauvegardeCrypto.Cles, identifiant: String? = null) {
        if (identifiant != null) reglages(c).edit().putString("sauv_identifiant", SauvegardeCrypto.normaliserIdentifiant(identifiant)).apply()
        Base.secrets(c).edit().putString("sauv_id", cles.id).putString("sauv_jeton", cles.jeton).putString("sauv_chiffre", cles.chiffre.joinToString("") { "%02x".format(it) }).apply()
        reglages(c).edit().putString("sauv_mode", "cloud").apply()
    }
    private fun oublier(c: Context) {
        Base.secrets(c).edit().remove("sauv_id").remove("sauv_jeton").remove("sauv_chiffre").apply()
        reglages(c).edit().remove("sauv_mode").remove("sauv_dernier").remove("sauv_identifiant").remove("sauv_connu").apply()
    }

    /** Désactive : efface la sauvegarde sur le serveur (jeton exigé) puis oublie les clés. */
    suspend fun desactiver(c: Context): Resultat = try {
        val secrets = Base.secrets(c)
        val id = secrets.getString("sauv_id", null); val jeton = secrets.getString("sauv_jeton", null)
        if (id != null && jeton != null) withContext(Dispatchers.IO) {
            appel(c, SauvegardeCrypto.Cles(id, jeton, ByteArray(32)), "DELETE").use { r -> if (!r.isSuccessful && r.code != 404) throw Erreur(messageServeur(r, "Effacement impossible")) }
        }
        oublier(c); reglages(c).edit().putString("sauv_mode", "local").apply()
        Resultat(true, "Sauvegarde en ligne effacée et désactivée")
    } catch (e: Erreur) { Resultat(false, e.message ?: "Désactivation impossible") }

    // ---------------------------------------------------------------- COMPARAISON ET ALERTE « AUTRE APPAREIL » (1.62, demande du propriétaire : deux appareils, aucune donnée écrasée par l'autre)
    /** Une ligne du tableau de comparaison : ce que contient la sauvegarde en ligne et ce que contient CE téléphone pour une même rubrique. */
    data class LigneComparaison(val libelle: String, val sauvegarde: Long, val telephone: Long) {
        val identique get() = sauvegarde == telephone
        /** Éléments présents seulement sur ce téléphone : ils seraient mis de côté (rétablissables) par une restauration. */
        val enPlusSurTelephone get() = (telephone - sauvegarde).coerceAtLeast(0)
        /** Éléments présents seulement dans la sauvegarde (faits sur un autre appareil, par exemple). */
        val enPlusEnLigne get() = (sauvegarde - telephone).coerceAtLeast(0)
    }
    /** Compare rubrique par rubrique (seules les rubriques non vides d'un côté au moins sont listées). Fonction pure, testée. */
    fun comparer(sauvegarde: Map<String, Long>, telephone: Map<String, Long>): List<LigneComparaison> =
        LIBELLES.mapNotNull { (cle, nom) ->
            val a = sauvegarde[cle] ?: 0L; val b = telephone[cle] ?: 0L
            if (a == 0L && b == 0L) null else LigneComparaison(nom.replaceFirstChar { it.uppercase() }, a, b)
        }
    /** Total des éléments qui n'existent QUE sur ce téléphone : s'il est > 0, restaurer ferait « perdre » (mettre de côté) ces ajouts. */
    fun ajoutsLocaux(lignes: List<LigneComparaison>): Long = lignes.sumOf { it.enPlusSurTelephone }

    /** Alerte mémorisée : date de la sauvegarde faite par un AUTRE appareil et pas encore traitée (restaurée ou remplacée en connaissance de cause). */
    fun alerteAutreAppareil(c: Context): String? = reglages(c).getString("sauv_alerte_autre", null)
    private fun poserAlerte(c: Context, e: Existante) = reglages(c).edit().putString("sauv_alerte_autre", e.majLe).apply()
    private fun effacerAlerte(c: Context) = reglages(c).edit().remove("sauv_alerte_autre").apply()

    /**
     * À CHAQUE ouverture : regarde (sans rien télécharger) si un autre appareil a sauvegardé dans ce casier depuis. Si oui, l'alerte est posée : l'écran d'accueil et l'écran de sauvegarde
     * la montrent, pour que la personne compare puis choisisse. Aucun envoi, aucun remplacement. Silencieuse en cas de réseau absent.
     */
    suspend fun verifierAutreAppareil(c: Context) {
        try {
            if (!actif(c)) return
            val s = Base.secrets(c)
            val cles = SauvegardeCrypto.Cles(s.getString("sauv_id", null) ?: return, s.getString("sauv_jeton", null) ?: return, ByteArray(32))
            val e = existante(c, cles)
            if (estAutreAppareil(c, e)) poserAlerte(c, e!!) else effacerAlerte(c)
        } catch (e: Exception) { /* hors connexion : on reverra à la prochaine ouverture */ }
    }

    /** Sauvegarde automatique : une fois par jour à l'ouverture, en mode « cloud ». Rend null si rien n'était à faire. */
    suspend fun sauvegardeAutomatique(c: Context): Resultat? =
        if (actif(c) && System.currentTimeMillis() - dernierEnvoi(c) > INTERVALLE_AUTO_MS) sauvegarder(c) else null

    // ---------------------------------------------------------------- restauration
    /** Ce que contient la sauvegarde (vérifié en rouvrant sa base) et ce que contient ce téléphone : montré AVANT de remplacer quoi que ce soit. */
    class Apercu(val entrees: Map<String, ByteArray>, val cles: SauvegardeCrypto.Cles, val sauvegarde: Map<String, Long>, val pieces: Int, val telephone: Map<String, Long>,
                 val identifiant: String = "", val appareil: String? = null, val majLe: String = "", val ancienne: Boolean = false, val fichier: Boolean = false) {
        val vide get() = total(sauvegarde) == 0L
        val telephoneVide get() = total(telephone) == 0L
    }

    /** Télécharge, déchiffre et VÉRIFIE la sauvegarde sans rien modifier sur le téléphone. */
    suspend fun apercu(c: Context, phrase: String, identifiant: String = "", precedente: Boolean = false): Pair<Apercu?, Resultat> = try {
        // Avec identifiant : on cherche d'abord le casier « identifiant + phrase » (1.60) ; s'il n'existe pas, l'ancien casier « phrase seule » (sauvegardes d'avant la 1.60).
        val candidates = buildList {
            if (identifiant.isNotBlank()) add(Pair(withContext(Dispatchers.Default) { SauvegardeCrypto.deriver(phrase, identifiant) }, false))
            add(Pair(withContext(Dispatchers.Default) { SauvegardeCrypto.deriver(phrase) }, true))
        }
        var cles: SauvegardeCrypto.Cles? = null; var ancienne = false; var infos: Existante? = null
        for ((k, ancien) in candidates) { val e = existante(c, k); if (e != null) { cles = k; ancienne = ancien; infos = e; break } }
        if (cles == null) throw Erreur(if (identifiant.isBlank()) "Aucune sauvegarde trouvée pour cette phrase secrète (sauvegardes d'avant la version 1.60). Si vous avez activé la sauvegarde avec un e-mail ou un téléphone, saisissez-le."
            else "Aucune sauvegarde trouvée pour cet identifiant et cette phrase secrète. Vérifiez l'e-mail ou le numéro (avec l'indicatif) et la phrase.")
        val cl = cles!!
        val chiffre = withContext(Dispatchers.IO) {
            appel(c, cl, "GET", if (precedente) "?precedente=1" else "").use { r ->
                if (r.code == 404) throw Erreur("Aucune sauvegarde trouvée pour cette phrase secrète")
                if (!r.isSuccessful) throw Erreur(messageServeur(r, "Téléchargement impossible"))
                r.body!!.bytes()
            }
        }
        analyser(c, chiffre, cl, if (ancienne) "" else identifiant, infos?.appareil, ancienne, fichier = false, majLe = infos?.majLe.orEmpty())
    } catch (e: Erreur) { Pair(null, Resultat(false, e.message ?: "Restauration impossible")) } catch (e: Exception) { Pair(null, Resultat(false, "Restauration impossible : ${e.message ?: e.javaClass.simpleName}")) }

    /** Déchiffre et VÉRIFIE une valise (reçue du serveur ou lue dans un fichier) sans rien modifier sur le téléphone. Même contrôle dans les deux cas. */
    private suspend fun analyser(c: Context, chiffre: ByteArray, cl: SauvegardeCrypto.Cles, identifiant: String, appareil: String?, ancienne: Boolean, fichier: Boolean, majLe: String = ""): Pair<Apercu?, Resultat> {
        val entrees = try { SauvegardeCrypto.ouvrir(SauvegardeCrypto.dechiffrer(cl.chiffre, chiffre)) } catch (e: IllegalArgumentException) { throw Erreur(e.message ?: "Phrase secrète inexacte") }
        if (entrees["manifeste"]?.let { String(it).startsWith("personnel-maison;") } != true || entrees["base/${Base.NOM_FICHIER}"] == null || entrees["secrets/cle_base"] == null)
            throw Erreur("Cette sauvegarde n'est pas celle de Personnel de maison")
        val (sauv, tel) = withContext(Dispatchers.IO) {
            Pair(Base.compterCopie(c, entrees["base/${Base.NOM_FICHIER}"]!!, entrees["base/${Base.NOM_FICHIER}-wal"], String(entrees["secrets/cle_base"]!!)), Base.compterLive(c))
        }
        return Pair(Apercu(entrees, cl, sauv, entrees.keys.count { it.startsWith("pieces/") }, tel, identifiant, appareil, majLe, ancienne, fichier), Resultat(true, ""))
    }

    // ---------------------------------------------------------------- COPIE DE SÉCURITÉ SUR LE TÉLÉPHONE (version 1.61, demande du propriétaire)
    // Un FICHIER chiffré (même valise, même chiffrement AES-256-GCM que la sauvegarde en ligne) enregistré où la personne le choisit (Documents, carte SD…) : il survit à la
    // désinstallation de l'application et permet de ne rien perdre pendant le passage à l'identifiant (e-mail ou téléphone). Il se restaure ensuite, en ligne comprise.
    // Format : « PMSV » + version (1) + mode (1 = phrase seule d'avant la 1.60, 2 = identifiant + phrase) puis la valise chiffrée.
    private val MAGIE_FICHIER = "PMSV".toByteArray()
    fun nomCopieLocale(): String = "personnel-maison-" + java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.FRANCE).format(java.util.Date()) + ".pmsauv"
    fun derniereCopieLocale(c: Context): Long = reglages(c).getLong("sauv_copie_locale", 0L)
    fun copieLocaleFaite(c: Context) { reglages(c).edit().putLong("sauv_copie_locale", System.currentTimeMillis()).apply() }
    /** Une copie de sécurité a-t-elle été enregistrée ces dernières 24 heures ? (condition pour passer à l'identifiant) */
    fun copieLocaleRecente(c: Context) = System.currentTimeMillis() - derniereCopieLocale(c) < INTERVALLE_AUTO_MS

    /** Photographie VÉRIFIÉE de toutes les données (même contrôle que la sauvegarde en ligne), chiffrée avec identifiant + phrase : octets du fichier à enregistrer. */
    suspend fun preparerCopieLocale(c: Context, phrase: String, identifiant: String): Pair<ByteArray?, Resultat> = try {
        val cles = withContext(Dispatchers.Default) { SauvegardeCrypto.deriver(phrase, identifiant) }
        val (entrees, comptes) = instantane(c)
        val valise = withContext(Dispatchers.IO) { SauvegardeCrypto.chiffrer(cles.chiffre, SauvegardeCrypto.archiver(entrees)) }
        Pair(MAGIE_FICHIER + byteArrayOf(1, 2) + valise, Resultat(true, "Copie prête : ${resumer(comptes, entrees.keys.count { it.startsWith("pieces/") })} (${valise.size / 1024} Ko, chiffrée)"))
    } catch (e: Erreur) { Pair(null, Resultat(false, e.message ?: "Copie impossible")) } catch (e: Exception) { Pair(null, Resultat(false, "Copie impossible : ${e.message ?: e.javaClass.simpleName}")) }

    /** Lit un fichier de copie de sécurité : déchiffre avec identifiant + phrase (ou phrase seule pour une copie d'avant la 1.60) et VÉRIFIE, sans rien modifier. */
    suspend fun apercuDepuisFichier(c: Context, octets: ByteArray, phrase: String, identifiant: String): Pair<Apercu?, Resultat> = try {
        if (octets.size < 8 || !octets.copyOfRange(0, 4).contentEquals(MAGIE_FICHIER)) throw Erreur("Ce fichier n'est pas une copie de sécurité de Personnel de maison")
        val ancienne = octets[5] == 1.toByte()
        val cles = withContext(Dispatchers.Default) { if (ancienne) SauvegardeCrypto.deriver(phrase) else SauvegardeCrypto.deriver(phrase, identifiant) }
        analyser(c, octets.copyOfRange(6, octets.size), cles, if (ancienne) "" else identifiant, null, ancienne, fichier = true)
    } catch (e: Erreur) { Pair(null, Resultat(false, e.message ?: "Lecture impossible")) } catch (e: Exception) { Pair(null, Resultat(false, "Lecture impossible : ${e.message ?: e.javaClass.simpleName}")) }

    // ---------------------------------------------------------------- PASSAGE À L'IDENTIFIANT d'une sauvegarde en ligne ancienne (phrase seule)
    /** Vrai si la sauvegarde en ligne est active mais SANS identifiant (avant la 1.60) : à sécuriser. */
    fun aMigrer(c: Context) = actif(c) && identifiant(c) == null

    /**
     * Envoie les données actuelles dans le NOUVEAU casier (identifiant + phrase), vérifie qu'il est bien lu en ligne, retient les nouvelles clés, puis efface l'ANCIEN casier (phrase seule).
     * Rien n'est perdu en cas d'échec : tant que le nouveau casier n'est pas vérifié, les anciennes clés restent en place et l'ancien casier n'est pas touché.
     */
    suspend fun migrer(c: Context, phrase: String, identifiant: String, forcer: Boolean = false): Resultat = try {
        val secrets = Base.secrets(c)
        val ancienId = secrets.getString("sauv_id", null); val ancienJeton = secrets.getString("sauv_jeton", null); val ancienChiffre = secrets.getString("sauv_chiffre", null)
        if (ancienId == null || ancienJeton == null || ancienChiffre == null) Resultat(false, "La sauvegarde en ligne n'est pas activée")
        else {
            val nouvelles = withContext(Dispatchers.Default) { SauvegardeCrypto.deriver(phrase, identifiant) }
            val r = envoyer(c, nouvelles, forcer)
            if (!r.ok) r
            else {
                val verifie = existante(c, nouvelles)
                if (verifie == null) Resultat(false, "Le nouveau casier n'a pas pu être relu en ligne : rien n'a été changé, votre ancienne sauvegarde est intacte.")
                else {
                    retenir(c, nouvelles, identifiant)                                   // les nouvelles clés remplacent les anciennes
                    // ancien casier : effacé seulement maintenant que le nouveau est vérifié (un échec ici est sans gravité : il reste inutilisé)
                    val effacement = try { withContext(Dispatchers.IO) { appel(c, SauvegardeCrypto.Cles(ancienId, ancienJeton, ByteArray(32)), "DELETE").use { it.isSuccessful || it.code == 404 } } } catch (e: Erreur) { false }
                    Resultat(true, "Sauvegarde passée à votre identifiant (${SauvegardeCrypto.masquerIdentifiant(identifiant)}) et vérifiée en ligne." +
                        if (effacement) " L'ancienne sauvegarde (phrase seule) a été effacée." else " L'ancienne sauvegarde n'a pas pu être effacée maintenant : elle reste inutilisée.")
                }
            }
        }
    } catch (e: Erreur) { Resultat(false, e.message ?: "Passage impossible") } catch (e: Exception) { Resultat(false, "Passage impossible : ${e.message ?: e.javaClass.simpleName}") }

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
            // Restauration EN LIGNE : la sauvegarde reste active avec les mêmes clés. Restauration depuis un FICHIER avec identifiant : la sauvegarde en ligne est activée avec ces clés et la
            // première sauvegarde part à la prochaine ouverture (« sauv_dernier » = 0) ; depuis un fichier ancien (phrase seule), on n'active rien de nouveau.
            if (!(a.fichier && a.ancienne)) retenir(c, a.cles, a.identifiant.ifBlank { null })
            effacerAlerte(c); retenirConnu(c, a.appareil); if (a.majLe.isNotEmpty()) reglages(c).edit().putString("sauv_connu_maj", a.majLe).apply()                                                  // l'installation dont je viens de restaurer la sauvegarde n'est plus une « autre » installation
            reglages(c).edit().putLong("sauv_dernier", if (a.fichier) 0L else System.currentTimeMillis()).apply()
        }
        Resultat(true, "Données restaurées : ${resumer(a.sauvegarde, a.pieces)}" + if (a.ancienne) ". Cette sauvegarde date d'avant l'identifiant : pour la protéger d'une phrase identique chez quelqu'un d'autre, désactivez la sauvegarde puis réactivez-la avec votre e-mail ou téléphone." else "")
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
