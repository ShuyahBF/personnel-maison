package org.shuyah.personnelmaison

import android.app.Activity
import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * MESURE EN DIRECT PAR RÉALITÉ AUGMENTÉE (v1.45) — Google ARCore (« Services Google Play pour la RA »).
 *
 * Pour un développeur WinDev : ARCore suit les mouvements du téléphone et repère les surfaces planes (sol, table, mur). À chaque image de la caméra,
 * on lui demande « quel point de la surface se trouve sous le centre de l'écran ? » ; il répond par un point en MÈTRES dans l'espace. Quand l'utilisateur
 * appuie sur « Poser un point », ce point est gardé (« ancre » : ARCore le maintient à sa place réelle même si on bouge). Les calculs (surface, longueurs,
 * position à l'écran) sont dans MesureSurface.kt.
 *
 * Ce fichier fait deux choses qu'on ne peut pas tester sans téléphone : afficher l'image de la caméra (OpenGL) et dialoguer avec ARCore.
 * Tout ce qui touche ARCore se fait sur le fil d'affichage OpenGL (les ordres de l'écran y sont envoyés par `enFile`).
 */
class MesureAr(private val activite: Activity) : GLSurfaceView.Renderer {

    /** Ce que l'écran doit dessiner pour l'image en cours. */
    data class Etat(
        val suivi: Boolean = false,                               // ARCore sait où est le téléphone
        val surfaceVisee: Boolean = false,                        // une surface est sous le centre de l'écran : on peut poser un point
        val reticule: MesureSurface.P3? = null,                   // point de la surface sous le centre de l'écran
        val points: List<MesureSurface.P3> = emptyList(),         // points déjà posés (mètres)
        val vue: FloatArray = FloatArray(16), val projection: FloatArray = FloatArray(16),
        val largeur: Int = 0, val hauteur: Int = 0,
        val message: String = "",
    )

    /**
     * IMAGE FIGÉE (v1.56) : photo de ce que voyait la caméra au moment où un point a été posé, avec les matrices de la caméra à cet instant ; elle permet de
     * revoir la mesure plus tard et de CORRIGER un point mal posé (main tremblante) en le déplaçant sur l'image.
     */
    class Instantane(val image: android.graphics.Bitmap, val vue: FloatArray, val projection: FloatArray, val largeur: Int, val hauteur: Int)

    /** Images figées gardées (4 au plus) : la première au 1er point, puis une nouvelle seulement quand un point posé n'apparaît dans aucune image déjà gardée. */
    private val instantanes = java.util.concurrent.CopyOnWriteArrayList<Instantane>()
    fun instantanes(): List<Instantane> = instantanes.toList()

    /** Appelé (sur le fil de l'écran) à chaque nouvelle image. */
    var surEtat: (Etat) -> Unit = {}
    /** Appelé quand un point demandé à l'écran (toucher) ne peut pas être posé : aucune surface repérée à cet endroit. */
    var surRefus: () -> Unit = {}

    private var session: Session? = null
    private val ancres = ArrayList<Anchor>()
    private val principal = Handler(Looper.getMainLooper())
    private var vueGl: GLSurfaceView? = null
    private var installationDemandee = false
    @Volatile private var enPause = true

    // ---- OpenGL : image de la caméra en fond d'écran ----
    private var texture = -1
    private var programme = 0
    private var largeur = 0; private var hauteur = 0
    private val quad: FloatBuffer = tampon(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))   // l'écran entier, en deux triangles
    private val uv: FloatBuffer = tampon(FloatArray(8))                                        // quelle partie de l'image caméra va sur chaque coin
    private fun tampon(v: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(v); position(0) }

    /** Vue à placer dans l'écran : elle affiche la caméra et fait tourner la mesure. */
    fun creerVue(c: Context): GLSurfaceView = GLSurfaceView(c).apply {
        preserveEGLContextOnPause = true
        setEGLContextClientVersion(2)
        setRenderer(this@MesureAr)
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        keepScreenOn = true                                         // l'écran ne s'éteint pas pendant qu'on mesure
        vueGl = this
    }

    /**
     * Prépare ARCore. Renvoie null si tout est prêt, sinon le message à afficher (téléphone non compatible, installation refusée…).
     * À rappeler quand l'écran revient au premier plan : Google Play peut avoir installé le composant entre-temps.
     */
    fun demarrer(): String? {
        try {
            if (session == null) {
                when (ArCoreApk.getInstance().requestInstall(activite, !installationDemandee)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { installationDemandee = true; return "Installation des « Services Google Play pour la RA » en cours : revenez ici ensuite." }
                    else -> {}
                }
                val s = Session(activite)
                s.configure(Config(s).apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL    // sols ET murs
                    focusMode = Config.FocusMode.AUTO
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    // v1.51 : si le téléphone sait estimer la PROFONDEUR (Depth API), les points visés sont plus justes qu'avec les seuls plans (constat : 136 cm mesurés pour 140 cm réels)
                    if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) depthMode = Config.DepthMode.AUTOMATIC
                })
                session = s
            }
            session!!.resume()
            enPause = false
            vueGl?.onResume()
            return null
        } catch (e: UnavailableUserDeclinedInstallationException) {
            return "La mesure en direct a besoin des « Services Google Play pour la RA » (gratuit, Google Play). Vous pouvez utiliser la mesure sur photo."
        } catch (e: UnavailableDeviceNotCompatibleException) {
            return "Ce téléphone n'est pas compatible avec la réalité augmentée de Google (ARCore). Utilisez la mesure sur photo."
        } catch (e: UnavailableArcoreNotInstalledException) {
            return "Les « Services Google Play pour la RA » ne sont pas installés. Utilisez la mesure sur photo, ou installez-les depuis Google Play."
        } catch (e: UnavailableApkTooOldException) {
            return "Les « Services Google Play pour la RA » doivent être mis à jour (Google Play)."
        } catch (e: UnavailableSdkTooOldException) {
            return "Cette version de l'application est trop ancienne pour la réalité augmentée : mettez l'application à jour."
        } catch (e: Exception) {
            session = null
            return "La caméra de mesure n'a pas pu démarrer (${e.javaClass.simpleName}). Fermez les autres applications qui utilisent la caméra, ou utilisez la mesure sur photo."
        }
    }

    /** Écran masqué : la caméra est rendue au téléphone. */
    fun suspendre() {
        enPause = true
        vueGl?.onPause()
        try { session?.pause() } catch (e: Exception) { }
    }

    /** Écran quitté : tout est libéré. */
    fun fermer() {
        suspendre()
        val s = session; session = null
        ancres.clear(); instantanes.clear()
        // La fermeture d'une session peut prendre du temps : hors du fil de l'écran
        Thread { try { s?.close() } catch (e: Exception) { } }.start()
    }

    private fun enFile(action: () -> Unit) { vueGl?.queueEvent(action) }
    @Volatile private var poseDemandee = false
    @Volatile private var poseX = -1f
    @Volatile private var poseY = -1f
    // Déplacement d'un point par glisser du doigt (v1.53) : numéro du point et position du doigt (pixels de la vue) ; -1 = aucun
    @Volatile private var deplaceIndice = -1
    @Volatile private var deplaceX = 0f
    @Volatile private var deplaceY = 0f

    /** « Poser un point » : le point visé à la prochaine image est gardé. Sans position : le centre de l'écran (viseur) ; avec (x, y) : l'endroit TOUCHÉ de l'écran (v1.53). */
    fun poser(x: Float = -1f, y: Float = -1f) { poseX = x; poseY = y; poseDemandee = true }

    /** Déplace le point numéro `indice` sous le doigt (x, y en pixels de la vue) : le point est reposé sur la surface visée à cet endroit. */
    fun deplacer(indice: Int, x: Float, y: Float) { deplaceX = x; deplaceY = y; deplaceIndice = indice }
    /** « Annuler » : retire le dernier point posé. */
    fun annulerDernier() = enFile { if (ancres.isNotEmpty()) ancres.removeAt(ancres.size - 1).detach() }
    /** « Recommencer » : retire tous les points. */
    fun vider() = enFile { ancres.forEach { it.detach() }; ancres.clear(); instantanes.clear() }

    /** Remplace tous les points par ceux-ci (positions en mètres) : après une CORRECTION sur l'image figée, quand la mesure reprend. À appeler une fois la caméra relancée. */
    fun remplacer(points: List<MesureSurface.P3>) = enFile {
        val s = session ?: return@enFile
        ancres.forEach { it.detach() }; ancres.clear()
        points.forEach { p -> try { ancres.add(s.createAnchor(com.google.ar.core.Pose(floatArrayOf(p.x.toFloat(), p.y.toFloat(), p.z.toFloat()), floatArrayOf(0f, 0f, 0f, 1f)))) } catch (e: Exception) { } }
    }

    // =====================================================================================
    // OpenGL
    // =====================================================================================

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Texture « externe » : ARCore y dépose chaque image de la caméra
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0); texture = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        // Petit programme graphique : recopie l'image de la caméra sur tout l'écran
        fun compiler(type: Int, source: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, source); GLES20.glCompileShader(it) }
        val sommets = compiler(GLES20.GL_VERTEX_SHADER, "attribute vec4 a_Position; attribute vec2 a_Tex; varying vec2 v_Tex; void main() { gl_Position = a_Position; v_Tex = a_Tex; }")
        val pixels = compiler(GLES20.GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 v_Tex; uniform samplerExternalOES u_Camera; void main() { gl_FragColor = texture2D(u_Camera, v_Tex); }")
        programme = GLES20.glCreateProgram().also { GLES20.glAttachShader(it, sommets); GLES20.glAttachShader(it, pixels); GLES20.glLinkProgram(it) }
    }

    override fun onSurfaceChanged(gl: GL10?, l: Int, h: Int) {
        largeur = l; hauteur = h
        GLES20.glViewport(0, 0, l, h)
    }

    @Suppress("DEPRECATION")
    private fun rotationEcran(): Int = try { activite.windowManager.defaultDisplay.rotation } catch (e: Exception) { Surface.ROTATION_0 }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val s = session ?: return
        if (enPause || largeur == 0) return
        try {
            s.setDisplayGeometry(rotationEcran(), largeur, hauteur)
            s.setCameraTextureName(texture)
            val image = s.update()
            // Cadrage de l'image caméra sur l'écran (dépend de l'orientation et du format de l'écran)
            if (image.hasDisplayGeometryChanged() || !uvPret) {
                image.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad, Coordinates2d.TEXTURE_NORMALIZED, uv)
                uvPret = true
            }
            dessinerCamera()

            val camera = image.camera
            val suivi = camera.trackingState == TrackingState.TRACKING
            val vue = FloatArray(16); val projection = FloatArray(16)
            camera.getViewMatrix(vue, 0)
            camera.getProjectionMatrix(projection, 0, 0.05f, 100f)

            // Point de la surface sous le CENTRE de l'écran : d'abord une surface plane reconnue, sinon un point isolé dont l'orientation est estimée
            val vise = if (suivi) viser(image, largeur / 2f, hauteur / 2f) else null
            if (poseDemandee) {
                poseDemandee = false
                // Position touchée (v1.53) : la surface est cherchée à CET endroit ; sinon le viseur du centre
                val cible = if (poseX >= 0f) (if (suivi) viser(image, poseX, poseY) else null) else vise
                if (cible != null && ancres.size < 60) {
                    val ancre = cible.createAnchor(); ancres.add(ancre)
                    // Image figée : au 1er point, puis quand ce point n'est visible dans aucune image déjà gardée
                    val pp = MesureSurface.P3(ancre.pose.tx().toDouble(), ancre.pose.ty().toDouble(), ancre.pose.tz().toDouble())
                    if (instantanes.size < 4 && instantanes.none { dansImage(pp, it) }) lireImage()?.let { instantanes.add(Instantane(it, vue.copyOf(), projection.copyOf(), largeur, hauteur)) }
                } else if (poseX >= 0f) principal.post { surRefus() }
                poseX = -1f; poseY = -1f
            }
            // Point glissé du doigt : repose le point sur la surface visée sous le doigt (l'ancien repère est retiré seulement si la nouvelle position est valable)
            val i = deplaceIndice
            if (i >= 0) {
                deplaceIndice = -1
                val cible = if (suivi) viser(image, deplaceX, deplaceY) else null
                if (cible != null && i in ancres.indices) { val ancien = ancres[i]; ancres[i] = cible.createAnchor(); ancien.detach() }
            }
            val points = ancres.filter { it.trackingState != TrackingState.STOPPED }.map { val p = it.pose; MesureSurface.P3(p.tx().toDouble(), p.ty().toDouble(), p.tz().toDouble()) }
            val reticule = vise?.hitPose?.let { MesureSurface.P3(it.tx().toDouble(), it.ty().toDouble(), it.tz().toDouble()) }
            val message = when {
                !suivi -> "Bougez lentement le téléphone pour qu'il repère les lieux…"
                vise == null -> "Visez le sol ou le mur et balayez lentement : la surface n'est pas encore repérée."
                else -> ""
            }
            publier(Etat(suivi, vise != null, reticule, points, vue, projection, largeur, hauteur, message))
        } catch (e: Exception) {
            // Image manquante, session en pause entre-temps… : on réessaie à l'image suivante, sans jamais faire tomber l'application
        }
    }
    private var uvPret = false

    /** Ce point de l'espace apparaît-il dans cette image figée (avec une marge de 24 pixels) ? */
    private fun dansImage(p: MesureSurface.P3, i: Instantane): Boolean {
        val e = MesureSurface.versEcran(p, i.vue, i.projection, i.largeur, i.hauteur) ?: return false
        return e.x in 24.0..(i.largeur - 24.0) && e.y in 24.0..(i.hauteur - 24.0)
    }

    /** Lit l'image de la caméra qui vient d'être dessinée (OpenGL, dans le bon sens). Appelé sur le fil OpenGL seulement ; null en cas de problème. */
    private fun lireImage(): android.graphics.Bitmap? = try {
        val w = largeur; val h = hauteur
        val tampon = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, tampon)
        tampon.rewind()
        val lu = tampon.asIntBuffer(); val pixels = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val v = lu.get(y * w + x)                                          // octets R, G, B, A rangés dans l'ordre du processeur
            pixels[(h - 1 - y) * w + x] = (0xFF shl 24) or ((v and 0xFF) shl 16) or (((v shr 8) and 0xFF) shl 8) or ((v shr 16) and 0xFF)
        }
        android.graphics.Bitmap.createBitmap(pixels, w, h, android.graphics.Bitmap.Config.ARGB_8888)
    } catch (e: Throwable) { null }

    /** Surface visée en (x, y) pixels : d'abord un plan reconnu (et dans son contour), puis un point de profondeur, enfin un point isolé d'orientation estimée. */
    private fun viser(image: com.google.ar.core.Frame, x: Float, y: Float): com.google.ar.core.HitResult? {
        val touches = image.hitTest(x, y)
        return touches.firstOrNull { val t = it.trackable; t is Plane && t.trackingState == TrackingState.TRACKING && t.isPoseInPolygon(it.hitPose) }
            ?: touches.firstOrNull { it.trackable is com.google.ar.core.DepthPoint }
            ?: touches.firstOrNull { val t = it.trackable; t is Point && t.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL }
    }

    private fun dessinerCamera() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(programme)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programme, "u_Camera"), 0)
        val position = GLES20.glGetAttribLocation(programme, "a_Position"); val tex = GLES20.glGetAttribLocation(programme, "a_Tex")
        quad.position(0); uv.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 0, uv)
        GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(tex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position); GLES20.glDisableVertexAttribArray(tex)
    }

    private fun publier(e: Etat) { principal.post { surEtat(e) } }

    companion object {
        /** Le téléphone peut-il faire de la réalité augmentée ? (réponse parfois différée d'une fraction de seconde : « inconnu » = on laissera ARCore répondre au démarrage) */
        fun compatible(c: Context): Boolean? = try {
            val d = ArCoreApk.getInstance().checkAvailability(c)
            when {
                d.isTransient -> null
                d.isSupported -> true
                else -> false
            }
        } catch (e: Exception) { false }
    }
}
