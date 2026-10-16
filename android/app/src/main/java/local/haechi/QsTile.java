package local.haechi;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile "Haechi": shows on/off and toggles the filter. */
public class QsTile extends TileService {

    /** The tile instance while the panel is open; the system only calls us while it is "listening". */
    private static volatile QsTile live;
    private static final Handler main = new Handler(Looper.getMainLooper());

    /**
     * Called when the service starts or stops. If the panel is open the tile is redrawn right away
     * (requestListeningState does nothing then); otherwise it is redrawn the next time it is shown.
     */
    static void refresh(Context c) {
        main.post(() -> {
            QsTile t = live;
            if (t != null) t.render(BlockerService.running);
        });
        try {
            TileService.requestListeningState(c, new ComponentName(c, QsTile.class));
        } catch (RuntimeException ignored) {}   // tile not added / not allowed right now
    }

    @Override
    public void onStartListening() {
        live = this;
        render(BlockerService.running);
    }

    @Override
    public void onStopListening() {
        if (live == this) live = null;
    }

    private void render(boolean on) {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (Build.VERSION.SDK_INT >= 29) {
            t.setSubtitle(on ? BlockerService.blocked.get() + "개 차단" : "꺼짐");
        }
        t.updateTile();
    }

    @Override
    public void onClick() {
        if (BlockerService.running) {
            render(false);   // show the new state at once; the service confirms with refresh()
            startService(new Intent(this, BlockerService.class).setAction(BlockerService.ACTION_STOP));
            return;
        }
        if (VpnService.prepare(this) == null) {   // consent already given
            try {
                startForegroundService(new Intent(this, BlockerService.class).setAction(BlockerService.ACTION_START));
                render(true);
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
