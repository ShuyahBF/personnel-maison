package org.shuyah.personnelmaison

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * GÉOLOCALISATION SANS Google Play Services : une seule position, demandée quand l'utilisateur appuie sur « Ma position ».
 * Utilise le GPS s'il est activé, sinon le réseau ; à défaut, la dernière position connue. Résultat null si rien n'est trouvé en 25 secondes.
 */
object Localisation {
    fun permissionAccordee(c: Context): Boolean =
        c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            c.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Vrai si le GPS ou la localisation réseau est activé dans les réglages du téléphone. */
    fun activee(c: Context): Boolean {
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } catch (e: Exception) { false }
    }

    @SuppressLint("MissingPermission")
    fun obtenir(c: Context, resultat: (Location?) -> Unit) {
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var fini = false
        val principal = Handler(Looper.getMainLooper())
        // Une seule réponse, quelle que soit la source (position, dernière connue ou délai dépassé)
        fun terminer(l: Location?) { if (!fini) { fini = true; resultat(l) } }
        fun derniere(): Location? = try { listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time } } catch (e: Exception) { null }
        try {
            val fournisseur = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).firstOrNull { lm.isProviderEnabled(it) }
            if (fournisseur == null) { terminer(derniere()); return }
            principal.postDelayed({ terminer(derniere()) }, 25_000)
            if (Build.VERSION.SDK_INT >= 30) lm.getCurrentLocation(fournisseur, null, c.mainExecutor) { terminer(it ?: derniere()) }
            else lm.requestSingleUpdate(fournisseur, object : LocationListener {
                override fun onLocationChanged(l: Location) { terminer(l) }
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }, Looper.getMainLooper())
        } catch (e: Exception) { terminer(derniere()) }
    }
}
