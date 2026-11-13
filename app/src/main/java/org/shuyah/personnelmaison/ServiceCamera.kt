package org.shuyah.personnelmaison

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * ÉTAT DE DIAGNOSTIC de la caméra partagée : ce que le service a fait au dernier tour et pourquoi aucune image ne part le cas échéant. Gardé en mémoire, lu par l'écran Ma famille
 * (carte « Ma caméra ») toutes les secondes.
 */
object EtatCamera {
    @Volatile var dernierTourMs = 0L
    @Volatile var etape = "Le service n'a pas encore tourné."
    @Volatile var erreur: String? = null                  // dernière erreur (capture ou envoi), effacée par un envoi réussi
    @Volatile var imagesEnvoyees = 0
    @Volatile var dernierEnvoiMs = 0L
    @Volatile var dernierePhotoKo = 0
    @Volatile var tours = 0
}

/**
 * CAMÉRA PARTAGÉE : service au premier plan avec une NOTIFICATION PERMANENTE (« Caméra partagée avec … », bouton Arrêter) : le partage est toujours visible.
 * Toutes les X secondes : si un proche coché REGARDE (signal « je regarde » reçu depuis moins d'une minute) — ou en continu si ce mode est choisi —, une photo
 * est prise, réduite, chiffrée pour chaque proche concerné et déposée sur le serveur. L'arrêt efface les images du serveur.
 */
class ServiceCamera : Service() {
    companion object {
        private const val CANAL = "famille_camera"
        private const val ID_NOTIF = 4102
        private const val ACTION_ARRET = "org.shuyah.personnelmaison.ARRET_CAMERA"
        fun demarrer(c: Context) { ReglagesFamille.cameraActive(c, true); ContextCompat.startForegroundService(c, Intent(c, ServiceCamera::class.java)) }
        fun arreter(c: Context) { ReglagesFamille.cameraActive(c, false); c.stopService(Intent(c, ServiceCamera::class.java)) }
    }

    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        if (i?.action == ACTION_ARRET) { arreter(this); return START_NOT_STICKY }
        val n = notification("Caméra partagée avec vos proches : active")
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID_NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA) else startForeground(ID_NOTIF, n)
        } catch (e: Exception) {
            // Autorisation de la caméra absente, ou démarrage refusé par le système (application en arrière-plan) : on le dit au lieu de planter
            EtatCamera.etape = "Le service n'a pas pu démarrer."; EtatCamera.erreur = "Démarrage refusé : " + (e.message ?: e.javaClass.simpleName); arreter(this); return START_NOT_STICKY
        }
        EtatCamera.etape = "Service démarré : en attente du premier tour."; EtatCamera.erreur = null
        portee.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        portee.launch { while (isActive) { try { cycle() } catch (e: Exception) { /* jamais bloquant : prochain tour */ }; delay(ReglagesFamille.cameraIntervalleS(this@ServiceCamera).coerceIn(5, 600) * 1000L) } }
        return START_STICKY
    }

    private fun notification(texte: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Caméra partagée", NotificationManager.IMPORTANCE_LOW))
        val ouvrir = PendingIntent.getActivity(this, 6, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val arret = PendingIntent.getService(this, 7, Intent(this, ServiceCamera::class.java).setAction(ACTION_ARRET), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CANAL).setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("Ma famille").setContentText(texte)
            .setContentIntent(ouvrir).setOngoing(true).addAction(Notification.Action.Builder(null, "Arrêter la caméra", arret).build()).build()
    }

    /** Un tour : qui est concerné ? (proches cochés et vérifiés, qui regardent sauf mode continu), photo, envoi chiffré. Chaque étape est notée dans `EtatCamera` (diagnostic). */
    private suspend fun cycle() {
        EtatCamera.tours++; EtatCamera.dernierTourMs = System.currentTimeMillis()
        val choisis = ReglagesFamille.partageCameraAvec(this)
        val liens = try { ClientFamille.liens(this).filter { it.verifie && it.id in choisis } } catch (e: ClientFamille.Erreur) { EtatCamera.etape = "Serveur injoignable : impossible de lire les liens."; EtatCamera.erreur = e.message; return }
        if (liens.isEmpty()) { EtatCamera.etape = "Aucun proche coché : ouvrez la fiche d'un proche vérifié et activez « Partager ma caméra avec lui »."; getSystemService(NotificationManager::class.java).notify(ID_NOTIF, notification("Aucun proche coché : rien n'est partagé")); return }
        val concernes = if (ReglagesFamille.cameraSeulementSiRegarde(this)) liens.filter { try { ClientFamille.quelquUnRegarde(this, it.id) } catch (e: ClientFamille.Erreur) { false } } else liens
        getSystemService(NotificationManager::class.java).notify(ID_NOTIF, notification("Caméra partagée avec : " + liens.joinToString(", ") { it.nom } + if (concernes.isNotEmpty()) " (en cours de visionnage)" else ""))
        if (concernes.isEmpty()) { EtatCamera.etape = "En attente : personne ne regarde (aucun signal « je regarde » reçu depuis moins d'une minute). ${liens.joinToString(", ") { it.nom }} doit ouvrir « Voir la caméra de ${ClientFamille.nom(this)} »."; return }
        EtatCamera.etape = "${concernes.joinToString(", ") { it.nom }} regarde : prise de la photo…"
        val resultat = suspendCancellableCoroutine<Pair<ByteArray?, String?>> { k -> CaptureCamera.photoDetaillee(this, ReglagesFamille.cameraAvant(this)) { jpeg, raison -> if (k.isActive) k.resume(jpeg to raison) } }
        val jpeg = resultat.first
        if (jpeg == null) { EtatCamera.etape = "La photo a échoué."; EtatCamera.erreur = "Capture : " + (resultat.second ?: "raison inconnue"); return }
        EtatCamera.dernierePhotoKo = jpeg.size / 1024
        EtatCamera.etape = "Photo prise (${EtatCamera.dernierePhotoKo} Ko) : envoi chiffré à ${concernes.joinToString(", ") { it.nom }}…"
        var envoyees = 0
        concernes.forEach { try { ClientFamille.deposerImage(this, it, jpeg); envoyees++ } catch (e: ClientFamille.Erreur) { EtatCamera.erreur = "Envoi à ${it.nom} : ${e.message}" } }
        if (envoyees > 0) { EtatCamera.imagesEnvoyees += envoyees; EtatCamera.dernierEnvoiMs = System.currentTimeMillis(); EtatCamera.erreur = null; EtatCamera.etape = "Image envoyée à $envoyees proche(s)." }
        else EtatCamera.etape = "La photo est prise mais l'envoi a échoué."
    }

    /** À l'arrêt : les images partagées sont EFFACÉES du serveur (au plus 5 secondes d'attente). */
    override fun onDestroy() {
        portee.cancel()
        try {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(5000) { ReglagesFamille.partageCameraAvec(this@ServiceCamera).forEach { try { ClientFamille.effacerImage(this@ServiceCamera, it) } catch (e: Exception) {} } }
            }
        } catch (e: Exception) {}
        super.onDestroy()
    }
}
