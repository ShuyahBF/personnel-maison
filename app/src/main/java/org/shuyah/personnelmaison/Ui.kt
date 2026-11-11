package org.shuyah.personnelmaison

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

// Couleurs de l'application (mêmes que les autres applications du propriétaire)
val Bleu = Color(0xFF0E6BA8)
val BleuNuit = Color(0xFF0B1F3A)
val Vert = Color(0xFF2E9E6B)
val Rouge = Color(0xFFD64545)
val Orange = Color(0xFFF6A35B)
val Gris = Color(0xFF475569)
val Fond = Color(0xFFF1F5F9)

/** Action = pictogramme rond + légende courte (règle 14 : jamais de boutons à texte de longueurs inégales). */
@Composable
fun Tuile(icone: ImageVector, legende: String, couleur: Color = Orange, actif: Boolean = true, surAppui: () -> Unit) =
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp).clickable(enabled = actif) { surAppui() }) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(if (actif) couleur else Color(0xFFCBD5E1)), contentAlignment = Alignment.Center) {
            Icon(icone, legende, tint = if (couleur == Orange) BleuNuit else Color.White, modifier = Modifier.size(28.dp))
        }
        Text(legende, fontSize = 11.sp, color = BleuNuit, maxLines = 2, lineHeight = 13.sp, modifier = Modifier.padding(top = 4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }

/** Rangée de tuiles qui passe à la ligne quand la largeur est épuisée. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RangeeTuiles(contenu: @Composable () -> Unit) = FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { contenu() }

/** Carte blanche avec un titre et son pictogramme. */
@Composable
fun Carte(titre: String? = null, icone: ImageVector? = null, modifier: Modifier = Modifier, contenu: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).padding(14.dp)) {
        if (titre != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            if (icone != null) Icon(icone, null, tint = Bleu, modifier = Modifier.size(22.dp))
            Text(titre, color = BleuNuit, fontSize = 16.sp, modifier = Modifier.padding(start = if (icone != null) 8.dp else 0.dp))
        }
        contenu()
    }
}

/** Ligne « libellé : valeur ». */
@Composable
fun Ligne(libelle: String, valeur: String) = Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
    Text(libelle, color = Gris, fontSize = 13.sp, modifier = Modifier.width(120.dp))
    Text(valeur.ifBlank { "—" }, color = BleuNuit, fontSize = 14.sp, modifier = Modifier.weight(1f))
}

/** Champ de saisie. */
@Composable
fun Champ(libelle: String, valeur: String, surChangement: (String) -> Unit, clavier: KeyboardType = KeyboardType.Text, lignes: Int = 1, modifier: Modifier = Modifier) =
    OutlinedTextField(valeur, surChangement, label = { Text(libelle) }, modifier = modifier.fillMaxWidth().padding(vertical = 3.dp),
        keyboardOptions = KeyboardOptions(keyboardType = clavier), minLines = lignes, maxLines = if (lignes == 1) 1 else 8, singleLine = lignes == 1)

/**
 * Liste déroulante (règle 10 : un ensemble fini de valeurs = liste déroulante). `valeurLibre` ajoute « Autre… » qui ouvre une saisie libre ;
 * une valeur déjà enregistrée mais absente de la liste reste affichée et sélectionnable.
 */
@Composable
fun Liste(libelle: String, valeur: String, options: List<String>, valeurLibre: Boolean = false, surChoix: (String) -> Unit) {
    var ouvert by remember { mutableStateOf(false) }
    var saisieLibre by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        OutlinedTextField(valeur, {}, readOnly = true, label = { Text(libelle) }, modifier = Modifier.fillMaxWidth(),
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) })
        Box(Modifier.matchParentSize().clickable { ouvert = true })
        DropdownMenu(ouvert, { ouvert = false }) {
            val tout = if (valeur.isNotBlank() && valeur !in options) listOf(valeur) + options else options
            tout.forEach { o -> DropdownMenuItem({ Text(o) }, { surChoix(o); ouvert = false }) }
            if (valeurLibre) DropdownMenuItem({ Text("Autre…") }, { ouvert = false; saisieLibre = true })
        }
    }
    if (saisieLibre) {
        var texte by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { saisieLibre = false }, title = { Text(libelle) },
            text = { Champ("Précisez", texte, { texte = it }) },
            confirmButton = { Tuile(Icons.Filled.Check, "Valider", Orange) { if (texte.isNotBlank()) surChoix(texte.trim()); saisieLibre = false } },
            dismissButton = { Tuile(Icons.Filled.Close, "Annuler", Orange) { saisieLibre = false } })
    }
}

/** Étoiles de 1 à 5 ; `surChoix` null = affichage seul. */
@Composable
fun Etoiles(note: Int, taille: Int = 22, surChoix: ((Int) -> Unit)? = null) = Row {
    for (i in 1..5) Icon(if (i <= note) Icons.Filled.Star else Icons.Filled.StarBorder, "$i", tint = Orange,
        modifier = Modifier.size(taille.dp).let { if (surChoix != null) it.clickable { surChoix(i) } else it })
}

/** Sélecteur d'heure du système (24 h) ; `initial` en minutes depuis minuit. */
fun choisirHeure(c: Context, titre: String, initial: Int, surChoix: (Int) -> Unit) =
    TimePickerDialog(c, { _, h, m -> surChoix(h * 60 + m) }, initial / 60, initial % 60, true).apply { setTitle(titre) }.show()

/** Sélecteur de date du système. */
fun choisirDate(c: Context, initial: LocalDate, surChoix: (LocalDate) -> Unit) =
    DatePickerDialog(c, { _, a, m, j -> surChoix(LocalDate.of(a, m + 1, j)) }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()

/** Champ « date » : affiche JJ/MM/AAAA, un appui ouvre le sélecteur. `valeur` au format ISO ou null. */
@Composable
fun ChampDate(libelle: String, valeur: String?, surChoix: (LocalDate) -> Unit, surEffacement: (() -> Unit)? = null) {
    val c = androidx.compose.ui.platform.LocalContext.current
    Box(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        OutlinedTextField(if (valeur.isNullOrBlank()) "" else Calculs.dateFr(valeur), {}, readOnly = true, label = { Text(libelle) }, modifier = Modifier.fillMaxWidth(),
            trailingIcon = { if (surEffacement != null && !valeur.isNullOrBlank()) Icon(Icons.Filled.Close, "Effacer", modifier = Modifier.clickable { surEffacement() }) else Icon(Icons.Filled.CalendarMonth, null) })
        Box(Modifier.matchParentSize().padding(end = 48.dp).clickable { choisirDate(c, try { LocalDate.parse(valeur) } catch (e: Exception) { LocalDate.now() }, surChoix) })
    }
}

/** Champ « heure ». */
@Composable
fun ChampHeure(libelle: String, minutes: Int, surChoix: (Int) -> Unit) {
    val c = androidx.compose.ui.platform.LocalContext.current
    Box(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        OutlinedTextField(Calculs.heure(minutes), {}, readOnly = true, label = { Text(libelle) }, modifier = Modifier.fillMaxWidth(), trailingIcon = { Icon(Icons.Filled.Schedule, null) })
        Box(Modifier.matchParentSize().clickable { choisirHeure(c, libelle, minutes, surChoix) })
    }
}

/** Confirmation avant une suppression. */
@Composable
fun Confirmation(titre: String, message: String, surConfirmation: () -> Unit, surAnnulation: () -> Unit) =
    AlertDialog(onDismissRequest = surAnnulation, title = { Text(titre) }, text = { Text(message) },
        confirmButton = { Tuile(Icons.Filled.Delete, "Supprimer", Rouge) { surConfirmation() } },
        dismissButton = { Tuile(Icons.Filled.Close, "Annuler", Orange) { surAnnulation() } })

/** Fonctions proposées dans la liste déroulante (plus « Autre… »). */
val FONCTIONS = listOf("Aide ménagère", "Gardien", "Laveur de véhicule", "Cuisinier(ère)", "Chauffeur", "Jardinier", "Nounou", "Lingère")
