package org.shuyah.personnelmaison

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// =====================================================================================================================
// « MA FAMILLE » : comptes, liens vérifiés entre téléphones, messagerie chiffrée de bout en bout.
// Les liens ne sont utilisables qu'après que les DEUX personnes ont comparé le numéro de sécurité (vérification de l'authenticité du lien).
// =====================================================================================================================

/** Écran « Ma famille » : création du compte, liste des proches liés, invitation, saisie d'un code reçu. */
@Composable
fun EcranFamille(ouvrir: (Ecran) -> Unit, retour: () -> Unit) = Page("Ma famille", retour) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var inscrit by remember { mutableStateOf(ClientFamille.inscrit(c)) }
    var nom by remember { mutableStateOf("") }
    var liens by remember { mutableStateOf<List<ClientFamille.Lien>>(emptyList()) }
    var message by remember { mutableStateOf("") }
    var occupe by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var codeInvitation by remember { mutableStateOf<String?>(null) }
    var codeCreeLe by remember { mutableLongStateOf(0L) }                       // instant de création du code affiché (il vaut 15 minutes)
    var maintenant by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var connus by remember { mutableStateOf<Set<String>?>(null) }                // liens déjà vus : un lien qui apparaît ensuite vient d'être créé par un proche
    LaunchedEffect(Unit) { while (true) { maintenant = System.currentTimeMillis(); delay(1_000) } }
    // Un code affiché n'est valable que 15 minutes et une seule fois : passé ce délai, on cesse de l'afficher (un code périmé induit en erreur)
    if (codeInvitation != null && maintenant - codeCreeLe > 15 * 60_000L) { codeInvitation = null; message = "Le code a expiré : créez-en un nouveau." }

    // Les liens sont relus toutes les 10 secondes tant que l'écran est ouvert (un proche peut accepter une invitation ou vérifier le lien)
    LaunchedEffect(inscrit) {
        while (inscrit) {
            try {
                liens = ClientFamille.liens(c)
                val ids = liens.map { it.id }.toSet()
                val avant = connus
                connus = ids
                if (avant != null) liens.firstOrNull { it.id !in avant && !it.verifieParMoi }?.let { nouveau ->
                    // Un proche vient d'accepter mon invitation : le code est utilisé, et il faut maintenant VÉRIFIER le lien (comparer le numéro de sécurité)
                    codeInvitation = null; message = "${nouveau.nom} a accepté votre invitation : vérifiez le lien."
                    ouvrir(Ecran.FamilleLien(nouveau.id))
                }
                // Contact NFC : l'autre téléphone nous a transmis l'empreinte de sa clé ; si elle concorde avec la clé du lien, nous confirmons la vérification
                FamilleNfc.Etat.empreinteRecue?.let { recue ->
                    liens.firstOrNull { !it.verifieParMoi && FamilleCrypto.empreinte(it.clePublique) == recue }?.let { l ->
                        ClientFamille.verifier(c, l.id); FamilleNfc.Etat.empreinteRecue = null; FamilleNfc.Etat.arreter(); message = "Lien avec ${l.nom} vérifié par NFC."; liens = ClientFamille.liens(c)
                    }
                }
            } catch (e: ClientFamille.Erreur) { message = e.message.orEmpty() }
            delay(if (FamilleNfc.Etat.active()) 2_000 else 10_000)
        }
    }

    if (!inscrit) {
        Carte("Créer mon compte famille", Icons.Filled.FamilyRestroom) {
            Text("« Ma famille » relie votre téléphone à ceux de vos proches pour échanger des messages chiffrés. Rien de ce qui est dans l'application (personnel, portefeuille, profil) n'est envoyé : le serveur ne reçoit que le nom que vous choisissez ci-dessous, une clé publique et des messages chiffrés qu'il ne peut pas lire.", fontSize = 14.sp, color = Gris)
            Champ("Votre nom (vu par vos proches)", nom, { nom = it })
            GrosBouton(Icons.Filled.CheckCircle, if (occupe) "Création…" else "Créer mon compte", Vert, actif = nom.trim().length >= 2 && !occupe) {
                occupe = true; message = ""
                scope.launch {
                    try { ClientFamille.inscrire(c, nom.trim()); inscrit = true } catch (e: ClientFamille.Erreur) { message = e.message.orEmpty() }
                    occupe = false
                }
            }
            if (message.isNotBlank()) Text(message, color = Rouge, fontSize = 13.sp)
        }
        return@Page
    }

    Text("Compte : ${ClientFamille.nom(c)}", fontSize = 13.sp, color = Gris)
    // Liens à vérifier : bandeau d'action impossible à manquer (les deux personnes doivent comparer le numéro de sécurité et confirmer)
    liens.filter { !it.verifie }.forEach { l ->
        Carte(if (l.verifieParMoi) "En attente de ${l.nom}" else "À vérifier : ${l.nom}", Icons.Filled.GppMaybe) {
            if (l.verifieParMoi) Text("Vous avez confirmé. ${l.nom} doit maintenant comparer et confirmer de son côté.", fontSize = 14.sp, color = Gris)
            else {
                Text("Pour finir de lier ${l.nom} : ouvrez le lien, comparez le NUMÉRO DE SÉCURITÉ (30 chiffres) avec celui affiché sur son téléphone, puis appuyez sur « Les numéros sont identiques ». Faites-le sur les deux téléphones.", fontSize = 14.sp, color = Gris)
                GrosBouton(Icons.Filled.VerifiedUser, "Vérifier le lien avec ${l.nom}", Orange) { ouvrir(Ecran.FamilleLien(l.id)) }
            }
        }
    }
    Carte("Mes proches", Icons.Filled.Groups) {
        if (liens.isEmpty()) Text("Aucun proche lié pour le moment. Invitez quelqu'un ou saisissez le code qu'il vous a donné.", fontSize = 14.sp, color = Gris)
        liens.forEach { l ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Fond).clickableLigne { ouvrir(Ecran.FamilleLien(l.id)) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (l.verifie) Icons.Filled.VerifiedUser else Icons.Filled.GppMaybe, null, tint = if (l.verifie) Vert else Orange, modifier = Modifier.size(30.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(l.nom, fontSize = 17.sp, color = BleuNuit)
                    Text(if (l.verifie) "Lien vérifié · messagerie ouverte" else if (l.verifieParMoi) "En attente de la vérification de ${l.nom}" else "À vérifier : comparez le numéro de sécurité", fontSize = 12.sp, color = if (l.verifie) Vert else Gris)
                }
                Icon(Icons.Filled.ChevronRight, null, tint = Gris)
            }
            Spacer(Modifier.height(6.dp))
        }
    }

    CarteNfcFamille { id -> connus = (connus ?: emptySet()) + id; ouvrir(Ecran.FamilleLien(id)) }
    CartePositionFamille()
    CarteCameraFamille()

    Carte("Inviter un proche", Icons.Filled.PersonAdd) {
        Text("Un code à usage unique, valable 15 minutes. Donnez-le à votre proche (de vive voix ou par message) : il le saisit dans son application. Quand il l'aura fait, ce téléphone ouvre tout seul la vérification du lien.", fontSize = 13.sp, color = Gris)
        if (codeInvitation != null) {
            val reste = ((15 * 60_000L - (maintenant - codeCreeLe)) / 1000).coerceAtLeast(0)
            Text("Valable encore %d min %02d s · un seul code à la fois : le dernier créé".format(reste / 60, reste % 60), fontSize = 12.sp, color = Gris, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Text(codeInvitation!!, fontSize = 32.sp, fontFamily = FontFamily.Monospace, color = BleuNuit, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), textAlign = TextAlign.Center)
            GrosBouton(Icons.Filled.Share, "Partager le code", Bleu) {
                c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "Rejoignez ma famille sur Personnel de maison : saisissez le code ${codeInvitation} (valable 15 minutes)."), "Partager le code").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            GrosBouton(Icons.Filled.Refresh, "Nouveau code (annule celui-ci)", Gris, actif = !occupe) {
                occupe = true; message = ""
                scope.launch { try { codeInvitation = ClientFamille.inviter(c); codeCreeLe = System.currentTimeMillis() } catch (e: ClientFamille.Erreur) { message = e.message.orEmpty() }; occupe = false }
            }
        } else GrosBouton(Icons.Filled.QrCode, "Créer un code", Bleu, actif = !occupe) {
            occupe = true; message = ""
            scope.launch { try { codeInvitation = ClientFamille.inviter(c); codeCreeLe = System.currentTimeMillis() } catch (e: ClientFamille.Erreur) { message = e.message.orEmpty() }; occupe = false }
        }
    }

    Carte("J'ai reçu un code", Icons.Filled.Key) {
        Champ("Code (ex. ABCD-EFGH)", code, { code = it.uppercase() })
        GrosBouton(Icons.Filled.Link, "Me lier à cette personne", Vert, actif = code.filter { it.isLetterOrDigit() }.length == 8 && !occupe) {
            occupe = true; message = ""
            scope.launch {
                try { val id = ClientFamille.accepter(c, code); code = ""; liens = ClientFamille.liens(c); connus = liens.map { it.id }.toSet(); ouvrir(Ecran.FamilleLien(id)) }
                catch (e: ClientFamille.Erreur) {
                    // Un code ne sert qu'une fois : après un lien (même supprimé ensuite) il faut en demander un nouveau au proche
                    message = e.message.orEmpty() + if (e.message.orEmpty().contains("inconnu")) " Demandez un NOUVEAU code à votre proche (bouton « Créer un code » ou « Nouveau code » sur son téléphone) : un code ne sert qu'une fois, dure 15 minutes, et le dernier créé annule les précédents." else ""
                }
                occupe = false
            }
        }
    }
    if (message.isNotBlank()) Text(message, color = Rouge, fontSize = 13.sp)
}

/** Une ligne cliquable (évite d'importer `clickable` partout). */
private fun Modifier.clickableLigne(surAppui: () -> Unit) = this.then(Modifier.clickable { surAppui() })

/**
 * Un proche : tant que le lien n'est pas vérifié des deux côtés, le NUMÉRO DE SÉCURITÉ à comparer ; ensuite la MESSAGERIE chiffrée de bout en bout
 * (les messages sont relus toutes les 4 secondes tant que l'écran est ouvert).
 */
@Composable
fun EcranLienFamille(lienId: String, retour: () -> Unit) = Page("Ma famille", retour) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var lien by remember { mutableStateOf<ClientFamille.Lien?>(null) }
    var introuvable by remember { mutableStateOf(false) }
    var messages by remember { mutableStateOf<List<ClientFamille.Message>>(emptyList()) }
    var saisie by remember { mutableStateOf("") }
    var erreur by remember { mutableStateOf("") }
    var rupture by remember { mutableStateOf(false) }

    // Relecture régulière : état du lien (vérification de l'autre personne) puis, si vérifié, les nouveaux messages après le dernier reçu
    LaunchedEffect(lienId) {
        var dernier = 0L
        while (true) {
            try {
                val l = ClientFamille.liens(c).firstOrNull { it.id == lienId }
                if (l == null) { introuvable = true; break }
                lien = l
                if (l.verifie) {
                    val nouveaux = ClientFamille.messages(c, l, dernier)
                    if (nouveaux.isNotEmpty()) { messages = messages + nouveaux; dernier = nouveaux.last().seq }
                }
            } catch (e: ClientFamille.Erreur) { erreur = e.message.orEmpty() }
            delay(4_000)
        }
    }

    val l = lien
    if (introuvable) { Text("Ce lien n'existe plus (il a été rompu).", color = Gris); return@Page }
    if (l == null) { Text(if (erreur.isBlank()) "Chargement…" else erreur, color = Gris); return@Page }

    Carte(l.nom, if (l.verifie) Icons.Filled.VerifiedUser else Icons.Filled.GppMaybe) {
        if (!l.verifie) {
            Text("Vérifier le lien", fontSize = 18.sp, color = BleuNuit)
            Text("Pour être sûr que vous parlez bien à ${l.nom} (et que personne ne s'est glissé entre vous), comparez ce numéro avec celui affiché sur son téléphone, de vive voix ou en face à face.", fontSize = 14.sp, color = Gris)
            Text(l.numeroSecurite, fontSize = 26.sp, fontFamily = FontFamily.Monospace, color = BleuNuit, lineHeight = 36.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), textAlign = TextAlign.Center)
            if (l.verifieParMoi) Text("Vous avez confirmé. En attente de la confirmation de ${l.nom}.", fontSize = 14.sp, color = Vert)
            else GrosBouton(Icons.Filled.CheckCircle, "Les numéros sont identiques", Vert) {
                scope.launch { try { ClientFamille.verifier(c, l.id); lien = ClientFamille.liens(c).firstOrNull { it.id == lienId } } catch (e: ClientFamille.Erreur) { erreur = e.message.orEmpty() } }
            }
            if (l.verifieParLui && !l.verifieParMoi) Text("${l.nom} a déjà confirmé de son côté.", fontSize = 13.sp, color = Gris)
        } else Text("Lien vérifié des deux côtés : les messages sont chiffrés de bout en bout, seuls vos deux téléphones peuvent les lire.", fontSize = 13.sp, color = Vert)
    }

    if (l.verifie) {
        Carte("Messages", Icons.Filled.Chat) {
            if (messages.isEmpty()) Text("Aucun message. Écrivez le premier.", fontSize = 14.sp, color = Gris)
            messages.forEach { m ->
                Box(Modifier.fillMaxWidth(), contentAlignment = if (m.deMoi) Alignment.CenterEnd else Alignment.CenterStart) {
                    Text(m.texte ?: "(message illisible)", fontSize = 16.sp, color = if (m.deMoi) Color.White else BleuNuit,
                        modifier = Modifier.padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(if (m.deMoi) Bleu else Fond).padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
            Champ("Votre message", saisie, { saisie = it }, lignes = 2)
            GrosBouton(Icons.Filled.Send, "Envoyer", Bleu, actif = saisie.isNotBlank()) {
                val texte = saisie; saisie = ""
                scope.launch { try { ClientFamille.envoyer(c, l, texte) } catch (e: ClientFamille.Erreur) { erreur = e.message.orEmpty(); saisie = texte } }
            }
        }
    }
    if (l.verifie) CartePositionProche(l)
    if (l.verifie) CarteMedicamentsProche(l)
    if (l.verifie) CarteRepasProche(l)
    if (l.verifie) CarteCameraProche(l)
    if (erreur.isNotBlank()) Text(erreur, color = Rouge, fontSize = 13.sp)
    GrosBouton(Icons.Filled.LinkOff, "Rompre le lien", Rouge) { rupture = true }
    if (rupture) Confirmation("Rompre le lien", "Le lien avec ${l.nom} sera supprimé et les messages échangés seront effacés. Continuer ?", {
        rupture = false
        scope.launch { try { ClientFamille.rompre(c, l.id); retour() } catch (e: ClientFamille.Erreur) { erreur = e.message.orEmpty() } }
    }, { rupture = false })
}
