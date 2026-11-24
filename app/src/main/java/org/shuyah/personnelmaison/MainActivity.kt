package org.shuyah.personnelmaison

import android.animation.ObjectAnimator
import android.content.Context
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
    object OuSuisJe : Ecran()    // « Vous êtes ici » : lieu de la balise la plus proche (SAWALI lot 388)
    object Tele : Ecran()        // projection de vidéos sur un téléviseur (SAWALI lot 418) : abonnement, appairage, télécommande
    object Sauvegarde : Ecran()  // sauvegarde en ligne chiffrée (v1.35) : activer, sauvegarder, restaurer après réinstallation
    object Mesure : Ecran()      // mesurer une surface avec la caméra (v1.45) : en direct (réalité augmentée) ou sur photo avec un repère
    object Agenda : Ecran()      // Google Agenda par la passerelle SAWALI (lot 403) : rendez-vous, note vocale
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

class MainActivity : androidx.fragment.app.FragmentActivity() {
    // ---- VERROU BIOMÉTRIQUE (« à la connexion ») : l'écran reste vide tant que le téléphone n'a pas reconnu l'utilisateur.
    // Redemandé après plus d'une minute d'absence. Sans capteur ni verrouillage d'écran : pas de verrou.
    private var ouvert by mutableStateOf(false)
    private var derniereSortie = 0L
    private var verrouEnCours = false
    private fun verrouiller() {
        if (verrouEnCours) return
        if (!Biometrie.verrouPossible(this) || (derniereSortie != 0L && System.currentTimeMillis() - derniereSortie < 60_000)) { ouvert = true; return }
        verrouEnCours = true; ouvert = false
        Biometrie.verifier(this, "Ouvrir Personnel de maison", "Posez votre doigt pour continuer",
            surOk = { verrouEnCours = false; VerrouEcrans.succes(); ouvert = true },
            surEchec = { message -> verrouEnCours = false; Toast.makeText(this, "L'application est verrouillée : $message", Toast.LENGTH_LONG).show(); finish() })
    }
    override fun onStop() { super.onStop(); if (!verrouEnCours) derniereSortie = System.currentTimeMillis() }
    override fun onStart() { super.onStart(); verrouiller() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Presence.demarrer(lifecycleScope)
        PlanificateurMedicaments.planifierTout(this)          // rappels de médicaments : alarmes remises à jour à chaque ouverture
        PlanificateurRepas.planifierTout(this)                // rappels de repas : alarmes remises à jour à chaque ouverture
        SurveillanceFamille.programmer(this)                  // veille des proches (médicaments oubliés, messages)
        setContent { MaterialTheme { if (ouvert) Racine() else Box(Modifier.fillMaxSize().background(BleuNuit)) } }
        if (BuildConfig.ENREGISTREMENT_ECRAN) installerIconeEnregistrement()   // débogage : icône discrète d'enregistrement vidéo
        traiterEtiquette(intent)
    }

    // ---- ENREGISTREMENT D'ÉCRAN DE DÉBOGAGE : accord d'Android, puis démarrage du service d'enregistrement (voir EnregistreurEcran.kt)
    private val demandeEnregistrement = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val donnees = r.data
        if (r.resultCode == RESULT_OK && donnees != null) {
            val depart = Intent(this, ServiceEnregistrementEcran::class.java).setAction(ServiceEnregistrementEcran.ACTION_DEMARRER)
                .putExtra(ServiceEnregistrementEcran.EXTRA_CODE, r.resultCode).putExtra(ServiceEnregistrementEcran.EXTRA_DONNEES, donnees)
            startForegroundService(depart)
        } else android.widget.Toast.makeText(this, "Enregistrement d'écran non autorisé", android.widget.Toast.LENGTH_SHORT).show()
    }
    // Android 13 et plus : la notification « enregistrement en cours » doit être autorisée ; la suite est lancée quelle que soit la réponse
    private var suiteApresNotifications: (() -> Unit)? = null
    private val demandeNotificationsEnregistrement = registerForActivityResult(ActivityResultContracts.RequestPermission()) { suiteApresNotifications?.invoke(); suiteApresNotifications = null }
    private var clignotement: ObjectAnimator? = null

    /**
     * Icône DISCRÈTE de l'enregistrement d'écran (débogage) : un petit rond translucide, posé AU-DESSUS de tout l'écran (donc visible aussi sous le verrou d'ouverture).
     *  - TOUCHER = commencer l'enregistrement ; pendant l'enregistrement il devient rouge et clignote ; toucher = arrêter et envoyer la vidéo.
     *  - GLISSER le doigt dessus = le DÉPLACER où l'on veut (sa place est retenue, en fraction de l'écran, donc valable sur tous les téléphones et en rotation).
     *  - APPUI LONG (sans bouger) = le RÉDUIRE en minuscule point presque invisible (masqué) ; un nouvel appui long le rend à sa taille normale.
     *    Pendant un enregistrement il ne peut pas être masqué (il faut pouvoir l'arrêter).
     */
    private fun installerIconeEnregistrement() {
        EnregistreurEcran.nettoyer(this)                                    // vieilles vidéos (plus de 7 jours) effacées
        val d = resources.displayMetrics.density
        val prefsPos = getSharedPreferences("enregistrement_ecran", Context.MODE_PRIVATE)
        val discret = android.graphics.Color.parseColor("#E60B1F3A")
        val tailleNormale = (46 * d).toInt(); val tailleReduite = (14 * d).toInt()   // rond normal / rond « masqué »
        val margeHaut = (28 * d).toInt(); val margeBas = (24 * d).toInt()            // ni sous la barre d'état, ni sur la zone des gestes
        var reduit = prefsPos.getBoolean("icone_reduite", false)
        val icone = TextView(this).apply {
            text = "●"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(android.graphics.Color.WHITE)
            contentDescription = "Enregistrer l'écran pour le débogage"
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(discret) }
            alpha = 0.92f
        }
        val ecranL = { resources.displayMetrics.widthPixels }; val ecranH = { resources.displayMetrics.heightPixels }
        val taille = { if (reduit) tailleReduite else tailleNormale }

        // Pose le rond à (x, y) pixels, en gardant sa taille actuelle (normale ou réduite). Gravité HAUT-GAUCHE : les marges sont des coordonnées.
        fun poser(x: Int, y: Int) {
            val (bx, by) = EnregistreurEcranLogique.positionBornee(x, y, ecranL(), ecranH(), taille(), margeHaut, margeBas)
            icone.layoutParams = FrameLayout.LayoutParams(taille(), taille(), Gravity.TOP or Gravity.START).apply { leftMargin = bx; topMargin = by }
        }
        // Remet le rond à la place mémorisée (fractions) ; à défaut, en bas à gauche, au-dessus de la barre du bas comme avant.
        fun placeMemorisee() {
            if (prefsPos.contains("icone_fx")) {
                val (x, y) = EnregistreurEcranLogique.depuisFractions(prefsPos.getFloat("icone_fx", 0f), prefsPos.getFloat("icone_fy", 1f), ecranL(), ecranH(), taille(), margeHaut, margeBas)
                poser(x, y)
            } else poser((10 * d).toInt(), ecranH() - taille() - (140 * d).toInt())
        }
        fun memoriserPlace() {
            val lp = icone.layoutParams as FrameLayout.LayoutParams
            val (fx, fy) = EnregistreurEcranLogique.versFractions(lp.leftMargin, lp.topMargin, ecranL(), ecranH(), taille(), margeHaut, margeBas)
            prefsPos.edit().putFloat("icone_fx", fx).putFloat("icone_fy", fy).apply()
        }
        // Aspect normal ou réduit (point de 14 dp, très transparent, texte caché). Le rond reste à sa place : seul son centre est conservé.
        fun appliquerTaille() {
            val lp = icone.layoutParams as FrameLayout.LayoutParams
            val centreX = lp.leftMargin + lp.width / 2; val centreY = lp.topMargin + lp.height / 2
            icone.textSize = if (reduit) 0f else 22f
            icone.alpha = if (reduit) 0.35f else 0.92f
            icone.contentDescription = if (reduit) "Bouton d'enregistrement masqué : appui long pour le réafficher" else "Enregistrer l'écran pour le débogage"
            poser(centreX - taille() / 2, centreY - taille() / 2)
        }

        // Geste : toucher court = enregistrement ; glisser = déplacer ; appui long immobile = masquer / réafficher.
        val seuilGlisse = android.view.ViewConfiguration.get(this).scaledTouchSlop
        val delaiLong = android.view.ViewConfiguration.getLongPressTimeout().toLong()
        val fil = android.os.Handler(android.os.Looper.getMainLooper())
        var departX = 0f; var departY = 0f; var departGauche = 0; var departHaut = 0
        var glisse = false; var appuiLong = false
        val surAppuiLong = Runnable {
            if (EnregistreurEcran.enCours) return@Runnable                  // jamais masqué pendant un enregistrement
            appuiLong = true; reduit = !reduit
            prefsPos.edit().putBoolean("icone_reduite", reduit).apply()
            appliquerTaille(); memoriserPlace()
            icone.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            Toast.makeText(this, if (reduit) "Bouton d'enregistrement masqué (appui long pour le réafficher)" else "Bouton d'enregistrement réaffiché", Toast.LENGTH_SHORT).show()
        }
        icone.setOnTouchListener { v, e ->
            val lp = v.layoutParams as FrameLayout.LayoutParams
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    departX = e.rawX; departY = e.rawY; departGauche = lp.leftMargin; departHaut = lp.topMargin
                    glisse = false; appuiLong = false
                    fil.postDelayed(surAppuiLong, delaiLong); true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - departX; val dy = e.rawY - departY
                    if (!glisse && !appuiLong && (kotlin.math.abs(dx) > seuilGlisse || kotlin.math.abs(dy) > seuilGlisse)) { glisse = true; fil.removeCallbacks(surAppuiLong) }
                    if (glisse) poser(departGauche + dx.toInt(), departHaut + dy.toInt())
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    fil.removeCallbacks(surAppuiLong)
                    if (glisse) memoriserPlace()                            // fin du déplacement : la nouvelle place est retenue
                    else if (!appuiLong) { if (reduit) { reduit = false; prefsPos.edit().putBoolean("icone_reduite", false).apply(); appliquerTaille(); memoriserPlace() } else basculerEnregistrement() }
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> { fil.removeCallbacks(surAppuiLong); if (glisse) memoriserPlace(); true }
                else -> false
            }
        }
        addContentView(icone, FrameLayout.LayoutParams(taille(), taille(), Gravity.TOP or Gravity.START))
        placeMemorisee()                                                    // à la place retenue (ou, la première fois, en bas à gauche au-dessus de la barre du bas)
        if (reduit) appliquerTaille()
        // Aspect : rouge et clignotant pendant l'enregistrement ; discret (rond bleu nuit) sinon. Toujours un FOND PLEIN : jamais un « ● » nu.
        // Redessiné aussi à chaque retour au premier plan (onResume), selon l'état RÉEL de l'enregistrement.
        habillerIcone = { actif ->
            clignotement?.cancel()
            icone.visibility = android.view.View.VISIBLE
            icone.background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(if (actif) android.graphics.Color.parseColor("#DC2626") else discret) }
            if (actif) {
                if (reduit) { reduit = false; prefsPos.edit().putBoolean("icone_reduite", false).apply(); appliquerTaille() }   // un enregistrement commence : le rond reprend sa taille pour pouvoir l'arrêter
                clignotement = ObjectAnimator.ofFloat(icone, "alpha", 1f, 0.45f).apply { duration = 900; repeatCount = ObjectAnimator.INFINITE; repeatMode = ObjectAnimator.REVERSE; start() }
                icone.contentDescription = "Arrêter l'enregistrement d'écran et envoyer la vidéo"
            } else {
                icone.alpha = if (reduit) 0.35f else 0.92f; icone.contentDescription = if (reduit) "Bouton d'enregistrement masqué : appui long pour le réafficher" else "Enregistrer l'écran pour le débogage"
            }
        }
        habillerIcone?.invoke(EnregistreurEcran.enCours)
        // À la fin d'un enregistrement, la feuille de partage s'ouvre toute seule
        EnregistreurEcran.auChangement = { actif, fichier ->
            habillerIcone?.invoke(actif)
            if (!actif && fichier != null) EnregistreurEcran.partager(this, fichier)
        }
    }

    private var habillerIcone: ((Boolean) -> Unit)? = null

    /** Retour au premier plan : l'icône est redessinée selon l'état RÉEL de l'enregistrement (elle ne peut plus rester sans fond). */
    override fun onResume() { super.onResume(); habillerIcone?.invoke(EnregistreurEcran.enCours) }

    /** Touche de l'icône : arrête l'enregistrement en cours, ou explique puis demande l'accord d'Android pour en commencer un. */
    private fun basculerEnregistrement() {
        if (EnregistreurEcran.enCours) {
            startService(Intent(this, ServiceEnregistrementEcran::class.java).setAction(ServiceEnregistrementEcran.ACTION_ARRETER)); return
        }
        // Lot « direct » : l'explication n'est montrée qu'UNE SEULE FOIS ; ensuite le rond lance DIRECTEMENT l'enregistrement
        // (il ne reste que la fenêtre de consentement d'Android, imposée par le système à chaque enregistrement).
        val prefs = getSharedPreferences("enregistrement_ecran", Context.MODE_PRIVATE)
        val demarrer = {
            val lancer = { demandeEnregistrement.launch((getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager).createScreenCaptureIntent()) }
            if (Build.VERSION.SDK_INT >= 33) { suiteApresNotifications = lancer; demandeNotificationsEnregistrement.launch(android.Manifest.permission.POST_NOTIFICATIONS) } else lancer()
        }
        if (prefs.getBoolean("explication_vue", false)) { demarrer(); return }
        android.app.AlertDialog.Builder(this).setTitle("Enregistrer l'écran")
            .setMessage("Tout ce qui s'affiche à l'écran sera filmé (15 minutes au plus, sans le son), même sur la page de connexion.\n\n" +
                "Ne tapez pas de mot de passe ni de code secret en clair pendant l'enregistrement.\n\nLa vidéo reste sur ce téléphone : vous choisissez à qui l'envoyer à la fin ; elle est effacée au bout de 7 jours.\n\nCette explication ne sera plus affichée : ensuite, toucher le rond lance directement l'enregistrement.")
            .setPositiveButton("Commencer") { _, _ -> prefs.edit().putBoolean("explication_vue", true).apply(); demarrer() }
            .setNegativeButton("Annuler", null).show()
    }


    override fun onDestroy() { EnregistreurEcran.auChangement = null; habillerIcone = null; clignotement?.cancel(); super.onDestroy() }

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
    // 1.71 : SYNCHRONISATION AUTOMATIQUE entre appareils, seulement quand l'application est AU PREMIER PLAN : un tour au démarrage puis toutes les 45 s ; chaque changement reçu est annoncé
    val cycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        cycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            var dernierMessage = ""
            while (true) {
                if (SauvegardeEnLigne.actif(c) && SauvegardeEnLigne.synchroAutoActive(c)) {
                    val r = SauvegardeEnLigne.synchroniser(c)
                    val annoncer = r.recu.isNotEmpty() || (!r.ok && r.message.isNotBlank() && r.message != dernierMessage)       // jamais silencieux : un changement reçu ou un problème nouveau
                    if (annoncer) Toast.makeText(c.applicationContext, r.message, Toast.LENGTH_LONG).show()
                    dernierMessage = if (r.ok) "" else r.message
                }
                kotlinx.coroutines.delay(45_000)
            }
        }
    }
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
                NavigationBarItem(onglet == e, { ouvrir(e) }, { Icon(icone, nom) }, label = { Text(nom, fontSize = 9.sp, maxLines = 1, softWrap = false) })      // légendes du bas plus petites : sur une seule ligne, jamais coupées
            }
        }
    }) { marge ->
        // Bords de l'écran : barre d'état toujours ; barre de navigation du téléphone seulement quand l'onglet du bas n'est pas affiché
        Column(Modifier.fillMaxSize().padding(marge).statusBarsPadding().then(if (pile.isNotEmpty()) Modifier.navigationBarsPadding() else Modifier)) {
            if (pile.isEmpty()) BandeauMiseAJour()      // nouvelle version publiée dans ShuyahStore (v1.57)
            Box(Modifier.fillMaxWidth().weight(1f)) {
            // ÉCRANS SENSIBLES (budget, famille, documents) : empreinte exigée si le dernier déverrouillage date de plus de 2 minutes
            val sensible = courant is Ecran.Budget || courant is Ecran.BudgetStats || courant is Ecran.Famille || courant is Ecran.FamilleLien ||
                courant is Ecran.FicheOperation || courant is Ecran.FormOperation || courant is Ecran.PieceOperation || courant is Ecran.Piece || courant is Ecran.Document
            var deverrouille by remember { mutableIntStateOf(0) }                      // incrémenté après un déverrouillage : relit l'état
            val activite = c as? androidx.fragment.app.FragmentActivity
            val bloque = sensible && deverrouille >= 0 && activite != null && Biometrie.verrouPossible(c) && !VerrouEcrans.recent()
            val demanderDoigt = {
                if (activite != null) Biometrie.verifier(activite, "Écran protégé", "Posez votre doigt pour voir ces informations", { VerrouEcrans.succes(); deverrouille++ },
                    { message -> Toast.makeText(c, message, Toast.LENGTH_SHORT).show() })
            }
            if (bloque) {
                LaunchedEffect(courant) { demanderDoigt() }
                Column(Modifier.fillMaxSize().background(Fond), verticalArrangement = Arrangement.Center, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    Text("🖐️ Écran protégé", fontSize = 20.sp, color = BleuNuit)
                    Button({ demanderDoigt() }, Modifier.padding(top = 12.dp)) { Icon(Icons.Filled.Lock, null); Spacer(Modifier.width(6.dp)); Text("Déverrouiller avec mon empreinte") }
                    if (pile.isNotEmpty()) OutlinedButton({ retour() }, Modifier.padding(top = 8.dp)) { Icon(Icons.Filled.ArrowBack, null); Spacer(Modifier.width(6.dp)); Text("Retour") }
                }
            } else when (courant) {
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
                Ecran.OuSuisJe -> EcranOuSuisJe(::retour)
                Ecran.Sauvegarde -> EcranSauvegarde(::retour)
                Ecran.Agenda -> EcranAgenda(::retour)
                Ecran.Tele -> EcranTele(::retour)
                Ecran.Mesure -> EcranMesure(::retour)
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
}

/** Titre d'écran avec, à droite, des tuiles d'action éventuelles. */
@Composable
fun Titre(texte: String, retour: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}, actualiser: (() -> Unit)? = null) =
    Column(Modifier.fillMaxWidth().background(BleuNuit).padding(horizontal = 12.dp, vertical = 8.dp)) {
        // Ligne 1 : retour, titre (toute la largeur disponible), « Actualiser » (1.67) et actions de l'écran
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            if (retour != null) Icon(Icons.Filled.ArrowBack, "Retour", tint = Color.White, modifier = Modifier.size(28.dp).padding(end = 8.dp).clickable { retour() })
            Text(texte, color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
            if (actualiser != null) Row(Modifier.clip(RoundedCornerShape(50)).clickable { actualiser() }.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Icon(Icons.Filled.Refresh, "Actualiser", tint = Color.White, modifier = Modifier.size(20.dp)); Text(" Actualiser", color = Color.White, fontSize = 13.sp, maxLines = 1)
            }
            Spacer(Modifier.width(4.dp))
            actions()
        }
        // Ligne 2 (1.68) : date, heure et batterie sous le titre, pour ne plus écraser le titre
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            if (SauvegardeEnLigne.etatSynchro.isNotEmpty()) Text(SauvegardeEnLigne.etatSynchro + "  ·  ", color = Color.White.copy(alpha = 0.9f), fontSize = 11.sp, maxLines = 1)
            BarreEtat()
        }
    }

/** Compteur d'actualisation commun : « Actualiser » le fait avancer et chaque écran reconstruit son contenu (relecture des préférences et des données). */
object Actualisation { var version by androidx.compose.runtime.mutableIntStateOf(0) }

/** Conteneur défilant des écrans (marge et espacement communs). */
@Composable
fun Page(titre: String, retour: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}, contenu: @Composable ColumnScope.() -> Unit) = Column(Modifier.fillMaxSize()) {
    val c = androidx.compose.ui.platform.LocalContext.current
    val portee = androidx.compose.runtime.rememberCoroutineScope()
    var enCours by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    // 1.67 : « Actualiser » dans le bandeau noir de TOUS les écrans : « … en cours » tout de suite, puis le résultat (jamais silencieux)
    Titre(titre, retour, actions, actualiser = {
        if (!enCours) { enCours = true; Toast.makeText(c.applicationContext, "Actualisation en cours…", Toast.LENGTH_SHORT).show()
            portee.launch { val m = try { SauvegardeEnLigne.actualiser(c) } catch (e: Exception) { "Écran actualisé." }; Actualisation.version++; enCours = false; Toast.makeText(c.applicationContext, m, Toast.LENGTH_LONG).show() } }
    })
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        androidx.compose.runtime.key(Actualisation.version) { contenu() }
        Text(Version.libelle(), fontSize = 11.sp, color = Gris, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
    }
}
