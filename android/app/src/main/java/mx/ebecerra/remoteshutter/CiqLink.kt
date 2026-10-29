package mx.ebecerra.remoteshutter

import android.content.Context
import android.util.Log
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice

/**
 * Thin wrapper around Garmin's Connect IQ Mobile SDK for Android.
 *
 * How the plumbing actually works (worth knowing, it is not what the name
 * "BLE SDK" suggests):
 *
 *   watch  --BLE-->  Garmin Connect Mobile  --Android binder-->  this app
 *
 * The SDK is a *client* of Garmin Connect Mobile: it binds to the
 * `com.garmin.android.apps.connectmobile` service and exchanges IQMessage
 * parcels. Consequences:
 *
 *   * We never touch the Bluetooth stack, so we need no BLUETOOTH permissions.
 *   * Garmin Connect Mobile must be installed and paired with the watch.
 *   * We do not fight Garmin Connect for the BLE connection, because it is
 *     Garmin Connect that owns it.
 *
 * Registration is deliberately lazy and idempotent: a watch that is asleep at
 * app start gets subscribed as soon as it reconnects, and never twice.
 */
class CiqLink(private val context: Context) {

    companion object {
        /** Must match <iq:application id="..."> in watch/manifest.xml */
        const val APP_UUID = "a4d0bb18b083423f9197ac0c746a3b65"
        private const val TAG = "CiqLink"
    }

    /** Invoked once per message from the watch, already parsed. */
    @Volatile
    var onCommand: ((Protocol.Command) -> Unit)? = null

    /** Invoked with a human readable line for the on-screen status. */
    @Volatile
    var onStatus: ((String) -> Unit)? = null

    private val app = IQApp(APP_UUID)
    private var ciq: ConnectIQ? = null
    private var lastDevice: IQDevice? = null
    private var started = false

    private val watchedDevices = mutableSetOf<Long>()
    private val subscribedApps = mutableSetOf<Long>()

    fun start() {
        if (started) return
        started = true
        val instance = ConnectIQ.getInstance(context, ConnectIQ.IQConnectType.WIRELESS)
        ciq = instance
        instance.initialize(context, true, object : ConnectIQ.ConnectIQListener {
            override fun onSdkReady() {
                status("Connected to Garmin Connect")
                refresh()
            }

            override fun onInitializeError(error: ConnectIQ.IQSdkErrorStatus) {
                status("Garmin Connect problem: $error")
                Log.w(TAG, "initialize error: $error")
            }

            override fun onSdkShutDown() {
                status("Garmin Connect shut down")
            }
        })
    }

    /**
     * Discover the watches Garmin Connect knows about, watch their connection
     * state, and subscribe to app messages from each of them.
     */
    fun refresh() {
        val instance = ciq ?: return
        val devices = try {
            instance.knownDevices
        } catch (e: Exception) {
            status("Cannot list watches: ${e.message}")
            return
        }
        if (devices.isNullOrEmpty()) {
            status("No watch paired in Garmin Connect")
            return
        }
        for (device in devices) watch(instance, device)
        lastDevice = devices.firstOrNull { it.status == IQDevice.IQDeviceStatus.CONNECTED }
            ?: devices.first()
        status("Watch: ${lastDevice?.friendlyName ?: "none"} (${lastDevice?.status})")
    }

    private fun watch(instance: ConnectIQ, device: IQDevice) {
        if (watchedDevices.add(device.deviceIdentifier)) {
            try {
                instance.registerForDeviceEvents(device) { dev, state ->
                    Log.d(TAG, "${dev.friendlyName} -> $state")
                    if (state == IQDevice.IQDeviceStatus.CONNECTED) {
                        subscribeApp(instance, dev)
                        lastDevice = dev
                        status("Watch: ${dev.friendlyName} (connected)")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "device events failed: ${e.message}")
            }
        }
        subscribeApp(instance, device)
    }

    private fun subscribeApp(instance: ConnectIQ, device: IQDevice) {
        if (!subscribedApps.add(device.deviceIdentifier)) return
        try {
            instance.registerForAppEvents(device, app) { dev, _, message, msgStatus ->
                // The SDK hands us the deserialized payload (a List). Protocol
                // owns the interpretation so it can be unit-tested.
                val command = Protocol.parseMessage(message)
                Log.d(TAG, "from ${dev.friendlyName}: $command ($msgStatus)")
                lastDevice = dev
                onCommand?.invoke(command)
            }
            Log.d(TAG, "subscribed on ${device.friendlyName}")
        } catch (e: Exception) {
            subscribedApps.remove(device.deviceIdentifier)
            Log.w(TAG, "subscribe failed on ${device.friendlyName}: ${e.message}")
        }
    }

    /** Send the acknowledgement back to the watch. */
    fun reply(text: String) {
        val instance = ciq ?: return
        val device = lastDevice ?: return
        try {
            instance.sendMessage(device, app, text) { _, _, msgStatus ->
                Log.d(TAG, "reply '$text' -> $msgStatus")
            }
        } catch (e: Exception) {
            Log.w(TAG, "reply failed: ${e.message}")
        }
    }

    private fun status(text: String) {
        onStatus?.invoke(text)
    }
}
