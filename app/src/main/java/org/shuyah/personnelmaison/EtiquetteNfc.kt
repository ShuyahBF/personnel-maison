package org.shuyah.personnelmaison

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Bundle
import java.security.SecureRandom
import kotlin.math.abs

/**
 * ÉTIQUETTE NFC DES MÉDICAMENTS : on écrit une petite étiquette NFC (autocollant ou carte vierge NTAG213 ou plus) que l'on colle sur la boîte de médicaments ;
 * poser ensuite le téléphone dessus CONFIRME LA PRISE du médicament et prévient les proches choisis.
 *
 * Ce que contient l'étiquette : UNIQUEMENT un identifiant (« pm-med|code de l'installation|numéro du médicament ») — aucun nom, aucune dose, aucune donnée de santé.
 * Le code de l'installation, tiré au hasard à la première utilisation, fait qu'une étiquette n'agit que sur le téléphone qui l'a écrite.
 *
 * Pour un développeur WinDev : la partie « logique » (charger, relire, choisir la prise) est pure et testée par EtiquetteNfcTest ; la partie NFC Android est dessous.
 */
object EtiquetteNfc {
    /** Types MIME des étiquettes (médicament, repas) : l'application est lancée quand une étiquette de ce type est approchée (voir le manifeste). */
    const val MIME = "application/vnd.shuyah.med"
    const val MIME_REPAS = "application/vnd.shuyah.repas"
    /** Valeur « selon l'heure » d'une étiquette de repas : le repas déclaré est celui dont l'heure est la plus proche. */
    const val REPAS_AUTO = "auto"
    /** Une étiquette de repas s'applique de 2 h 30 avant à 1 h 30 après l'heure du repas. */
    const val REPAS_AVANT_MIN = 150
    const val REPAS_APRES_MIN = 90
    private const val PREFIXE = "pm-med"
    /** Une prise peut être confirmée de 6 h après son heure prévue (prise tardive, oubli rattrapé) jusqu'à 90 minutes avant. */
    const val AVANT_MS = 90 * 60_000L
    const val APRES_MS = 6 * 3_600_000L

    /** Contenu de l'étiquette. */
    fun charge(codeInstallation: String, medicamentId: Long): String = "$PREFIXE|$codeInstallation|$medicamentId"

    /** Relit le contenu d'une étiquette : (code de l'installation, numéro du médicament) ou null si ce n'est pas une de nos étiquettes. */
    fun lireCharge(texte: String): Pair<String, Long>? {
        val p = texte.split("|")
        return if (p.size == 3 && p[0] == PREFIXE && p[1].length in 4..16) p[2].toLongOrNull()?.let { p[1] to it } else null
    }

    /** Contenu d'une étiquette de repas : « pm-repas|code de l'installation|code du repas ou auto ». */
    fun chargeRepas(codeInstallation: String, repas: String): String = "pm-repas|$codeInstallation|$repas"

    /** Relit une étiquette de repas : (code de l'installation, code du repas ou « auto »), ou null. */
    fun lireChargeRepas(texte: String): Pair<String, String>? {
        val p = texte.split("|")
        return if (p.size == 3 && p[0] == "pm-repas" && p[1].length in 4..16 && (p[2] == REPAS_AUTO || RappelsRepas.repas(p[2]) != null)) p[1] to p[2] else null
    }

    /** Repas à déclarer « selon l'heure » : parmi les repas suivis (code, heure en minutes), le plus proche de l'heure actuelle dans la fenêtre [−2 h 30, +1 h 30], ou null. */
    fun choisirRepas(suivis: List<Pair<String, Int>>, maintenantMin: Int): String? =
        suivis.filter { maintenantMin - it.second in -REPAS_AVANT_MIN..REPAS_APRES_MIN }.minByOrNull { abs(maintenantMin - it.second) }?.first

    /** Parmi les heures prévues non encore prises, celle qu'il faut confirmer maintenant : la plus proche de l'instant présent dans la fenêtre [−6 h, +90 min]. */
    fun choisirPrise(candidates: List<Long>, maintenantMs: Long): Long? =
        candidates.filter { it >= maintenantMs - APRES_MS && it <= maintenantMs + AVANT_MS }.minByOrNull { abs(it - maintenantMs) }

    /** Code propre à cette installation (créé une fois, gardé dans les préférences chiffrées). */
    fun codeInstallation(c: Context): String {
        val p = ClientFamille.prefs(c)
        p.getString("code_etiquettes", null)?.let { return it }
        val code = ByteArray(4).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        p.edit().putString("code_etiquettes", code).apply()
        return code
    }

    /** Le message NDEF à écrire : l'identifiant, plus un « enregistrement d'application » qui fait ouvrir cette application. */
    private fun message(c: Context, mime: String, contenu: String) = NdefMessage(arrayOf(
        NdefRecord.createMime(mime, contenu.toByteArray(Charsets.UTF_8)),
        NdefRecord.createApplicationRecord(c.packageName)))

    /** Une de nos étiquettes lue : `type` « med » ou « repas », code de l'installation et valeur (numéro du médicament, code du repas ou « auto »). */
    data class Etiquette(val type: String, val code: String, val valeur: String)

    /** Si l'intention reçue vient d'une de nos étiquettes, son contenu ; sinon null. */
    fun lireIntent(i: Intent?): Etiquette? {
        if (i?.action != NfcAdapter.ACTION_NDEF_DISCOVERED) return null
        @Suppress("DEPRECATION") val messages = i.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES) ?: return null
        for (m in messages) for (r in (m as NdefMessage).records) {
            if (r.tnf != NdefRecord.TNF_MIME_MEDIA) continue
            val type = String(r.type, Charsets.US_ASCII); val texte = String(r.payload, Charsets.UTF_8)
            if (type == MIME) lireCharge(texte)?.let { return Etiquette("med", it.first, it.second.toString()) }
            if (type == MIME_REPAS) lireChargeRepas(texte)?.let { return Etiquette("repas", it.first, it.second) }
        }
        return null
    }

    /**
     * Mode ÉCRITURE : attend qu'une étiquette soit approchée et y écrit l'identifiant de ce médicament. `surResultat` reçoit un message lisible (succès ou motif de l'échec).
     * À arrêter avec `FamilleNfc.arreterLecteur`.
     */
    fun demarrerEcriture(a: Activity, medicamentId: Long, surResultat: (ok: Boolean, message: String) -> Unit) =
        demarrerEcriture(a, MIME, charge(codeInstallation(a), medicamentId), surResultat)

    /** Mode écriture d'une étiquette de repas (`repas` = code du repas ou « auto » : selon l'heure). */
    fun demarrerEcritureRepas(a: Activity, repas: String, surResultat: (ok: Boolean, message: String) -> Unit) =
        demarrerEcriture(a, MIME_REPAS, chargeRepas(codeInstallation(a), repas), surResultat)

    private fun demarrerEcriture(a: Activity, mime: String, contenu: String, surResultat: (ok: Boolean, message: String) -> Unit) {
        val adaptateur = NfcAdapter.getDefaultAdapter(a) ?: return
        val msg = message(a, mime, contenu)
        adaptateur.enableReaderMode(a, { tag -> val (ok, texte) = ecrire(tag, msg); surResultat(ok, texte) },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V, Bundle())
    }

    /** Écrit le message sur l'étiquette ; rend (réussi, message pour l'utilisateur). */
    private fun ecrire(tag: Tag, msg: NdefMessage): Pair<Boolean, String> {
        try {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.connect()
                try {
                    if (!ndef.isWritable) return Pair(false, "Cette étiquette est en lecture seule : utilisez une étiquette vierge.")
                    if (ndef.maxSize < msg.byteArrayLength) return Pair(false, "Étiquette trop petite : prenez une NTAG213 ou plus.")
                    ndef.writeNdefMessage(msg)
                } finally { try { ndef.close() } catch (e: Exception) {} }
                return Pair(true, "Étiquette écrite. Collez-la à l'endroit voulu.")
            }
            val formatable = NdefFormatable.get(tag) ?: return Pair(false, "Cette carte n'est pas une étiquette NFC inscriptible (carte bancaire ou de transport ?).")
            formatable.connect()
            try { formatable.format(msg) } finally { try { formatable.close() } catch (e: Exception) {} }
            return Pair(true, "Étiquette formatée et écrite. Collez-la à l'endroit voulu.")
        } catch (e: Exception) { return Pair(false, "Écriture interrompue : gardez l'étiquette contre le téléphone et recommencez.") }
    }
}
