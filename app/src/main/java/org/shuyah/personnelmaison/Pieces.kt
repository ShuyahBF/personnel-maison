package org.shuyah.personnelmaison

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

/**
 * COPIES DES PIÈCES D'IDENTITÉ : chaque photo est réduite (1600 px au plus), puis enregistrée CHIFFRÉE (AES-256, clé du Keystore Android)
 * dans le dossier privé de l'application : aucune autre application ne peut la lire et elle n'apparaît pas dans la galerie.
 * L'OCR (lecture du texte) est fait sur le téléphone par ML Kit, sans envoyer l'image nulle part.
 */
object Pieces {
    private fun cle(c: Context) = MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    private fun dossier(c: Context) = File(c.filesDir, "pieces").also { it.mkdirs() }

    /** Charge une image depuis une adresse (photo prise ou galerie) en la réduisant ; null si illisible. */
    suspend fun charger(c: Context, uri: Uri, maxPx: Int = 1600): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, limites) }
            var echantillon = 1
            while (maxOf(limites.outWidth, limites.outHeight) / echantillon > 2400) echantillon *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = echantillon }
            val brut = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return@withContext null
            val plusGrand = maxOf(brut.width, brut.height)
            if (plusGrand <= maxPx) brut else {
                val f = maxPx.toFloat() / plusGrand
                Bitmap.createScaledBitmap(brut, (brut.width * f).toInt(), (brut.height * f).toInt(), true)
            }
        } catch (e: Exception) { null }
    }

    /** Enregistre l'image chiffrée ; retourne le nom du fichier (à garder dans la fiche du personnel). */
    suspend fun enregistrer(c: Context, image: Bitmap): String = withContext(Dispatchers.IO) {
        val nom = UUID.randomUUID().toString() + ".enc"
        val octets = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        val fichier = EncryptedFile.Builder(c, File(dossier(c), nom), cle(c), EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB).build()
        fichier.openFileOutput().use { it.write(octets) }
        nom
    }

    /** Relit et déchiffre une image enregistrée ; null si absente. */
    suspend fun lire(c: Context, nom: String?): Bitmap? = withContext(Dispatchers.IO) {
        if (nom.isNullOrBlank()) return@withContext null
        try {
            val f = File(dossier(c), nom)
            if (!f.exists()) return@withContext null
            val octets = EncryptedFile.Builder(c, f, cle(c), EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB).build().openFileInput().use { it.readBytes() }
            BitmapFactory.decodeByteArray(octets, 0, octets.size)
        } catch (e: Exception) { null }
    }

    /** Enregistre des octets quelconques (PDF) chiffrés ; retourne le nom du fichier. */
    suspend fun enregistrerOctets(c: Context, octets: ByteArray): String = withContext(Dispatchers.IO) {
        val nom = UUID.randomUUID().toString() + ".enc"
        EncryptedFile.Builder(c, File(dossier(c), nom), cle(c), EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB).build().openFileOutput().use { it.write(octets) }
        nom
    }

    /** Relit et déchiffre un fichier enregistré ; null si absent. */
    suspend fun lireOctets(c: Context, nom: String?): ByteArray? = withContext(Dispatchers.IO) {
        if (nom.isNullOrBlank()) return@withContext null
        try {
            val f = File(dossier(c), nom)
            if (!f.exists()) null else EncryptedFile.Builder(c, f, cle(c), EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB).build().openFileInput().use { it.readBytes() }
        } catch (e: Exception) { null }
    }

    /** Octets d'un fichier choisi (galerie, gestionnaire de fichiers) ; null si illisible ou trop gros (20 Mo). */
    suspend fun octetsDe(c: Context, uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        try { c.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.takeIf { it.size <= 20 * 1024 * 1024 } } catch (e: Exception) { null }
    }

    /**
     * Pages d'un PDF enregistré, rendues en images dans l'application (le PDF déchiffré n'est écrit que le temps du rendu, dans le dossier
     * temporaire privé, puis supprimé). 10 pages au plus.
     */
    suspend fun pagesPdf(c: Context, nom: String?, maxPages: Int = 10): List<Bitmap> = withContext(Dispatchers.IO) {
        val octets = lireOctets(c, nom) ?: return@withContext emptyList()
        val tmp = File(c.cacheDir, "pdf_" + UUID.randomUUID() + ".pdf")
        try {
            tmp.writeBytes(octets)
            android.os.ParcelFileDescriptor.open(tmp, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                android.graphics.pdf.PdfRenderer(pfd).use { r ->
                    (0 until minOf(r.pageCount, maxPages)).map { i ->
                        r.openPage(i).use { page ->
                            val w = 1200; val h = (w.toFloat() / page.width * page.height).toInt()
                            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(android.graphics.Color.WHITE); page.render(it, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                        }
                    }
                }
            }
        } catch (e: Exception) { emptyList() } finally { tmp.delete() }
    }

    /** Supprime la copie chiffrée. */
    fun supprimer(c: Context, nom: String?) { if (!nom.isNullOrBlank()) File(dossier(c), nom).delete() }

    /** OCR : texte lu sur l'image (vide si rien n'est reconnu ou si ML Kit échoue). */
    suspend fun lireTexte(image: Bitmap): String = suspendCancellableCoroutine { suite ->
        try {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(InputImage.fromBitmap(image, 0))
                .addOnSuccessListener { suite.resume(it.text) }
                .addOnFailureListener { suite.resume("") }
        } catch (e: Exception) { suite.resume("") }
    }
}
