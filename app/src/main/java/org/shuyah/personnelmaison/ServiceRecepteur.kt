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
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * SERVICE « RÉCEPTEUR DE BADGES » : service au premier plan (NOTIFICATION PERMANENTE avec bouton Arrêter) qui écoute les balises Bluetooth et envoie les détections à SAWALI
 * toutes les 5 secondes (contrat v1, voir PresenceRecepteur). Il garde le processeur éveillé (le téléphone doit rester branché sur secteur) et s'arrête à la demande.
 */
class ServiceRecepteur : Service() {
    companion object {
        private const val CANAL = "recepteur_badges"
        private const val ID_NOTIF = 4201
        private const val ACTION_ARRET = "org.shuyah.personnelmaison.ARRET_RECEPTEUR"

        fun demarrer(c: Context) { PresenceRecepteur.actif(c, true); ContextCompat.startForegroundService(c, Intent(c, ServiceRecepteur::class.java)) }
        fun arreter(c: Context) { PresenceRecepteur.actif(c, false); c.stopService(Intent(c, ServiceRecepteur::class.java)) }
    }

    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var boucle: Job? = null
    private var verrou: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        if (i?.action == ACTION_ARRET) { arreter(this); return START_NOT_STICKY }
        val notification = notification("Écoute des badges…")
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID_NOTIF, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) else startForeground(ID_NOTIF, notification)
        if (!ScanBalises.permissionsAccordees(this) || !ScanBalises.bluetoothActif(this)) { PresenceRecepteur.Statut.dernierMessage = "Bluetooth ou autorisations manquants"; arreter(this); return START_NOT_STICKY }
        verrou = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "personnelmaison:recepteur").apply { acquire() }
        ScanBalises.vider()
        ScanBalises.demarrer(this)
        demarrerBoucle()
        return START_STICKY
    }

    private fun notification(texte: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Récepteur de badges", NotificationManager.IMPORTANCE_LOW))
        val ouvrir = PendingIntent.getActivity(this, 11, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val arret = PendingIntent.getService(this, 12, Intent(this, ServiceRecepteur::class.java).setAction(ACTION_ARRET), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CANAL).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle("Récepteur de badges").setContentText(texte)
            .setContentIntent(ouvrir).setOngoing(true).addAction(Notification.Action.Builder(null, "Arrêter", arret).build()).build()
    }

    /** Toutes les 5 s : badges entendus depuis le dernier tour ; envoi s'il y en a, sinon un envoi à vide toutes les 30 s (« je suis vivant »). */
    private fun demarrerBoucle() {
        boucle?.cancel()
        boucle = portee.launch {
            var dernierTour = System.currentTimeMillis()
            var dernierEnvoi = 0L
            while (isActive) {
                delay(PresenceRecepteur.INTERVALLE_MS)
                val maintenant = System.currentTimeMillis()
                val d = PresenceRecepteur.detections(ScanBalises.instantane(), dernierTour)
                dernierTour = maintenant
                if (d.isEmpty() && maintenant - dernierEnvoi < PresenceRecepteur.BATTEMENT_MS) continue
                val (ok, message) = PresenceRecepteur.envoyer(PresenceRecepteur.url(this@ServiceRecepteur), PresenceRecepteur.identifiant(this@ServiceRecepteur), PresenceRecepteur.cle(this@ServiceRecepteur), d)
                dernierEnvoi = maintenant
                PresenceRecepteur.Statut.apply { envois++; badgesDernierEnvoi = d.size; dernierOk = ok; dernierMessage = message; dernierEnvoiMs = maintenant }
                getSystemService(NotificationManager::class.java).notify(ID_NOTIF, notification(if (ok) "${d.size} badge(s) envoyé(s) à SAWALI" else "Envoi impossible : $message"))
            }
        }
    }

    override fun onDestroy() {
        boucle?.cancel(); portee.cancel()
        ScanBalises.arreter(this)
        try { verrou?.takeIf { it.isHeld }?.release() } catch (e: Exception) {}
        super.onDestroy()
    }
}
