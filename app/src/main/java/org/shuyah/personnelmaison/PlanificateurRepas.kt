package org.shuyah.personnelmaison

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * REPAS : à l'heure de chaque repas suivi, un rappel s'affiche avec le bouton « Je prends mon repas » ; si le repas n'est pas déclaré une heure plus tard,
 * les proches choisis reçoivent « n'a pas encore pris son repas » (message chiffré de bout en bout). Déclarer « Je prends mon repas » ou « Repas terminé »
 * (écran Repas, ou bouton du rappel) prévient aussi les proches. Tout est reprogrammé à l'ouverture de l'application et au redémarrage du téléphone.
 */
object PlanificateurRepas {
    const val CANAL = "repas_rappel"
    const val ACTION_HEURE = "org.shuyah.personnelmaison.REPAS_HEURE"        // l'heure du repas est arrivée
    const val ACTION_MANGE = "org.shuyah.personnelmaison.REPAS_MANGE"        // bouton « Je prends mon repas »
    const val ACTION_CONTROLE = "org.shuyah.personnelmaison.REPAS_CONTROLE"  // une heure après : repas déclaré ?
    private val zone get() = ZoneId.systemDefault()

    private fun index(code: String) = RappelsRepas.REPAS.indexOfFirst { it.code == code }.coerceAtLeast(0)
    private fun pending(c: Context, action: String, code: String, prevuMs: Long = 0): PendingIntent =
        PendingIntent.getBroadcast(c, 7000 + index(code) * 10 + (if (action == ACTION_CONTROLE) 1 else if (action == ACTION_MANGE) 2 else 0),
            Intent(c, RecepteurRepas::class.java).setAction(action).putExtra("repas", code).putExtra("prevu", prevuMs), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun jour(ms: Long = System.currentTimeMillis()) = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()

    /** Programme (ou annule) l'alarme de l'heure du prochain repas de ce type. */
    fun planifier(c: Context, code: String) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (!ReglagesFamille.repasActif(c, code)) { am.cancel(pending(c, ACTION_HEURE, code)); am.cancel(pending(c, ACTION_CONTROLE, code)); return }
        val prochaine = RappelsRepas.prochaineHeure(ReglagesFamille.repasMinutes(c, code), System.currentTimeMillis(), zone)
        PlanificateurMedicaments.alarme(c, prochaine, pending(c, ACTION_HEURE, code, prochaine))
    }

    fun planifierTout(c: Context) { RappelsRepas.REPAS.forEach { planifier(c, it.code) } }

    private fun notifier(c: Context, code: String, texte: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Rappels de repas", NotificationManager.IMPORTANCE_HIGH))
        val ouvrir = PendingIntent.getActivity(c, 8, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(30_000 + index(code), Notification.Builder(c, CANAL).setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle("Heure du repas").setContentText(texte).setContentIntent(ouvrir)
            .addAction(Notification.Action.Builder(null, "Je prends mon repas", pending(c, ACTION_MANGE, code)).build()).build())
    }

    /** Prévient les proches choisis (liens vérifiés). Jamais bloquant. */
    private suspend fun signaler(c: Context, e: RappelsRepas.Evenement) {
        if (!ClientFamille.inscrit(c)) return
        try {
            val choisis = ReglagesFamille.partageRepasAvec(c)
            val texte = RappelsRepas.evenementVersTexte(e)
            ClientFamille.liens(c).filter { it.verifie && it.id in choisis }.forEach { try { ClientFamille.deposerEvenement(c, it, "repas", texte) } catch (x: ClientFamille.Erreur) { /* réseau absent */ } }
        } catch (x: ClientFamille.Erreur) { /* serveur injoignable : l'état reste noté ici */ }
    }

    /** Déclaration par la personne : « prend » ou « termine ». Enregistrée pour aujourd'hui, rappel et contrôle retirés, proches informés. */
    suspend fun declarer(c: Context, code: String, statut: String) {
        val j = jour()
        ReglagesFamille.repasEtat(c, code, j, statut)
        if (statut == "prend") { (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(c, ACTION_CONTROLE, code)); c.getSystemService(NotificationManager::class.java).cancel(30_000 + index(code)) }
        val prevu = java.time.LocalDate.now().atStartOfDay(zone).plusMinutes(ReglagesFamille.repasMinutes(c, code).toLong()).toInstant().toEpochMilli()
        signaler(c, RappelsRepas.Evenement(code, statut, prevu, ClientFamille.nom(c)))
    }

    /** Appelé par les alarmes (voir RecepteurRepas). */
    suspend fun traiter(c: Context, action: String, code: String, prevuMs: Long) {
        val repas = RappelsRepas.repas(code) ?: return
        when (action) {
            ACTION_HEURE -> {
                if (ReglagesFamille.repasActif(c, code)) {
                    if (ReglagesFamille.repasEtat(c, code, jour(prevuMs)) == null) {
                        notifier(c, code, "C'est l'heure de ${repas.nom.lowercase()}. Appuyez quand vous commencez.")
                        PlanificateurMedicaments.alarme(c, prevuMs + RappelsRepas.DELAI_ALERTE_MIN * 60_000L, pending(c, ACTION_CONTROLE, code, prevuMs))
                    }
                    planifier(c, code)                                                       // repas du lendemain
                }
            }
            ACTION_MANGE -> declarer(c, code, "prend")
            ACTION_CONTROLE -> if (ReglagesFamille.repasEtat(c, code, jour(prevuMs)) == null) {   // toujours rien de déclaré : les proches sont prévenus
                ReglagesFamille.repasEtat(c, code, jour(prevuMs), "alerte")
                c.getSystemService(NotificationManager::class.java).cancel(30_000 + index(code))
                signaler(c, RappelsRepas.Evenement(code, "pas_pris", prevuMs, ClientFamille.nom(c)))
            }
        }
    }
}

/** Reçoit les alarmes de repas et le bouton « Je prends mon repas ». */
class RecepteurRepas : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val attente = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { PlanificateurRepas.traiter(c, i.action ?: "", i.getStringExtra("repas") ?: "", i.getLongExtra("prevu", 0L)) } catch (e: Exception) { /* jamais bloquant */ } finally { attente.finish() }
        }
    }
}
