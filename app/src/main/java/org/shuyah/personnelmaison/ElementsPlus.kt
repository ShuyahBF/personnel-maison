package org.shuyah.personnelmaison

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Titre de rubrique de la page « Plus » (texte bleu nuit, petit, en majuscules d'initiale). */
@Composable
fun Rubrique(titre: String) =
    Text(titre, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Bleu, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp, start = 4.dp))

/**
 * Une option de la page « Plus » : ligne blanche à bord fin, PICTOGRAMME AU TRACÉ BLEU dans un rond au contour bleu,
 * titre, explication courte et flèche. Toute la ligne se touche. [etat] = petit texte à droite (ex. « … » pendant une vérification).
 */
@Composable
fun LigneOption(icone: ImageVector, titre: String, explication: String, etat: String? = null, surAppui: () -> Unit) =
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White).border(BorderStroke(1.dp, Color(0xFFD6E4F0)), RoundedCornerShape(14.dp))
        .clickable { surAppui() }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).clip(CircleShape).border(BorderStroke(1.5.dp, Bleu), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icone, null, tint = Bleu, modifier = Modifier.size(26.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(titre, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = BleuNuit)
            Text(explication, fontSize = 13.sp, color = Gris, lineHeight = 16.sp)
        }
        if (etat != null) Text(etat, fontSize = 14.sp, color = Bleu, modifier = Modifier.padding(end = 4.dp))
        Icon(Icons.Filled.ChevronRight, null, tint = Bleu)
    }

/**
 * BANDEAU « NOUVELLE VERSION DISPONIBLE » (v1.57), affiché en haut des onglets quand ShuyahStore publie une version plus récente.
 * « Mettre à jour » ouvre ShuyahStore ; la croix = « Plus tard » (la version est mémorisée : le bandeau ne revient que pour une version encore plus récente).
 * Compact et clair (pas de bloc noir qui masque l'écran).
 */
@Composable
fun BandeauMiseAJour() {
    val c = LocalContext.current
    var nouvelle by remember { mutableStateOf<MiseAJourStore.Nouvelle?>(null) }
    LaunchedEffect(Unit) { nouvelle = MiseAJourStore.verifier(c) }          // réseau lu en arrière-plan ; rien ne s'affiche si hors connexion
    val n = nouvelle ?: return
    Row(Modifier.fillMaxWidth().background(Color(0xFFE8F3FB)).border(BorderStroke(1.dp, Bleu)).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.SystemUpdate, null, tint = Bleu, modifier = Modifier.size(26.dp))
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text("Nouvelle version ${n.nom} disponible", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = BleuNuit)
            Text("Vous avez la ${Version.VERSION}.", fontSize = 12.sp, color = Gris)
        }
        Button({ MiseAJourStore.ouvrirStore(c) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            Icon(Icons.Outlined.SystemUpdate, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Mettre à jour", fontSize = 13.sp)
        }
        IconButton({ MiseAJourStore.ignorer(c, n.code); nouvelle = null }) { Icon(Icons.Filled.Close, "Plus tard", tint = Gris) }
    }
}
