package org.shuyah.personnelmaison

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * VEILLE DES PROCHES : toutes les 5 minutes environ, même application fermée, le téléphone regarde sur le serveur s'il y a du nouveau chez les proches liés et
 * vérifiés : rappels de médicaments pris ou OUBLIÉS (notification d'alerte), nouveaux messages (« Nouveau message de Awa » : le contenu n'est jamais affiché
 * sur l'écran verrouillé). Seuls les éléments plus récents que la dernière lecture sont notifiés. Sans Google Play Services : alarmes du système.
 */
object SurveillanceFamille {
    private const val CANAL_ALERTE = "famille_alertes"
    private const val CANAL_INFO = "famille_infos"
    private const val INTERVALLE_MS = 5 * 60_000L

    /** Programme la prochaine veille (si un compte famille existe). À rappeler au démarrage, après la création d'un compte et au redémarrage du téléphone. */
    fun programmer(c: Context) {
        if (!ClientFamille.inscrit(c)) return
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(c, 5, Intent(c, RecepteurSurveillance::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + INTERVALLE_MS, pi)
    }

    private fun notifier(c: Context, canal: String, importance: Int, id: Int, titre: String, texte: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(canal, if (canal == CANAL_ALERTE) "Alertes de la famille" else "Nouvelles de la famille", importance))
        val ouvrir = PendingIntent.getActivity(c, 4, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(id, Notification.Builder(c, canal).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(titre).setContentText(texte).setStyle(Notification.BigTextStyle().bigText(texte))
            .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(ouvrir).setAutoCancel(true).build())
    }

    /** Une veille : lit ce qu'il y a de nouveau et notifie. Jamais bloquant ; sans réseau, on réessaie à la prochaine. */
    suspend fun veiller(c: Context) {
        if (!ClientFamille.inscrit(c)) return
        try {
            for (lien in ClientFamille.liens(c).filter { it.verifie }) {
                // Événements (médicaments…)
                val curE = ReglagesFamille.curseur(c, "evt", lien.id)
                val nouveaux = ClientFamille.evenements(c, lien, curE)
                nouveaux.forEach { e ->
                    val id = (lien.id.hashCode() xor e.seq.toInt()) and 0x7FFFFFFF
                    when (e.type) {
                        "medicament" -> e.texte?.let { RappelsMedicaments.evenementDepuisTexte(it) }?.let { ev ->
                            val oubli = ev.statut != "pris"
                            notifier(c, if (oubli) CANAL_ALERTE else CANAL_INFO, if (oubli) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_LOW, id,
                                if (oubli) "⚠ Médicament non pris" else "Médicament pris", RappelsMedicaments.libelleEvenement(ev.copy(par = lien.nom)))
                        }
                        "repas" -> e.texte?.let { RappelsRepas.evenementDepuisTexte(it) }?.let { ev ->
                            val manque = ev.statut == "pas_pris"
                            notifier(c, if (manque) CANAL_ALERTE else CANAL_INFO, if (manque) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_LOW, id,
                                if (manque) "⚠ Repas non pris" else "Repas", RappelsRepas.libelleEvenement(ev.copy(par = lien.nom)))
                        }
                    }
                }
                if (nouveaux.isNotEmpty()) ReglagesFamille.curseur(c, "evt", lien.id, nouveaux.maxOf { it.seq })
                // Messages
                val curM = ReglagesFamille.curseur(c, "msg", lien.id)
                val tous = ClientFamille.messages(c, lien, curM)
                val messages = tous.filter { !it.deMoi }
                if (messages.isNotEmpty()) notifier(c, CANAL_INFO, NotificationManager.IMPORTANCE_DEFAULT, lien.id.hashCode() and 0x7FFFFFFF, "Nouveau message de ${lien.nom}", if (messages.size > 1) "${messages.size} nouveaux messages" else "1 nouveau message")
                tous.lastOrNull()?.let { ReglagesFamille.curseur(c, "msg", lien.id, it.seq) }
            }
        } catch (e: ClientFamille.Erreur) { /* hors ligne : prochaine veille */ }
    }
}

/** Réveil de la veille : lit les nouveautés, puis programme la suivante. */
class RecepteurSurveillance : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val attente = goAsync()
        CoroutineScope(Dispatchers.IO).launch { try { SurveillanceFamille.veiller(c) } finally { SurveillanceFamille.programmer(c); attente.finish() } }
    }
}
