package android.net.wifi;

/**
 * Minimal stand-in for a ROM framework WifiManager. It keeps the SoftApCallback
 * nested type that the public SDK android.jar strips as a hidden API.
 */
public class WifiManager {

    public abstract static class SoftApCallback {

        public void onStateChanged(int state, int failureReason) {
        }
    }
}
