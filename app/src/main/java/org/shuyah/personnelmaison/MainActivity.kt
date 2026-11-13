package org.shuyah.personnelmaison

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import android.content.Intent
import android.widget.Toast
import kotlinx.coroutines.launch

/** Les écrans de l'application. Les quatre premiers sont les onglets du bas ; les autres s'ouvrent par-dessus. */
sealed class Ecran {
    object Aujourdhui : Ecran()
    object Personnels : Ecran()
    object Societes : Ecran()
    object Stats : Ecran()
    object Profil : Ecran()
    object Budget : Ecran()
    object Plus : Ecran()
    object Famille : Ecran()
    object Medicaments : Ecran()
    object Repas : Ecran()
    object Aide : Ecran()
    object LaboNfc : Ecran()
    object Balises : Ecran()
    object Recepteur : Ecran()   // récepteur de badges (présence par balises, SAWALI lot 387)
    data class FormMedicament(val id: Long?) : Ecran()
    data class FamilleLien(val id: String) : Ecran()
    object BudgetStats : Ecran()
    data class FicheOperation(val id: Long) : Ecran()
    data class FormOperation(val id: Long?, val genre: String, val scan: Boolean) : Ecran()
    data class PieceOperation(val id: Long) : Ecran()
    object Noter : Ecran()
    object Corrections : Ecran()
    data class Correction(val personnelId: Long, val date: String) : Ecran()
    data class FichePersonnel(val id: Long) : Ecran()
    data class FormPersonnel(val id: Long?) : Ecran()
    data class Piece(val id: Long) : Ecran()
    data class Document(val id: Long) : Ecran()
    data class FicheSociete(val id: Long) : Ecran()
    data class FormSociete(val id: Long?) : Ecran()
    data class FormEvaluation(val societeId: Long, val id: Long?) : Ecran()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Presence.demarrer(lifecycleScope)
        PlanificateurMedicaments.planifierTout(this)          // rappels de médicaments : alarmes remises à jour à chaque ouverture
        PlanificateurRepas.planifierTout(this)                // rappels de repas : alarmes remises à jour à chaque ouverture
        SurveillanceFamille.programmer(this)                  // veille des proches (médicaments oubliés, messages)
        setContent { MaterialTheme { Racine() } }
        traiterEtiquette(intent)
    }

    /** L'application est déjà ouverte et une étiquette est approchée (mode « singleTask » : l'intention arrive ici). */
    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); traiterEtiquette(i) }

    /**
     * Étiquette NFC lue. MÉDICAMENT : confirme la prise la plus proche de l'heure actuelle (de 6 h avant à 90 minutes après son heure prévue). REPAS : « Je prends mon repas »,
     * puis « Repas terminé » au second contact ; le repas est celui de l'étiquette, ou le plus proche de l'heure si elle est « selon l'heure ». Les proches choisis sont prévenus.
     * Une étiquette écrite par un autre téléphone est ignorée.
     */
    private fun traiterEtiquette(i: Intent?) {
        val e = EtiquetteNfc.lireIntent(i) ?: return
        i?.action = null                                                       // traitée une seule fois (rotation de l'écran, retour à l'application)
        if (e.code != EtiquetteNfc.codeInstallation(this)) { Toast.makeText(this, "Cette étiquette n'a pas été écrite par ce téléphone.", Toast.LENGTH_LONG).show(); return }
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val message = if (e.type == "med") confirmerMedicament(e.valeur.toLongOrNull() ?: 0L) else declarerRepas(e.valeur)
            runOnUiThread { Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show() }
        }
    }

    private suspend fun confirmerMedicament(medicamentId: Long): String {
        val dao = Base.dao(this); val zone = java.time.ZoneId.systemDefault()
        val m = dao.medicament(medicamentId) ?: return "Médicament introuvable : l'étiquette est périmée."
        val jour = java.time.LocalDate.now()
        val candidates = (RappelsMedicaments.prisesPrevues(m, jour.minusDays(1), zone) + RappelsMedicaments.prisesPrevues(m, jour, zone)).filter { dao.prise(m.id, it)?.statut != "PRIS" }
        val prevu = EtiquetteNfc.choisirPrise(candidates, System.currentTimeMillis()) ?: return "${m.nom} : aucune prise à confirmer pour le moment."
        PlanificateurMedicaments.confirmerPrise(this, m.id, prevu)
        return "✔ ${m.nom} : prise de " + RappelsMedicaments.libelleHeure(java.time.Instant.ofEpochMilli(prevu).atZone(zone).let { it.hour * 60 + it.minute }) + " confirmée."
    }

    private suspend fun declarerRepas(valeur: String): String {
        val maintenant = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
        val code = if (valeur == EtiquetteNfc.REPAS_AUTO) EtiquetteNfc.choisirRepas(RappelsRepas.REPAS.filter { ReglagesFamille.repasActif(this, it.code) }.map { it.code to ReglagesFamille.repasMinutes(this, it.code) }, maintenant) else valeur
        val repas = code?.let { RappelsRepas.repas(it) } ?: return "Aucun repas à déclarer pour le moment."
        return when (ReglagesFamille.repasEtat(this, repas.code, PlanificateurRepas.jour())) {
            "termine" -> "${repas.nom} : déjà terminé."
            "prend" -> { PlanificateurRepas.declarer(this, repas.code, "termine"); "✔ ${repas.nom} terminé." }
            else -> { PlanificateurRepas.declarer(this, repas.code, "prend"); "🍽 Bon appétit ! ${repas.nom} déclaré." }
        }
    }
}

@Composable
private fun Racine() {
    val c = androidx.compose.ui.platform.LocalContext.current
    // « Texte plus grand » : agrandit tout le texte de l'application (par défaut oui)
    var grosTexte by remember { mutableStateOf(Reglages.grosTexte(c)) }
    val surGrosTexte: (Boolean) -> Unit = { grosTexte = it; Reglages.grosTexte(c, it) }
    val densite = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(densite.density, densite.fontScale * (if (grosTexte) 1.25f else 1f))) {
    var onglet by remember { mutableStateOf<Ecran>(Ecran.Aujourdhui) }
    val pile = remember { mutableStateListOf<Ecran>() }               // écrans ouverts par-dessus l'onglet
    fun ouvrir(e: Ecran) { if (e in listOf(Ecran.Aujourdhui, Ecran.Personnels, Ecran.Budget, Ecran.Societes, Ecran.Plus)) { pile.clear(); onglet = e } else pile.add(e) }
    fun retour() { if (pile.isNotEmpty()) pile.removeAt(pile.lastIndex) }
    BackHandler(enabled = pile.isNotEmpty()) { retour() }
    val courant = pile.lastOrNull() ?: onglet

    Scaffold(containerColor = Fond, bottomBar = {
        if (pile.isEmpty()) NavigationBar(containerColor = Color.White) {
            listOf(Triple(Ecran.Aujourdhui, Icons.Filled.Today, "Aujourd'hui"), Triple(Ecran.Personnels, Icons.Filled.Groups, "Personnel"),
                Triple(Ecran.Budget, Icons.Filled.AccountBalanceWallet, "Portefeuille"), Triple(Ecran.Societes, Icons.Filled.Business, "Sociétés"), Triple(Ecran.Plus, Icons.Filled.MoreHoriz, "Plus")).forEach { (e, icone, nom) ->
                NavigationBarItem(onglet == e, { ouvrir(e) }, { Icon(icone, nom) }, label = { Text(nom, fontSize = 11.sp) })
            }
        }
    }) { marge ->
        // Bords de l'écran : barre d'état toujours ; barre de navigation du téléphone seulement quand l'onglet du bas n'est pas affiché
        Box(Modifier.fillMaxSize().padding(marge).statusBarsPadding().then(if (pile.isNotEmpty()) Modifier.navigationBarsPadding() else Modifier)) {
            when (courant) {
                Ecran.Aujourdhui -> EcranAujourdhui(::ouvrir, grosTexte, surGrosTexte)
                Ecran.Noter -> EcranNoter(::retour)
                Ecran.Corrections -> EcranCorrections(::ouvrir, ::retour)
                is Ecran.Correction -> EcranCorrection(courant.personnelId, courant.date, ::retour)
                Ecran.Personnels -> EcranListePersonnel(::ouvrir)
                Ecran.Societes -> EcranListeSocietes(::ouvrir)
                Ecran.Stats -> EcranStats(::ouvrir, ::retour)
                Ecran.Profil -> EcranProfil(::retour)
                Ecran.Budget -> EcranBudget(::ouvrir)
                Ecran.Plus -> EcranPlus(::ouvrir)
                Ecran.BudgetStats -> EcranBudgetStats(::retour)
                Ecran.Famille -> EcranFamille(::ouvrir, ::retour)
                Ecran.Medicaments -> EcranMedicaments(::ouvrir, ::retour)
                Ecran.Repas -> EcranRepas(::retour)
                Ecran.Aide -> EcranAide(::ouvrir, ::retour)
                Ecran.LaboNfc -> EcranLaboNfc(::retour)
                Ecran.Balises -> EcranBalises(::retour)
                Ecran.Recepteur -> EcranRecepteur(::retour)
                is Ecran.FormMedicament -> EcranFormMedicament(courant.id, ::retour)
                is Ecran.FamilleLien -> EcranLienFamille(courant.id, ::retour)
                is Ecran.FicheOperation -> EcranFicheOperation(courant.id, ::ouvrir, ::retour)
                is Ecran.FormOperation -> EcranFormOperation(courant.id, courant.genre, courant.scan, ::retour)
                is Ecran.PieceOperation -> EcranPieceOperation(courant.id, ::retour)
                is Ecran.FichePersonnel -> EcranFichePersonnel(courant.id, ::ouvrir, ::retour)
                is Ecran.FormPersonnel -> EcranFormPersonnel(courant.id, ::retour)
                is Ecran.Piece -> EcranPiece(courant.id, ::retour)
                is Ecran.Document -> EcranDocument(courant.id, ::retour)
                is Ecran.FicheSociete -> EcranFicheSociete(courant.id, ::ouvrir, ::retour)
                is Ecran.FormSociete -> EcranFormSociete(courant.id, ::retour)
                is Ecran.FormEvaluation -> EcranFormEvaluation(courant.societeId, courant.id, ::retour)
            }
            ChatPermanent(Modifier.align(androidx.compose.ui.Alignment.BottomStart))      // messagerie des proches : pastille ou panneau, sur tous les écrans
        }
    }
    }
}

/** Titre d'écran avec, à droite, des tuiles d'action éventuelles. */
@Composable
fun Titre(texte: String, retour: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) =
    Row(Modifier.fillMaxWidth().background(BleuNuit).padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        if (retour != null) Icon(Icons.Filled.ArrowBack, "Retour", tint = Color.White, modifier = Modifier.size(28.dp).padding(end = 8.dp).clickable { retour() })
        Text(texte, color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
        BarreEtat()                                          // date/heure et batterie, dans toutes les barres de titre
        Spacer(Modifier.width(8.dp))
        actions()
    }

/** Conteneur défilant des écrans (marge et espacement communs). */
@Composable
fun Page(titre: String, retour: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}, contenu: @Composable ColumnScope.() -> Unit) = Column(Modifier.fillMaxSize()) {
    Titre(titre, retour, actions)
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        contenu()
        Text(Version.libelle(), fontSize = 11.sp, color = Gris, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
    }
}
