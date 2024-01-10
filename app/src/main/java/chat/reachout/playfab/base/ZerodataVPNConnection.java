package chat.reachout.playfab.base;

import android.app.PendingIntent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.Pipe;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public class ZerodataVPNConnection implements Runnable {
    /**
     * Callback interface to let the {@link ZerodataMultipathSubscriberVPNService} know about new connections
     * and update the foreground notification with connection status.
     */
    public interface OnEstablishListener {
        void onEstablish(ParcelFileDescriptor tunInterface);
    }

    /**
     * Maximum packet size is constrained by the MTU, which is given as a signed short.
     */
    private static final int MAX_PACKET_SIZE = Short.MAX_VALUE;

    /**
     * Time to wait in between losing the connection and retrying.
     */
    private static final long RECONNECT_WAIT_MS = TimeUnit.SECONDS.toMillis(3);
    /**
     * Time between keepalives if there is no traffic at the moment.
     * <p>
     * TODO: don't do this; it's much better to let the connection die and then reconnect when
     *       necessary instead of keeping the network hardware up for hours on end in between.
     **/
    private static final long KEEPALIVE_INTERVAL_MS = TimeUnit.SECONDS.toMillis(15);
    /**
     * Time to wait without receiving any response before assuming the server is gone.
     */
    private static final long RECEIVE_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(20);
    /**
     * Time between polling the VPN interface for new traffic, since it's non-blocking.
     * <p>
     * TODO: really don't do this; a blocking read on another thread is much cleaner.
     */
    private static final long IDLE_INTERVAL_MS = TimeUnit.MILLISECONDS.toMillis(100);
    /**
     * Number of periods of length {@IDLE_INTERVAL_MS} to wait before declaring the handshake a
     * complete and abject failure.
     * <p>
     * TODO: use a higher-level protocol; hand-rolling is a fun but pointless exercise.
     */
    private static final int MAX_HANDSHAKE_ATTEMPTS = 50;

    private final ZerodataMultipathSubscriberVPNService mService;
    private PendingIntent mConfigureIntent;
    private OnEstablishListener mOnEstablishListener;

    private Pipe.SourceChannel mSourceChannel;

    public ZerodataVPNConnection(final ZerodataMultipathSubscriberVPNService service, Pipe.SourceChannel sinkChannel) {
        mService = service;
        mSourceChannel = sinkChannel;
        Log.d("VPN Connection", "********* Starting VPN Connection II");
    }

    /**
     * Optionally, set an intent to configure the VPN. This is {@code null} by default.
     */
    public void setConfigureIntent(PendingIntent intent) {
        mConfigureIntent = intent;
    }

    public void setOnEstablishListener(OnEstablishListener listener) {
        mOnEstablishListener = listener;
    }

    @Override
    public void run() {
        try {
            Log.i(getTag(), "Starting");
            // We try to create the tunnel several times.
            // TODO: The better way is to work with ConnectivityManager, trying only when the
            // network is available.
            // Here we just use a counter to keep things simple.
            for (int attempt = 0; attempt < 10; ++attempt) {
                // Reset the counter if we were connected.

                // mService.sendPacketPayload();

                if (runner()) {
                    attempt = 0;
                }
                // Sleep for a while. This also checks if we got interrupted.
                Thread.sleep(3000);
            }
            Log.i(getTag(), "Giving up");
        } catch (IOException | InterruptedException | IllegalArgumentException e) {
            Log.e(getTag(), "Connection failed, exiting", e);
        }
    }


    private boolean runner()
            throws IOException, InterruptedException, IllegalArgumentException {


        ParcelFileDescriptor iface = null;
        boolean connected = false;


        Log.d("VPN Connection", "********* Starting VPN Connection III");

        try {

            // Authenticate and configure the virtual network interface.
            iface = handshake();
            // Now we are connected. Set the flag.
            connected = true;
            // Packets to be sent are queued in this input stream.
            FileChannel in = new FileInputStream(iface.getFileDescriptor()).getChannel();
            // Packets received need to be written to this output stream.
            FileOutputStream out = new FileOutputStream(iface.getFileDescriptor());
            // Allocate the buffer for a single packet.
            ByteBuffer packet = ByteBuffer.allocate(MAX_PACKET_SIZE);

            // Timeouts:
            //   - when data has not been sent in a while, send empty keepalive messages.
            //   - when data has not been received in a while, assume the connection is broken.
            long lastSendTime = System.currentTimeMillis();
            long lastReceiveTime = System.currentTimeMillis();
            // We keep forwarding packets till something goes wrong.

            while (true) {
                // Assume that we did not make any progress in this iteration.
                boolean idle = true;
                // Read the outgoing packet from the input stream.
                // int length = in.read(packet.array());
                int length = in.read(packet);
                if (length > 0) {
                    // Write the outgoing packet to the tunnel.
                    packet.flip();

                    Log.d(getTag(), "************ Packet from kernel received - routing to UDP: " + length);

                    // int count = tunnel.write(packet);

                    byte[] packetArr = Arrays.copyOf(packet.array(), packet.remaining());
                    mService.sendPacketPayload(packetArr);

                    int count = packetArr.length;

                    packet.clear();

                    Log.d(getTag(), "************ Finished writing packet to UDP: " + count);

                    // There might be more outgoing packets.
                    idle = false;
                    //Original below:
                    // lastReceiveTime = System.currentTimeMillis();
                    // Our's below:
                    lastSendTime = System.currentTimeMillis();
                }

                length = mSourceChannel.read(packet);

                if (length > 0) {
                    Log.d(getTag(), "************ Packet from VPN headed ENd received- routing: " + length);
                    // Ignore control messages, which start with zero.
//                    if (packet.get(0) != 0) {
//
//                    }

                    // Write the incoming packet to the output stream.
                    packet.flip();
                    byte[] packetBytes = Arrays.copyOf(packet.array(), packet.remaining());
                    Log.d(getTag(), "************ Packet is to be written " + packetBytes.length);
                    out.write(packetBytes, 0, length);
                    packet.clear();

                    // There might be more incoming packets.
                    idle = false;
                    //Original below:
                    // lastSendTime = System.currentTimeMillis();
                    // Our's below
                    lastReceiveTime = System.currentTimeMillis();
                }

                if (idle) {
                    Thread.sleep(IDLE_INTERVAL_MS);
                    final long timeNow = System.currentTimeMillis();
                    if (lastSendTime + KEEPALIVE_INTERVAL_MS <= timeNow) {
                        // We are receiving for a long time but not sending.
                        // Send empty control messages.
                        packet.put((byte) 0).limit(1);

                        packet.clear();
                        lastSendTime = timeNow;
                    } else if (lastReceiveTime + RECEIVE_TIMEOUT_MS <= timeNow) {
                        // We are sending for a long time but not receiving.
                        // throw new IllegalStateException("Timed out");
                    }
                }
            }

        } catch (SocketException e) {
            Log.e(getTag(), "Cannot use socket", e);
        } catch (Exception e) {
            Log.e(getTag(), "Cannot use socket", e);
        } finally {
            if (iface != null) {
                try {
                    Log.i(getTag(), "SHUTTING DOWN INTERFACE");
                    iface.close();
                } catch (IOException e) {
                    Log.e(getTag(), "Unable to close interface", e);
                }
            }
        }
        return connected;
    }

    // pass in a ref to the Nearby Connection holder
    private ParcelFileDescriptor handshake()
            throws IOException, InterruptedException {
        // Wait for the parameters within a limited time.
        for (int i = 0; i < MAX_HANDSHAKE_ATTEMPTS; ++i) {
            Thread.sleep(IDLE_INTERVAL_MS);
            Log.d(getTag(), "WAIT FOR HANDSHAKE PARAMS FROM SERVER. Count: %d" + i);
            return configure();
        }
        throw new IOException("Timed out");
    }

    private ParcelFileDescriptor configure() throws IllegalArgumentException {
        // Configure a builder while parsing the parameters.
        VpnService.Builder builder = mService.new Builder();

        builder.setMtu(1420);
        builder.addAddress("10.10.0.2", 32);
        builder.addRoute("0.0.0.0", 0);
        builder.addDnsServer("4.2.2.2");
        // builder.addSearchDomain("");

        // Create a new interface using the builder and save the parameters.
        final ParcelFileDescriptor vpnInterface;

        builder.setSession("ZerodataVPN").setConfigureIntent(mConfigureIntent);

        synchronized (mService) {
            vpnInterface = builder.establish();
            if (mOnEstablishListener != null) {
                mOnEstablishListener.onEstablish(vpnInterface);
            }
        }
        Log.i(getTag(), "New interface: " + vpnInterface);
        return vpnInterface;
    }

    private final String getTag() {
        return ZerodataVPNConnection.class.getSimpleName() + "[" + "ZeordataConnectionID" + "]" + "##################";
    }
}