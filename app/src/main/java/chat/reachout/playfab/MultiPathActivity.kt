package chat.reachout.playfab

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy


class MultiPathActivity : Activity(), ZeroDataMultiPathConnector.ZeroDataMultiPathListener {

    private lateinit var aSwitch: Switch
    private lateinit var editText: EditText
    private lateinit var connectTextView: TextView
    private lateinit var messageTextView: TextView
    private lateinit var errorTextView: TextView
    private lateinit var sendMessage: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_multi_path)

        aSwitch = findViewById(R.id.switch_id)
        editText = findViewById(R.id.editor)
        connectTextView = findViewById(R.id.connect)
        messageTextView = findViewById(R.id.text)
        errorTextView = findViewById(R.id.error)
        sendMessage = findViewById(R.id.send_message)

        sendMessage.setOnClickListener {
            zeroDataMultiPathService?.sendMessage(editText.text.toString())
            editText.setText("")
        }
    }

    private var zeroDataMultiPathService: ZeroDataMultiPathService? = null
    private var isBound = false

    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as ZeroDataMultiPathService.LocalBinder
            zeroDataMultiPathService = binder.service
            isBound = true
            zeroDataMultiPathService?.registerListener(this@MultiPathActivity)

            zeroDataMultiPathService?.initializeConnector(
                ZeroDataMultiPathConnector.getDeviceIdentifier(
                    this@MultiPathActivity, if (aSwitch.isChecked) {
                        ZeroDataMultiPathConnector.Endpoint.EndpointType.GATEWAY_NODE
                    } else {
                        ZeroDataMultiPathConnector.Endpoint.EndpointType.SUBSCRIBER_NODE
                    }
                ),
                "Multipath Test",
                Strategy.P2P_CLUSTER
            )
            zeroDataMultiPathService?.startAdvertising()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, ZeroDataMultiPathService::class.java)
        bindService(intent, connection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            zeroDataMultiPathService!!.unregisterListener(this)
            unbindService(connection)
            isBound = false
        }
    }

    override fun onAdvertisingStarted() {
        Log.d(MainActivity::class.java.simpleName, "On Advertising Started")
        errorTextView.text = errorTextView.text.toString() + "\n" + "On Advertising Started "
    }

    override fun onAdvertisingFailed() {
        Log.d(MainActivity::class.java.simpleName, "On Advertising Failed")
        errorTextView.text = errorTextView.text.toString() + "\n" + "On Advertising Failed "
    }

    override fun onDiscoveryStarted() {
        Log.d(MainActivity::class.java.simpleName, "On Discovery Started")
        errorTextView.text = errorTextView.text.toString() + "\n" + "On Discovery Started "
    }

    override fun onDiscoveryFailed(throwable: Throwable?) {
        Log.d(MainActivity::class.java.simpleName, "On Discovery Failed $throwable")
        errorTextView.text =
            errorTextView.text.toString() + "\n" + "On Discovery Failed " + throwable?.message
    }

    override fun onEndpointConnected(endpoint: ZeroDataMultiPathConnector.Endpoint?) {
        Log.d(MainActivity::class.java.simpleName, "On Endpoint Connected")
        messageTextView.text =
            messageTextView.text.toString() + "\n" + "On Endpoint Connected " + endpoint?.name
    }

    override fun onEndpointDisconnected(endpoint: ZeroDataMultiPathConnector.Endpoint?) {
        Log.d(MainActivity::class.java.simpleName, "On Endpoint Disconnected")
        messageTextView.text =
            messageTextView.text.toString() + "\n" + "On Endpoint Disconnected " + endpoint?.name
    }

    override fun onEndpointDiscovered(endpoint: ZeroDataMultiPathConnector.Endpoint?) {
        Log.d(MainActivity::class.java.simpleName, "On Endpoint Discovered")
        messageTextView.text =
            messageTextView.text.toString() + "\n" + "On Endpoint Discovered " + endpoint?.name
    }

    override fun onPayloadReceived(endpointId: String, payload: Payload) {
        Log.d(
            MainActivity::class.java.simpleName,
            "On Payload Received: ${String(payload.asBytes()!!)}"
        )

        messageTextView.text =
            messageTextView.text.toString() + "\n" + "On Payload Received " + String(payload.asBytes()!!)
    }

    override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
        Log.d(MainActivity::class.java.simpleName, "On Payload Transfer Updated $endpointId")
        messageTextView.text =
            messageTextView.text.toString() + "\n" + "On Payload Transfer Updated " + endpointId
    }

    override fun onPayloadSent(id: String?) {
        Log.d(MainActivity::class.java.simpleName, "On Payload Sent $id")
        messageTextView.text = messageTextView.text.toString() + "\n" + "On Payload Sent " + id
    }

    override fun onPayloadFailed(throwable: Throwable?, id: String?) {
        Log.d(MainActivity::class.java.simpleName, "On Payload Failed $id $throwable")
        errorTextView.text =
            errorTextView.text.toString() + "\n" + "On Payload Failed " + id + " " + throwable?.message
    }

    override fun onEndpointConnectionInitiated(
        endpoint: ZeroDataMultiPathConnector.Endpoint?,
        connectionInfo: ConnectionInfo
    ) {
        Log.d(
            MainActivity::class.java.simpleName,
            "On Connection Initiated ${endpoint?.id} ${connectionInfo.endpointName}"
        )
        messageTextView.text =
            messageTextView.text.toString() + "\n" + "On Connection Initiated " + { endpoint?.id } + " " + connectionInfo.endpointName
    }

    override fun onEndpointConnectionFailed(throwable: Throwable?) {
        Log.d(
            MainActivity::class.java.simpleName,
            "On Endpoint connection Failed ${throwable?.message}"
        )
        errorTextView.text =
            errorTextView.text.toString() + "\n" + "On Endpoint connection Failed ${throwable?.message}"
    }

}