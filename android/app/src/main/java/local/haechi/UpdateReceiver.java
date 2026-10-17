package local.haechi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

/**
 * Installing a new version kills the app and with it the filter. Android then sends
 * MY_PACKAGE_REPLACED (only to us); if the filter was on before, switch it back on. Starting a
 * foreground service from this broadcast is one of Android's allowed background starts.
 */
public class UpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) return;
        ListUpdater.schedule(c);   // the job survives updates, but make sure it exists
        if (!Settings.wantOn(c) || BlockerService.running) return;
        if (VpnService.prepare(c) != null) return;   // VPN permission gone: needs the user
        try {
            c.startForegroundService(new Intent(c, BlockerService.class).setAction(BlockerService.ACTION_START));
        } catch (RuntimeException ignored) {}   // background start refused: the user turns it on
    }
}
