package de.astrapi.sync.network

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Kotlin-Pendant zu astrapi_sync_cli/api_client.py -- spricht dieselbe
 * Server-API (astrapi_sync/api/sync.py). Pfad-Segmente werden konsequent
 * über HttpUrl.Builder().addPathSegments() aufgebaut (kodiert automatisch)
 * statt per String-Konkatenation -- vermeidet von Anfang an den im
 * Python-Client gefundenen Bug mit '#'/'?' in Dateinamen (T-214-SYNC). */
class ApiClient(serverUrl: String, private val deviceToken: String) {

    private val baseUrl: HttpUrl = serverUrl.trimEnd('/').toHttpUrl()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        val json = Json { ignoreUnknownKeys = true }
        private val JSON_MEDIA = "application/json".toMediaType()

        /** Kein Geräte-Token nötig -- Pairing authentifiziert sich über
         * den (kurzlebigen) Pairing-Code selbst. */
        suspend fun pair(
            serverUrl: String,
            pairingCode: String,
            description: String = "",
            platform: String = "android",
        ): PairResult = withContext(Dispatchers.IO) {
            val url = serverUrl.trimEnd('/').toHttpUrl().newBuilder()
                .addPathSegments("api/sync/pair")
                .build()
            val body = json.encodeToString(PairRequest(pairingCode, description, platform))
                .toRequestBody(JSON_MEDIA)
            val request = Request.Builder().url(url).post(body).build()
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
                .newCall(request)
                .execute()
                .use { resp -> json.decodeFromString(PairResult.serializer(), bodyOrThrow(resp)) }
        }

        /** Wirft ApiException mit der Server-Fehlermeldung (FastAPI-Format
         * {"detail": "..."}), falls vorhanden -- statt eines rohen
         * Statuscodes ohne Kontext (T-229-SYNC). */
        private fun bodyOrThrow(resp: Response): String {
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                val detail = runCatching {
                    json.parseToJsonElement(text).jsonObject["detail"]?.jsonPrimitive?.content
                }.getOrNull()
                throw ApiException(resp.code, detail ?: "HTTP ${resp.code}: ${resp.message}")
            }
            return text
        }
    }

    private fun folderUrl(vararg segments: String): HttpUrl {
        val b = baseUrl.newBuilder().addPathSegments("api/sync")
        for (s in segments) b.addPathSegments(s)
        return b.build()
    }

    private fun authedRequest(url: HttpUrl): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer $deviceToken")

    private fun execute(request: Request): Response = client.newCall(request).execute()

    suspend fun listFolders(): List<FolderInfo> = withContext(Dispatchers.IO) {
        val req = authedRequest(folderUrl("folders")).get().build()
        execute(req).use { resp ->
            json.decodeFromString(FoldersResponse.serializer(), bodyOrThrow(resp)).folders
        }
    }

    suspend fun getIndex(folderId: String): IndexResponse = withContext(Dispatchers.IO) {
        val req = authedRequest(folderUrl("folders", folderId, "index")).get().build()
        execute(req).use { resp ->
            json.decodeFromString(IndexResponse.serializer(), bodyOrThrow(resp))
        }
    }

    /** Streamt die Server-Antwort in [out] -- Aufrufer verantwortet
     * Ziel-Handling (bei SAF: über eine temporäre DocumentFile +
     * atomares renameTo(), siehe SafFileOps). */
    suspend fun download(folderId: String, relPath: String, out: OutputStream) =
        withContext(Dispatchers.IO) {
            val req = authedRequest(folderUrl("folders", folderId, "files", relPath)).get().build()
            execute(req).use { resp ->
                if (!resp.isSuccessful) bodyOrThrow(resp)
                resp.body?.byteStream()?.use { input: InputStream -> input.copyTo(out) }
                    ?: throw ApiException(resp.code, "Leere Antwort beim Download")
            }
        }

    suspend fun upload(
        folderId: String,
        relPath: String,
        meta: UploadMeta,
        changedBytes: ByteArray,
    ): UploadResult = withContext(Dispatchers.IO) {
        val metaJson = json.encodeToString(meta)
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("meta", null, metaJson.toRequestBody(JSON_MEDIA))
            .addFormDataPart(
                "data", "data.bin",
                changedBytes.toRequestBody("application/octet-stream".toMediaType()),
            )
            .build()
        val req = authedRequest(folderUrl("folders", folderId, "files", relPath))
            .post(multipart as RequestBody)
            .build()
        execute(req).use { resp ->
            if (resp.code == 409) throw ConflictException(relPath)
            json.decodeFromString(UploadResult.serializer(), bodyOrThrow(resp))
        }
    }

    suspend fun delete(folderId: String, relPath: String) = withContext(Dispatchers.IO) {
        val req = authedRequest(folderUrl("folders", folderId, "files", relPath)).delete().build()
        execute(req).use { resp ->
            if (!resp.isSuccessful && resp.code != 404) bodyOrThrow(resp)
        }
    }

    suspend fun createDir(folderId: String, relPath: String) = withContext(Dispatchers.IO) {
        val req = authedRequest(folderUrl("folders", folderId, "dirs", relPath))
            .post("".toRequestBody(null))
            .build()
        execute(req).use { resp -> bodyOrThrow(resp) }
    }

    /** Gibt zurück, ob das Verzeichnis tatsächlich entfernt wurde --
     * false z.B. wenn es zwischenzeitlich (noch im selben Lauf) doch
     * nicht mehr leer war (siehe astrapi_sync/api/sync.py::delete_dir()). */
    suspend fun deleteDir(folderId: String, relPath: String): Boolean = withContext(Dispatchers.IO) {
        val req = authedRequest(folderUrl("folders", folderId, "dirs", relPath)).delete().build()
        execute(req).use { resp ->
            if (resp.code == 404) return@withContext false
            json.decodeFromString(DirOpResult.serializer(), bodyOrThrow(resp)).deleted
        }
    }

    /** Best-effort -- darf den Sync-Lauf selbst nicht zum Scheitern
     * bringen, Aufrufer (SyncEngine) fängt Fehler ab. */
    suspend fun logSync(folderId: String, summary: SyncSummary) = withContext(Dispatchers.IO) {
        val body = json.encodeToString(summary).toRequestBody(JSON_MEDIA)
        val req = authedRequest(folderUrl("folders", folderId, "sync-log")).post(body).build()
        execute(req).use { resp -> bodyOrThrow(resp) }
    }
}
