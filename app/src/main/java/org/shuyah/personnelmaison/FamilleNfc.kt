package org.shuyah.personnelmaison

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.nfc.NfcAdapter
import android.nfc.cardemulation.HostApduService
import android.nfc.tech.IsoDep
import android.os.Bundle

/**
 * AJOUT D'UN PROCHE PAR CONTACT NFC (deux téléphones dos à dos).
 *
 * Pour un développeur WinDev : un téléphone joue le rôle d'une « carte » (émulation de carte, HCE) et l'autre celui du « lecteur » ; ils échangent des
 * messages courts (APDU) : le lecteur envoie « SELECT » avec l'identifiant de l'application, la carte répond avec le CODE D'INVITATION et l'EMPREINTE de sa clé
 * publique ; le lecteur renvoie alors l'empreinte de SA clé. Le lien est ensuite créé comme d'habitude (code), puis les deux téléphones comparent l'empreinte
 * reçue par NFC avec la clé que le serveur annonce : si elles concordent, le lien est vérifié AUTOMATIQUEMENT des deux côtés (le contact physique remplace la
 * comparaison du numéro de sécurité). Si elles diffèrent, la vérification manuelle reste nécessaire.
 * La logique des messages est pure (testée par FamilleNfcTest) ; le service et le lecteur Android sont dessous.
 */
object FamilleNfc {
    /** Identifiant d'application (AID propriétaire, préfixe F0) : « F0 'PMFAM' 01 ». */
    val AID = byteArrayOf(0xF0.toByte(), 0x50, 0x4D, 0x46, 0x41, 0x4D, 0x01)
    private val OK = byteArrayOf(0x90.toByte(), 0x00)
    private val INCONNU = byteArrayOf(0x6D, 0x00)
    private const val CLA_PROPRIETAIRE = 0x80.toByte()
    private const val INS_EMPREINTE = 0x10.toByte()

    /** Commande SELECT envoyée par le lecteur. */
    fun commandeSelect(): ByteArray = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, AID.size.toByte()) + AID

    /** Vrai si la commande est un SELECT de notre application. */
    fun estSelect(apdu: ByteArray): Boolean = apdu.size >= 5 + AID.size && apdu[0] == 0x00.toByte() && apdu[1] == 0xA4.toByte() && apdu.copyOfRange(5, 5 + AID.size).contentEquals(AID)

    /** Réponse de la « carte » : « CODE|EMPREINTE » suivi du statut 90 00. */
    fun reponseInvitation(code: String, empreinte: String): ByteArray = "$code|$empreinte".toByteArray(Charsets.UTF_8) + OK

    /** Lit la réponse de la carte : (code, empreinte) ou null si le statut n'est pas 90 00 ou le contenu mal formé. */
    fun lireReponseInvitation(reponse: ByteArray): Pair<String, String>? {
        if (reponse.size < 3 || !reponse.copyOfRange(reponse.size - 2, reponse.size).contentEquals(OK)) return null
        val p = String(reponse, 0, reponse.size - 2, Charsets.UTF_8).split("|")
        return if (p.size == 2 && p[0].filter { it.isLetterOrDigit() }.length == 8 && p[1].length == 64) p[0] to p[1] else null
    }

    /** Commande du lecteur qui transmet SON empreinte (64 caractères hexadécimaux). */
    fun commandeEmpreinte(empreinte: String): ByteArray { val d = empreinte.toByteArray(Charsets.US_ASCII); return byteArrayOf(CLA_PROPRIETAIRE, INS_EMPREINTE, 0x00, 0x00, d.size.toByte()) + d }

    /** Empreinte contenue dans une commande du lecteur, ou null. */
    fun lireEmpreinte(apdu: ByteArray): String? {
        if (apdu.size < 5 || apdu[0] != CLA_PROPRIETAIRE || apdu[1] != INS_EMPREINTE) return null
        val n = apdu[4].toInt() and 0xFF
        if (n != 64 || apdu.size < 5 + n) return null
        val s = String(apdu, 5, n, Charsets.US_ASCII)
        return if (s.all { it in '0'..'9' || it in 'a'..'f' }) s else null
    }

    fun statutOk() = OK
    fun statutInconnu() = INCONNU

    /** Invitation proposée par NFC en ce moment (téléphone qui invite) et empreinte reçue du lecteur. Gardées en mémoire seulement, 15 minutes au plus. */
    object Etat {
        @Volatile var code: String? = null
        @Volatile var empreinte: String? = null
        @Volatile var expireMs: Long = 0
        @Volatile var empreinteRecue: String? = null
        fun proposer(code: String, empreinte: String) { this.code = code; this.empreinte = empreinte; empreinteRecue = null; expireMs = System.currentTimeMillis() + 15 * 60_000L }
        fun arreter() { code = null; empreinte = null; expireMs = 0 }
        fun active() = code != null && System.currentTimeMillis() < expireMs
    }

    /** NFC du téléphone : 0 = absent, 1 = désactivé dans les réglages, 2 = prêt. */
    fun etatNfc(c: Context): Int { val a = NfcAdapter.getDefaultAdapter(c) ?: return 0; return if (a.isEnabled) 2 else 1 }

    /** L'activité qui contient ce contexte (nécessaire au mode lecteur). */
    fun activite(c: Context): Activity? = generateSequence(c) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()

    /**
     * Mode LECTEUR : attend qu'un téléphone « carte » soit approché, lit son code et son empreinte, lui envoie la nôtre. `surResultat(code, empreinte)` est appelé
     * (fil secondaire) ; `surErreur` si l'échange échoue. À arrêter avec `arreterLecteur`.
     */
    fun demarrerLecteur(a: Activity, monEmpreinte: String, surResultat: (String, String) -> Unit, surErreur: (String) -> Unit) {
        val adaptateur = NfcAdapter.getDefaultAdapter(a) ?: return
        adaptateur.enableReaderMode(a, { tag ->
            val iso = IsoDep.get(tag)
            if (iso == null) surErreur("Ce n'est pas un téléphone prêt à inviter.")
            else try {
                iso.connect(); iso.timeout = 3000
                val lu = lireReponseInvitation(iso.transceive(commandeSelect()))
                if (lu == null) surErreur("Le téléphone approché n'a pas d'invitation NFC en cours.")
                else { iso.transceive(commandeEmpreinte(monEmpreinte)); surResultat(lu.first, lu.second) }
            } catch (e: Exception) { surErreur("Échange interrompu : gardez les téléphones dos à dos et recommencez.") } finally { try { iso.close() } catch (e: Exception) {} }
        }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, Bundle())
    }

    fun arreterLecteur(a: Activity) { try { NfcAdapter.getDefaultAdapter(a)?.disableReaderMode(a) } catch (e: Exception) {} }
}

/** Service « carte » : répond au lecteur avec le code d'invitation tant qu'une invitation NFC est proposée, et retient l'empreinte que le lecteur transmet. */
class ServiceNfcFamille : HostApduService() {
    override fun processCommandApdu(commande: ByteArray, extras: Bundle?): ByteArray {
        if (FamilleNfc.estSelect(commande)) {
            val code = FamilleNfc.Etat.code; val empreinte = FamilleNfc.Etat.empreinte
            return if (FamilleNfc.Etat.active() && code != null && empreinte != null) FamilleNfc.reponseInvitation(code, empreinte) else FamilleNfc.statutInconnu()
        }
        FamilleNfc.lireEmpreinte(commande)?.let { if (FamilleNfc.Etat.active()) { FamilleNfc.Etat.empreinteRecue = it; return FamilleNfc.statutOk() } }
        return FamilleNfc.statutInconnu()
    }
    override fun onDeactivated(raison: Int) { /* contact terminé : rien à faire, l'état reste en mémoire jusqu'à la fin de l'invitation */ }
}
