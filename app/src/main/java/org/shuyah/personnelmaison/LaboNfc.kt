package org.shuyah.personnelmaison

import android.app.Activity
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcA
import android.os.Bundle
import android.os.SystemClock
import java.security.SecureRandom

/**
 * LABORATOIRE NFC : écrire, relire ou effacer une carte NFC VIERGE (simulation avant d'utiliser de vraies cartes), en SUIVANT CHAQUE ÉTAPE à l'écran :
 * carte détectée (identifiant, technologies, type et capacité), état NDEF (formatée ? inscriptible ? taille), préparation du message, écriture, VÉRIFICATION
 * par relecture et comparaison octet par octet, durée de chaque étape. Le journal peut être copié pour être envoyé.
 *
 * La partie « logique » (lecture du type de carte, formats d'affichage) est pure et testée par LaboNfcTest ; la partie NFC Android est dessous.
 */
object LaboNfc {
    enum class Etat { EN_COURS, OK, ECHEC, INFO }

    /** Une ligne du journal : ce qui a été fait, son résultat et un détail lisible. */
    data class Etape(val libelle: String, val etat: Etat, val detail: String = "", val dureeMs: Long? = null)

    /** Octets en hexadécimal « 04:A2:… » (identifiant de la carte). */
    fun hex(octets: ByteArray, separateur: String = ":") = octets.joinToString(separateur) { "%02X".format(it) }

    /**
     * Type de carte NTAG / Ultralight d'après la réponse à la commande GET_VERSION (8 octets) : octet 2 = famille (0x04 NTAG, 0x03 Ultralight EV1), octet 6 = capacité.
     * Rend (nom, capacité utile en octets) ou null si la réponse n'est pas reconnue.
     */
    fun typeDepuisVersion(v: ByteArray?): Pair<String, Int>? {
        if (v == null || v.size < 8) return null
        return when (v[2].toInt() and 0xFF to (v[6].toInt() and 0xFF)) {
            0x04 to 0x0F -> "NTAG213" to 144
            0x04 to 0x11 -> "NTAG215" to 504
            0x04 to 0x13 -> "NTAG216" to 888
            0x03 to 0x0B -> "Mifare Ultralight EV1 (MF0UL11)" to 48
            0x03 to 0x0E -> "Mifare Ultralight EV1 (MF0UL21)" to 128
            else -> null
        }
    }

    /** Famille de carte d'après l'octet SAK (réponse de sélection) : aide à comprendre pourquoi une carte n'est pas inscriptible. */
    fun familleDepuisSak(sak: Int): String = when (sak) {
        0x00 -> "Type 2 (NTAG ou Mifare Ultralight) — inscriptible"
        0x08 -> "Mifare Classic 1K — inscriptible selon le téléphone"
        0x18 -> "Mifare Classic 4K — inscriptible selon le téléphone"
        0x09 -> "Mifare Mini"
        0x20 -> "Carte sécurisée ISO-DEP (bancaire, transport, DESFire) — non inscriptible librement"
        else -> "Famille inconnue (SAK %02X)".format(sak)
    }

    /**
     * Progression du test (0 à 100 %) et étape en cours, déduites du journal. Logique pure, testée (LaboNfcTest).
     * Jalons : carte détectée 20 % · état lu 35 % · message prêt 50 % · écriture 65 % puis 80 % · vérification à faire 85 % ·
     * carte représentée 90 % · vérification faite 95 % · terminé 100 %. Une étape d'échec garde le pourcentage atteint.
     */
    fun progression(etapes: List<Etape>): Pair<Int, String> {
        var pourcent = 0
        var texte = "En attente de la carte"
        for (e in etapes) {
            val (v, t) = when {
                e.libelle == "Carte détectée" -> 20 to "Carte détectée"
                e.libelle == "État NDEF" -> 35 to "État de la carte lu"
                e.libelle == "Taille du message" || e.libelle == "Préparation" -> 50 to "Message préparé"
                e.libelle == "Vérification à faire" -> 85 to "Éloignez la carte puis reposez-la pour la vérification"
                e.libelle == "Carte représentée" -> 90 to "Relecture de contrôle"
                e.libelle.startsWith("Vérification") -> (if (e.etat == Etat.EN_COURS) 90 else 95) to "Vérification faite"
                e.libelle.startsWith("Écriture") || e.libelle == "Formatage et écriture" ->
                    (if (e.etat == Etat.EN_COURS) 65 to "Écriture en cours : gardez la carte immobile" else 80 to "Écriture terminée")
                e.libelle == "Test réussi" || e.libelle == "Lecture terminée" -> 100 to "Terminé"
                else -> continue
            }
            if (v >= pourcent) { pourcent = v; texte = t }
        }
        if (etapes.any { it.etat == Etat.ECHEC }) texte = "Échec : voir le journal"
        return pourcent to texte
    }

    /** Ce que l'on sait d'une carte pour la TRIER : vierge, formatée vide, avec des données, verrouillée ou non inscriptible. */
    enum class GenreCarte(val libelle: String) {
        VIERGE("VIERGE (non formatée)"), FORMATEE_VIDE("Formatée, VIDE"), AVEC_CONTENU("Contient des données"),
        VERROUILLEE("Lecture seule (verrouillée)"), NON_INSCRIPTIBLE("Non inscriptible (carte bancaire, transport…)")
    }

    /** Une carte vue pendant le tri : identifiant, genre, résumé du contenu, famille, nombre de passages et heure du dernier. */
    data class CarteVue(val id: String, val genre: GenreCarte, val resume: String, val famille: String, val passages: Int, val vueLe: Long)

    /**
     * Genre d'une carte (logique pure, testée) : `ndefPresent` = la carte est déjà formatée NDEF ; `formatable` = elle peut l'être ; `inscriptible` = non verrouillée ;
     * `vide` = aucun enregistrement utile (message absent ou « enregistrement vide »).
     */
    fun classer(ndefPresent: Boolean, formatable: Boolean, inscriptible: Boolean, vide: Boolean): GenreCarte = when {
        ndefPresent && !inscriptible -> GenreCarte.VERROUILLEE
        ndefPresent && vide -> GenreCarte.FORMATEE_VIDE
        ndefPresent -> GenreCarte.AVEC_CONTENU
        formatable -> GenreCarte.VIERGE
        else -> GenreCarte.NON_INSCRIPTIBLE
    }

    /** Ajoute (ou met à jour) une carte dans la liste de tri : la plus récemment lue passe en tête, son nombre de passages augmente. */
    fun fusionnerCarte(liste: List<CarteVue>, nouvelle: CarteVue): List<CarteVue> {
        val avant = liste.firstOrNull { it.id == nouvelle.id }
        return listOf(nouvelle.copy(passages = (avant?.passages ?: 0) + 1)) + liste.filter { it.id != nouvelle.id }
    }

    /** Bilan du tri : « 5 cartes · 2 vierges · 2 formatées vides · 1 avec données ». */
    fun bilanTri(liste: List<CarteVue>): String {
        if (liste.isEmpty()) return "Aucune carte lue"
        val n = liste.size
        val parties = mutableListOf("$n carte${if (n > 1) "s" else ""}")
        GenreCarte.values().forEach { g -> val k = liste.count { it.genre == g }; if (k > 0) parties += "$k ${g.libelle.substringBefore(" (").lowercase()}" }
        return parties.joinToString(" · ")
    }

    /** Un enregistrement NDEF résumé : type, taille et début du contenu lisible (« URI https://… », « MIME application/… »). */
    fun resumeEnregistrement(tnf: Short, type: ByteArray, payload: ByteArray): String {
        val t = String(type, Charsets.US_ASCII)
        return when {
            tnf == NdefRecord.TNF_WELL_KNOWN && t == "U" && payload.isNotEmpty() -> "URI " + prefixeUri(payload[0].toInt() and 0xFF) + String(payload, 1, payload.size - 1, Charsets.UTF_8) + " (${payload.size} octets)"
            tnf == NdefRecord.TNF_WELL_KNOWN && t == "T" && payload.isNotEmpty() -> "Texte « " + String(payload, 1 + (payload[0].toInt() and 0x3F), payload.size - 1 - (payload[0].toInt() and 0x3F), Charsets.UTF_8) + " » (${payload.size} octets)"
            tnf == NdefRecord.TNF_MIME_MEDIA -> "MIME $t : " + String(payload, Charsets.UTF_8).take(80) + " (${payload.size} octets)"
            tnf == NdefRecord.TNF_EXTERNAL_TYPE && t.startsWith("android.com:pkg") -> "Application " + String(payload, Charsets.UTF_8)
            tnf == NdefRecord.TNF_EMPTY -> "Enregistrement vide"
            else -> "Enregistrement $t (${payload.size} octets)"
        }
    }

    private fun prefixeUri(code: Int) = when (code) { 0 -> ""; 1 -> "http://www."; 2 -> "https://www."; 3 -> "http://"; 4 -> "https://"; 5 -> "tel:"; else -> "" }

    /** Début de l'adresse publique d'une ordonnance (site SAWALI) : le jeton s'ajoute à la fin. */
    const val ADRESSE_SITE_ORDONNANCE = "https://sawalismartsystems.com/officine/ordonnance/"

    /** Contenus de simulation proposés pour une carte vierge (liste finie → liste déroulante). Ils ne contiennent AUCUNE donnée réelle. */
    val CONTENUS = listOf("Ordonnance (lien de test)", "Carte marchand (simulation, non signée)", "Étiquette médicament (simulation)", "Texte libre de test")

    /** Message NDEF de simulation pour ce contenu ; `jeton` = identifiant aléatoire de test. */
    fun messageDeTest(contenu: String, jeton: String, paquet: String): NdefMessage {
        val enregistrement = when (contenu) {
            // Version 1.24 : la page publique des ordonnances est sur le SITE (sawalismartsystems.com), pas sur l'adresse de l'API : le lien de test s'ouvre vraiment
            CONTENUS[0] -> NdefRecord.createUri(ADRESSE_SITE_ORDONNANCE + "TEST-$jeton")
            CONTENUS[1] -> NdefRecord.createMime("application/vnd.shuyah.marchand", "{\"test\":true,\"m\":\"Pharmacie TEST\",\"op\":\"Operateur TEST\",\"code\":\"000000\",\"ussd\":\"*000*TEST*<montant>#\",\"jeton\":\"$jeton\"}".toByteArray(Charsets.UTF_8))
            CONTENUS[2] -> NdefRecord.createMime(EtiquetteNfc.MIME, EtiquetteNfc.charge("test$jeton".take(8), 0).toByteArray(Charsets.UTF_8))
            else -> NdefRecord.createTextRecord("fr", "Test NFC Personnel de maison $jeton")
        }
        return NdefMessage(arrayOf(enregistrement, NdefRecord.createApplicationRecord(paquet)))
    }

    private fun maintenant() = SystemClock.elapsedRealtime()

    /**
     * Inspecte la carte (identifiant, technologies, type, état NDEF) en journalisant chaque constat. Rend le message NDEF qu'elle contient (ou null) et si elle est inscriptible
     * (null = inconnu), pour que le mode écriture sache s'il faut la formater.
     */
    private fun inspecter(tag: Tag, journal: (Etape) -> Unit): Triple<Ndef?, NdefFormatable?, Boolean> {
        journal(Etape("Carte détectée", Etat.OK, "Identifiant " + hex(tag.id)))
        journal(Etape("Technologies de la carte", Etat.INFO, tag.techList.joinToString(", ") { it.substringAfterLast('.') }))
        NfcA.get(tag)?.let { a ->
            val sak = a.sak.toInt()
            journal(Etape("Famille", Etat.INFO, familleDepuisSak(sak) + " · ATQA " + hex(a.atqa, "")))
            try {
                a.connect()
                try { typeDepuisVersion(a.transceive(byteArrayOf(0x60)))?.let { journal(Etape("Type de carte", Etat.OK, "${it.first} — capacité utile ${it.second} octets")) } ?: journal(Etape("Type de carte", Etat.INFO, "Non identifié par GET_VERSION (carte ancienne ou autre famille)")) }
                finally { try { a.close() } catch (e: Exception) {} }
            } catch (e: Exception) { journal(Etape("Type de carte", Etat.INFO, "GET_VERSION non pris en charge")) }
        }
        MifareClassic.get(tag)?.let { journal(Etape("Mifare Classic", Etat.INFO, "${it.size} octets, ${it.sectorCount} secteurs (écriture NDEF selon le téléphone)")) }
        MifareUltralight.get(tag)?.let { journal(Etape("Mifare Ultralight", Etat.INFO, "type ${it.type}")) }
        val ndef = Ndef.get(tag); val formatable = NdefFormatable.get(tag)
        when {
            ndef != null -> journal(Etape("État NDEF", Etat.OK, "Formatée NDEF · " + (if (ndef.isWritable) "inscriptible" else "LECTURE SEULE") + " · capacité maximale ${ndef.maxSize} octets · type ${ndef.type}"))
            formatable != null -> journal(Etape("État NDEF", Etat.OK, "Vierge (non formatée) : elle sera formatée à l'écriture"))
            else -> journal(Etape("État NDEF", Etat.ECHEC, "Cette carte ne prend pas en charge NDEF (carte bancaire, de transport, ou technologie non gérée)"))
        }
        return Triple(ndef, formatable, ndef?.isWritable ?: (formatable != null))
    }

    private fun contenuActuel(ndef: Ndef?, journal: (Etape) -> Unit) {
        try {
            ndef?.connect()
            val m = ndef?.ndefMessage ?: ndef?.cachedNdefMessage
            if (m == null || m.records.isEmpty()) journal(Etape("Contenu actuel", Etat.INFO, "vide"))
            else m.records.forEachIndexed { i, r -> journal(Etape("Contenu actuel · enregistrement ${i + 1}", Etat.INFO, resumeEnregistrement(r.tnf, r.type, r.payload))) }
        } catch (e: Exception) { journal(Etape("Contenu actuel", Etat.INFO, "illisible : " + (e.message ?: e.javaClass.simpleName))) }
        finally { try { ndef?.close() } catch (e: Exception) {} }
    }

    /** Mode LECTURE : inspecte la carte et affiche son contenu, sans rien écrire. */
    fun demarrerLecture(a: Activity, journal: (Etape) -> Unit, surFin: () -> Unit) = lecteur(a, journal, surFin) { tag ->
        val debut = maintenant()
        val (ndef, _, _) = inspecter(tag, journal)
        contenuActuel(ndef, journal)
        journal(Etape("Lecture terminée", Etat.OK, "", maintenant() - debut)); surFin()
    }

    /**
     * Mode ÉCRITURE (ou EFFACEMENT si `message` est null) : inspecte, prépare, écrit (en formatant si besoin), puis RELIT la carte et compare avec ce qui devait être écrit.
     * Ne verrouille jamais la carte.
     */
    fun demarrerEcriture(a: Activity, message: NdefMessage?, journal: (Etape) -> Unit, surFin: (Boolean) -> Unit) {
        // Version 1.20 : une carte VIERGE (ex. Mifare Classic) n'est « NDEF » qu'après le formatage ; Android ne met pas à jour la liste de
        // ses technologies pendant la même présentation. La vérification par relecture se fait donc à la présentation SUIVANTE de la carte :
        // `aVerifier` garde alors le message attendu (null = aucune vérification en attente).
        var aVerifier: NdefMessage? = null
        var debutVerification = 0L
        lecteur(a, journal, { surFin(false) }) { tag ->
            // --- Deuxième présentation : relecture de contrôle après un formatage ---
            val attendu = aVerifier
            if (attendu != null) {
                aVerifier = null
                journal(Etape("Carte représentée", Etat.OK, "Identifiant " + hex(tag.id)))
                val relu = Ndef.get(tag)
                if (relu == null) { journal(Etape("Vérification par relecture", Etat.ECHEC, "la carte n'est pas reconnue comme formatée NDEF")); surFin(false); return@lecteur }
                try { relu.connect(); val lu = relu.ndefMessage
                    val identique = lu != null && lu.toByteArray().contentEquals(attendu.toByteArray())
                    journal(Etape("Vérification par relecture", if (identique) Etat.OK else Etat.ECHEC, if (identique) "le contenu relu est IDENTIQUE à celui écrit (${lu!!.byteArrayLength} octets)" else "le contenu relu est DIFFÉRENT de celui écrit", maintenant() - debutVerification))
                    journal(Etape(if (identique) "Test réussi" else "Test échoué", if (identique) Etat.OK else Etat.ECHEC))
                    surFin(identique)
                } catch (e: Exception) { journal(Etape("Vérification par relecture", Etat.ECHEC, "interrompue : " + (e.message ?: e.javaClass.simpleName))); surFin(false) }
                finally { try { relu.close() } catch (e: Exception) {} }
                return@lecteur
            }
            // --- Première présentation : inspection, écriture (avec formatage si besoin) ---
            val debutTotal = maintenant()
            val (ndef, formatable, inscriptible) = inspecter(tag, journal)
            if (ndef == null && formatable == null) { journal(Etape("Arrêt", Etat.ECHEC, "Rien ne peut être écrit sur cette carte.")); surFin(false); return@lecteur }
            if (!inscriptible) { journal(Etape("Arrêt", Etat.ECHEC, "La carte est en lecture seule (verrou définitif) : utilisez une carte vierge.")); surFin(false); return@lecteur }
            contenuActuel(ndef, journal)
            val aEcrire = message ?: NdefMessage(NdefRecord(NdefRecord.TNF_EMPTY, ByteArray(0), ByteArray(0), ByteArray(0)))
            val taille = aEcrire.byteArrayLength
            message?.records?.forEachIndexed { i, r -> journal(Etape("Message préparé · enregistrement ${i + 1}", Etat.INFO, resumeEnregistrement(r.tnf, r.type, r.payload))) }
            if (message == null) journal(Etape("Préparation", Etat.INFO, "Effacement : un message vide sera écrit"))
            journal(Etape("Taille du message", if (ndef != null && ndef.maxSize < taille) Etat.ECHEC else Etat.OK, "$taille octets" + (ndef?.let { " sur ${it.maxSize} disponibles" } ?: "")))
            if (ndef != null && ndef.maxSize < taille) { journal(Etape("Arrêt", Etat.ECHEC, "Carte trop petite : prenez une NTAG213 ou plus.")); surFin(false); return@lecteur }
            try {
                val t0 = maintenant()
                journal(Etape(if (ndef != null) "Écriture" else "Formatage et écriture", Etat.EN_COURS, "gardez la carte contre le téléphone…"))
                if (ndef != null) { ndef.connect(); try { ndef.writeNdefMessage(aEcrire) } finally { try { ndef.close() } catch (e: Exception) {} } }
                else { formatable!!.connect(); try { formatable.format(aEcrire) } finally { try { formatable.close() } catch (e: Exception) {} } }
                journal(Etape(if (ndef != null) "Écriture" else "Formatage et écriture", Etat.OK, "terminée", maintenant() - t0))
                if (ndef == null) {
                    // Carte qui vient d'être formatée : relecture à la présentation suivante (voir plus haut)
                    aVerifier = aEcrire; debutVerification = maintenant()
                    journal(Etape("Vérification à faire", Etat.EN_COURS, "ÉCRITURE RÉUSSIE. Éloignez la carte du téléphone puis REPOSEZ-LA : elle sera relue et comparée automatiquement."))
                    return@lecteur
                }
                // Carte déjà formatée : on rouvre la carte (nouvelle connexion) et on compare octet par octet
                val t1 = maintenant()
                val relu = Ndef.get(tag)
                if (relu == null) { journal(Etape("Vérification", Etat.ECHEC, "Impossible de rouvrir la carte pour la relire")); surFin(false); return@lecteur }
                relu.connect()
                val lu = try { relu.ndefMessage } finally { try { relu.close() } catch (e: Exception) {} }
                val identique = lu != null && lu.toByteArray().contentEquals(aEcrire.toByteArray())
                journal(Etape("Vérification par relecture", if (identique) Etat.OK else Etat.ECHEC, if (identique) "le contenu relu est IDENTIQUE à celui écrit (${lu!!.byteArrayLength} octets)" else "le contenu relu est DIFFÉRENT de celui écrit", maintenant() - t1))
                journal(Etape(if (identique) "Test réussi" else "Test échoué", if (identique) Etat.OK else Etat.ECHEC, "", maintenant() - debutTotal))
                surFin(identique)
            } catch (e: Exception) {
                journal(Etape("Écriture", Etat.ECHEC, "interrompue : " + (e.message ?: e.javaClass.simpleName) + " — gardez la carte contre le téléphone et recommencez")); surFin(false)
            }
        }
    }

    /**
     * Mode TRI : le lecteur reste ouvert ; à CHAQUE carte posée, `surCarte` reçoit son identifiant, son genre (vierge, formatée vide, avec données…) et un résumé du contenu.
     * Rien n'est écrit. On pose les cartes l'une après l'autre : l'écran liste le résultat de chacune.
     */
    fun demarrerTri(a: Activity, surCarte: (CarteVue) -> Unit, surErreur: () -> Unit) = lecteur(a, { }, surErreur) { tag ->
        val ndef = Ndef.get(tag); val formatable = NdefFormatable.get(tag)
        var resume = ""; var vide = true
        if (ndef != null) try {
            ndef.connect()
            val m = ndef.ndefMessage ?: ndef.cachedNdefMessage
            val utiles = m?.records?.filter { it.tnf != NdefRecord.TNF_EMPTY } ?: emptyList()
            vide = utiles.isEmpty()
            resume = if (vide) "aucun contenu" else resumeEnregistrement(utiles[0].tnf, utiles[0].type, utiles[0].payload).take(90)
        } catch (e: Exception) { resume = "contenu illisible" } finally { try { ndef.close() } catch (e: Exception) {} }
        val sak = NfcA.get(tag)?.sak?.toInt()
        val genre = classer(ndef != null, formatable != null, ndef?.isWritable ?: true, vide)
        surCarte(CarteVue(hex(tag.id), genre, resume, sak?.let { familleDepuisSak(it).substringBefore(" —") } ?: "famille inconnue", 1, System.currentTimeMillis()))
    }

    /**
     * Version 1.20 : on NE met PAS l'option « ignorer le contrôle NDEF ». Avec elle, Android ne signale ni « Ndef » ni « NdefFormatable » : une carte
     * vierge apparaissait à tort comme « ne prend pas en charge NDEF » (constat du 13/11/2026 sur une Mifare Classic 1K, que TagInfo voyait bien formatable).
     */
    private fun lecteur(a: Activity, journal: (Etape) -> Unit, surErreur: () -> Unit, surCarte: (Tag) -> Unit) {
        val adaptateur = NfcAdapter.getDefaultAdapter(a) ?: return
        adaptateur.enableReaderMode(a, { tag ->
            // Version 1.21 : une erreur inattendue est AFFICHÉE dans le journal (avant, elle était avalée et « rien ne se produisait »)
            try { surCarte(tag) } catch (e: Exception) { journal(Etape("Erreur inattendue", Etat.ECHEC, (e.message ?: e.javaClass.simpleName) + " — éloignez la carte, reposez-la et recommencez")); surErreur() }
        }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V, Bundle())
    }

    fun arreter(a: Activity) { try { NfcAdapter.getDefaultAdapter(a)?.disableReaderMode(a) } catch (e: Exception) {} }

    /** Identifiant aléatoire court pour marquer un contenu de test (8 caractères hexadécimaux). */
    fun jetonDeTest(): String = ByteArray(4).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
}
