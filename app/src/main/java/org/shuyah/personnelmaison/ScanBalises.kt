package org.shuyah.personnelmaison

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.util.concurrent.ConcurrentHashMap

/**
 * ÉCOUTE DES BALISES BLUETOOTH (Bluetooth Low Energy) pour l'écran de test : le téléphone écoute toutes les annonces autour de lui, `Balises.analyserAnnonce` les lit, et
 * l'écran relit l'état toutes les secondes. L'écoute ne tourne que pendant que l'écran de test est ouvert (rien en arrière-plan, rien n'est envoyé ailleurs).
 */
object ScanBalises {
    /** État d'un appareil vu : dernière annonce, signal lissé, heures. */
    class Vue(val cle: String, val adresse: String) {
        @Volatile var annonce: Balises.Annonce = Balises.Annonce("Autre", null, "", null, null, emptyList())
        @Volatile var rssiBrut = 0
        @Volatile var rssiLisse = 0.0
        @Volatile var premiereVue = 0L
        @Volatile var derniereVue = 0L
        @Volatile var nbAnnonces = 0
        val lissage = Balises.Lissage(5)
    }

    private val vues = ConcurrentHashMap<String, Vue>()
    private var callback: ScanCallback? = null
    @Volatile var erreur: String? = null

    /** Permissions Bluetooth à demander : BLUETOOTH_SCAN (Android 12 et plus) et la localisation, nécessaire pour voir les balises. */
    fun permissionsRequises(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.ACCESS_FINE_LOCATION) else arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)

    fun permissionsAccordees(c: Context) = permissionsRequises().all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    fun adaptateur(c: Context): BluetoothAdapter? = (c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    fun bluetoothActif(c: Context) = adaptateur(c)?.isEnabled == true
    fun enCours() = callback != null
    fun instantane(): List<Vue> = vues.values.toList()
    fun vider() = vues.clear()

    @SuppressLint("MissingPermission")
    fun demarrer(c: Context): Boolean {
        arreter(c)
        erreur = null
        val scanner = adaptateur(c)?.bluetoothLeScanner ?: run { erreur = "Bluetooth indisponible ou désactivé."; return false }
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val octets = r.scanRecord?.bytes ?: return
                val a = Balises.analyserAnnonce(octets)
                val adresse = r.device.address ?: return
                val cle = a.identifiant ?: "mac:$adresse"                         // sans identifiant de balise, on suit l'adresse de l'appareil
                val v = vues.getOrPut(cle) { Vue(cle, adresse).also { it.premiereVue = System.currentTimeMillis() } }
                v.annonce = a; v.rssiBrut = r.rssi; v.rssiLisse = v.lissage.ajouter(r.rssi); v.derniereVue = System.currentTimeMillis(); v.nbAnnonces++
            }
            override fun onScanFailed(code: Int) { erreur = "Écoute impossible (code $code) : vérifiez le Bluetooth et la localisation." }
        }
        return try { scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb); callback = cb; true } catch (e: Exception) { erreur = "Écoute impossible : " + (e.message ?: e.javaClass.simpleName); false }
    }

    @SuppressLint("MissingPermission")
    fun arreter(c: Context) {
        val cb = callback ?: return
        try { adaptateur(c)?.bluetoothLeScanner?.stopScan(cb) } catch (e: Exception) {}
        callback = null
    }
}
