package be.casperverswijvelt.unifiedinternetqs.tiles

import android.graphics.drawable.Icon
import android.os.Handler
import android.service.quicksettings.Tile
import android.util.Log
import be.casperverswijvelt.unifiedinternetqs.R
import be.casperverswijvelt.unifiedinternetqs.data.BITPreferences
import be.casperverswijvelt.unifiedinternetqs.listeners.CellularChangeListener
import be.casperverswijvelt.unifiedinternetqs.listeners.NetworkChangeCallback
import be.casperverswijvelt.unifiedinternetqs.listeners.NetworkChangeType
import be.casperverswijvelt.unifiedinternetqs.listeners.WifiChangeListener
import be.casperverswijvelt.unifiedinternetqs.util.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class InternetTileService : ReportingTileService() {

    private companion object {
        const val TAG = "InternetTile"
    }

    private var wifiConnected = false
    private var wifiSSID: String? = null
    private lateinit var preferences: BITPreferences

    private var isTurningOnData = false
    private var isTurningOnWifi = false

    private val runCycleInternet = Runnable {
        cycleInternet()
        syncTile()
    }
    private val cellularChangeCallback = object : NetworkChangeCallback {
        override fun handleChange(type: NetworkChangeType?) {
            syncTile()
        }
    }
    private val wifiChangeCallback = object : NetworkChangeCallback {
        override fun handleChange(type: NetworkChangeType?) {
            when (type) {
                NetworkChangeType.NETWORK_LOST -> {
                    wifiConnected = false
                    wifiSSID = null
                    setLastConnectedWifi(applicationContext, wifiSSID)
                }
                NetworkChangeType.NETWORK_AVAILABLE -> {
                    wifiConnected = true
                    getConnectedWifiSSID(applicationContext) {
                        wifiSSID = it
                        setLastConnectedWifi(applicationContext, wifiSSID)
                        syncTile()
                    }
                }
                else -> {}
            }
            syncTile()
        }
    }

    private var wifiChangeListener: WifiChangeListener? = null
    private var cellularChangeListener: CellularChangeListener? = null
    private var mainHandler: Handler? = null

    override fun onCreate() {
        super.onCreate()
        log("Internet tile service created")

        mainHandler = Handler(mainLooper)

        wifiChangeListener = WifiChangeListener(wifiChangeCallback)
        cellularChangeListener = CellularChangeListener(cellularChangeCallback)
        preferences = BITPreferences(this)

        runBlocking {
            wifiSSID = preferences.getLastConnectedSSID.first()
        }
    }

    override fun onStartListening() {
        super.onStartListening()

        syncTile()
        setListeners()
    }


    override fun onStopListening() {
        super.onStopListening()

        removeListeners()
    }

    override fun onClick() {
        super.onClick()

        if (!hasShellAccess(applicationContext)) {

            // Either root or Shizuku access is needed to enable/disable mobile data and Wi-Fi.
            //  There is currently no other way to do this, so this functionality will not work
            //  without root Shizuku access.
            showDialog(getShellAccessRequiredDialog(applicationContext))
            return
        }

        runBlocking {
            if (preferences.getRequireUnlock.first()) {

                unlockAndRun(runCycleInternet)

            } else {

                mainHandler?.post(runCycleInternet)
            }
        }
    }

    private fun cycleInternet() {

        // Cycle trough internet connection modes:
        //  If Wi-Fi is enabled -> disable Wi-Fi and enable mobile data
        //  If mobile data is enabled -> disable mobile data and enable Wi-Fi
        //  Else -> enable Wi-Fi

        val dataEnabled = getDataEnabled(applicationContext)
        val wifiEnabled = getWifiEnabled(applicationContext)

        isTurningOnData = false
        isTurningOnWifi = false

        when {
            wifiEnabled -> {
                executeShellCommandAsync("svc wifi disable")

                isTurningOnData = true
                executeShellCommandAsync("svc data enable") {
                    if (it?.isSuccess != true) {
                        isTurningOnData = false
                    }
                    syncTile()
                }
            }
            dataEnabled -> {
                executeShellCommandAsync("svc data disable")

                isTurningOnWifi = true
                executeShellCommandAsync("svc wifi enable") {
                    if (it?.isSuccess != true) {
                        isTurningOnWifi = false
                    }
                    syncTile()
                }
            }
            else -> {
                isTurningOnWifi = true
                executeShellCommandAsync("svc wifi enable") {
                    if (it?.isSuccess != true) {
                        isTurningOnWifi = false
                    }
                }
            }
        }
    }

    private fun syncTile() {

        qsTile?.let {

            val dataEnabled = getDataEnabled(applicationContext)
            val wifiEnabled = getWifiEnabled(applicationContext)

            when {
                (isTurningOnWifi || wifiEnabled) && !isTurningOnData -> {

                    if (wifiEnabled) {
                        isTurningOnWifi = false
                    }

                    it.state = Tile.STATE_ACTIVE
                    it.icon = getWifiIcon(applicationContext)
                    it.label = if (isTurningOnWifi)
                        resources.getString(R.string.turning_on)
                    else
                        (if (wifiConnected) wifiSSID else null)
                            ?: resources.getString(R.string.not_connected)
                }
                isTurningOnData || dataEnabled -> {

                    if (dataEnabled) {
                        isTurningOnData = false
                    }

                    it.state = Tile.STATE_ACTIVE
                    it.icon = getCellularNetworkIcon(applicationContext)
                    it.label = getCellularNetworkText(
                        applicationContext,
                        cellularChangeListener?.currentTelephonyDisplayInfo
                    )
                }
                else -> {

                    it.state = Tile.STATE_INACTIVE
                    it.icon = Icon.createWithResource(
                        applicationContext,
                        R.drawable.ic_baseline_public_off_24
                    )
                    it.label = resources.getString(R.string.internet)
                }
            }

            it.updateTile()
        }
    }

    private fun setListeners() {

        log("Setting listeners")

        // set wifiConnected to false, it will be updated asynchronously
        //  after starting to listen to the wifiChangeListener.
        wifiConnected = false
        isTurningOnWifi = false
        isTurningOnData = false

        wifiChangeListener?.startListening(applicationContext)
        cellularChangeListener?.startListening(applicationContext)
    }

    private fun removeListeners() {

        log("Removing listeners")

        wifiChangeListener?.stopListening(applicationContext)
        cellularChangeListener?.stopListening(applicationContext)
    }

    private fun log(text: String) {
        Log.d(TAG, text)
    }
}