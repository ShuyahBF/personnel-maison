package org.shuyah.personnelmaison

import android.content.Context
import androidx.room.*
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import java.security.SecureRandom

// ======================= Entités (une table chacune) =======================

/** Société de placement dont relèvent les personnels. Les numéros de paiement mobile (Orange, Moov, Telecel Money) sont sur SA fiche. */
@Entity(tableName = "societes")
data class Societe(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nom: String,
    val telephone: String = "",
    val adresse: String = "",
    val orange: String = "",      // numéro Orange Money
    val moov: String = "",        // numéro Moov Money
    val telecel: String = "",     // numéro Telecel Money
    val remarques: String = ""
)

/** Une personne de maison : aide ménagère, gardien, laveur de véhicule… Dates au format ISO « AAAA-MM-JJ ». */
@Entity(tableName = "personnels", indices = [Index("societeId")])
data class Personnel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nom: String,
    val prenoms: String,
    val fonction: String,
    val societeId: Long? = null,
    val dateDebut: String,
    val dateFin: String? = null,
    val debutMin: Int = 7 * 60,          // horaire contractuel : début (minutes depuis minuit)
    val finMin: Int = 17 * 60,           // horaire contractuel : fin
    val jours: Int = 63,                 // jours travaillés (bit 0 = lundi … bit 6 = dimanche) ; 63 = lundi à samedi
    val salaireNet: Long = 0,            // salaire net à payer (FCFA)
    val telephone: String = "",
    val pieceChemin: String? = null,     // copie CHIFFRÉE de la pièce d'identité (fichier privé de l'application)
    val pieceTexte: String = "",         // texte lu par l'OCR sur la pièce
    val creeLe: Long = System.currentTimeMillis()
)

/** Pointage d'un jour : heures d'arrivée et de départ (minutes depuis minuit), retard signalé à la main, absence. Une ligne par personne et par jour. */
@Entity(tableName = "pointages", indices = [Index(value = ["personnelId", "date"], unique = true)])
data class Pointage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personnelId: Long,
    val date: String,
    val arrivee: Int? = null,
    val depart: Int? = null,
    val retardSignale: Boolean = false,
    val absent: Boolean = false,
    val motif: String = ""
)

/** Note ou remarque personnelle sur un personnel, horodatée (millisecondes). */
@Entity(tableName = "notes", indices = [Index("personnelId")])
data class NotePerso(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personnelId: Long,
    val horodatage: Long = System.currentTimeMillis(),
    val texte: String
)

/** Évaluation d'une société de placement : journalière (période « AAAA-MM-JJ ») ou mensuelle (« AAAA-MM »), note de 1 à 5 et remarque. */
@Entity(tableName = "evaluations", indices = [Index("societeId")])
data class Evaluation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val societeId: Long,
    val type: String,               // « JOUR » ou « MOIS »
    val periode: String,
    val note: Int,
    val remarque: String = "",
    val partageable: Boolean = false,   // peut devenir un témoignage public (étape suivante)
    val creeLe: Long = System.currentTimeMillis()
)

/**
 * JOURNAL DES POINTAGES : chaque création, modification ou suppression d'une heure d'arrivée, de départ, d'une absence ou d'un retard laisse une
 * ligne HORODATÉE (moment où l'utilisateur a enregistré, pas l'heure du pointage). Permet de rappeler un pointage pour le corriger et de savoir qui a changé quoi, quand.
 */
@Entity(tableName = "journal_pointages", indices = [Index("personnelId")])
data class JournalPointage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personnelId: Long,
    val date: String,                 // jour du pointage (AAAA-MM-JJ)
    val genre: String,                // ARRIVEE, DEPART, ABSENCE ou RETARD
    val heure: Int?,                  // heure enregistrée (minutes depuis minuit), null pour une suppression ou une absence
    val action: String,               // CREATION, MODIFICATION ou SUPPRESSION
    val horodatage: Long = System.currentTimeMillis()
)

// ======================= Accès aux données =======================

@Dao
interface DaoMaison {
    // ---- Sociétés ----
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun ajouterSociete(s: Societe): Long
    @Update(onConflict = OnConflictStrategy.ABORT) suspend fun modifierSociete(s: Societe)
    @Query("SELECT * FROM societes ORDER BY nom COLLATE NOCASE") fun societes(): Flow<List<Societe>>
    @Query("SELECT * FROM societes WHERE id = :id") suspend fun societe(id: Long): Societe?
    @Query("DELETE FROM societes WHERE id = :id") suspend fun supprimerSociete(id: Long)
    @Query("UPDATE personnels SET societeId = NULL WHERE societeId = :id") suspend fun detacherPersonnel(id: Long)
    @Query("DELETE FROM evaluations WHERE societeId = :id") suspend fun supprimerEvaluationsDe(id: Long)

    // ---- Personnel ----
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun ajouterPersonnel(p: Personnel): Long
    @Update(onConflict = OnConflictStrategy.ABORT) suspend fun modifierPersonnel(p: Personnel)
    @Query("SELECT * FROM personnels ORDER BY nom COLLATE NOCASE, prenoms COLLATE NOCASE") fun personnel(): Flow<List<Personnel>>
    @Query("SELECT * FROM personnels WHERE id = :id") fun personnelFlow(id: Long): Flow<Personnel?>
    @Query("SELECT * FROM personnels WHERE id = :id") suspend fun personnelParId(id: Long): Personnel?
    @Query("DELETE FROM personnels WHERE id = :id") suspend fun supprimerPersonnel(id: Long)
    @Query("DELETE FROM pointages WHERE personnelId = :id") suspend fun supprimerPointagesDe(id: Long)
    @Query("DELETE FROM notes WHERE personnelId = :id") suspend fun supprimerNotesDe(id: Long)

    // ---- Pointages ----
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun enregistrerPointage(p: Pointage): Long
    @Query("SELECT * FROM pointages WHERE date = :date") fun pointagesDuJour(date: String): Flow<List<Pointage>>
    @Query("SELECT * FROM pointages WHERE personnelId = :id AND date >= :debut AND date <= :fin ORDER BY date") fun pointagesPersonnel(id: Long, debut: String, fin: String): Flow<List<Pointage>>
    @Query("SELECT * FROM pointages WHERE date >= :debut AND date <= :fin ORDER BY date") fun pointagesPeriode(debut: String, fin: String): Flow<List<Pointage>>
    @Query("DELETE FROM pointages WHERE personnelId = :id AND date = :date") suspend fun supprimerPointage(id: Long, date: String)

    // ---- Journal et rappel des pointages ----
    @Query("SELECT * FROM pointages WHERE personnelId = :id AND date = :date") suspend fun pointage(id: Long, date: String): Pointage?
    @Query("SELECT * FROM pointages WHERE date >= :depuis ORDER BY date DESC, id DESC LIMIT 80") fun derniersPointages(depuis: String): Flow<List<Pointage>>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun ajouterJournal(j: JournalPointage): Long
    @Query("SELECT * FROM journal_pointages WHERE personnelId = :id AND date = :date ORDER BY horodatage DESC") fun journal(id: Long, date: String): Flow<List<JournalPointage>>
    @Query("SELECT * FROM journal_pointages WHERE date >= :depuis ORDER BY horodatage DESC") fun journalDepuis(depuis: String): Flow<List<JournalPointage>>
    @Query("DELETE FROM journal_pointages WHERE personnelId = :id") suspend fun supprimerJournalDe(id: Long)

    // ---- Notes ----
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun ajouterNote(n: NotePerso): Long
    @Query("SELECT * FROM notes WHERE personnelId = :id ORDER BY horodatage DESC") fun notes(id: Long): Flow<List<NotePerso>>
    @Query("DELETE FROM notes WHERE id = :id") suspend fun supprimerNote(id: Long)

    // ---- Évaluations ----
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun ajouterEvaluation(e: Evaluation): Long
    @Update(onConflict = OnConflictStrategy.ABORT) suspend fun modifierEvaluation(e: Evaluation)
    @Query("SELECT * FROM evaluations WHERE societeId = :id ORDER BY periode DESC, creeLe DESC") fun evaluations(id: Long): Flow<List<Evaluation>>
    @Query("SELECT * FROM evaluations WHERE id = :id") suspend fun evaluationParId(id: Long): Evaluation?
    @Query("SELECT * FROM evaluations") fun toutesEvaluations(): Flow<List<Evaluation>>
    @Query("DELETE FROM evaluations WHERE id = :id") suspend fun supprimerEvaluation(id: Long)
}

@Database(entities = [Societe::class, Personnel::class, Pointage::class, NotePerso::class, Evaluation::class, JournalPointage::class], version = 2, exportSchema = true)
abstract class BaseDonnees : RoomDatabase() { abstract fun dao(): DaoMaison }

/**
 * Ouverture de la base. Elle est CHIFFRÉE (SQLCipher) : la clé est créée une fois, au hasard, et gardée dans les préférences chiffrées
 * par le Keystore Android. Sans le téléphone déverrouillé, les données (identités, salaires, notes) ne sont pas lisibles.
 */
/** Migration 1 → 2 : ajout du journal des pointages (les données existantes sont conservées). */
val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `journal_pointages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `personnelId` INTEGER NOT NULL, `date` TEXT NOT NULL, `genre` TEXT NOT NULL, `heure` INTEGER, `action` TEXT NOT NULL, `horodatage` INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_journal_pointages_personnelId` ON `journal_pointages` (`personnelId`)")
    }
}

object Base {
    private var instance: BaseDonnees? = null

    /** Accès unique à la base (ouverte au premier appel ; synchronisé : appelé depuis plusieurs fils). */
    fun dao(c: Context): DaoMaison = synchronized(this) { (instance ?: ouvrir(c.applicationContext).also { instance = it }).dao() }

    private fun ouvrir(c: Context): BaseDonnees {
        SQLiteDatabase.loadLibs(c)
        val prefs = EncryptedSharedPreferences.create(c, "secrets", MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        var cle = prefs.getString("cle_base", null)
        if (cle == null) {
            val octets = ByteArray(32).also { SecureRandom().nextBytes(it) }
            cle = octets.joinToString("") { "%02x".format(it) }
            prefs.edit().putString("cle_base", cle).apply()
        }
        return Room.databaseBuilder(c, BaseDonnees::class.java, "personnel_maison.db")
            .openHelperFactory(SupportFactory(SQLiteDatabase.getBytes(cle.toCharArray()))).addMigrations(MIGRATION_1_2).build()
    }
}
