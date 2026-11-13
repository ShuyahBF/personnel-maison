package org.shuyah.personnelmaison

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * CRYPTOGRAPHIE DE « MA FAMILLE » (logique pure, sans Android : testée par FamilleCryptoTest).
 *
 * Pour un développeur WinDev :
 *  - chaque téléphone possède une PAIRE DE CLÉS (courbe P-256) : la clé publique est envoyée au serveur (c'est ce que voit le correspondant),
 *    la clé privée ne quitte JAMAIS le téléphone ;
 *  - les deux personnes calculent la MÊME clé secrète sans jamais l'échanger (échange Diffie-Hellman « ECDH » entre ma clé privée et la clé
 *    publique de l'autre), puis la dérivent (HKDF-SHA-256) en une clé de 256 bits ;
 *  - les messages sont chiffrés avec AES-256-GCM (chiffrement ET contrôle d'intégrité) : le serveur ne voit que du texte chiffré ;
 *  - le NUMÉRO DE SÉCURITÉ (30 chiffres) est calculé à partir des deux clés publiques : identique des deux côtés si personne n'a remplacé une clé en
 *    route (même le serveur). Les deux personnes le comparent de vive voix : c'est la « vérification du lien ».
 */
object FamilleCrypto {
    private val b64e = Base64.getEncoder()
    private val b64d = Base64.getDecoder()
    private val hasard = SecureRandom()

    /** Nouvelle paire de clés : (clé publique X.509 en base64, clé privée PKCS#8 en base64). */
    fun genererPaire(): Pair<String, String> {
        val g = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val p = g.generateKeyPair()
        return b64e.encodeToString(p.public.encoded) to b64e.encodeToString(p.private.encoded)
    }

    private fun hmac(cle: ByteArray, donnees: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(cle, "HmacSHA256")) }.doFinal(donnees)

    /** Les deux clés publiques dans un ordre fixe (le même pour les deux personnes). */
    private fun triees(a: String, b: String) = listOf(a, b).sorted()

    /** Clé secrète de 32 octets partagée par les deux téléphones d'un lien (ECDH + HKDF-SHA-256, sel = empreinte des deux clés publiques). */
    fun cleCommune(priveeB64: String, publiqueAutreB64: String, maPubliqueB64: String): ByteArray {
        val kf = KeyFactory.getInstance("EC")
        val ka = KeyAgreement.getInstance("ECDH").apply {
            init(kf.generatePrivate(PKCS8EncodedKeySpec(b64d.decode(priveeB64))))
            doPhase(kf.generatePublic(X509EncodedKeySpec(b64d.decode(publiqueAutreB64))), true)
        }
        val secret = ka.generateSecret()
        val (x, y) = triees(maPubliqueB64, publiqueAutreB64)
        val sel = MessageDigest.getInstance("SHA-256").digest("$x|$y".toByteArray())
        val prk = hmac(sel, secret)                                                                       // HKDF : extraction
        return hmac(prk, "personnel-maison-famille-v1".toByteArray() + byteArrayOf(1))                    // HKDF : expansion (32 octets)
    }

    /** Chiffre un texte : (nonce, texte chiffré) en base64. `lien` est authentifié avec le message (un message ne peut pas être rejoué sur un autre lien). */
    fun chiffrer(cle: ByteArray, texte: String, lien: String): Pair<String, String> {
        val nonce = ByteArray(12).also { hasard.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(cle, "AES"), GCMParameterSpec(128, nonce)); updateAAD(lien.toByteArray()) }
        return b64e.encodeToString(nonce) to b64e.encodeToString(c.doFinal(texte.toByteArray(Charsets.UTF_8)))
    }

    /** Déchiffre un message ; null si la clé est mauvaise ou si le message a été altéré. */
    fun dechiffrer(cle: ByteArray, nonceB64: String, chiffreB64: String, lien: String): String? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(cle, "AES"), GCMParameterSpec(128, b64d.decode(nonceB64))); updateAAD(lien.toByteArray()) }
        String(c.doFinal(b64d.decode(chiffreB64)), Charsets.UTF_8)
    } catch (e: Exception) { null }

    /** Empreinte d'une clé publique (SHA-256 en hexadécimal, 64 caractères) : échangée par NFC pour vérifier un lien sans comparer le numéro de sécurité. */
    fun empreinte(publiqueB64: String): String = MessageDigest.getInstance("SHA-256").digest(b64d.decode(publiqueB64)).joinToString("") { "%02x".format(it) }

    /** Numéro de sécurité « 12345 67890 … » (6 groupes de 5 chiffres) : le même des deux côtés si les deux clés publiques sont les bonnes. */
    fun numeroSecurite(publiqueA: String, publiqueB: String): String {
        val (x, y) = triees(publiqueA, publiqueB)
        val h = MessageDigest.getInstance("SHA-256").digest("numero-securite|$x|$y".toByteArray())
        return (0 until 6).joinToString(" ") { g ->
            var v = 0L; for (i in 0 until 5) v = v * 256 + (h[g * 5 + i].toInt() and 0xFF)
            "%05d".format(v % 100000)
        }
    }
}
