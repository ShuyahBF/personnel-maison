package org.shuyah.personnelmaison

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * SUIVI DE POSITION (« Ma famille ») : service au premier plan, avec une NOTIFICATION PERMANENTE (« Position partagée avec … », bouton Arrêter) : le partage
 * est toujours visible. Une fois par minute, la position est lue (GPS, sinon réseau) ; selon le mode, elle est envoyée — CHIFFRÉE — aux seuls proches
 * cochés (liens vérifiés) : en mode « hors du domicile », seulement quand la personne est sortie du périmètre (et une dernière fois au retour).
 * L'arrêt du service efface la position partagée sur le serveur.
 */
class ServicePosition : Service() {
    companion object {
        private const val CANAL = "famille_position"
        private const val ID_NOTIF = 4101
        private const val ACTION_ARRET = "org.shuyah.personnelmaison.ARRET_POSITION"

        fun demarrer(c: Context) { ReglagesFamille.actif(c, true); ContextCompat.startForegroundService(c, Intent(c, ServicePosition::class.java)) }
        fun arreter(c: Context) { ReglagesFamille.actif(c, false); c.stopService(Intent(c, ServicePosition::class.java)) }
    }

    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var gestionnaire: LocationManager? = null
    private var dehorsPrecedent: Boolean? = null
    private var liensCache: List<ClientFamille.Lien> = emptyList()
    private var liensLusLe = 0L

    private val ecouteur = object : LocationListener {
        override fun onLocationChanged(l: Location) { traiter(l) }
        @Deprecated("Ancienne API") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        if (i?.action == ACTION_ARRET) { arreter(this); return START_NOT_STICKY }
        val notification = notification("Partage de la position avec vos proches actif")
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID_NOTIF, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(ID_NOTIF, notification)
        abonner()
        return START_STICKY
    }

    private fun notification(texte: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Position partagée", NotificationManager.IMPORTANCE_LOW))
        val ouvrir = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val arret = PendingIntent.getService(this, 2, Intent(this, ServicePosition::class.java).setAction(ACTION_ARRET), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CANAL).setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("Ma famille").setContentText(texte)
            .setContentIntent(ouvrir).setOngoing(true).addAction(Notification.Action.Builder(null, "Arrêter le partage", arret).build()).build()
    }

    /** Écoute la position toutes les minutes (GPS et réseau) ; sans autorisation, le service s'arrête. */
    @SuppressLint("MissingPermission")
    private fun abonner() {
        if (!Localisation.permissionAccordee(this)) { arreter(this); return }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        gestionnaire = lm
        try {
            lm.removeUpdates(ecouteur)
            for (f in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) if (lm.isProviderEnabled(f)) lm.requestLocationUpdates(f, FamillePosition.INTERVALLE_MS, 0f, ecouteur)
        } catch (e: Exception) { /* fournisseur indisponible : le service continue avec l'autre */ }
    }

    /** Une position reçue : état par rapport au domicile, décision d'envoi, puis dépôt chiffré chez chaque proche coché et vérifié. */
    private fun traiter(l: Location) {
        portee.launch {
            val domicile = ReglagesFamille.domicile(this@ServicePosition)
            val etat = FamillePosition.etat(domicile, ReglagesFamille.lieux(this@ServicePosition), l.latitude, l.longitude)
            val envoyer = FamillePosition.doitEnvoyer(ReglagesFamille.mode(this@ServicePosition), domicile, dehorsPrecedent, etat)
            dehorsPrecedent = etat.dehors
            if (!envoyer) return@launch
            try {
                if (System.currentTimeMillis() - liensLusLe > 5 * 60_000L) { liensCache = ClientFamille.liens(this@ServicePosition); liensLusLe = System.currentTimeMillis() }
                val choisis = ReglagesFamille.partageAvec(this@ServicePosition)
                val cibles = liensCache.filter { it.verifie && it.id in choisis }
                val texte = FamillePosition.versTexte(l.latitude, l.longitude, l.accuracy.toInt(), l.time.takeIf { it > 0 } ?: System.currentTimeMillis(), etat)
                cibles.forEach { try { ClientFamille.deposerPosition(this@ServicePosition, it, texte) } catch (e: ClientFamille.Erreur) { /* réseau absent : prochaine minute */ } }
                getSystemService(NotificationManager::class.java).notify(ID_NOTIF, notification(if (cibles.isEmpty()) "Aucun proche coché : rien n'est partagé" else "Position partagée avec : " + cibles.joinToString(", ") { it.nom }))
            } catch (e: ClientFamille.Erreur) { /* serveur injoignable : on réessaie à la prochaine position */ }
        }
    }

    /** À l'arrêt : on cesse d'écouter et la position partagée est EFFACÉE du serveur (au plus 5 secondes d'attente). */
    override fun onDestroy() {
        try { gestionnaire?.removeUpdates(ecouteur) } catch (e: Exception) {}
        try {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(5000) { ReglagesFamille.partageAvec(this@ServicePosition).forEach { try { ClientFamille.effacerPosition(this@ServicePosition, it) } catch (e: Exception) {} } }
            }
        } catch (e: Exception) {}
        portee.cancel()
        super.onDestroy()
    }
}
