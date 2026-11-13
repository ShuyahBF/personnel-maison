package org.shuyah.personnelmaison

import kotlin.math.pow

/**
 * BALISES BLUETOOTH (Estimote, iBeacon, Eddystone) — logique pure (sans Android), testée par BalisesTest : lecture d'une annonce Bluetooth, estimation de la distance,
 * lissage du signal, et suivi de présence (arrivée / départ).
 *
 * Pour un développeur WinDev : une balise ne fait qu'ÉMETTRE, plusieurs fois par seconde, un petit paquet d'octets (« annonce »). Le téléphone l'écoute et en tire :
 * un IDENTIFIANT (qui est la balise), la FORCE DU SIGNAL en dBm (plus proche = plus fort, ex. −55 dBm près, −90 dBm loin) et parfois des mesures (batterie, température).
 * L'annonce est une suite de blocs [longueur][type][données] que `analyserAnnonce` découpe.
 */
object Balises {
    /** Ce que l'on sait lire d'une annonce : format, identifiant stable, texte lisible, puissance de référence (dBm à 1 m) et octets bruts utiles. */
    data class Annonce(val format: String, val identifiant: String?, val resume: String, val puissance1m: Int?, val nom: String?, val details: List<String>)

    private fun hex(b: ByteArray, de: Int, a: Int) = (de until a.coerceAtMost(b.size)).joinToString("") { "%02X".format(b[it]) }
    private fun u16be(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
    private fun uuid(b: ByteArray, de: Int) = hex(b, de, de + 16).let { "${it.substring(0, 8)}-${it.substring(8, 12)}-${it.substring(12, 16)}-${it.substring(16, 20)}-${it.substring(20)}" }

    private val SCHEMAS = listOf("http://www.", "https://www.", "http://", "https://")
    private val SUFFIXES = listOf(".com/", ".org/", ".edu/", ".net/", ".info/", ".biz/", ".gov/", ".com", ".org", ".edu", ".net", ".info", ".biz", ".gov")

    /** Découpe l'annonce en blocs et reconnaît iBeacon (Apple), Eddystone (Google, service FEAA) et Estimote (fabricant 015D, service FE9A). */
    fun analyserAnnonce(o: ByteArray): Annonce {
        var format = "Autre"; var identifiant: String? = null; var resume = "Annonce Bluetooth"; var puissance: Int? = null; var nom: String? = null
        val details = mutableListOf<String>()
        var i = 0
        while (i < o.size) {
            val l = o[i].toInt() and 0xFF
            if (l == 0 || i + 1 + l > o.size) break                                                        // bloc vide ou tronqué : fin de l'annonce
            val type = o[i + 1].toInt() and 0xFF
            val d = o.copyOfRange(i + 2, i + 1 + l)
            when (type) {
                0x08, 0x09 -> nom = String(d, Charsets.UTF_8)
                0x0A -> if (d.isNotEmpty()) details += "Puissance d'émission annoncée : ${d[0].toInt()} dBm"
                0xFF -> if (d.size >= 2) {
                    val societe = (d[0].toInt() and 0xFF) or ((d[1].toInt() and 0xFF) shl 8)
                    val p = d.copyOfRange(2, d.size)
                    if (societe == 0x004C && p.size >= 23 && p[0] == 0x02.toByte() && p[1] == 0x15.toByte()) {                    // iBeacon
                        val major = u16be(p, 18); val minor = u16be(p, 20); puissance = p[22].toInt()
                        format = "iBeacon"; identifiant = "ib:${uuid(p, 2)}:$major:$minor"
                        resume = "UUID ${uuid(p, 2)} · major $major · minor $minor"; details += "Puissance de référence à 1 m : $puissance dBm"
                    } else if (societe == 0x015D) {                                                                                // Estimote (fabricant)
                        if (format == "Autre") { format = "Estimote"; resume = "Annonce Estimote (fabricant 015D)" }
                        details += "Données Estimote : " + hex(p, 0, p.size)
                    } else details += "Fabricant %04X : ".format(societe) + hex(p, 0, p.size)
                }
                0x16 -> if (d.size >= 2) {
                    val service = (d[0].toInt() and 0xFF) or ((d[1].toInt() and 0xFF) shl 8)
                    val p = d.copyOfRange(2, d.size)
                    when {
                        service == 0xFEAA && p.isNotEmpty() -> when (p[0].toInt() and 0xFF) {
                            0x00 -> if (p.size >= 18) {                                                                           // Eddystone-UID
                                puissance = p[1].toInt() - 41                                                                      // mesurée à 0 m : on retranche 41 dB pour 1 m
                                format = "Eddystone-UID"; identifiant = "ed:${hex(p, 2, 12)}:${hex(p, 12, 18)}"
                                resume = "Espace ${hex(p, 2, 12)} · instance ${hex(p, 12, 18)}"; details += "Puissance à 0 m : ${p[1].toInt()} dBm"
                            }
                            0x10 -> if (p.size >= 4) {                                                                            // Eddystone-URL
                                puissance = p[1].toInt() - 41
                                val sb = StringBuilder(SCHEMAS.getOrElse(p[2].toInt() and 0xFF) { "" })
                                for (k in 3 until p.size) { val c = p[k].toInt() and 0xFF; if (c < SUFFIXES.size) sb.append(SUFFIXES[c]) else sb.append(c.toChar()) }
                                format = "Eddystone-URL"; identifiant = "edu:$sb"; resume = sb.toString()
                            }
                            0x20 -> if (p.size >= 14) {                                                                           // Eddystone-TLM (télémétrie)
                                val mv = u16be(p, 2); val t = p[4].toInt() + (p[5].toInt() and 0xFF) / 256.0
                                details += "Télémétrie : batterie $mv mV · température " + "%.1f".format(java.util.Locale.ROOT, t) + " °C"
                            }
                        }
                        service == 0xFE9A -> {                                                                                     // Estimote (service FE9A : télémétrie, localisation…)
                            if (format == "Autre") { format = "Estimote"; resume = "Service Estimote FE9A" }
                            if (p.size >= 9) { identifiant = identifiant ?: "es:${hex(p, 1, 9)}"; details += "Identifiant Estimote (octets 1 à 8) : ${hex(p, 1, 9)}" }
                            details += "Données du service FE9A : " + hex(p, 0, p.size)
                        }
                        else -> details += "Service %04X : ".format(service) + hex(p, 0, p.size)
                    }
                }
            }
            i += l + 1
        }
        return Annonce(format, identifiant, resume, puissance, nom, details)
    }

    /** Distance estimée en mètres d'après la force du signal (modèle simple en espace libre, exposant 2) ; null si la puissance de référence est inconnue. Très approximative. */
    fun distanceM(rssi: Double, puissance1m: Int?, exposant: Double = 2.0): Double? = puissance1m?.let { 10.0.pow((it - rssi) / (10 * exposant)) }

    /** « ≈ 2,3 m », « < 1 m », « > 20 m » : à lire comme un ordre de grandeur (murs, corps humain et téléphone déforment le signal). */
    fun libelleDistance(m: Double?): String = when { m == null -> "distance inconnue"; m < 1.0 -> "< 1 m"; m > 20 -> "> 20 m"; else -> "≈ " + "%.1f".format(java.util.Locale.FRANCE, m) + " m" }

    /** Lissage du signal : moyenne des `taille` dernières mesures (les mesures brutes sautent de ±10 dBm d'une annonce à l'autre). */
    class Lissage(private val taille: Int = 5) {
        private val mesures = ArrayDeque<Int>()
        fun ajouter(rssi: Int): Double { mesures.addLast(rssi); while (mesures.size > taille) mesures.removeFirst(); return mesures.average() }
    }

    /** Un changement de présence : arrivée ou départ de cette balise à cet instant. */
    data class Evenement(val identifiant: String, val arrivee: Boolean, val ms: Long)

    /**
     * Suivi de présence (simulation du pointage automatique) : une balise est PRÉSENTE si elle a été vue depuis moins de `delaiAbsenceMs`, ABSENTE sinon. `mettreAJour` rend les
     * arrivées et départs survenus depuis le dernier appel.
     */
    class Suivi(var delaiAbsenceMs: Long) {
        private val dernierVu = HashMap<String, Long>()
        private val presentes = HashSet<String>()
        fun vue(id: String, ms: Long) { dernierVu[id] = ms }
        fun estPresente(id: String) = id in presentes
        fun dernierVu(id: String) = dernierVu[id]
        fun mettreAJour(maintenantMs: Long): List<Evenement> {
            val evenements = mutableListOf<Evenement>()
            for ((id, vu) in dernierVu) {
                val present = maintenantMs - vu <= delaiAbsenceMs
                if (present && presentes.add(id)) evenements += Evenement(id, true, vu)
                else if (!present && presentes.remove(id)) evenements += Evenement(id, false, maintenantMs)
            }
            return evenements.sortedBy { it.ms }
        }
    }
}
