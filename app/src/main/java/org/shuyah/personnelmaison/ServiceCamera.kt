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
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID_NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA) else startForeground(ID_NOTIF, n)
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

    /** Un tour : qui est concerné ? (proches cochés et vérifiés, qui regardent sauf mode continu), photo, envoi chiffré. */
    private suspend fun cycle() {
        val choisis = ReglagesFamille.partageCameraAvec(this)
        val liens = ClientFamille.liens(this).filter { it.verifie && it.id in choisis }
        if (liens.isEmpty()) { getSystemService(NotificationManager::class.java).notify(ID_NOTIF, notification("Aucun proche coché : rien n'est partagé")); return }
        val concernes = if (ReglagesFamille.cameraSeulementSiRegarde(this)) liens.filter { try { ClientFamille.quelquUnRegarde(this, it.id) } catch (e: ClientFamille.Erreur) { false } } else liens
        getSystemService(NotificationManager::class.java).notify(ID_NOTIF, notification("Caméra partagée avec : " + liens.joinToString(", ") { it.nom } + if (concernes.isNotEmpty()) " (en cours de visionnage)" else ""))
        if (concernes.isEmpty()) return
        val jpeg = suspendCancellableCoroutine<ByteArray?> { k -> CaptureCamera.photo(this, ReglagesFamille.cameraAvant(this)) { if (k.isActive) k.resume(it) } } ?: return
        concernes.forEach { try { ClientFamille.deposerImage(this, it, jpeg) } catch (e: ClientFamille.Erreur) { /* réseau absent : prochain tour */ } }
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
