package com.maxrave.kotlinytmusicscraper.extractor

import com.maxrave.kotlinytmusicscraper.models.SongItem
import com.maxrave.kotlinytmusicscraper.models.response.DownloadProgress
import com.maxrave.ktorext.getEngine
import com.maxrave.logger.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.concurrent.Volatile
import kotlin.random.Random

private const val TAG = "Extractor"

/**
 * Stream URLs on iOS, where none of the JVM extractors (PipePipe, BravePipe) exist.
 *
 * It makes the one request PipePipe makes by default: the player endpoint as the visionOS YouTube
 * app, with a visitor id fetched first. That client is the reason no cipher code is needed here —
 * its response carries plain `url`s (no `signatureCipher`, no `n` challenge) that the CDN serves
 * at any byte offset without a PO token, plus an HLS manifest. The iOS app client looks the same
 * but is refused past the first megabyte; the TV and web clients are refused outright without a
 * signed-in session. Measured on 2026-11-16, 5 of 5 tracks read end to end.
 *
 * Constants mirror PipePipe's `ClientsConstants` (VISIONOS_*) so the two can be bumped together.
 *
 * Blocking, like the JVM actual: callers are already on an IO dispatcher.
 */
actual class Extractor {
    private val client by lazy { HttpClient(getEngine()) }
    private val json = Json { ignoreUnknownKeys = true }

    /** Kept for the process: YouTube ties the stream URLs to the visitor that asked for them. */
    @Volatile
    private var visitorData: String? = null

    actual fun init() {
    }

    // The visionOS request is anonymous; a signed-in session would only matter for Premium itags.
    actual fun logIn(cookie: String?) {}

    actual fun newPipePlayer(videoId: String): List<Pair<Int, String>> {
        val streamingData = streamingData(videoId) ?: return emptyList()
        val streams = mutableListOf<Pair<Int, String>>()
        for (key in listOf("formats", "adaptiveFormats")) {
            streamingData[key]?.jsonArray?.forEach { element ->
                val format = element.jsonObject
                val itag = format["itag"]?.jsonPrimitive?.intOrNull ?: return@forEach
                val url = format["url"]?.jsonPrimitive?.content ?: return@forEach
                streams.add(itag to url)
            }
        }
        // YouTube.newPipePlayer reads the HLS manifest from the pseudo-itag 96, as PipePipe reports it.
        streamingData["hlsManifestUrl"]?.jsonPrimitive?.content?.let { streams.add(HLS_ITAG to it) }
        if (streams.isNotEmpty()) ExtractSource.record(videoId, "visionOS")
        Logger.i(TAG, "extract source=visionOS itags=${streams.map { it.first }} for $videoId")
        return streams
    }

    actual fun liveHlsUrl(videoId: String): String? =
        streamingData(videoId)
            ?.get("hlsManifestUrl")
            ?.jsonPrimitive
            ?.content
            ?.also { ExtractSource.record(videoId, "visionOS · live") }

    /**
     * `streamingData` of the visionOS player response, or null when YouTube refused.
     *
     * A refusal is retried once with a fresh visitor id: an id that has gone stale is the one
     * failure this client can recover from by itself.
     */
    private fun streamingData(videoId: String): JsonObject? =
        runBlocking {
            repeat(2) { attempt ->
                try {
                    val visitor = visitorData ?: fetchVisitorData().also { visitorData = it }
                    val response = post("player", "&t=${randomToken(12)}&id=$videoId", playerBody(videoId, visitor))
                    val status = response["playabilityStatus"]?.jsonObject
                    val streamingData = response["streamingData"]?.jsonObject
                    if (status?.get("status")?.jsonPrimitive?.content == "OK" && streamingData != null) {
                        return@runBlocking streamingData
                    }
                    Logger.w(
                        TAG,
                        "visionOS player refused $videoId (attempt ${attempt + 1}): " +
                            "${status?.get("status")?.jsonPrimitive?.content} ${status?.get("reason")?.jsonPrimitive?.content}",
                    )
                } catch (e: Exception) {
                    Logger.w(TAG, "visionOS player failed for $videoId (attempt ${attempt + 1}): ${e.message}")
                }
                visitorData = null
            }
            null
        }

    private suspend fun fetchVisitorData(): String =
        post("visitor_id", "", context(null))["responseContext"]
            ?.jsonObject
            ?.get("visitorData")
            ?.jsonPrimitive
            ?.content
            ?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("no visitorData in response")

    private suspend fun post(
        endpoint: String,
        extraQuery: String,
        body: JsonObject,
    ): JsonObject {
        val response =
            client.post("$API_URL$endpoint?prettyPrint=false$extraQuery") {
                header("Content-Type", "application/json")
                header("User-Agent", USER_AGENT)
                header("X-Goog-Api-Format-Version", "2")
                setBody(body.toString())
            }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw IllegalStateException("HTTP ${response.status.value} from $endpoint")
        return json.parseToJsonElement(text).jsonObject
    }

    private fun playerBody(
        videoId: String,
        visitor: String,
    ): JsonObject =
        JsonObject(
            context(visitor) +
                buildJsonObject {
                    put("videoId", videoId)
                    put("cpn", randomToken(16))
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                },
        )

    private fun context(visitor: String?): JsonObject =
        buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", CLIENT_NAME)
                    put("clientVersion", CLIENT_VERSION)
                    put("clientScreen", "WATCH")
                    put("platform", "MOBILE")
                    if (visitor != null) put("visitorData", visitor)
                    put("deviceMake", "Apple")
                    put("deviceModel", DEVICE_MODEL)
                    put("osName", "visionOS")
                    put("osVersion", OS_VERSION)
                    put("hl", "en")
                    put("gl", "US")
                    put("utcOffsetMinutes", 0)
                }
                putJsonObject("request") {
                    put("internalExperimentFlags", buildJsonArray { })
                    put("useSsl", true)
                }
                putJsonObject("user") {
                    put("lockedSafetyMode", false)
                }
            }
        }

    private fun randomToken(length: Int): String = (1..length).map { TOKEN_ALPHABET[Random.nextInt(TOKEN_ALPHABET.length)] }.joinToString("")

    actual fun mergeAudioVideoDownload(filePath: String): DownloadProgress = DownloadProgress.failed("Not supported on iOS")

    actual fun saveAudioWithThumbnail(
        filePath: String,
        track: SongItem,
    ): DownloadProgress = DownloadProgress.failed("Not supported on iOS")

    private companion object {
        const val API_URL = "https://youtubei.googleapis.com/youtubei/v1/"
        const val CLIENT_NAME = "VISIONOS"
        const val CLIENT_VERSION = "1.02"
        const val DEVICE_MODEL = "RealityDevice14,1"
        const val OS_VERSION = "25.6.0.23O471"
        const val USER_AGENT =
            "com.google.visionos.youtube/$CLIENT_VERSION($DEVICE_MODEL; U; CPU visionOS 25_6_0 like Mac OS X; US)"
        const val HLS_ITAG = 96
        const val TOKEN_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
    }
}
