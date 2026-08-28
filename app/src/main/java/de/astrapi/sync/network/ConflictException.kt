package de.astrapi.sync.network

/** Server antwortet mit 409 auf einen Upload: die Datei wurde seit dem
 * letzten bekannten Stand des Clients serverseitig geaendert (echter
 * Konflikt, siehe astrapi_sync/api/sync.py::upload_file()). Aequivalent
 * zu ConflictError im Python-Client. */
class ConflictException(val relPath: String) :
    Exception("Konflikt bei $relPath: Server-Stand hat sich geändert")
