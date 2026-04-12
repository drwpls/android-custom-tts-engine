package com.example.CustomTts // <<< ANPASSEN

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log
import com.example.CustomTts.data.PrefKeys // <<< ANPASSEN
import com.example.CustomTts.data.settingsDataStore // <<< ANPASSEN
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder


class CustomTtsService : TextToSpeechService() {




    companion object {
        // Logging Tag
        private const val TAG = "DummyTtsService"
    }

    private data class WavHeaderInfo(
        val sampleRate: Int,
        val numChannels: Short,
        val bitsPerSample: Short, // Bleibt 32
        val audioFormatCode: Short, // Der Code aus dem Header (1 oder 3)
        val androidEncoding: Int, // Das passende AudioFormat.ENCODING_*
        val dataOffset: Int,
        val dataSize: Int
    )

    private fun parseWavHeader(wavBytes: ByteArray): WavHeaderInfo? {
        if (wavBytes.size < 44) {
            Log.e(TAG, "WAV data too short for header: ${wavBytes.size} bytes")
            return null // Mindestens 44 Bytes für Standard-Header benötigt
        }

        val buffer = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN) // WAV ist Little Endian

        try {
            // Überprüfe RIFF Header
            val riffChunkId = ByteArray(4).apply { buffer.get(this) }.toString(Charsets.US_ASCII)
            if (riffChunkId != "RIFF") {
                Log.e(TAG, "Invalid WAV: Missing 'RIFF' chunk ID.")
                return null
            }

            buffer.position(8) // Gehe zum Format-Feld (nach ChunkSize)
            val format = ByteArray(4).apply { buffer.get(this) }.toString(Charsets.US_ASCII)
            if (format != "WAVE") {
                Log.e(TAG, "Invalid WAV: Missing 'WAVE' format.")
                return null
            }

            // Suche nach dem 'fmt ' Sub-Chunk
            buffer.position(12)
            val fmtChunkId = ByteArray(4).apply { buffer.get(this) }.toString(Charsets.US_ASCII)
            if (fmtChunkId != "fmt ") {
                Log.e(TAG, "Invalid WAV: Missing 'fmt ' sub-chunk ID.")
                // Hier könnte man noch weitersuchen, aber für Standard-WAV ist es hier
                return null
            }

            val fmtChunkSize = buffer.int // Größe des fmt Chunks (sollte 16 für PCM sein)
            val audioFormat = buffer.short // Audioformat (1 = PCM)
            val numChannels = buffer.short // Anzahl Kanäle
            val sampleRate = buffer.int // Sample Rate
            val byteRate = buffer.int // Bytes pro Sekunde (SampleRate * NumChannels * BitsPerSample/8)
            val blockAlign = buffer.short // Bytes pro Sample Frame (NumChannels * BitsPerSample/8)
            val bitsPerSample = buffer.short // Bits pro Sample (z.B. 16)

            Log.d(TAG, "WAV Header: Format=$audioFormat, Channels=$numChannels, Rate=$sampleRate, Bits=$bitsPerSample, FmtChunkSize=$fmtChunkSize")


            // Überprüfe auf unterstütztes Format (16-bit Int ODER 32-bit Float PCM)
            val androidEncoding = when {
                audioFormat == 1.toShort() && bitsPerSample == 16.toShort() -> AudioFormat.ENCODING_PCM_16BIT
                audioFormat == 3.toShort() && bitsPerSample == 32.toShort() -> AudioFormat.ENCODING_PCM_FLOAT // Float erkennen!
                else -> {
                    Log.e(TAG, "Unsupported WAV format: AudioFormat=$audioFormat, BitsPerSample=$bitsPerSample. Only 16-bit Int (1) or 32-bit Float (3) PCM supported.")
                    return null
                }
            }



            // Suche nach dem 'data' Sub-Chunk (kann direkt nach 'fmt ' kommen oder später)
            // Wir überspringen ggf. zusätzliche fmt-Daten oder andere Chunks
            buffer.position(20 + fmtChunkSize) // Gehe zum Ende des fmt-Chunks (12 ID + 4 size + fmtChunkSize data)
            var dataChunkId = ByteArray(4).apply { buffer.get(this) }.toString(Charsets.US_ASCII)
            while(dataChunkId != "data" && buffer.remaining() >= 8) {
                Log.d(TAG,"Skipping chunk '$dataChunkId'")
                val chunkSize = buffer.int // Größe des unbekannten Chunks lesen
                if (buffer.remaining() < chunkSize + 4) break // Nicht genug Daten übrig
                buffer.position(buffer.position() + chunkSize) // Chunk überspringen
                if (buffer.remaining() < 4) break
                dataChunkId = ByteArray(4).apply { buffer.get(this) }.toString(Charsets.US_ASCII)
            }


            if (dataChunkId != "data") {
                Log.e(TAG, "Invalid WAV: Missing 'data' sub-chunk ID.")
                return null
            }

            val rawDataSize = buffer.int // Größe der Audiodaten
            val dataOffset = buffer.position() // Aktuelle Position ist der Start der Daten
            // OpenAI WAV uses 0xFFFFFFFF (reads as -1) for unknown/streaming size — fall back to actual bytes
            val dataSize = if (rawDataSize <= 0) wavBytes.size - dataOffset else rawDataSize

            if (wavBytes.size < dataOffset + dataSize) {
                Log.e(TAG, "WAV data shorter than expected by header. Expected >= ${dataOffset + dataSize}, got ${wavBytes.size}")
                return null
            }


            return WavHeaderInfo(
                sampleRate = sampleRate,
                numChannels = numChannels,
                bitsPerSample = bitsPerSample,
                audioFormatCode = audioFormat, // Code speichern
                androidEncoding = androidEncoding, // Passendes Android Encoding speichern
                dataOffset = dataOffset,
                dataSize = dataSize
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing WAV header", e)
            return null
        }
    }

    // Ktor HTTP Client
    private lateinit var httpClient: HttpClient
    // Coroutine Scope für Hintergrundaufgaben
    private lateinit var serviceScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // Scope und Client initialisieren
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        httpClient = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
            // HttpTimeout Plugin HINZUFÜGEN:
            install(HttpTimeout) {
                // Timeout für die gesamte Anfrage (Senden + Warten + Empfangen)
                // Setze diesen Wert großzügig, z.B. 60 Sekunden (60_000 ms) oder mehr
                requestTimeoutMillis = 60000

                // Timeout für den Verbindungsaufbau zum Server
                connectTimeoutMillis = 10000 // 10 Sekunden sollten reichen

                // Timeout zwischen dem Empfang von Datenpaketen (wichtig bei langsamen Antworten)
                // Setze diesen auch großzügig, z.B. 60 Sekunden
                socketTimeoutMillis = 60000
            }
            // Optional: Timeouts etc.
            // engine { requestTimeout = 30_000 }
        }
        Log.i(TAG, "Service Created, HttpClient and Scope initialized.")
    }

    override fun onDestroy() {
        Log.d(TAG, "Service Destroyed")
        // Ressourcen freigeben
        if (::httpClient.isInitialized) {
            httpClient.close()
            Log.i(TAG, "HttpClient closed.")
        }
        if (::serviceScope.isInitialized) {
            serviceScope.cancel() // Wichtig: Bricht laufende Coroutinen ab
            Log.i(TAG, "Coroutine Scope cancelled.")
        }
        super.onDestroy()
    }

    //--------------------------------------------------------------------------
    // Implementierung der abstrakten TTS-Methoden
    //--------------------------------------------------------------------------

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        Log.d(TAG, "onIsLanguageAvailable: lang=$lang, country=$country, variant=$variant")
        // Beispiel: Annahme, dass Backend Englisch, Deutsch, Spanisch unterstützt
        // TODO: An tatsächliche Backend-Fähigkeiten anpassen
        return when (lang?.lowercase()) {
            "eng" -> TextToSpeech.LANG_COUNTRY_AVAILABLE // Oder LANG_AVAILABLE
            "de" -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            "es" -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            else -> TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onGetLanguage(): Array<String>? {
        Log.d(TAG, "onGetLanguage called")
        // Beispiel: Gibt Englisch US als Standard zurück
        // TODO: An ausgewählte oder konfigurierte Standardsprache anpassen
        return arrayOf("eng", "USA", "")
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val result = onIsLanguageAvailable(lang, country, variant)
        Log.d(TAG, "onLoadLanguage for $lang-$country-$variant: Result=$result")
        // TODO: Hier könnte man intern die zu verwendende Stimme für die nächste Synthese wählen
        return result
    }

    override fun onStop() {
        Log.d(TAG, "onStop called")
        // TODO: Implementiere Logik zum Abbrechen laufender Synthesen.
        // Das erfordert, die Coroutine-Jobs zu verwalten und `job.cancel()` aufzurufen.
        // Momentan wird eine laufende Anfrage NICHT abgebrochen.
    }

    //--------------------------------------------------------------------------
    // Haupt-Synthese-Logik
    //--------------------------------------------------------------------------

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        // Null-Checks für Request und Callback
        if (request == null || callback == null) {
            Log.e(TAG, "onSynthesizeText: Request or Callback is null")
            return
        }

        // Extrahiere Parameter aus der Anfrage
        val text = request.charSequenceText?.toString() ?: ""
        val language = request.language ?: "en"
        val country = request.country ?: "US"
        // Android Rate (100=normal) -> OpenAI Speed (1.0=normal)
        val androidRate = request.speechRate.toFloat().coerceIn(20f, 300f)
        val openAiSpeed = androidRate / 100.0f

        Log.d(TAG, "Synthesize request received: lang=$language-$country, text='${text.take(50)}...', rate=$androidRate")

        // Leeren Text nicht verarbeiten, aber Callback abschließen
        if (text.isBlank()) {
            Log.w(TAG, "Text to synthesize is blank.")
            try {
                callback.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1) // Dummy Start
                callback.done()
            } catch (e: Exception) { Log.e(TAG, "Error completing callback for blank text", e)}
            return
        }

        // Starte die Coroutine für die eigentliche Arbeit
        serviceScope.launch {
            // Stelle sicher, dass der Callback noch gültig ist
            val safeCallback = callback ?: run {
                Log.w(TAG, "Callback became null before coroutine could process.")
                return@launch
            }

            try {
                // --- Einstellungen lesen ---
                Log.d(TAG, "Reading settings from DataStore...")
                val currentSettings = applicationContext.settingsDataStore.data.first()
                val backendUrl = currentSettings[PrefKeys.BACKEND_URL] ?: "" // Kein Standard hier, muss gesetzt sein
                val apiKey = currentSettings[PrefKeys.API_KEY] ?: ""
                val apiModel = currentSettings[PrefKeys.TTS_MODEL] ?: "tts-1" // Standardmodell
                val apiVoice = currentSettings[PrefKeys.TTS_VOICE] ?: "alloy" // Standardstimme
                val requestedAudioFormat = currentSettings[PrefKeys.RESPONSE_FORMAT] ?: "wav"


                // Prüfen, ob notwendige Einstellungen vorhanden sind
                if (backendUrl.isBlank()) {
                    Log.e(TAG, "Backend URL is missing in settings.")
                    safeCallback.error(TextToSpeech.ERROR_SERVICE)
                    return@launch
                }
                Log.d(TAG, "Using Settings: URL=$backendUrl, Key=******, Model=$apiModel, Voice=$apiVoice, Format=$requestedAudioFormat, Speed=$openAiSpeed")
                Log.d(TAG, "Using Settings: ..., Format=$requestedAudioFormat, ...")
                // --- Netzwerkanfrage ---
                Log.d(TAG, "Sending request to backend...")
                val response: HttpResponse = httpClient.post(backendUrl) {
                    if (apiKey.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $apiKey")
                    contentType(ContentType.Application.Json)
                    setBody(TtsRequestPayload(
                        model = apiModel,
                        input = text,
                        voice = apiVoice,
                        response_format = requestedAudioFormat,
                        speed = openAiSpeed
                    ))
                }
                Log.d(TAG, "Backend response status: ${response.status}")

                // --- Antwortverarbeitung ---
                if (response.status.isSuccess()) {
                    val audioBytes = response.readBytes()
                    Log.d(TAG, "Received ${audioBytes.size} bytes for format '$requestedAudioFormat'.")

                    if (audioBytes.isEmpty()) { /* Fehler: Leere Daten */ return@launch }

                    // --- Callback starten & Audio streamen ---
                    if (requestedAudioFormat == "pcm") {
                        // PCM direkt verarbeiten (Annahme: Format bekannt)
                        Log.d(TAG, "Processing as raw PCM...")
                        val sampleRateInHz = 24000 // Annahme! Muss zum Backend passen!
                        val encoding = AudioFormat.ENCODING_PCM_16BIT
                        val channelCount = 1 // Annahme!
                        val startResult = safeCallback.start(sampleRateInHz, encoding, channelCount)
                        if (startResult == TextToSpeech.ERROR) { Log.e(TAG,"PCM start failed"); safeCallback.error(TextToSpeech.ERROR_OUTPUT); return@launch }

                        // PCM Bytes streamen (gesamtes Array, da keine Header)
                        val audioChunkSize = 8192
                        var offset = 0
                        while (offset < audioBytes.size) {
                            val chunkSize = Math.min(audioBytes.size - offset, audioChunkSize)
                            val audioAvailableResult = safeCallback.audioAvailable(audioBytes, offset, chunkSize)
                            if (audioAvailableResult == TextToSpeech.ERROR) { Log.e(TAG,"PCM audioAvailable failed"); safeCallback.error(TextToSpeech.ERROR_OUTPUT); return@launch }
                            offset += chunkSize
                        }
                        safeCallback.done()
                        Log.i(TAG, "PCM processing completed.")

                    } else if (requestedAudioFormat == "wav") {
                        // WAV verarbeiten
                        Log.d(TAG, "Processing as WAV...")
                        val wavInfo = parseWavHeader(audioBytes) // Header parsen

                        if (wavInfo == null) {
                            // Ungültiger oder nicht unterstützter WAV-Header
                            Log.e(TAG, "Failed to parse or unsupported WAV header.")
                            safeCallback.error(TextToSpeech.ERROR_INVALID_REQUEST) // Fehler im empfangenen Format
                            return@launch
                        }

                        Log.d(TAG, "Calling callback.start() with WAV params: Rate=${wavInfo.sampleRate}, Encoding=${wavInfo.androidEncoding}, Channels=${wavInfo.numChannels}")
                        val startResult = safeCallback.start(wavInfo.sampleRate, wavInfo.androidEncoding, wavInfo.numChannels.toInt())
                        if (startResult == TextToSpeech.ERROR) {
                            Log.e(TAG, "WAV start failed!")
                            safeCallback.error(TextToSpeech.ERROR_OUTPUT)
                            return@launch
                        }

                        // Bytes NACH dem Header streamen (Die Bytes sind bereits Float oder Int, je nach Header)
                        val dataOffset = wavInfo.dataOffset
                        val dataSize = wavInfo.dataSize
                        Log.d(TAG, "Streaming ${dataSize} WAV data bytes (Format: ${wavInfo.audioFormatCode}, Encoding: ${wavInfo.androidEncoding}) starting from offset ${dataOffset}...")

                        val audioChunkSize = 8192
                        var bytesStreamed = 0
                        while (bytesStreamed < dataSize) {
                            val readOffset = dataOffset + bytesStreamed
                            // Sicherheitscheck: Stelle sicher, dass wir nicht über das Ende des Arrays lesen
                            if (readOffset >= audioBytes.size) {
                                Log.w(TAG, "WAV data ended prematurely based on header size.")
                                break // Schleife beenden
                            }
                            // Berechne Chunk-Größe sicher
                            val chunkSize = Math.min(dataSize - bytesStreamed, audioChunkSize).toInt()
                            val availableBytesInArray = audioBytes.size - readOffset
                            val actualChunkSize = Math.min(chunkSize, availableBytesInArray)

                            if (actualChunkSize <= 0) break // Nichts mehr zu lesen

                            val audioAvailableResult = safeCallback.audioAvailable(audioBytes, readOffset, actualChunkSize)
                            if (audioAvailableResult == TextToSpeech.ERROR) {
                                Log.e(TAG, "WAV audioAvailable failed!")
                                safeCallback.error(TextToSpeech.ERROR_OUTPUT)
                                return@launch
                            }
                            bytesStreamed += actualChunkSize
                        } // Ende while

                        safeCallback.done()
                        Log.i(TAG, "WAV processing completed. Streamed $bytesStreamed bytes.")

                    } else if (requestedAudioFormat == "mp3" || requestedAudioFormat == "opus") {
                        // Komprimierte Formate -> Dekodierung NÖTIG!
                        Log.e(TAG,"Decoding for format '$requestedAudioFormat' not implemented yet!")
                        safeCallback.error(TextToSpeech.ERROR_SERVICE) // Fehler: Noch nicht implementiert
                        return@launch
                    } else {
                        // Nicht unterstütztes Format angefordert/empfangen
                        Log.e(TAG, "Unsupported audio format requested/received: $requestedAudioFormat")
                        safeCallback.error(TextToSpeech.ERROR_INVALID_REQUEST)
                        return@launch
                    }
                } else {
                    // Fehler bei der Backend-Antwort
                    val errorBody = try { response.bodyAsText() } catch (e: Exception) { "Could not read error body: ${e.message}" }
                    Log.e(TAG, "Backend request failed: Status=${response.status}, Body='$errorBody'")
                    safeCallback.error(TextToSpeech.ERROR_NETWORK)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Exception during network request or processing", e)

                // Versuche, spezifischere Fehlercodes zu setzen
                val errorCode = when (e) {
                    // Ktor: Fehler bei der Client-Anfrage (z.B. ungültige URL, DNS-Problem)
                    is io.ktor.client.plugins.ClientRequestException -> TextToSpeech.ERROR_NETWORK
                    // Ktor: Fehler bei der Server-Antwort (z.B. 5xx Serverfehler)
                    is io.ktor.client.plugins.ServerResponseException -> TextToSpeech.ERROR_NETWORK // Oder ERROR_SERVICE?
                    // Ktor: Fehler beim Umleiten
                    is io.ktor.client.plugins.RedirectResponseException -> TextToSpeech.ERROR_NETWORK
                    // Allgemeine Netzwerkprobleme (z.B. keine Verbindung, DNS-Problem wie zuvor)
                    is java.net.UnknownHostException -> TextToSpeech.ERROR_NETWORK
                    is java.net.ConnectException -> TextToSpeech.ERROR_NETWORK_TIMEOUT // Oder ERROR_NETWORK
                    // Timeout beim Socket
                    is java.net.SocketTimeoutException -> TextToSpeech.ERROR_NETWORK_TIMEOUT
                    // Allgemeine IO-Fehler (können auch Netzwerkprobleme sein)
                    is java.io.IOException -> TextToSpeech.ERROR_NETWORK // Fängt diverse Netzwerk-IO-Probleme
                    // Fehler bei der JSON-Verarbeitung
                    is kotlinx.serialization.SerializationException -> TextToSpeech.ERROR_INVALID_REQUEST
                    // Sonstige Fehler
                    else -> TextToSpeech.ERROR_SERVICE
                }
                Log.w(TAG, "Reporting error code: $errorCode")

                try {
                    safeCallback.error(errorCode)
                } catch (callbackError: Exception) {
                    Log.e(TAG, "Error reporting error code $errorCode to callback", callbackError)
                }
            } // Ende try-catch
        } // Ende serviceScope.launch

        Log.d(TAG, "onSynthesizeText finished launching coroutine.")
    } // Ende onSynthesizeText

} // Ende DummyTtsService Klasse