package de.rennrad.tracker

import android.content.Context
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONObject

internal data class Server(val base: String, val key: String) {
    fun familyLink(renew: Boolean = false): String {
        val body = if (renew) JSONObject() else null
        return linkFrom(JSONObject(request("/api/owner/link", body)))
    }

    private fun linkFrom(result: JSONObject): String {
        val path = result.getString("path")
        if (!Regex("/f/[0-9a-fA-F-]{36}").matches(path)) throw ServerError(R.string.server_invalid_link)
        return "$base$path"
    }

    fun upload(ride: RideFiles.Upload) {
        val id = ride.folder.name.substringAfter('-')
        request("/api/owner/rides/$id", ride.body)
    }

    fun installDeviceCredential(context: Context): Server = Sync.lock.withLock {
        val info = JSONObject(request("/api/owner/link", null))
        if (!info.optBoolean("recovery")) {
            Setup.save(context, this, linkFrom(info), deviceCredential = info.has("recovery"))
            return@withLock this
        }
        val candidate = Setup.recoveryCandidate(context, this)
        val enrollment = JSONObject().put("challenge", fingerprint(candidate)).put("name", deviceName(context))
        request("/api/owner/devices", enrollment)
        val device = copy(key = candidate)
        Setup.save(context, device, linkFrom(info), deviceCredential = true)
        Setup.clearRecoveryCandidate(context)
        device
    }

    fun approve(request: PairingRequest) {
        check(request.base == base)
        this.request("/api/owner/pair", request.json())
    }

    fun claimPairing(): String? {
        val result = request("/api/pair/claim", JSONObject())
        return if (result.isEmpty()) null else linkFrom(JSONObject(result))
    }

    fun devices(): JSONObject = JSONObject(request("/api/owner/devices", null))

    fun revokeDevice(id: String) {
        require(Regex("[0-9a-f-]{36}").matches(id))
        request("/api/owner/devices/$id", null, "DELETE")
    }

    private fun request(path: String, body: JSONObject?, method: String = if (body == null) "GET" else "POST"): String {
        val connection = URI.create("$base$path").toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 6_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.requestMethod = method
            if (body != null) {
                val data = body.toString().toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(data.size)
                connection.outputStream.use { it.write(data) }
            }
            return when (val code = connection.responseCode) {
                202, 204 -> ""
                200 -> connection.inputStream.bufferedReader().use { it.readText() }
                401 -> throw ServerError(R.string.server_unauthorized)
                409 -> throw ServerError(R.string.server_conflict)
                410 -> throw ServerError(R.string.pair_expired)
                429 -> throw ServerError(R.string.server_rate_limit)
                else -> throw ServerError(R.string.server_http_error, code)
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        fun address(address: String): String {
            val uri = URI(address.trim().trimEnd('/'))
            val local = uri.host in listOf("10.0.2.2", "127.0.0.1", "localhost")
            require(uri.scheme == "https" || (BuildConfig.DEBUG && uri.scheme == "http" && local))
            require(uri.host != null && uri.rawPath.isEmpty() && uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null)
            return uri.toASCIIString()
        }

    }
}

internal class ServerError(private val messageId: Int, private vararg val arguments: Any) : IOException() {
    fun text(context: Context): String = context.getString(messageId, *arguments)
}

internal fun newKey(): String = ByteArray(32).also(SecureRandom()::nextBytes).toHex()
internal fun fingerprint(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).toHex()
private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun deviceName(context: Context): String = android.os.Build.MODEL.trim().take(48).ifBlank { context.getString(R.string.default_device) }

internal object Setup {
    private fun prefs(context: Context) = context.getSharedPreferences("server", Context.MODE_PRIVATE)

    fun server(context: Context): Server? {
        val prefs = prefs(context)
        val base = prefs.getString("base", null) ?: return null
        val key = prefs.getString("key", null) ?: return null
        return Server(base, key)
    }

    fun link(context: Context): String? = prefs(context).getString("link", null)

    fun hasDeviceCredential(context: Context) = prefs(context).getBoolean("deviceCredential", false)

    fun save(context: Context, server: Server, link: String, deviceCredential: Boolean = hasDeviceCredential(context)) {
        check(prefs(context).edit().putString("base", server.base).putString("key", server.key).putString("link", link)
            .putBoolean("deviceCredential", deviceCredential).commit())
    }

    fun recoveryCandidate(context: Context, server: Server): String {
        val prefs = prefs(context)
        val owner = fingerprint(server.base + "\n" + server.key)
        if (prefs.getString("recoveryOwner", null) == owner) {
            prefs.getString("recoveryCandidate", null)?.let { return it }
        }
        val candidate = newKey()
        check(prefs.edit().putString("recoveryOwner", owner).putString("recoveryCandidate", candidate).commit())
        return candidate
    }

    fun clearRecoveryCandidate(context: Context) {
        prefs(context).edit().remove("recoveryOwner").remove("recoveryCandidate").apply()
    }

    fun pendingPair(context: Context): PairingSession? = prefs(context).getString("pendingPair", null)?.let(PairingSession::restore)

    fun pendingPair(context: Context, session: PairingSession?) {
        check(prefs(context).edit().putString("pendingPair", session?.persist()).commit())
    }
}

internal object Sync {
    val lock = ReentrantLock()

    fun flush(context: Context) = lock.withLock {
        val server = Setup.server(context) ?: return@withLock
        while (!Thread.currentThread().isInterrupted) {
            val ride = RideFiles.nextUpload(context) ?: return@withLock
            server.upload(ride)
            RideFiles.confirm(ride)
        }
    }
}
