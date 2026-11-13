package org.shuyah.personnelmaison

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * INFORMATIONS DE L'APPAREIL : version d'Android, date et heure, niveau de batterie. Les fonctions de libellé sont pures (testées par InfosAppareilTest) ;
 * `BarreEtat` est le petit bloc « date/heure + batterie » affiché à droite de chaque barre de titre.
 */
object InfosAppareil {
    /** « Android 14 (API 34) · Xiaomi 23021RAA2Y » : version du système, niveau d'API (celui qui compte pour les fonctions de l'application) et modèle du terminal. */
    fun libelleAndroid(release: String, sdk: Int, marque: String, modele: String): String {
        val m = marque.trim().replaceFirstChar { it.uppercase() }
        val nomModele = if (modele.trim().startsWith(marque.trim(), ignoreCase = true)) modele.trim() else "$m ${modele.trim()}"
        return "Android $release (API $sdk) · ${nomModele.trim()}"
    }

    /** « 🔋 57 % » ou « ⚡ 57 % » en charge ; « 🔋 ? » si le niveau est inconnu. */
    fun libelleBatterie(pourcent: Int?, enCharge: Boolean): String = (if (enCharge) "⚡ " else "🔋 ") + (pourcent?.let { "$it %" } ?: "?")

    /** « 13/11 15:57 » (jour/mois et heure, fuseau du téléphone). */
    fun libelleDateHeure(ms: Long): String = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(Date(ms))

    /** Batterie faible : 15 % ou moins et pas en charge (affichée en orange). */
    fun batterieFaible(pourcent: Int?, enCharge: Boolean) = pourcent != null && pourcent <= 15 && !enCharge

    fun versionAndroid() = libelleAndroid(Build.VERSION.RELEASE, Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.MODEL)

    /** Niveau de batterie (0 à 100, null si inconnu) et état de charge, lus sur l'intention système « batterie » (sans autorisation). */
    fun batterie(c: Context): Pair<Int?, Boolean> {
        val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null to false
        val niveau = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val echelle = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val statut = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val pct = if (niveau >= 0 && echelle > 0) niveau * 100 / echelle else null
        return pct to (statut == BatteryManager.BATTERY_STATUS_CHARGING || statut == BatteryManager.BATTERY_STATUS_FULL)
    }
}

/** Date/heure et batterie, à droite d'une barre de titre ; relus toutes les 15 secondes. */
@Composable
fun BarreEtat() {
    val c = LocalContext.current
    var maintenant by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var batterie by remember { mutableStateOf(InfosAppareil.batterie(c)) }
    LaunchedEffect(Unit) { while (true) { maintenant = System.currentTimeMillis(); batterie = InfosAppareil.batterie(c); delay(15_000) } }
    Column(horizontalAlignment = Alignment.End) {
        Text(InfosAppareil.libelleDateHeure(maintenant), fontSize = 11.sp, color = Color.White.copy(alpha = 0.9f))
        Text(InfosAppareil.libelleBatterie(batterie.first, batterie.second), fontSize = 11.sp, color = if (InfosAppareil.batterieFaible(batterie.first, batterie.second)) Color(0xFFF6A35B) else Color.White.copy(alpha = 0.9f))
    }
}
