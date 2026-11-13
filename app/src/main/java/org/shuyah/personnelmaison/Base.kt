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

/** Horaire contractuel d'UN jour de la semaine (jour 0 = lundi … 6 = dimanche). Un jour sans ligne = jour de repos. Permet des horaires différents (samedi plus court…). */
@Entity(tableName = "horaires_personnel", indices = [Index(value = ["personnelId", "jour"], unique = true)])
data class HorairePersonnel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personnelId: Long,
    val jour: Int,
    val debutMin: Int,
    val finMin: Int
)

/** Numéro de téléphone d'un personnel ou d'une société (3 au plus par fiche, un seul « par défaut »). */
@Entity(tableName = "numeros", indices = [Index(value = ["proprietaire", "proprietaireId"])])
data class NumeroTel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val proprietaire: String,          // « PERSONNEL » ou « SOCIETE »
    val proprietaireId: Long,
    val numero: String,
    val parDefaut: Boolean = false
)

/** Bonus (prime) payé ou retenue (retrait) faite sur le salaire d'un personnel. */
@Entity(tableName = "paiements", indices = [Index("personnelId")])
data class Paiement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personnelId: Long,
    val date: String,                  // AAAA-MM-JJ
    val genre: String,                 // « BONUS » ou « RETENUE »
    val montant: Long,                 // FCFA, toujours positif
    val motif: String = "",
    val creeLe: Long = System.currentTimeMillis()
)

/** Tâche habituelle d'un personnel (liste de ses tâches par défaut). */
@Entity(tableName = "taches", indices = [Index("personnelId")])
data class Tache(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personnelId: Long,
    val libelle: String
)

/** Document d'une société (contrat de prestations…) : photo ou PDF, enregistré CHIFFRÉ dans le dossier privé de l'application. */
@Entity(tableName = "documents_societe", indices = [Index("societeId")])
data class DocumentSociete(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val societeId: Long,
    val libelle: String,
    val type: String,                  // « IMAGE » ou « PDF »
    val fichier: String,               // nom du fichier chiffré
    val ajouteLe: Long = System.currentTimeMillis()
)

/**
 * PROFIL DE L'UTILISATEUR (une seule fiche, id = 1) : identité, adresse, position GPS de la maison, personne à prévenir, remarques.
 * Ses numéros de téléphone (3 au plus) sont dans la table des numéros, propriétaire « UTILISATEUR ». Reste sur le téléphone, jamais envoyé.
 */
@Entity(tableName = "profil")
data class ProfilUtilisateur(
    @PrimaryKey val id: Long = 1,
    val nom: String = "",
    val prenoms: String = "",
    val email: String = "",
    val adresse: String = "",
    val quartier: String = "",          // quartier, secteur ou village
    val ville: String = "",
    val repere: String = "",            // point de repère (« derrière le marché », « portail bleu »…) : très utile pour être retrouvé
    val latitude: Double? = null,       // position GPS de la maison
    val longitude: Double? = null,
    val precisionM: Float? = null,      // précision de la position, en mètres
    val geolocaliseLe: Long? = null,    // moment où la position a été relevée
    val urgenceNom: String = "",        // personne à prévenir en cas d'urgence
    val urgenceTelephone: String = "",
    val remarques: String = ""
)

/**
 * OPÉRATION DU PORTEFEUILLE : une dépense (course, facture…) ou une recette (salaire, vente…). Montants en FCFA, toujours positifs ;
 * le `genre` dit si c'est une dépense ou une recette. `portefeuille` = d'où vient / où va l'argent (espèces, Orange Money…).
 */
@Entity(tableName = "operations", indices = [Index("date")])
data class Operation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val genre: String,                  // « DEPENSE » ou « RECETTE »
    val date: String,                   // AAAA-MM-JJ
    val montant: Long,
    val tiers: String = "",             // marchand (dépense) ou source (recette)
    val categorie: String = "",
    val portefeuille: String = "Espèces",
    val note: String = "",
    val creeLe: Long = System.currentTimeMillis(),
    val modifieLe: Long = System.currentTimeMillis()
)

/** Ligne de détail d'une opération (un article d'un ticket de caisse, une course saisie à la main). `montant` = total de la ligne. */
@Entity(tableName = "lignes_operation", indices = [Index("operationId")])
data class LigneOperation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val operationId: Long,
    val libelle: String,
    val quantite: Double = 1.0,
    val montant: Long,
    /** Version 1.6 : ligne cochée (« dans le panier ») comme une liste de courses ; la liste a ainsi deux totaux (liste / cochés). */
    @androidx.room.ColumnInfo(defaultValue = "0") val coche: Boolean = false
)

/** Pièce justificative d'une opération : photo CHIFFRÉE du ticket ou de la facture, avec le texte lu par l'OCR (corrigeable). */
@Entity(tableName = "pieces_operation", indices = [Index("operationId")])
data class PieceOperation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val operationId: Long,
    val fichier: String,
    val texte: String = "",
    val ajouteLe: Long = System.currentTimeMillis()
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

    // ---- Portefeuille : opérations, lignes de détail, pièces ----
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun ajouterOperation(o: Operation): Long
    @Update(onConflict = OnConflictStrategy.ABORT) suspend fun modifierOperation(o: Operation)
    @Query("DELETE FROM operations WHERE id = :id") suspend fun supprimerOperation(id: Long)
    @Query("SELECT * FROM operations WHERE id = :id") suspend fun operation(id: Long): Operation?
    @Query("SELECT * FROM operations WHERE id = :id") fun operationFlow(id: Long): Flow<Operation?>
    @Query("SELECT * FROM operations ORDER BY date DESC, id DESC") fun operations(): Flow<List<Operation>>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun ajouterLigneOperation(l: LigneOperation): Long
    @Query("DELETE FROM lignes_operation WHERE operationId = :id") suspend fun supprimerLignesOperation(id: Long)
    @Query("SELECT * FROM lignes_operation WHERE operationId = :id ORDER BY id") fun lignesOperation(id: Long): Flow<List<LigneOperation>>
    @Query("SELECT * FROM lignes_operation WHERE operationId = :id ORDER BY id") suspend fun lignesOperationListe(id: Long): List<LigneOperation>
    /** Coche ou décoche une ligne de détail (liste de courses). */
    @Query("UPDATE lignes_operation SET coche = :coche WHERE id = :id") suspend fun cocherLigneOperation(id: Long, coche: Boolean)
    @Query("SELECT * FROM lignes_operation") fun toutesLignesOperation(): Flow<List<LigneOperation>>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun ajouterPieceOperation(p: PieceOperation): Long
    @Update(onConflict = OnConflictStrategy.ABORT) suspend fun modifierPieceOperation(p: PieceOperation)
    @Query("DELETE FROM pieces_operation WHERE id = :id") suspend fun supprimerPieceOperation(id: Long)
    @Query("DELETE FROM pieces_operation WHERE operationId = :id") suspend fun supprimerPiecesOperation(id: Long)
    @Query("SELECT * FROM pieces_operation WHERE operationId = :id ORDER BY id") fun piecesOperation(id: Long): Flow<List<PieceOperation>>
    @Query("SELECT * FROM pieces_operation WHERE operationId = :id ORDER BY id") suspend fun piecesOperationListe(id: Long): List<PieceOperation>
    @Query("SELECT * FROM pieces_operation WHERE id = :id") suspend fun pieceOperation(id: Long): PieceOperation?
    @Query("SELECT operationId, COUNT(*) AS n FROM pieces_operation GROUP BY operationId") fun nombrePieces(): Flow<List<NombrePieces>>

    // ---- Profil de l'utilisateur ----
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun enregistrerProfil(p: ProfilUtilisateur): Long
    @Query("SELECT * FROM profil WHERE id = 1") fun profil(): Flow<ProfilUtilisateur?>
    @Query("SELECT * FROM profil WHERE id = 1") suspend fun profilUneFois(): ProfilUtilisateur?

    // ---- Horaires par jour ----
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun ajouterHoraire(h: HorairePersonnel): Long
    @Query("DELETE FROM horaires_personnel WHERE personnelId = :id") suspend fun supprimerHoraires(id: Long)
    @Query("SELECT * FROM horaires_personnel") fun tousHoraires(): Flow<List<HorairePersonnel>>
    @Query("SELECT * FROM horaires_personnel WHERE personnelId = :id") suspend fun horairesDe(id: Long): List<HorairePersonnel>

    // ---- Numéros de téléphone ----
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun ajouterNumero(n: NumeroTel): Long
    @Query("DELETE FROM numeros WHERE proprietaire = :proprietaire AND proprietaireId = :id") suspend fun supprimerNumeros(proprietaire: String, id: Long)
    @Query("SELECT * FROM numeros") fun tousNumeros(): Flow<List<NumeroTel>>
    @Query("SELECT * FROM numeros WHERE proprietaire = :proprietaire AND proprietaireId = :id ORDER BY parDefaut DESC, id") suspend fun numerosDe(proprietaire: String, id: Long): List<NumeroTel>

    // ---- Bonus et retenues ----
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun ajouterPaiement(p: Paiement): Long
    @Query("DELETE FROM paiements WHERE id = :id") suspend fun supprimerPaiement(id: Long)
    @Query("DELETE FROM paiements WHERE personnelId = :id") suspend fun supprimerPaiementsDe(id: Long)
    @Query("SELECT * FROM paiements WHERE personnelId = :id ORDER BY date DESC, id DESC") fun paiementsDe(id: Long): Flow<List<Paiement>>
    @Query("SELECT * FROM paiements") fun tousPaiements(): Flow<List<Paiement>>

    // ---- Tâches par défaut ----
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun ajouterTache(t: Tache): Long
    @Query("DELETE FROM taches WHERE id = :id") suspend fun supprimerTache(id: Long)
    @Query("DELETE FROM taches WHERE personnelId = :id") suspend fun supprimerTachesDe(id: Long)
    @Query("SELECT * FROM taches WHERE personnelId = :id ORDER BY id") fun tachesDe(id: Long): Flow<List<Tache>>

    // ---- Documents des sociétés ----
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun ajouterDocument(d: DocumentSociete): Long
    @Query("DELETE FROM documents_societe WHERE id = :id") suspend fun supprimerDocument(id: Long)
    @Query("SELECT * FROM documents_societe WHERE societeId = :id ORDER BY ajouteLe DESC") fun documentsDe(id: Long): Flow<List<DocumentSociete>>
    @Query("SELECT * FROM documents_societe WHERE societeId = :id") suspend fun documentsDeListe(id: Long): List<DocumentSociete>
    @Query("SELECT * FROM documents_societe WHERE id = :id") suspend fun documentParId(id: Long): DocumentSociete?
    @Query("DELETE FROM documents_societe WHERE societeId = :id") suspend fun supprimerDocumentsDe(id: Long)

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

/** Résultat de « nombre de pièces par opération » (affiche l'icône de pièce dans la liste). */
data class NombrePieces(val operationId: Long, val n: Int)

@Database(entities = [Societe::class, Personnel::class, Pointage::class, NotePerso::class, Evaluation::class, JournalPointage::class, HorairePersonnel::class, NumeroTel::class, Paiement::class, Tache::class, DocumentSociete::class, ProfilUtilisateur::class, Operation::class, LigneOperation::class, PieceOperation::class], version = 6, exportSchema = true)
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

/**
 * Migration 2 → 3 : horaires par jour, numéros de téléphone multiples, bonus et retenues, tâches, documents des sociétés.
 * Les données existantes sont conservées : les horaires uniformes d'avant deviennent une ligne par jour travaillé, et le téléphone d'avant
 * devient le numéro « par défaut ».
 */
val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `horaires_personnel` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `personnelId` INTEGER NOT NULL, `jour` INTEGER NOT NULL, `debutMin` INTEGER NOT NULL, `finMin` INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_horaires_personnel_personnelId_jour` ON `horaires_personnel` (`personnelId`, `jour`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `numeros` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `proprietaire` TEXT NOT NULL, `proprietaireId` INTEGER NOT NULL, `numero` TEXT NOT NULL, `parDefaut` INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_numeros_proprietaire_proprietaireId` ON `numeros` (`proprietaire`, `proprietaireId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `paiements` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `personnelId` INTEGER NOT NULL, `date` TEXT NOT NULL, `genre` TEXT NOT NULL, `montant` INTEGER NOT NULL, `motif` TEXT NOT NULL, `creeLe` INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_paiements_personnelId` ON `paiements` (`personnelId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `taches` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `personnelId` INTEGER NOT NULL, `libelle` TEXT NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_taches_personnelId` ON `taches` (`personnelId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `documents_societe` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `societeId` INTEGER NOT NULL, `libelle` TEXT NOT NULL, `type` TEXT NOT NULL, `fichier` TEXT NOT NULL, `ajouteLe` INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_documents_societe_societeId` ON `documents_societe` (`societeId`)")
        // Reprise des données : un horaire par jour travaillé (jours = masque de bits, lundi = bit 0) et le téléphone en numéro par défaut
        db.execSQL("INSERT INTO horaires_personnel (personnelId, jour, debutMin, finMin) SELECT p.id, d.j, p.debutMin, p.finMin FROM personnels p JOIN (SELECT 0 AS j UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6) d ON ((p.jours >> d.j) & 1) = 1")
        db.execSQL("INSERT INTO numeros (proprietaire, proprietaireId, numero, parDefaut) SELECT 'PERSONNEL', id, TRIM(telephone), 1 FROM personnels WHERE TRIM(telephone) <> ''")
        db.execSQL("INSERT INTO numeros (proprietaire, proprietaireId, numero, parDefaut) SELECT 'SOCIETE', id, TRIM(telephone), 1 FROM societes WHERE TRIM(telephone) <> ''")
    }
}

/** Migration 3 → 4 : ajout du profil de l'utilisateur. */
val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `profil` (`id` INTEGER NOT NULL, `nom` TEXT NOT NULL, `prenoms` TEXT NOT NULL, `email` TEXT NOT NULL, `adresse` TEXT NOT NULL, `quartier` TEXT NOT NULL, `ville` TEXT NOT NULL, `repere` TEXT NOT NULL, `latitude` REAL, `longitude` REAL, `precisionM` REAL, `geolocaliseLe` INTEGER, `urgenceNom` TEXT NOT NULL, `urgenceTelephone` TEXT NOT NULL, `remarques` TEXT NOT NULL, PRIMARY KEY(`id`))")
    }
}

/** Migration 4 → 5 : portefeuille (opérations, lignes de détail, pièces justificatives). */
val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `operations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `genre` TEXT NOT NULL, `date` TEXT NOT NULL, `montant` INTEGER NOT NULL, `tiers` TEXT NOT NULL, `categorie` TEXT NOT NULL, `portefeuille` TEXT NOT NULL, `note` TEXT NOT NULL, `creeLe` INTEGER NOT NULL, `modifieLe` INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_operations_date` ON `operations` (`date`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `lignes_operation` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `operationId` INTEGER NOT NULL, `libelle` TEXT NOT NULL, `quantite` REAL NOT NULL, `montant` INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_lignes_operation_operationId` ON `lignes_operation` (`operationId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `pieces_operation` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `operationId` INTEGER NOT NULL, `fichier` TEXT NOT NULL, `texte` TEXT NOT NULL, `ajouteLe` INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pieces_operation_operationId` ON `pieces_operation` (`operationId`)")
    }
}

/** Migration 5 → 6 : lignes de détail cochables (colonne `coche`, décochée par défaut ; les données existantes sont conservées). */
val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `lignes_operation` ADD COLUMN `coche` INTEGER NOT NULL DEFAULT 0")
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
            .openHelperFactory(SupportFactory(SQLiteDatabase.getBytes(cle.toCharArray()))).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build()
    }
}
