package com.wzm.launcher.server

/**
 * Stub for future CDNI integration.
 * Today it only provides placeholder routes; later it will handle
 * manifest.json, cdni.meta, shard metadata, asset lookup, etc.
 * DO NOT invent unverified WZM endpoints here — only stub /health, /, /__hits, /__reset.
 */
interface OfflineContentServer {
    fun handleHealth(): String = "OK"
    fun handleRoot(): String
    fun handleHits(): String
    fun handleReset(): String
}
