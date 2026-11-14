package de.rennrad.tracker

import android.app.AlertDialog
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject
import java.util.concurrent.Executor

private const val QR_PREFIX = "rennrad-pair:1:"
private const val PAIRING_MS = 300_000L

class PairScannerActivity : com.journeyapps.barcodescanner.CaptureActivity()

internal data class PairingRequest(val base: String, val challenge: String, val name: String, val expires: Long) {
    fun json(): JSONObject = JSONObject().put("base", base).put("challenge", challenge).put("name", name).put("expires", expires)

    companion object {
        fun scan(text: String): PairingRequest {
            require(text.startsWith(QR_PREFIX) && text.length < 600)
            val value = JSONObject(text.removePrefix(QR_PREFIX))
            val request = PairingRequest(Server.address(value.getString("base")), value.getString("challenge"),
                value.getString("name"), value.getLong("expires"))
            require(Regex("[0-9a-f]{64}").matches(request.challenge) && request.name.length in 1..48)
            return request
        }
    }
}

internal data class PairingSession(val base: String, val key: String, val name: String, val expires: Long) {
    fun qr(): String = QR_PREFIX + PairingRequest(base, fingerprint(key), name, expires).json()
    fun persist(): String = JSONObject().put("base", base).put("key", key).put("name", name).put("expires", expires).toString()

    companion object {
        fun start(base: String, name: String) = PairingSession(base, newKey(), name, System.currentTimeMillis() + PAIRING_MS)

        fun restore(value: String): PairingSession {
            val data = JSONObject(value)
            return PairingSession(data.getString("base"), data.getString("key"), data.getString("name"), data.getLong("expires"))
        }
    }
}

internal class Pairing(private val activity: MainActivity, private val network: Executor) {
    private val handler = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null

    fun showCode(base: String) {
        val name = android.widget.EditText(activity).apply {
            hint = activity.getString(R.string.device_name)
            setSingleLine()
            filters = arrayOf(android.text.InputFilter.LengthFilter(48))
            setText(Setup.pendingPair(activity)?.takeIf { it.base == base && it.expires > System.currentTimeMillis() }?.name ?: deviceName(activity))
        }
        dialog = AlertDialog.Builder(activity).setTitle(activity.getString(R.string.phone_pair)).setView(name)
            .setNegativeButton(activity.getString(android.R.string.cancel), null)
            .setPositiveButton(activity.getString(R.string.pair_show_qr)) { _, _ -> code(base, name.text.toString().trim().ifBlank { deviceName(activity) }) }
            .show()
    }

    private fun code(base: String, name: String) {
        val session = Setup.pendingPair(activity)?.takeIf {
            it.base == base && it.name == name && it.expires > System.currentTimeMillis()
        } ?: PairingSession.start(base, name)
        Setup.pendingPair(activity, session)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(26, 16, 26, 16)
        }
        content.addView(TextView(activity).apply { text = activity.getString(R.string.pair_instructions) })
        val image = ImageView(activity).apply {
            setImageBitmap(qrBitmap(session.qr()))
            contentDescription = activity.getString(R.string.pair_qr_description)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        content.addView(image, LinearLayout.LayoutParams(-1, (225 * activity.resources.displayMetrics.density).toInt()))
        val state = TextView(activity)
        content.addView(state)
        val box = AlertDialog.Builder(activity).setTitle(activity.getString(R.string.pair_title, name)).setView(content)
            .setNegativeButton(activity.getString(R.string.close), null).show()
        dialog = box
        box.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        var checking = false
        val poll = object : Runnable {
            override fun run() {
                if (!box.isShowing || activity.isDestroyed) return
                val remaining = (session.expires - System.currentTimeMillis() + 999) / 1000
                if (remaining <= 0) {
                    image.visibility = android.view.View.GONE
                    state.text = activity.getString(R.string.pair_expired_hint)
                    return
                }
                state.text = activity.getString(R.string.pair_countdown, remaining / 60, (remaining % 60).toString().padStart(2, '0'))
                handler.postDelayed(this, 2500)
                if (checking) return
                checking = true
                network.execute {
                    val server = Server(session.base, session.key)
                    val link = runCatching {
                        server.claimPairing()?.also { link ->
                            synchronized(Setup) {
                                if (Setup.pendingPair(activity) == session) {
                                    Setup.save(activity, server, link, deviceCredential = true)
                                    Setup.pendingPair(activity, null)
                                }
                            }
                        }
                    }.getOrNull()
                    handler.post {
                        checking = false
                        if (link != null && Setup.server(activity) == server) {
                            if (!activity.isDestroyed) {
                                box.dismiss()
                                activity.pairingComplete()
                            }
                        }
                    }
                }
            }
        }
        box.setOnDismissListener { handler.removeCallbacks(poll) }
        handler.post(poll)
    }

    fun stop() {
        dialog?.dismiss()
        handler.removeCallbacksAndMessages(null)
    }
}

private fun qrBitmap(text: String): Bitmap {
    val size = 540
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
        mapOf(com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8"))
    val pixels = IntArray(size * size)
    for (y in 0 until size) for (x in 0 until size) {
        pixels[y * size + x] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
