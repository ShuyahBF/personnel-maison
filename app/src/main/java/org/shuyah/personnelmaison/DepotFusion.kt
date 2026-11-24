package org.shuyah.personnelmaison

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import org.shuyah.personnelmaison.FusionAppareils.Fiche
import org.shuyah.personnelmaison.FusionAppareils.Instantane
import org.shuyah.personnelmaison.FusionAppareils.Operation
import org.shuyah.personnelmaison.FusionAppareils.Tombe

/**
 * DEPOT DE LA FUSION (version 1.67) : la partie qui LIT et ÉCRIT dans les bases SQLite pour le moteur FusionAppareils (pur).
 *  - `lire` : transforme une base (la nôtre, ou la copie de l'autre appareil ouverte à part) en `Instantane` : chaque fiche avec son identifiant unique et sa date, et les liens entre fiches
 *    (numéro local du parent) remplacés par l'identifiant unique du parent, comparable d'un téléphone à l'autre ;
 *  - `appliquer` : rejoue un plan sur NOTRE base, dans UNE transaction (tout ou rien pour chaque lot), en retraduisant les identifiants uniques en numéros locaux et en restituant,
 *    après chaque écriture, l'identifiant et la date d'origine dans le suivi (les déclencheurs du lot 1 les auraient remplacés par « maintenant »).
 * Les pièces jointes (fichiers) ne sont pas fusionnées dans ce lot.
 */
object DepotFusion {
    /** Lecteur minimal : une requête → des lignes (nom de colonne → valeur). Une implémentation pour Room, une pour la copie ouverte avec SQLCipher. */
    fun interface Requeteur { fun lignes(sql: String, args: Array<String>): List<Map<String, Any?>> }

    fun depuisCurseur(c: Cursor): List<Map<String, Any?>> = c.use { k ->
        val noms = k.columnNames; val sortie = ArrayList<Map<String, Any?>>()
        while (k.moveToNext()) sortie.add(noms.indices.associate { i ->
            noms[i] to when (k.getType(i)) { Cursor.FIELD_TYPE_INTEGER -> k.getLong(i); Cursor.FIELD_TYPE_FLOAT -> k.getDouble(i); Cursor.FIELD_TYPE_STRING -> k.getString(i); Cursor.FIELD_TYPE_BLOB -> k.getBlob(i); else -> null }
        })
        sortie
    }
    fun requeteurRoom(db: SupportSQLiteDatabase) = Requeteur { sql, args -> depuisCurseur(db.query(sql, args as Array<Any?>)) }

    /** Vrai si la base porte le suivi des modifications (installé par la 1.64) : sans lui, aucune fusion n'est possible. */
    fun aLeSuivi(r: Requeteur) = r.lignes("SELECT name FROM sqlite_master WHERE type='table' AND name='suivi_fiches'", emptyArray()).isNotEmpty()

    /** Photographie normalisée d'une base : fiches (avec liens en identifiants uniques) et pierres tombales. Une fiche sans identifiant, ou dont le parent est introuvable, est ignorée. */
    fun lire(r: Requeteur): Instantane {
        val suivi = r.lignes("SELECT t, id, uid, maj FROM suivi_fiches", emptyArray()).associate { (it["t"] as String to (it["id"] as Long)) to Pair(it["uid"] as String, it["maj"] as Long) }
        val presentes = r.lignes("SELECT name FROM sqlite_master WHERE type='table'", emptyArray()).map { it["name"] as String }.toSet()
        val fiches = ArrayList<Fiche>()
        for (table in FusionAppareils.ORDRE) {
            if (table !in presentes) continue
            val liens = FusionAppareils.LIENS[table] ?: emptyMap()
            lignes@ for (ligne in r.lignes("SELECT * FROM `$table`", emptyArray())) {
                val id = ligne["id"] as? Long ?: continue
                val (uid, maj) = suivi[table to id] ?: continue
                val champs = LinkedHashMap<String, Any?>(ligne.filterKeys { it != "id" })
                for ((col, parent) in liens) {
                    val v = ligne[col] as? Long ?: continue                                  // lien vide : on garde null
                    champs[col] = suivi[parent to v]?.first ?: continue@lignes               // parent sans identifiant : fiche ignorée
                }
                if (table == "numeros") {
                    val parent = FusionAppareils.parentNumero(ligne) ?: continue
                    val v = ligne["proprietaireId"] as? Long ?: continue
                    champs["proprietaireId"] = suivi[parent to v]?.first ?: continue
                }
                fiches.add(Fiche(table, uid, maj, champs))
            }
        }
        val tombes = r.lignes("SELECT t, uid, maj FROM suivi_suppressions", emptyArray()).map { Tombe(it["t"] as String, it["uid"] as String, it["maj"] as Long) }
        return Instantane(fiches, tombes)
    }

    /** Valeur lue dans du JSON : entiers en Long, décimaux en Double (comparables aux valeurs lues dans les tables). */
    private fun normaliser(v: Any?): Any? = when (v) { null, org.json.JSONObject.NULL -> null; is Int -> v.toLong(); is Long -> v; is Number -> v.toDouble(); else -> v }

    /** État de chaque fiche lors de la dernière synchronisation réussie (table `suivi_base`, dans la base chiffrée). Vide si jamais enregistré. Ne lève jamais d'exception. */
    fun lireBase(r: Requeteur): BaseAccord = try {
        r.lignes("SELECT t, cle, champs FROM suivi_base", emptyArray()).associate { ligne ->
            val o = org.json.JSONObject(ligne["champs"] as String)
            (ligne["t"] as String to (ligne["cle"] as String)) to o.keys().asSequence().associateWith { k -> normaliser(o.get(k)) }
        }
    } catch (e: Exception) { emptyMap() }

    /** Enregistre l'état actuel comme « dernier accord » (à appeler quand les deux appareils viennent d'être mis d'accord). Remplace l'ancien état, en une transaction. */
    fun ecrireBase(db: SupportSQLiteDatabase, inst: Instantane) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `suivi_base` (`t` TEXT NOT NULL, `cle` TEXT NOT NULL, `champs` TEXT NOT NULL, PRIMARY KEY(`t`, `cle`))")
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM suivi_base")
            for (f in inst.fiches) {
                val o = org.json.JSONObject()
                for ((k, v) in f.champs) o.put(k, v ?: org.json.JSONObject.NULL)
                db.execSQL("INSERT OR REPLACE INTO suivi_base (t, cle, champs) VALUES (?, ?, ?)", arrayOf(f.table, FusionAppareils.cleBase(f), o.toString()))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Bilan d'une application de plan. */
    class Bilan(val ajoutes: Int, val modifies: Int, val supprimes: Int, val ignores: List<String>)

    /** Rejoue le plan sur NOTRE base. Chaque opération est isolée : une qui échoue (lien introuvable, contrainte) est signalée dans `ignores` sans empêcher les autres. */
    fun appliquer(db: SupportSQLiteDatabase, plan: FusionAppareils.Plan): Bilan {
        var aj = 0; var mo = 0; var su = 0; val ignores = ArrayList<String>()
        fun un(sql: String, vararg a: Any?): Long? = db.query(sql, arrayOf(*a)).use { if (it.moveToFirst()) it.getLong(0) else null }
        fun idLocal(table: String, uid: String) = un("SELECT id FROM suivi_fiches WHERE t = ? AND uid = ?", table, uid)

        /** Colonnes à écrire pour une fiche venue de l'autre appareil : liens retraduits en numéros locaux ; null si un parent manque ici. */
        fun valeurs(f: Fiche, nouvelle: Boolean): LinkedHashMap<String, Any?>? {
            val protegees = FusionAppareils.PROTEGEES[f.table] ?: emptySet()
            val v = LinkedHashMap<String, Any?>()
            for ((col, val0) in f.champs) {
                if (col in protegees) { if (nouvelle) v[col] = null; continue }                 // chemin de fichier : jamais copié d'un autre téléphone
                val parent = if (f.table == "numeros" && col == "proprietaireId") FusionAppareils.parentNumero(f.champs) else FusionAppareils.LIENS[f.table]?.get(col)
                if (parent != null && val0 != null) v[col] = idLocal(parent, val0 as String) ?: return null else v[col] = val0
            }
            return v
        }

        db.beginTransaction()
        try {
            for (o in plan.operations) {
                try {
                    when (o) {
                        is Operation.AdopterUid -> db.execSQL("UPDATE suivi_fiches SET uid = ? WHERE t = ? AND uid = ?", arrayOf(o.nouveau, o.table, o.ancien))
                        is Operation.Inserer -> {
                            val v = valeurs(o.fiche, true)
                            if (v == null) { ignores.add("${o.fiche.table} : fiche liée à un élément absent d'ici"); continue }
                            if (o.fiche.table == "profil") v["id"] = 1L
                            val cols = v.keys.joinToString(", ") { "`$it`" }
                            db.execSQL("INSERT OR REPLACE INTO `${o.fiche.table}` ($cols) VALUES (${v.keys.joinToString(", ") { "?" }})", v.values.toTypedArray())
                            val id = un("SELECT last_insert_rowid()") ?: continue
                            db.execSQL("DELETE FROM suivi_fiches WHERE uid = ? AND NOT (t = ? AND id = ?)", arrayOf(o.fiche.uid, o.fiche.table, id))
                            db.execSQL("UPDATE suivi_fiches SET uid = ?, maj = ? WHERE t = ? AND id = ?", arrayOf(o.fiche.uid, o.fiche.maj, o.fiche.table, id))
                            db.execSQL("DELETE FROM suivi_suppressions WHERE t = ? AND uid = ?", arrayOf(o.fiche.table, o.fiche.uid))
                            aj++
                        }
                        is Operation.Mettre -> {
                            val id = idLocal(o.fiche.table, o.fiche.uid) ?: continue
                            val v = valeurs(o.fiche, false)
                            if (v == null) { ignores.add("${o.fiche.table} : fiche liée à un élément absent d'ici"); continue }
                            if (v.isNotEmpty()) db.execSQL("UPDATE `${o.fiche.table}` SET ${v.keys.joinToString(", ") { "`$it` = ?" }} WHERE id = ?", arrayOf(*v.values.toTypedArray(), id))
                            db.execSQL("UPDATE suivi_fiches SET maj = ? WHERE t = ? AND id = ?", arrayOf(o.fiche.maj, o.fiche.table, id))
                            mo++
                        }
                        is Operation.Supprimer -> {
                            val id = idLocal(o.table, o.uid) ?: continue
                            db.execSQL("DELETE FROM `${o.table}` WHERE id = ?", arrayOf(id))
                            su++
                        }
                    }
                } catch (e: Exception) { ignores.add("${(o as? Operation.Inserer)?.fiche?.table ?: (o as? Operation.Mettre)?.fiche?.table ?: "opération"} : ${e.message?.take(80)}") }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return Bilan(aj, mo, su, ignores)
    }
}
