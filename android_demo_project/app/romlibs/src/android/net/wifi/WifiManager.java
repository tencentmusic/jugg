package android.net.wifi;

/**
 * Minimal stand-in for a ROM framework WifiManager. It keeps the SoftApCallback
 * nested type that the public SDK android.jar strips as a hidden API.
 */
public class WifiManager {

    public interface ActionListener {}

    public void connect(int networkId, ActionListener listener) {}

    public void forget(int networkId, ActionListener listener) {}

    public void disconnectForNetId(int networkId) {}

    public void ipConfigurationLostReceived(int networkId) {}

    public abstract static class SoftApCallback {

        public void onStateChanged(int state, int failureReason) {
        }
    }
}
