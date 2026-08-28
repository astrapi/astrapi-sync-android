package de.astrapi.sync.network

/** Trägt (falls vorhanden) die eigentliche Server-Fehlermeldung
 * (FastAPI-Standardformat {"detail": "..."}) statt nur eines rohen
 * HTTP-Statuscodes -- vermeidet von Anfang an die im Python-Client
 * gefundene Lücke (T-229-SYNC: rohe Tracebacks statt verständlicher
 * Meldungen). */
class ApiException(val statusCode: Int, message: String) : Exception(message)
