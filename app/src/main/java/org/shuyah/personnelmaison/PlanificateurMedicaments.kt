package org.shuyah.personnelmaison

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * RAPPELS DE MÉDICAMENTS : alarmes du téléphone, notifications avec les boutons « Pris » et « Plus tard », détection de l'oubli et signalement aux proches.
 *
 * Pour un développeur WinDev : pour chaque médicament, UNE alarme est programmée à la prochaine prise ; quand elle sonne (`RecepteurMedicament`), le rappel
 * s'affiche, l'alarme suivante est programmée, et une alarme de contrôle est posée 30 minutes plus tard : si la prise n'a pas été confirmée, elle devient
 * « OUBLIÉE » et les proches choisis en sont informés (message chiffré de bout en bout). Tout se refait au redémarrage du téléphone.
 */
object PlanificateurMedicaments {
    const val CANAL = "medicaments_rappel"
    const val ACTION_PRISE = "org.shuyah.personnelmaison.MED_PRISE"          // l'heure d'une prise est arrivée
    const val ACTION_PRIS = "org.shuyah.personnelmaison.MED_PRIS"            // bouton « Pris »
    const val ACTION_PLUS_TARD = "org.shuyah.personnelmaison.MED_PLUS_TARD"  // bouton « Plus tard »
    const val ACTION_REPRISE = "org.shuyah.personnelmaison.MED_REPRISE"      // le rappel revient après « Plus tard »
    const val ACTION_RETARD = "org.shuyah.personnelmaison.MED_RETARD"        // contrôle : prise toujours non confirmée ?
    private val zone get() = ZoneId.systemDefault()

    private fun pending(c: Context, action: String, code: Int, medicamentId: Long, priseId: Long = 0, prevuMs: Long = 0): PendingIntent =
        PendingIntent.getBroadcast(c, code, Intent(c, RecepteurMedicament::class.java).setAction(action).putExtra("med", medicamentId).putExtra("prise", priseId).putExtra("prevu", prevuMs),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Alarme à l'heure voulue ; exacte si le système l'autorise, sinon approximative (à quelques minutes près). */
    private fun alarme(c: Context, quand: Long, pi: PendingIntent) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quand, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quand, pi)
        } catch (e: SecurityException) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quand, pi) }
    }

    /** Programme (ou annule) l'alarme de la prochaine prise de ce médicament. */
    fun planifier(c: Context, m: Medicament) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val code = codeMedicament(m.id)
        val prochaine = if (m.actif) RappelsMedicaments.prochainePrise(m, System.currentTimeMillis(), zone) else null
        if (prochaine == null) { am.cancel(pending(c, ACTION_PRISE, code, m.id)); return }
        alarme(c, prochaine, pending(c, ACTION_PRISE, code, m.id, prevuMs = prochaine))
    }

    fun annuler(c: Context, medicamentId: Long) { (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(c, ACTION_PRISE, codeMedicament(medicamentId), medicamentId)) }

    /** Reprogramme tous les médicaments (démarrage de l'application, redémarrage du téléphone, mise à jour). */
    fun planifierTout(c: Context) {
        CoroutineScope(Dispatchers.IO).launch { try { Base.dao(c).medicamentsListe().forEach { planifier(c, it) } } catch (e: Exception) { /* base indisponible : au prochain démarrage */ } }
    }

    private fun codeMedicament(id: Long) = (id % 100_000).toInt() * 10
    private fun codeRetard(priseId: Long) = 2_000_000 + (priseId % 1_000_000).toInt()
    private fun codeReprise(priseId: Long) = 3_000_000 + (priseId % 1_000_000).toInt()
    private fun idNotif(priseId: Long) = 20_000 + (priseId % 100_000).toInt()

    /** Notification du rappel, avec « Pris » et « Plus tard ». */
    fun notifierRappel(c: Context, m: Medicament, p: PriseMedicament) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Rappels de médicaments", NotificationManager.IMPORTANCE_HIGH))
        val ouvrir = PendingIntent.getActivity(c, 3, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val texte = (m.nom + if (m.dose.isNotBlank()) " — " + m.dose else "") + " · prévu à " + RappelsMedicaments.libelleHeure(((p.prevuMs - java.time.LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()) / 60_000L).toInt().coerceIn(0, 1439))
        nm.notify(idNotif(p.id), Notification.Builder(c, CANAL).setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle("Heure du médicament").setContentText(texte)
            .setStyle(Notification.BigTextStyle().bigText(texte)).setContentIntent(ouvrir).setAutoCancel(false)
            .addAction(Notification.Action.Builder(null, "Pris", pending(c, ACTION_PRIS, 1_000_000 + (p.id % 1_000_000).toInt(), m.id, p.id)).build())
            .addAction(Notification.Action.Builder(null, "Plus tard", pending(c, ACTION_PLUS_TARD, 4_000_000 + (p.id % 1_000_000).toInt(), m.id, p.id)).build()).build())
    }

    fun retirerNotification(c: Context, priseId: Long) { c.getSystemService(NotificationManager::class.java).cancel(idNotif(priseId)) }

    /** Alarme de contrôle 30 minutes après l'heure prévue. */
    fun planifierRetard(c: Context, m: Medicament, p: PriseMedicament) = alarme(c, p.prevuMs + RappelsMedicaments.RETARD_MIN * 60_000L, pending(c, ACTION_RETARD, codeRetard(p.id), m.id, p.id))
    fun annulerRetard(c: Context, m: Medicament, p: PriseMedicament) { (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(c, ACTION_RETARD, codeRetard(p.id), m.id, p.id)) }
    fun planifierReprise(c: Context, m: Medicament, p: PriseMedicament) = alarme(c, System.currentTimeMillis() + RappelsMedicaments.REPORT_MIN * 60_000L, pending(c, ACTION_REPRISE, codeReprise(p.id), m.id, p.id))

    /** Envoie « pris » ou « oublié » aux proches choisis (liens vérifiés), si ce médicament est partagé. Jamais bloquant. */
    suspend fun signaler(c: Context, m: Medicament, p: PriseMedicament, statut: String) {
        if (!m.partage || !ClientFamille.inscrit(c)) return
        try {
            val choisis = ReglagesFamille.partageMedsAvec(c)
            val texte = RappelsMedicaments.evenementVersTexte(RappelsMedicaments.Evenement(m.nom, m.dose, p.prevuMs, statut, ClientFamille.nom(c)))
            ClientFamille.liens(c).filter { it.verifie && it.id in choisis }.forEach { try { ClientFamille.deposerEvenement(c, it, "medicament", texte) } catch (e: ClientFamille.Erreur) { /* réseau absent */ } }
        } catch (e: ClientFamille.Erreur) { /* serveur injoignable : le signalement est perdu, la prise reste enregistrée ici */ }
    }

    /** Confirme une prise (bouton « Pris » de la notification ou de l'écran) : enregistrée, rappel retiré, proches informés. */
    suspend fun confirmerPrise(c: Context, medicamentId: Long, prevuMs: Long) {
        val dao = Base.dao(c)
        val m = dao.medicament(medicamentId) ?: return
        dao.ajouterPrise(PriseMedicament(medicamentId = medicamentId, prevuMs = prevuMs))          // ignorée si elle existe déjà
        val p = dao.prise(medicamentId, prevuMs) ?: return
        if (p.statut == "PRIS") return
        dao.majPrise(p.id, "PRIS", System.currentTimeMillis())
        retirerNotification(c, p.id); annulerRetard(c, m, p)
        signaler(c, m, p, "pris")
    }
}

/** Reçoit les alarmes de rappel, les boutons de la notification et le redémarrage du téléphone. */
class RecepteurMedicament : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val attente = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = Base.dao(c)
                val medId = i.getLongExtra("med", 0L); val priseId = i.getLongExtra("prise", 0L); val prevu = i.getLongExtra("prevu", 0L)
                when (i.action) {
                    PlanificateurMedicaments.ACTION_PRISE -> {                       // l'heure est arrivée : rappel, contrôle dans 30 minutes, prochaine alarme
                        val m = dao.medicament(medId)
                        if (m != null && m.actif && prevu > 0) {
                            dao.ajouterPrise(PriseMedicament(medicamentId = medId, prevuMs = prevu))
                            dao.prise(medId, prevu)?.let { p -> if (p.statut == "ATTENTE") { PlanificateurMedicaments.notifierRappel(c, m, p); PlanificateurMedicaments.planifierRetard(c, m, p) } }
                        }
                        if (m != null) PlanificateurMedicaments.planifier(c, m)
                    }
                    PlanificateurMedicaments.ACTION_PRIS -> dao.priseParId(priseId)?.let { PlanificateurMedicaments.confirmerPrise(c, it.medicamentId, it.prevuMs) }
                    PlanificateurMedicaments.ACTION_PLUS_TARD -> {
                        val p = dao.priseParId(priseId); val m = dao.medicament(medId)
                        if (p != null && m != null) { PlanificateurMedicaments.retirerNotification(c, p.id); PlanificateurMedicaments.planifierReprise(c, m, p) }
                    }
                    PlanificateurMedicaments.ACTION_REPRISE -> {
                        val p = dao.priseParId(priseId); val m = dao.medicament(medId)
                        if (p != null && m != null && p.statut == "ATTENTE") PlanificateurMedicaments.notifierRappel(c, m, p)
                    }
                    PlanificateurMedicaments.ACTION_RETARD -> {                      // toujours pas confirmée : oubli signalé
                        val p = dao.priseParId(priseId); val m = dao.medicament(medId)
                        if (p != null && m != null && p.statut == "ATTENTE") {
                            dao.majPrise(p.id, "OUBLIE", null); PlanificateurMedicaments.retirerNotification(c, p.id)
                            PlanificateurMedicaments.signaler(c, m, p, "oublie")
                        }
                    }
                    Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> { PlanificateurMedicaments.planifierTout(c); SurveillanceFamille.programmer(c) }
                }
            } catch (e: Exception) { /* un rappel raté ne doit jamais faire tomber l'application */ }
            finally { attente.finish() }
        }
    }
}
