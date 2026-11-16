package org.shuyah.personnelmaison

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.text.Normalizer
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * CHIFFREMENT DE LA SAUVEGARDE EN LIGNE (version 1.35) — même principe que la sauvegarde en ligne d'auRor : tout est chiffré SUR LE TÉLÉPHONE avec une clé tirée de la
 * PHRASE SECRÈTE de l'utilisateur ; le serveur (ShuyahStore) ne reçoit que des octets illisibles. Rien ici ne touche à Android : logique pure, testée sur ordinateur.
 *
 * Pour un développeur WinDev : la phrase secrète est transformée (PBKDF2, 200 000 tours : lent exprès contre les essais en série) en une clé « maître » de 32 octets, dont on
 * tire TROIS clés indépendantes en les « étiquetant » : « id » (le numéro de casier, public), « jeton » (la clé du casier, dont le serveur ne garde que l'empreinte) et « chiffre »
 * (la clé qui verrouille le contenu, qui ne quitte JAMAIS le téléphone). Connaître le numéro de casier ne donne ni la clé du casier ni celle du contenu.
 */
object SauvegardeCrypto {
    /** Sel fixe et public (il doit être retrouvé à partir de la seule phrase après une réinstallation). La force vient de la phrase et du nombre de tours. */
    private val SEL = "personnel-maison/sauvegarde/v1".toByteArray()
    private const val TOURS = 200_000
    const val LONGUEUR_MIN_PHRASE = 12

    /** Les trois clés tirées de la phrase : `id` et `jeton` en hexadécimal (64 caractères, ce que le serveur attend), `chiffre` = clé AES-256 en octets. */
    class Cles(val id: String, val jeton: String, val chiffre: ByteArray)

    /** Phrase « normalisée » : espaces superflus retirés, minuscules, accents composés de façon unique (« Été » et « été » donnent la même clé). */
    fun normaliser(phrase: String): String = Normalizer.normalize(phrase.trim().replace(Regex("\\s+"), " "), Normalizer.Form.NFC).lowercase()

    /** Phrase acceptable : 12 caractères au moins (la force de la sauvegarde dépend entièrement d'elle). */
    fun phraseValide(phrase: String) = normaliser(phrase).length >= LONGUEUR_MIN_PHRASE

    fun deriver(phrase: String): Cles {
        val maitre = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(normaliser(phrase).toCharArray(), SEL, TOURS, 256)).encoded
        fun etiquette(nom: String): ByteArray = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(maitre, "HmacSHA256")) }.doFinal(nom.toByteArray())
        fun hex(o: ByteArray) = o.joinToString("") { "%02x".format(it) }
        return Cles(hex(etiquette("id")), hex(etiquette("jeton")), etiquette("chiffre"))
    }

    /** Chiffre `clair` : octet de version (1) + vecteur aléatoire (12 octets) + texte chiffré avec sa marque d'authentification (AES-256-GCM). */
    fun chiffrer(cle: ByteArray, clair: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(cle, "AES"), GCMParameterSpec(128, iv)); updateAAD(byteArrayOf(1)) }
        return byteArrayOf(1) + iv + c.doFinal(clair)
    }

    /** Déchiffre ; lève `IllegalArgumentException` si la clé est fausse ou si le contenu a été modifié (la marque d'authentification ne correspond pas). */
    fun dechiffrer(cle: ByteArray, chiffre: ByteArray): ByteArray {
        require(chiffre.size > 29 && chiffre[0] == 1.toByte()) { "Sauvegarde illisible ou d'un format inconnu" }
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(cle, "AES"), GCMParameterSpec(128, chiffre.copyOfRange(1, 13))); updateAAD(byteArrayOf(1)) }
            return c.doFinal(chiffre, 13, chiffre.size - 13)
        } catch (e: java.security.GeneralSecurityException) { throw IllegalArgumentException("Phrase secrète inexacte ou sauvegarde abîmée") }
    }

    // ---------------------------------------------------------------- archive : un fichier ZIP en mémoire (nom → contenu)
    fun archiver(entrees: Map<String, ByteArray>): ByteArray {
        val sortie = ByteArrayOutputStream()
        ZipOutputStream(sortie).use { z -> entrees.forEach { (nom, octets) -> z.putNextEntry(ZipEntry(nom)); z.write(octets); z.closeEntry() } }
        return sortie.toByteArray()
    }

    fun ouvrir(zip: ByteArray): Map<String, ByteArray> {
        val resultat = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (!e.isDirectory) resultat[e.name] = z.readBytes() }
        }
        return resultat
    }

    // ---------------------------------------------------------------- préférences : (clé → valeur) ⇄ octets, en conservant le TYPE de chaque valeur
    fun prefsVersOctets(valeurs: Map<String, Any?>): ByteArray {
        val sortie = ByteArrayOutputStream()
        DataOutputStream(sortie).use { d ->
            val utiles = valeurs.filterValues { it != null }
            d.writeInt(utiles.size)
            utiles.forEach { (cle, v) ->
                d.writeUTF(cle)
                when (v) {
                    is String -> { d.writeByte(1); d.writeUTF(v) }
                    is Int -> { d.writeByte(2); d.writeInt(v) }
                    is Long -> { d.writeByte(3); d.writeLong(v) }
                    is Float -> { d.writeByte(4); d.writeFloat(v) }
                    is Boolean -> { d.writeByte(5); d.writeBoolean(v) }
                    is Set<*> -> { d.writeByte(6); d.writeInt(v.size); v.forEach { d.writeUTF(it.toString()) } }
                    else -> { d.writeByte(1); d.writeUTF(v.toString()) }
                }
            }
        }
        return sortie.toByteArray()
    }

    fun octetsVersPrefs(octets: ByteArray): Map<String, Any> {
        val resultat = LinkedHashMap<String, Any>()
        DataInputStream(ByteArrayInputStream(octets)).use { d ->
            repeat(d.readInt()) {
                val cle = d.readUTF()
                resultat[cle] = when (d.readByte().toInt()) {
                    1 -> d.readUTF(); 2 -> d.readInt(); 3 -> d.readLong(); 4 -> d.readFloat(); 5 -> d.readBoolean()
                    6 -> (1..d.readInt()).map { d.readUTF() }.toSet()
                    else -> throw IllegalArgumentException("Type de réglage inconnu")
                }
            }
        }
        return resultat
    }
}
