package org.shuyah.personnelmaison

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.text.Normalizer

/**
 * COMMANDE VOCALE de la mesure en direct (v1.53, propriétaire 21/11/2026 : « dire pose » pour poser un point).
 * Pour un développeur WinDev : on utilise la reconnaissance vocale intégrée à Android (SpeechRecognizer, service de Google). Elle écoute une phrase, rend du texte,
 * puis on la relance aussitôt pour écouter la suivante. CommandeVocaleLogique (pur, testé) décide quel mot déclenche quelle action.
 */
object CommandeVocaleLogique {
    enum class Commande { POSER, ANNULER, TERMINER }

    /** Minuscules sans accents ni ponctuation : « Posé ! » → « pose ». */
    fun normaliser(texte: String): String =
        Normalizer.normalize(texte.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z ]"), " ").trim()

    /** Commande contenue dans ce que le téléphone a compris ; si plusieurs mots-clés, le DERNIER dit l'emporte ; null si aucun. */
    fun reconnaitre(texte: String): Commande? {
        var trouvee: Commande? = null
        for (mot in normaliser(texte).split(" ").filter { it.isNotBlank() }) {
            trouvee = when (mot) {
                "pose", "poser", "posez" -> Commande.POSER
                "annule", "annuler", "annulez" -> Commande.ANNULER
                "termine", "terminer", "terminez" -> Commande.TERMINER
                else -> trouvee
            }
        }
        return trouvee
    }

    /** Une même commande répétée en moins de 1,5 s est ignorée (la reconnaissance rend souvent le même mot deux fois : résultat partiel puis final). */
    fun doublon(derniere: Commande?, derniereMs: Long, nouvelle: Commande, maintenantMs: Long): Boolean = derniere == nouvelle && maintenantMs - derniereMs < 1500
}

class CommandeVocale(
    private val c: Context,
    private val surCommande: (CommandeVocaleLogique.Commande) -> Unit,
    private val surProbleme: (String) -> Unit,
) {
    private var reconnaisseur: SpeechRecognizer? = null
    private var actif = false
    private val principal = Handler(Looper.getMainLooper())
    private var derniere: CommandeVocaleLogique.Commande? = null
    private var derniereMs = 0L
    private var erreursDeSuite = 0

    /** Démarre l'écoute. Rend false (et dit pourquoi) si le téléphone n'a pas de reconnaissance vocale. */
    fun demarrer(): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(c)) { surProbleme("La reconnaissance vocale n'est pas disponible sur ce téléphone (application Google à installer ou à mettre à jour)."); return false }
        actif = true; erreursDeSuite = 0
        ecouter()
        return true
    }

    /** Arrête l'écoute et libère le micro. */
    fun arreter() {
        actif = false
        principal.removeCallbacksAndMessages(null)
        try { reconnaisseur?.destroy() } catch (e: Exception) { }
        reconnaisseur = null
    }

    private fun traiter(paquet: Bundle?) {
        val textes = paquet?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        val commande = textes.firstNotNullOfOrNull { CommandeVocaleLogique.reconnaitre(it) } ?: return
        val maintenant = System.currentTimeMillis()
        if (CommandeVocaleLogique.doublon(derniere, derniereMs, commande, maintenant)) return
        derniere = commande; derniereMs = maintenant
        erreursDeSuite = 0
        surCommande(commande)
    }

    private fun relancer(delaiMs: Long) { if (actif) principal.postDelayed({ ecouter() }, delaiMs) }

    private fun ecouter() {
        if (!actif) return
        try { reconnaisseur?.destroy() } catch (e: Exception) { }
        val r = SpeechRecognizer.createSpeechRecognizer(c)
        reconnaisseur = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, p: Bundle?) {}
            override fun onPartialResults(p: Bundle?) = traiter(p)         // réaction rapide : dès que le mot est compris, sans attendre la fin de la phrase
            override fun onResults(p: Bundle?) { traiter(p); relancer(150) }
            override fun onError(code: Int) {
                when (code) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { actif = false; surProbleme("Autorisez le micro (Réglages → Applications → Personnel de maison → Autorisations)."); return }
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> erreursDeSuite += 2
                    else -> erreursDeSuite += 1                                // « rien compris » ou silence : normal, on réécoute
                }
                if (erreursDeSuite > 40) { actif = false; surProbleme("La commande vocale ne répond plus : touchez « Voix » pour la relancer."); return }
                relancer(if (code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 800 else 300)
            }
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        })
    }
}
