package de.rennrad.tracker

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import java.util.concurrent.Executors
import kotlin.concurrent.withLock

class MainActivity : Activity() {
    private val network = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var rideCard: LinearLayout
    private lateinit var familyCard: LinearLayout
    private lateinit var pairingHint: TextView
    private lateinit var rideButton: Button
    private lateinit var shareButton: Button
    private lateinit var renewButton: Button
    private lateinit var pairButton: Button
    private lateinit var devicesButton: Button
    private var busy = false
    private var starting = false
    private var migrating = false
    private var triedMigration = false
    private val pairing by lazy { Pairing(this, network) }
    private val dark = Color.rgb(21, 37, 31)
    private val muted = Color.rgb(82, 99, 87)
    private val paper = Color.rgb(244, 245, 238)
    private val green = Color.rgb(9, 109, 75)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            starting = false
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(paper); isFillViewport = true }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val keyboard = insets.getInsets(WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(28))
            isFocusableInTouchMode = true
        }
        scroll.addView(content)
        setContentView(scroll)
        if (Build.VERSION.SDK_INT >= 30) {
            val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(lightBars, lightBars)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        content.requestFocus()

        content.addView(label(getString(R.string.app_name), 31, dark, true), spacing(0))
        rideCard = card(content)
        status = label(getString(R.string.ready), 18, dark, true)
        rideCard.addView(status, spacing(0))
        rideButton = button(getString(R.string.ride_start), true) { toggleRide() }
        rideCard.addView(rideButton, spacing(14))

        familyCard = card(content)
        familyCard.addView(label(getString(R.string.family_link), 19, dark, true), spacing(0))
        familyCard.addView(label(getString(R.string.family_hint), 14, muted), spacing(6))
        shareButton = button(getString(R.string.link_share)) { share() }
        renewButton = button(getString(R.string.link_renew)) { confirmRenewal() }
        familyCard.addView(shareButton, spacing(12))
        familyCard.addView(renewButton, spacing(4))

        val devices = card(content)
        devices.addView(label(getString(R.string.phones), 19, dark, true), spacing(0))
        pairingHint = label(getString(R.string.pair_hint), 14, muted)
        devices.addView(pairingHint, spacing(6))
        pairButton = button(getString(R.string.phone_pair)) { pair() }
        devicesButton = button(getString(R.string.phones_connected)) { showDevices() }
        devices.addView(pairButton, spacing(8))
        devices.addView(devicesButton, spacing(4))

        content.addView(button(getString(R.string.imprint)) {
            openLegalPage(getString(R.string.imprint_path))
        }, spacing(18))
        content.addView(button(getString(R.string.privacy_policy)) {
            openLegalPage(getString(R.string.privacy_policy_path))
        }, spacing(4))

        refresh()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(TrackerService.UPDATE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, filter)
        if (Setup.server(this) != null) SyncJob.enqueue(this)
        migrateExistingSetup()
        refresh()
    }

    override fun onStop() {
        unregisterReceiver(receiver)
        super.onStop()
    }

    override fun onDestroy() {
        pairing.stop()
        network.shutdown()
        super.onDestroy()
    }

    private fun refresh() {
        val configured = Setup.server(this) != null
        val active = TrackerService.active
        if (active) starting = false
        status.text = when {
            active -> getString(when {
                TrackerService.uploadPending -> R.string.upload_pending
                TrackerService.gpsReady -> R.string.ride_active
                else -> R.string.gps_wait
            })
            starting -> getString(R.string.ride_starting)
            else -> getString(R.string.ride_ready)
        }
        rideCard.visibility = if (configured) View.VISIBLE else View.GONE
        familyCard.visibility = rideCard.visibility
        pairingHint.visibility = if (configured) View.GONE else View.VISIBLE
        rideButton.text = if (active) getString(R.string.ride_stop) else getString(R.string.ride_start)
        rideButton.isEnabled = configured && !busy && !starting
        shareButton.isEnabled = Setup.link(this) != null && !busy
        renewButton.isEnabled = configured && !busy && !migrating
        devicesButton.visibility = if (configured) View.VISIBLE else View.GONE
        devicesButton.isEnabled = !busy && !migrating
        pairButton.text = if (configured) getString(R.string.pair_scan) else getString(R.string.pair_with_qr)
        pairButton.isEnabled = !busy && !migrating
    }

    private fun migrateExistingSetup() {
        val server = Setup.server(this) ?: return
        if (triedMigration || Setup.hasDeviceCredential(this)) return
        triedMigration = true
        migrating = true
        network.execute {
            runCatching { server.installDeviceCredential(applicationContext) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                migrating = false
                refresh()
            }
        }
    }

    private fun pair() {
        if (Setup.server(this) != null) {
            val options = ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setCaptureActivity(PairScannerActivity::class.java)
                .setPrompt(getString(R.string.pair_scan_prompt)).setBeepEnabled(false)
            @Suppress("DEPRECATION")
            startActivityForResult(options.createScanIntent(this), SCAN)
        } else {
            pairing.showCode(BuildConfig.TRACKER_SERVER_URL)
        }
    }

    @Deprecated("ZXing result is received through the platform activity callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SCAN) return
        val value = ScanIntentResult.parseActivityResult(resultCode, data).contents ?: return
        val server = Setup.server(this) ?: return
        val request = runCatching { PairingRequest.scan(value) }.getOrElse {
            tell(getString(R.string.pair_invalid), getString(R.string.pair_invalid_hint))
            return
        }
        if (request.base != server.base) {
            tell(getString(R.string.pair_wrong), getString(R.string.pair_wrong_hint))
            return
        }
        android.app.AlertDialog.Builder(this).setTitle(getString(R.string.phone_add_question))
            .setMessage(getString(R.string.phone_access, request.name))
            .setNegativeButton(getString(android.R.string.cancel), null)
            .setPositiveButton(getString(R.string.phone_add)) { _, _ ->
                deviceAction({ server.approve(request) }) { tell(getString(R.string.phone_approved), getString(R.string.phone_approved_hint)) }
            }.show()
    }

    private fun showDevices() {
        val server = Setup.server(this) ?: return
        deviceAction(server::devices) { result ->
            val current = result.getString("current")
            val devices = result.getJSONArray("devices")
            val items = (0 until devices.length()).map(devices::getJSONObject)
            if (items.isEmpty()) {
                tell(getString(R.string.phones_connected), getString(R.string.phones_unavailable))
                return@deviceAction
            }
            val labels = items.map { device ->
                val name = device.getString("name")
                if (device.getString("id") == current) getString(R.string.this_phone, name) else name
            }
            android.app.AlertDialog.Builder(this).setTitle(getString(R.string.phones_connected))
                .setItems(labels.toTypedArray()) { _, index ->
                    val target = items[index]
                    val id = target.getString("id")
                    if (id == current) return@setItems
                    android.app.AlertDialog.Builder(this).setTitle(getString(R.string.phone_remove_question))
                        .setMessage(getString(R.string.phone_remove_hint, target.getString("name")))
                        .setNegativeButton(getString(android.R.string.cancel), null)
                        .setPositiveButton(getString(R.string.remove)) { _, _ -> deviceAction({ server.revokeDevice(id) }) { showDevices() } }
                        .show()
                }.setNegativeButton(getString(R.string.close), null).show()
        }
    }

    private fun <T> deviceAction(action: () -> T, done: (T) -> Unit) {
        busy = true
        refresh()
        network.execute {
            val result = runCatching(action)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busy = false
                refresh()
                result.onSuccess(done).onFailure { error ->
                    val message = if (error is ServerError) error.text(this) else getString(R.string.server_unreachable)
                    tell(getString(R.string.server), message)
                }
            }
        }
    }

    private fun tell(title: String, message: String) {
        android.app.AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton(getString(android.R.string.ok), null).show()
    }

    internal fun pairingComplete() {
        refresh()
        tell(getString(R.string.phone_paired), getString(R.string.phone_paired_hint))
    }

    private fun toggleRide() {
        if (TrackerService.active) {
            startService(Intent(this, TrackerService::class.java).setAction(TrackerService.STOP))
            return
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), LOCATION)
            return
        }
        askNotifications()
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getPreferences(MODE_PRIVATE).getBoolean("askedNotifications", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("askedNotifications", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATIONS)
        } else startRide()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        when (requestCode) {
            LOCATION -> if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) askNotifications()
                else status.text = getString(R.string.gps_precise)
            NOTIFICATIONS -> startRide()
        }
    }

    private fun startRide() {
        if (!getSystemService(LocationManager::class.java).isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            status.text = getString(R.string.gps_enable)
            return
        }
        starting = true
        refresh()
        try {
            startForegroundService(Intent(this, TrackerService::class.java))
            rideButton.postDelayed({ if (starting) { starting = false; refresh() } }, 5_000)
        } catch (_: Exception) {
            starting = false
            refresh()
            status.text = getString(R.string.ride_start_error)
        }
    }

    private fun share() {
        updateFamilyLink(renew = false)
    }

    private fun openLegalPage(path: String) {
        val base = Setup.server(this)?.base ?: BuildConfig.TRACKER_SERVER_URL
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(base + path)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.browser_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareLink(link: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), getString(R.string.family_share)))
    }

    private fun confirmRenewal() {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.family_renew))
            .setMessage(getString(R.string.family_renew_hint))
            .setNegativeButton(getString(android.R.string.cancel), null)
            .setPositiveButton(getString(R.string.renew)) { _, _ ->
                updateFamilyLink(renew = true)
            }.show()
    }

    private fun updateFamilyLink(renew: Boolean) {
        deviceAction({
            Sync.lock.withLock {
                val server = checkNotNull(Setup.server(this))
                server.familyLink(renew).also { Setup.save(this, server, it) }
            }
        }, ::shareLink)
    }

    private fun label(text: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun button(text: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        minimumHeight = dp(48)
        if (primary) {
            backgroundTintList = android.content.res.ColorStateList.valueOf(green)
            setTextColor(Color.WHITE)
        }
        setOnClickListener { action() }
    }

    private fun card(parent: LinearLayout) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(16).toFloat() }
        setPadding(dp(16), dp(16), dp(16), dp(16))
        parent.addView(this, spacing(14))
    }

    private fun spacing(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun dp(value: Int) = (resources.displayMetrics.density * value + .5f).toInt()

    companion object {
        private const val LOCATION = 1
        private const val NOTIFICATIONS = 2
        private const val SCAN = 3
    }
}
