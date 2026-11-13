package org.shuyah.personnelmaison

import android.media.RingtoneManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Logique pure du chat (testée par ChatLogiqueTest). */
object ChatLogique {
    /**
     * Nombre de messages ARRIVÉS (envoyés par l'autre personne) depuis la dernière observation : `dernierVu` = plus grand numéro déjà observé pour ce proche (null = première lecture
     * depuis l'ouverture de l'application : référence posée sans alerte, pour ne pas sonner à chaque démarrage pour de vieux messages).
     */
    fun arrivees(dernierVu: Long?, recus: List<Pair<Long, Boolean>>): Int = if (dernierVu == null) 0 else recus.count { (seq, deMoi) -> !deMoi && seq > dernierVu }
    fun plusGrand(dernierVu: Long?, recus: List<Pair<Long, Boolean>>): Long? = (recus.maxOfOrNull { it.first } ?: dernierVu)?.let { maxOf(it, dernierVu ?: it) }
}

/**
 * MESSAGERIE DES PROCHES EN PERMANENCE (mêmes spécifications que le support SAWALI d'aurOR) :
 *  - une PASTILLE RONDE en bas à gauche, sur tous les écrans, visible SEULEMENT quand le téléphone est connecté au serveur et qu'au moins un proche est lié et vérifié ;
 *  - une PASTILLE ROUGE avec le nombre de messages non lus ;
 *  - un appui ouvre un PANNEAU de discussion (60 % de l'écran) ; la FLÈCHE du haut le replie en pastille SANS PERDRE la discussion ; la CROIX le ferme et VIDE l'historique local
 *    (la conversation reste chiffrée sur le serveur et se recharge à la réouverture) ;
 *  - messages chiffrés de bout en bout ; rappel de ne jamais écrire de mot de passe.
 *
 * Pour un développeur WinDev : c'est une « fenêtre flottante » posée par-dessus toutes les pages. Elle relit le serveur toutes les 8 secondes (3 secondes quand le panneau est ouvert).
 */
@Composable
fun ChatPermanent(modifier: Modifier = Modifier) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var liens by remember { mutableStateOf<List<ClientFamille.Lien>>(emptyList()) }
    var enLigne by remember { mutableStateOf(false) }                                  // dernier échange avec le serveur réussi
    var ouvert by remember { mutableStateOf(false) }                                   // panneau affiché
    var choisi by remember { mutableStateOf<String?>(null) }                          // proche dont on lit la discussion
    val discussions = remember { mutableStateMapOf<String, List<ClientFamille.Message>>() }   // historique LOCAL par proche (vidé par la croix)
    val nonLus = remember { mutableStateMapOf<String, Int>() }
    var saisie by remember { mutableStateOf("") }
    var erreur by remember { mutableStateOf("") }
    val dernierVu = remember { mutableMapOf<String, Long>() }                          // plus grand numéro de message observé par proche (détecte les arrivées)
    var son by remember { mutableStateOf(ReglagesFamille.sonChat(c)) }
    var flash by remember { mutableIntStateOf(0) }                                    // incrémenté à chaque arrivée : fait clignoter l'en-tête du panneau ouvert

    /** Un message vient d'arriver : bruit de notification (si activé) et clignotement. */
    fun arrivee() {
        flash++
        if (ReglagesFamille.sonChat(c)) try { RingtoneManager.getRingtone(c, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play() } catch (e: Exception) { /* sonnerie indisponible : le clignotement suffit */ }
    }

    /** Relit le serveur : liens, messages non lus de chaque proche, et la discussion ouverte (incrémental : seulement ce qui est nouveau). */
    suspend fun relire() {
        if (!ClientFamille.inscrit(c)) { liens = emptyList(); enLigne = false; return }
        try {
            liens = ClientFamille.liens(c).filter { it.verifie }
            enLigne = true; erreur = ""
            for (l in liens) {
                if (ouvert && l.id == choisi) {
                    val dejaLus = discussions[l.id].orEmpty()
                    val nouveaux = ClientFamille.messages(c, l, dejaLus.lastOrNull()?.seq ?: 0L)
                    if (l.id in discussions && ChatLogique.arrivees(dernierVu[l.id] ?: dejaLus.lastOrNull()?.seq, nouveaux.map { it.seq to it.deMoi }) > 0) arrivee()
                    if (nouveaux.isNotEmpty() || l.id !in discussions) discussions[l.id] = dejaLus + nouveaux
                    discussions[l.id]?.lastOrNull()?.let { dernierVu[l.id] = it.seq }
                    discussions[l.id]?.lastOrNull()?.let { dernier -> ReglagesFamille.curseur(c, "chat", l.id, dernier.seq); ReglagesFamille.curseur(c, "msg", l.id, dernier.seq) }   // lu : plus de pastille ni de notification
                    nonLus[l.id] = 0
                } else {
                    val recus = ClientFamille.messages(c, l, ReglagesFamille.curseur(c, "chat", l.id))
                    nonLus[l.id] = recus.count { !it.deMoi }
                    val paires = recus.map { it.seq to it.deMoi }
                    // Première lecture : on pose la référence sans sonner ; ensuite, tout message de l'autre plus récent déclenche le bruit et le clignotement
                    if (ChatLogique.arrivees(dernierVu[l.id], paires) > 0) arrivee()
                    ChatLogique.plusGrand(dernierVu[l.id], paires)?.let { dernierVu[l.id] = it } ?: run { if (l.id !in dernierVu) dernierVu[l.id] = ReglagesFamille.curseur(c, "chat", l.id) }
                }
            }
            if (choisi == null || liens.none { it.id == choisi }) choisi = liens.firstOrNull()?.id
        } catch (e: ClientFamille.Erreur) { enLigne = false; erreur = e.message.orEmpty() }
    }
    LaunchedEffect(ouvert, choisi) { while (true) { relire(); delay(if (ouvert) 3_000 else 8_000) } }

    val total = nonLus.values.sum()
    val proche = liens.firstOrNull { it.id == choisi }
    // Sans compte famille, ou sans proche vérifié encore connu : rien à afficher. Compte existant mais serveur injoignable : pastille grise « Hors ligne » (la messagerie reste visible en permanence)
    if (!ClientFamille.inscrit(c) || (liens.isEmpty() && enLigne)) return
    if (liens.isEmpty() && !enLigne) return
    if (!enLigne && !ouvert) {
        Row(modifier.padding(start = 12.dp, bottom = 12.dp).clip(RoundedCornerShape(26.dp)).background(Color(0xFF94A3B8)).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CloudOff, null, tint = Color.White, modifier = Modifier.size(22.dp)); Text("  Messages · hors ligne", color = Color.White, fontSize = 13.sp)
        }
        return
    }

    if (!ouvert) {
        // Pilule « Messages » : clignote (opacité et taille qui pulsent) tant qu'il y a des messages non lus
        val pulsation = rememberInfiniteTransition(label = "chat")
        val alpha by pulsation.animateFloat(1f, 0.45f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "alpha")
        val taille by pulsation.animateFloat(1f, 1.12f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "taille")
        val clignote = total > 0
        Box(modifier.padding(start = 12.dp, bottom = 12.dp)) {
            Row(Modifier.scale(if (clignote) taille else 1f).alpha(if (clignote) alpha else 1f).clip(RoundedCornerShape(26.dp)).background(if (clignote) Color(0xFFD64545) else Bleu).clickable { ouvert = true }
                .padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Chat, "Messages des proches", tint = Color.White, modifier = Modifier.size(26.dp))
                Text(if (clignote) "  $total nouveau" + (if (total > 1) "x" else "") + " message" + (if (total > 1) "s" else "") else "  Messages", color = Color.White, fontSize = 14.sp)
            }
        }
        return
    }

    // Panneau de discussion : 60 % de l'écran
    // Hauteur du panneau : 60 % de la place disponible, sans jamais descendre sous 300 dp (ni dépasser la place disponible). Quand le clavier monte, la fenêtre se redimensionne déjà (adjustResize) :
    // pas de marge « clavier » supplémentaire, sinon la zone de saisie était écrasée et le texte tapé invisible.
    BoxWithConstraints(modifier.fillMaxWidth()) {
    val hauteurPanneau = (maxHeight * 0.6f).coerceAtLeast(300.dp).coerceAtMost(maxHeight)
    Surface(Modifier.fillMaxWidth().height(hauteurPanneau), shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp), shadowElevation = 12.dp, color = Color.White) {
        Column(Modifier.fillMaxSize()) {
            // L'en-tête clignote en orange un court instant à l'arrivée d'un message
            var eclair by remember { mutableStateOf(false) }
            LaunchedEffect(flash) { if (flash > 0) { repeat(3) { eclair = true; delay(250); eclair = false; delay(250) } } }
            Row(Modifier.fillMaxWidth().background(if (eclair) Orange else BleuNuit).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Chat, null, tint = Color.White, modifier = Modifier.size(22.dp))
                Text("  " + (proche?.nom ?: "Messages"), Modifier.weight(1f), color = Color.White, fontSize = 17.sp)
                Icon(if (son) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff, if (son) "Couper le son" else "Activer le son", tint = Color.White, modifier = Modifier.padding(end = 12.dp).size(26.dp).clickable { son = !son; ReglagesFamille.sonChat(c, son) })
                Icon(Icons.Filled.KeyboardArrowDown, "Replier", tint = Color.White, modifier = Modifier.size(30.dp).clickable { ouvert = false })          // replie sans perdre la discussion
                Icon(Icons.Filled.Close, "Fermer", tint = Color.White, modifier = Modifier.padding(start = 12.dp).size(26.dp).clickable { ouvert = false; discussions.clear(); nonLus.clear(); choisi = null; saisie = "" })   // ferme et vide l'historique local
            }
            if (liens.size > 1) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                liens.forEach { l -> FilterChip(selected = l.id == choisi, onClick = { choisi = l.id }, label = { Text(l.nom + (nonLus[l.id]?.takeIf { it > 0 }?.let { " ($it)" } ?: "")) }) }
            }
            val defilement = rememberScrollState()
            val messages = discussions[choisi].orEmpty()
            LaunchedEffect(messages.size, choisi) { defilement.animateScrollTo(defilement.maxValue) }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(defilement).padding(horizontal = 12.dp, vertical = 6.dp)) {
                if (messages.isEmpty()) Text("Aucun message. Écrivez le premier.", fontSize = 14.sp, color = Gris, modifier = Modifier.padding(top = 8.dp))
                messages.forEach { m ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.deMoi) Alignment.CenterEnd else Alignment.CenterStart) {
                        Text(m.texte ?: "(message illisible)", fontSize = 15.sp, color = if (m.deMoi) Color.White else BleuNuit,
                            modifier = Modifier.padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(if (m.deMoi) Bleu else Fond).padding(horizontal = 12.dp, vertical = 7.dp))
                    }
                }
            }
            if (erreur.isNotBlank()) Text(erreur, color = Rouge, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp))
            Text("Messages chiffrés de bout en bout. N'écrivez jamais de mot de passe.", fontSize = 10.sp, color = Gris, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(saisie, { saisie = it }, Modifier.weight(1f).heightIn(min = 56.dp), placeholder = { Text("Votre message") }, maxLines = 3,
                    textStyle = androidx.compose.ui.text.TextStyle(color = BleuNuit, fontSize = 16.sp))
                Icon(Icons.Filled.Send, "Envoyer", tint = if (saisie.isNotBlank() && proche != null) Bleu else Color(0xFFCBD5E1), modifier = Modifier.padding(start = 8.dp).size(32.dp).clickable(enabled = saisie.isNotBlank() && proche != null) {
                    val texte = saisie; saisie = ""
                    scope.launch { try { ClientFamille.envoyer(c, proche!!, texte); relire() } catch (e: ClientFamille.Erreur) { erreur = e.message.orEmpty(); saisie = texte } }
                })
            }
        }
    }
    }
}
