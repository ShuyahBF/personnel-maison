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

    // Les liens sont relus toutes les 10 secondes tant que l'écran est ouvert (un proche peut accepter une invitation ou vérifier le lien)
    LaunchedEffect(inscrit) {
        while (inscrit) {
            try { liens = ClientFamille.liens(c) } catch (e: ClientFamille.Erreur) { message = e.message.orEmpty() }
            delay(10_000)
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

    Carte("Inviter un proche", Icons.Filled.PersonAdd) {
        Text("Un code à usage unique, valable 15 minutes. Donnez-le à votre proche (de vive voix ou par message) : il le saisit dans son application.", fontSize = 13.sp, color = Gris)
        if (codeInvitation != null) {
            Text(codeInvitation!!, fontSize = 32.sp, fontFamily = FontFamily.Monospace, color = BleuNuit, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), textAlign = TextAlign.Center)
            GrosBouton(Icons.Filled.Share, "Partager le code", Bleu) {
                c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "Rejoignez ma famille sur Personnel de maison : saisissez le code ${codeInvitation} (valable 15 minutes)."), "Partager le code").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } else GrosBouton(Icons.Filled.QrCode, "Créer un code", Bleu, actif = !occupe) {
            occupe = true; message = ""
            scope.launch { try { codeInvitation = ClientFamille.inviter(c) } catch (e: ClientFamille.Erreur) { message = e.message.orEmpty() }; occupe = false }
        }
    }

    Carte("J'ai reçu un code", Icons.Filled.Key) {
        Champ("Code (ex. ABCD-EFGH)", code, { code = it.uppercase() })
        GrosBouton(Icons.Filled.Link, "Me lier à cette personne", Vert, actif = code.filter { it.isLetterOrDigit() }.length == 8 && !occupe) {
            occupe = true; message = ""
            scope.launch {
                try { val id = ClientFamille.accepter(c, code); code = ""; liens = ClientFamille.liens(c); ouvrir(Ecran.FamilleLien(id)) } catch (e: ClientFamille.Erreur) { message = e.message.orEmpty() }
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
    if (erreur.isNotBlank()) Text(erreur, color = Rouge, fontSize = 13.sp)
    GrosBouton(Icons.Filled.LinkOff, "Rompre le lien", Rouge) { rupture = true }
    if (rupture) Confirmation("Rompre le lien", "Le lien avec ${l.nom} sera supprimé et les messages échangés seront effacés. Continuer ?", {
        rupture = false
        scope.launch { try { ClientFamille.rompre(c, l.id); retour() } catch (e: ClientFamille.Erreur) { erreur = e.message.orEmpty() } }
    }, { rupture = false })
}
