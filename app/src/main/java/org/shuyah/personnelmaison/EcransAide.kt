package org.shuyah.personnelmaison

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// =====================================================================================================================
// AIDE ET À PROPOS : la liste des réglages à faire pour que tout fonctionne (alarmes, batterie, notifications, localisation, NFC, caméra…), COCHÉS
// automatiquement quand l'application peut le vérifier, avec un bouton pour corriger ceux qui ne le sont pas. Relue toutes les 2 secondes : on peut aller
// dans les réglages du téléphone et revenir, la case se coche d'elle-même.
// =====================================================================================================================

/** Un point de la liste : `ok` vrai / faux (vérifié par l'application) ou null (non vérifiable : l'utilisateur le coche lui-même, clé `manuel`). */
private data class Point(val titre: String, val detail: String, val ok: Boolean?, val manuel: String? = null, val bouton: String? = null, val action: (() -> Unit)? = null)

private fun ouvrirReglage(c: Context, i: Intent) {
    try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {
        try { c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e2: Exception) {}
    }
}

/** Aide et à propos : version, réglages à faire (cochés quand ils sont en ordre), et ce qu'il faut savoir. */
@Composable
fun EcranAide(ouvrir: (Ecran) -> Unit, retour: () -> Unit) = Page("Aide et à propos", retour) {
    val c = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var proches by remember { mutableStateOf<Int?>(null) }                // nombre de proches liés ET vérifiés (lu sur le serveur), null = pas encore lu
    LaunchedEffect(Unit) { while (true) { tick++; delay(2_000) } }
    LaunchedEffect(Unit) { if (ClientFamille.inscrit(c)) proches = try { ClientFamille.liens(c).count { it.verifie } } catch (e: ClientFamille.Erreur) { null } }

    val paquet = Uri.parse("package:" + c.packageName)
    val xiaomi = Build.MANUFACTURER.lowercase().let { it.contains("xiaomi") || it.contains("redmi") || it.contains("poco") }
    val points = remember(tick, proches) {
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val nm = c.getSystemService(NotificationManager::class.java)
        fun permis(p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        val manuel = { cle: String -> ClientFamille.prefs(c).getBoolean("aide_$cle", false) }
        val telephone = mutableListOf(
            Point("Économie de batterie : « Aucune restriction »", "Sans cela, le téléphone peut retarder ou arrêter les rappels, la veille des proches, la position et la caméra.", pm.isIgnoringBatteryOptimizations(c.packageName), bouton = "Autoriser") {
                ouvrirReglage(c, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, paquet)) },
            Point("Alarmes et rappels exacts", "Les rappels de médicaments et de repas sonnent à la minute près (sinon à quelques minutes près).", Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms(), bouton = "Autoriser") {
                if (Build.VERSION.SDK_INT >= 31) ouvrirReglage(c, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, paquet)) },
            Point("Notifications autorisées", "Rappels, alertes de la famille et nouveaux messages s'affichent par des notifications.", nm.areNotificationsEnabled(), bouton = "Réglages") {
                ouvrirReglage(c, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName)) },
            Point("NFC activé", "Pour ajouter un proche par contact et utiliser les étiquettes (médicaments, repas).", FamilleNfc.etatNfc(c) == 2, bouton = "Réglages NFC") {
                ouvrirReglage(c, Intent(Settings.ACTION_NFC_SETTINGS)) },
            Point("Autorisation de la caméra", "Nécessaire pour partager la caméra avec un proche.", permis(Manifest.permission.CAMERA), bouton = "Réglages") { ouvrirReglage(c, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, paquet)) },
            Point("Autorisation de localisation", "Nécessaire pour le domicile, les lieux et le partage de la position.", Localisation.permissionAccordee(c), bouton = "Réglages") { ouvrirReglage(c, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, paquet)) },
            Point("Localisation du téléphone activée (GPS)", "Sans elle, la position ne peut pas être lue.", Localisation.activee(c), bouton = "Réglages") { ouvrirReglage(c, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) })
        if (xiaomi) {
            telephone += Point("Xiaomi : démarrage automatique", "Réglage propre à MIUI : à activer pour que les rappels reprennent après un redémarrage. Android ne permet pas de le vérifier : cochez-le quand c'est fait.", null, "autostart", "Ouvrir") {
                ouvrirReglage(c, Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))) }
            telephone += Point("Xiaomi : verrouiller l'application en mémoire", "Dans la liste des applications récentes, appuyez longtemps sur l'application puis sur le cadenas, pour que le téléphone ne la ferme pas. Cochez-le quand c'est fait.", null, "cadenas")
        }
        val famille = listOf(
            Point("Compte « Ma famille » créé", "Pour les proches, la messagerie, la position, les rappels partagés et la caméra.", ClientFamille.inscrit(c), bouton = "Ouvrir") { ouvrir(Ecran.Famille) },
            Point("Au moins un proche lié et vérifié", "Les partages ne fonctionnent qu'avec des proches dont le lien est vérifié des deux côtés.", if (!ClientFamille.inscrit(c)) false else proches?.let { it > 0 }, bouton = "Ouvrir") { ouvrir(Ecran.Famille) },
            Point("Domicile défini", "Pour partager la position seulement quand vous êtes sorti du périmètre.", ReglagesFamille.domicile(c) != null, bouton = "Ouvrir") { ouvrir(Ecran.Famille) },
            Point("Au moins un médicament enregistré", "Pour recevoir des rappels et les partager.", null, "medicament", "Ouvrir") { ouvrir(Ecran.Medicaments) })
        telephone to famille
    }
    fun coche(p: Point) = p.ok ?: (p.manuel?.let { ClientFamille.prefs(c).getBoolean("aide_$it", false) } ?: false)
    val tous = points.first + points.second
    val enOrdre = tous.count { coche(it) }

    Carte("Personnel de maison", Icons.Filled.Info) {
        Text(Version.libelle(), fontSize = 14.sp, color = BleuNuit)
        Text("Suivi du personnel de maison, portefeuille et « Ma famille » (messages, position, médicaments, repas, caméra). Vos données restent sur le téléphone, chiffrées ; ce qui est partagé avec vos proches est chiffré de bout en bout.", fontSize = 12.sp, color = Gris, modifier = Modifier.padding(top = 4.dp))
        Text("$enOrdre réglages sur ${tous.size} en ordre", fontSize = 16.sp, color = if (enOrdre == tous.size) Vert else Orange, modifier = Modifier.padding(top = 8.dp))
    }

    @Composable fun Liste(titre: String, liste: List<Point>) {
        Carte(titre, Icons.Filled.Checklist) {
            liste.forEach { p ->
                val ok = coche(p)
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                    if (p.manuel != null && p.ok == null) Checkbox(ok, { v -> ClientFamille.prefs(c).edit().putBoolean("aide_${p.manuel}", v).apply(); tick++ })
                    else Icon(if (ok) Icons.Filled.CheckCircle else if (p.ok == null) Icons.Filled.HelpOutline else Icons.Filled.Cancel, null, tint = if (ok) Vert else if (p.ok == null) Gris else Rouge, modifier = Modifier.padding(end = 10.dp, top = 2.dp).size(26.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.titre, fontSize = 15.sp, color = BleuNuit)
                        Text(p.detail, fontSize = 12.sp, color = Gris)
                        if (!ok && p.action != null && p.bouton != null) Text("▶ ${p.bouton}", fontSize = 13.sp, color = Bleu, modifier = Modifier.padding(top = 4.dp).clickable { p.action.invoke() })
                    }
                }
            }
        }
    }
    Liste("Réglages du téléphone", points.first)
    Liste("Ma famille", points.second)

    Carte("À savoir", Icons.Filled.Lightbulb) {
        listOf(
            "Téléphone verrouillé : Android ne lit pas les étiquettes NFC et n'accepte pas le contact NFC d'un proche. Déverrouillez d'abord le téléphone.",
            "Après un redémarrage du téléphone, relancez le partage de la position et de la caméra (écran Ma famille) ; les rappels de médicaments et de repas reprennent seuls.",
            "La caméra partagée consomme des données mobiles : le mode « seulement quand un proche regarde » les économise.",
            "La veille des proches (alertes, nouveaux messages) interroge le serveur toutes les 5 minutes environ : sans Internet, rien n'arrive.",
            "Un proche qui change de téléphone doit recréer son compte et refaire le lien avec vous (la clé secrète ne quitte jamais le téléphone).",
            "Une étiquette NFC ne contient qu'un identifiant : la perdre ne révèle rien, mais une autre personne qui la pose sur ce téléphone déverrouillé déclarerait la prise.").forEach {
            Text("• $it", fontSize = 13.sp, color = BleuNuit, modifier = Modifier.padding(vertical = 3.dp))
        }
    }
}
