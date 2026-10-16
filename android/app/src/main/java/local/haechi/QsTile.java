package local.haechi;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile "광고 차단": shows on/off and toggles the filter. */
public class QsTile extends TileService {

    /** Ask the system to refresh the tile (called when the service starts or stops). */
    static void refresh(Context c) {
        try {
            TileService.requestListeningState(c, new ComponentName(c, QsTile.class));
        } catch (RuntimeException ignored) {}   // tile not added / not allowed right now
    }

    @Override
    public void onStartListening() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean on = BlockerService.running;
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (Build.VERSION.SDK_INT >= 29) {
            t.setSubtitle(on ? BlockerService.blocked.get() + "개 차단" : "꺼짐");
        }
        t.updateTile();
    }

    @Override
    public void onClick() {
        if (BlockerService.running) {
            startService(new Intent(this, BlockerService.class).setAction(BlockerService.ACTION_STOP));
            return;
        }
        if (VpnService.prepare(this) == null) {   // consent already given
            try {
                startForegroundService(new Intent(this, BlockerService.class).setAction(BlockerService.ACTION_START));
                return;
            } catch (RuntimeException e) {
                // e.g. background-start restriction: fall through and let the app start it
            }
        }
        // Consent needed: open the app, which asks for it and then starts the filter.
        Intent open = new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_AUTO_START, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 2, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapseLegacy(open);
        }
    }

    /** Android 8-13 only (the caller checks SDK_INT < 34, where this overload still works). */
    @SuppressWarnings("deprecation")
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private void startActivityAndCollapseLegacy(Intent i) {
        startActivityAndCollapse(i);
    }
}
