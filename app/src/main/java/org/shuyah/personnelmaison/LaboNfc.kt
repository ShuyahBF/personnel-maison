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

    /** Contenus de simulation proposés pour une carte vierge (liste finie → liste déroulante). Ils ne contiennent AUCUNE donnée réelle. */
    val CONTENUS = listOf("Ordonnance (lien de test)", "Carte marchand (simulation, non signée)", "Étiquette médicament (simulation)", "Texte libre de test")

    /** Message NDEF de simulation pour ce contenu ; `jeton` = identifiant aléatoire de test. */
    fun messageDeTest(contenu: String, jeton: String, paquet: String): NdefMessage {
        val enregistrement = when (contenu) {
            CONTENUS[0] -> NdefRecord.createUri("https://api.sawalismartsystems.com/officine/ordonnance/TEST-$jeton")
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
    fun demarrerLecture(a: Activity, journal: (Etape) -> Unit, surFin: () -> Unit) = lecteur(a) { tag ->
        val debut = maintenant()
        val (ndef, _, _) = inspecter(tag, journal)
        contenuActuel(ndef, journal)
        journal(Etape("Lecture terminée", Etat.OK, "", maintenant() - debut)); surFin()
    }

    /**
     * Mode ÉCRITURE (ou EFFACEMENT si `message` est null) : inspecte, prépare, écrit (en formatant si besoin), puis RELIT la carte et compare avec ce qui devait être écrit.
     * Ne verrouille jamais la carte.
     */
    fun demarrerEcriture(a: Activity, message: NdefMessage?, journal: (Etape) -> Unit, surFin: (Boolean) -> Unit) = lecteur(a) { tag ->
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
            // Vérification : on rouvre la carte (nouvelle connexion) et on compare octet par octet
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

    private fun lecteur(a: Activity, surCarte: (Tag) -> Unit) {
        val adaptateur = NfcAdapter.getDefaultAdapter(a) ?: return
        adaptateur.enableReaderMode(a, { tag -> try { surCarte(tag) } catch (e: Exception) { /* chaque étape a son propre journal */ } },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, Bundle())
    }

    fun arreter(a: Activity) { try { NfcAdapter.getDefaultAdapter(a)?.disableReaderMode(a) } catch (e: Exception) {} }

    /** Identifiant aléatoire court pour marquer un contenu de test (8 caractères hexadécimaux). */
    fun jetonDeTest(): String = ByteArray(4).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
}
