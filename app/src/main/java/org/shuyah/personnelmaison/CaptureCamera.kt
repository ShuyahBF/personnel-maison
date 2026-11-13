package org.shuyah.personnelmaison

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import java.io.ByteArrayOutputStream

/**
 * PRISE D'UNE PHOTO PAR LA CAMÉRA (API Camera2, sans bibliothèque supplémentaire) : la caméra est ouverte, on laisse passer une dizaine d'images pour que
 * l'exposition se règle, on garde la suivante, on la réduit (640 px de large) et on la compresse en JPEG, puis la caméra est refermée. Le résultat est
 * rendu à `surFin` (null en cas d'échec ou après 8 secondes). Aucune image n'est écrite sur le téléphone.
 *
 * Pour un développeur WinDev : tout se passe par rappels (« callbacks ») : l'ouverture de la caméra, la configuration de la session, puis chaque image reçue.
 */
object CaptureCamera {
    private const val IMAGES_A_PASSER = 10

    /** Version simple : la photo, ou null en cas d'échec. */
    fun photo(c: Context, avant: Boolean, surFin: (ByteArray?) -> Unit) = photoDetaillee(c, avant) { jpeg, _ -> surFin(jpeg) }

    /** Texte clair des codes d'erreur de la caméra Android (pour le diagnostic). */
    fun raisonErreur(code: Int) = when (code) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "caméra déjà utilisée par une autre application"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "trop de caméras ouvertes en même temps"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "caméra désactivée par le système (règle de sécurité, économiseur, ou accès refusé en arrière-plan)"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "erreur du pilote de la caméra"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "service caméra du téléphone en panne"
        else -> "erreur inconnue"
    } + " (code $code)"

    /** La photo ET, en cas d'échec, la RAISON précise (affichée dans le diagnostic de l'écran Ma famille). */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun photoDetaillee(c: Context, avant: Boolean, surFin: (ByteArray?, String?) -> Unit) {
        val gestionnaire = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val fil = HandlerThread("capture-famille").apply { start() }
        val h = Handler(fil.looper)
        var termine = false
        var camera: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var lecteur: ImageReader? = null
        fun finir(jpeg: ByteArray?, raison: String? = null) {
            if (termine) return
            termine = true
            try { session?.close() } catch (e: Exception) {}
            try { camera?.close() } catch (e: Exception) {}
            try { lecteur?.close() } catch (e: Exception) {}
            fil.quitSafely()
            surFin(jpeg, if (jpeg == null) (raison ?: "échec sans détail") else null)
        }
        try {
            val face = if (avant) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val id = gestionnaire.cameraIdList.firstOrNull { gestionnaire.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == face } ?: gestionnaire.cameraIdList.firstOrNull()
            if (id == null) { finir(null, "aucune caméra trouvée sur ce téléphone"); return }
            val caracteristiques = gestionnaire.getCameraCharacteristics(id)
            val tailles = caracteristiques.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YUV_420_888)
            val taille = tailles?.filter { it.width <= 1280 }?.maxByOrNull { it.width * it.height } ?: tailles?.minByOrNull { it.width * it.height }
            if (taille == null) { finir(null, "aucun format d'image compatible"); return }
            val rotation = caracteristiques.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val reader = ImageReader.newInstance(taille.width, taille.height, ImageFormat.YUV_420_888, 2).also { lecteur = it }
            var vues = 0
            reader.setOnImageAvailableListener({ r ->
                val image = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return@setOnImageAvailableListener
                try {
                    if (++vues >= IMAGES_A_PASSER && !termine) {
                        val p = image.planes
                        fun octets(i: Int) = ByteArray(p[i].buffer.remaining()).also { p[i].buffer.get(it) }
                        val nv21 = FamilleImage.versNv21(image.width, image.height, octets(0), p[0].rowStride, octets(1), octets(2), p[1].rowStride, p[1].pixelStride)
                        val flux = ByteArrayOutputStream()
                        YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null).compressToJpeg(Rect(0, 0, image.width, image.height), 85, flux)
                        val reduite = reduire(flux.toByteArray(), rotation); finir(reduite, if (reduite == null) "image illisible après compression" else null)
                    }
                } catch (e: Exception) { finir(null, "conversion de l'image impossible : " + (e.message ?: e.javaClass.simpleName)) } finally { try { image.close() } catch (e: Exception) {} }
            }, h)
            h.postDelayed({ finir(null, "délai dépassé (8 s) : la caméra n'a fourni aucune image") }, 8_000)                                    // délai maximal
            gestionnaire.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    camera = d
                    try {
                        d.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                try { s.setRepeatingRequest(d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(reader.surface); set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO) }.build(), null, h) } catch (e: Exception) { finir(null, "démarrage de la prise d'images impossible : " + (e.message ?: e.javaClass.simpleName)) }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { finir(null, "configuration de la caméra refusée par le téléphone") }
                        }, h)
                    } catch (e: Exception) { finir(null, "session de caméra impossible : " + (e.message ?: e.javaClass.simpleName)) }
                }
                override fun onDisconnected(d: CameraDevice) { d.close(); finir(null, "caméra déconnectée (utilisée par une autre application, ou fermée par le système)") }
                override fun onError(d: CameraDevice, erreur: Int) { d.close(); finir(null, raisonErreur(erreur)) }
            }, h)
        } catch (e: CameraAccessException) { finir(null, "accès à la caméra refusé par le système (" + e.message + ")") } catch (e: SecurityException) { finir(null, "autorisation de la caméra refusée") } catch (e: Exception) { finir(null, "caméra inutilisable : " + (e.message ?: e.javaClass.simpleName)) }
    }

    /** Redresse la photo (orientation du capteur), la réduit à 640 px de large et la recompresse (qualité 55) : de l'ordre de 30 à 60 Ko. */
    private fun reduire(jpeg: ByteArray, rotation: Int): ByteArray? {
        val source = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
        val (l, _) = FamilleImage.tailleCible(source.width, source.height, rotation)
        val echelle = l.toFloat() / (if (rotation % 180 == 90) source.height else source.width)
        val m = Matrix().apply { postRotate(rotation.toFloat()); postScale(echelle, echelle) }
        val final = Bitmap.createBitmap(source, 0, 0, source.width, source.height, m, true)
        val flux = ByteArrayOutputStream()
        final.compress(Bitmap.CompressFormat.JPEG, 55, flux)
        if (final !== source) source.recycle(); final.recycle()
        return flux.toByteArray()
    }
}
