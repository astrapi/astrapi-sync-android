package de.astrapi.sync.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Muss 1:1 zum Server-Protokoll passen -- siehe astrapi_sync/api/sync.py
 * und astrapi_sync/api/block_hash.py (gleiches JSON-Format wie beim
 * Python-Client, astrapi_sync_cli/api_client.py). */

@Serializable
data class PairRequest(
    val token: String,
    val description: String = "",
    val platform: String = "",
)

/** QR-Payload aus dem Pairing-Dialog des Servers
 * (astrapi_sync/modules/devices/ui/pairing.py::_qr_svg()) -- dieselben zwei
 * Felder wie bei PairRequest.token + die Server-URL, damit beim Scannen
 * nichts mehr von Hand eingetippt werden muss. */
@Serializable
data class PairingQrPayload(
    @SerialName("server_url") val serverUrl: String,
    val token: String,
) {
    companion object {
        /** Liefert null bei kaputtem JSON, fehlenden/leeren Feldern oder
         * QR-Codes ohne Bezug zu astrapi-sync -- der Scanner ignoriert
         * solche Treffer dann einfach weiter, statt einen Fehlerdialog für
         * z.B. einen falsch gescannten fremden QR-Code zu zeigen. */
        fun parse(raw: String): PairingQrPayload? = runCatching {
            ApiClient.json.decodeFromString(serializer(), raw)
        }.getOrNull()?.takeIf { it.serverUrl.isNotBlank() && it.token.isNotBlank() }
    }
}

@Serializable
data class PairResult(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_token") val deviceToken: String,
    @SerialName("folder_ids") val folderIds: List<String>,
)

@Serializable
data class FolderInfo(
    val id: String,
    val description: String,
    val color: String? = null,
)

@Serializable
data class FoldersResponse(
    val folders: List<FolderInfo>,
)

@Serializable
data class FileEntry(
    val path: String,
    val size: Long,
    val mtime: Double,
    @SerialName("block_size") val blockSize: Int,
    val sha256: String,
    val blocks: List<String>,
)

@Serializable
data class IndexResponse(
    val files: List<FileEntry>,
    val dirs: List<String> = emptyList(),
)

@Serializable
data class UploadMeta(
    val size: Long,
    val mtime: Double,
    @SerialName("block_size") val blockSize: Int,
    val blocks: List<String>,
    val changed: List<Int>,
    @SerialName("expected_server_sha256") val expectedServerSha256: String? = null,
)

@Serializable
data class UploadResult(
    val status: String,
    val sha256: String,
)

@Serializable
data class DirOpResult(
    val status: String,
    val deleted: Boolean = true,
)

@Serializable
data class SyncSummary(
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    @SerialName("deleted_local") val deletedLocal: Int = 0,
    @SerialName("deleted_remote") val deletedRemote: Int = 0,
    val conflicts: Int = 0,
    // Datei-genaue Ergänzung zu den obigen Zählern (T-338-SYNC) -- der
    // Server legt daraus je eine Verlaufszeile pro Pfad an, siehe
    // astrapi_sync/modules/folders/history.py.
    @SerialName("uploaded_paths") val uploadedPaths: List<String> = emptyList(),
    @SerialName("downloaded_paths") val downloadedPaths: List<String> = emptyList(),
    @SerialName("deleted_local_paths") val deletedLocalPaths: List<String> = emptyList(),
    @SerialName("deleted_remote_paths") val deletedRemotePaths: List<String> = emptyList(),
    @SerialName("conflict_paths") val conflictPaths: List<String> = emptyList(),
)

/** Eine Zeile aus astrapi_sync/modules/folders/history.py::list_history() --
 * device_label statt Herkunfts-Gerät-ID, da die App den fremden Gerätenamen
 * ohnehin nur zur Anzeige braucht (kein Zugriff auf devices-CRUD). */
@Serializable
data class SyncHistoryEntry(
    val path: String,
    val action: String,
    @SerialName("device_label") val deviceLabel: String,
    @SerialName("synced_at") val syncedAt: String,
)

@Serializable
data class SyncHistoryResponse(
    val entries: List<SyncHistoryEntry>,
)

/** Meldet die UnifiedPush-Endpoint-URL dieses Geräts an den Server, siehe
 * astrapi_sync/api/sync.py::register_push_endpoint(). Leerer String
 * deregistriert. */
@Serializable
data class PushRegistration(
    @SerialName("endpoint_url") val endpointUrl: String,
)
