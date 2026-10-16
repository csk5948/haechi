package local.haechi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.ProxyInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local DNS filter built on Android's VpnService API. It is NOT a real VPN:
 *
 *  - The system is told "the DNS server is 10.111.222.2", and only that single address is
 *    routed into our tunnel. All other traffic goes straight to Wi-Fi / mobile as usual.
 *  - Each DNS query that arrives is either answered locally with 0.0.0.0 / :: (blocked), or
 *    forwarded unchanged to the real DNS server of the current network (allowed).
 *
 * The only network traffic this app makes is that forwarding, in {@link #forward}.
 */
public class BlockerService extends VpnService {
    static final String ACTION_START = "local.haechi.START";
    static final String ACTION_STOP = "local.haechi.STOP";

    private static final String TAG = "haechi";
    private static final String TUN_ADDRESS = "10.111.222.1";
    private static final String FAKE_DNS = "10.111.222.2";
    private static final String FALLBACK_DNS = "1.1.1.1";
    private static final int UPSTREAM_TIMEOUT_MS = 1500;
    private static final String CHANNEL = "filter";
    private static final int NOTIFICATION_ID = 1;

    // Shared with the UI (same process).
    static volatile boolean running;
    static final AtomicInteger queries = new AtomicInteger();
    static final AtomicInteger blocked = new AtomicInteger();
    static final AtomicInteger dohFailures = new AtomicInteger();   // DoH failed -> used carrier DNS

    private ParcelFileDescriptor tun;
    private FileOutputStream tunOut;
    private Thread reader;
    private ThreadPoolExecutor pool, local;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback netCallback;
    private volatile InetAddress[] upstreams = new InetAddress[0];
    private volatile Network upstreamNet;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int shownBlocked = -1;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Anything other than STOP means start: our own button, the system's "always-on VPN"
        // (action android.net.VpnService), or a sticky restart with a null intent.
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (running) return START_STICKY;
        startInForeground();
        if (!startFilter()) {
            shutdown();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onRevoke() {   // another VPN took over, or the user revoked us in settings
        shutdown();
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private boolean startFilter() {
        try {
            Lists.load(this);
        } catch (IOException e) {
            Log.w(TAG, "list load failed", e);
        }
        // Bundled lists load in the background; until then only the user's own lists apply.
        Bundled.ensureLoaded(this);
        if (VpnService.prepare(this) != null) return false;   // user consent missing

        Builder b = new Builder()
                .setSession(getString(R.string.app_name))
                .addAddress(TUN_ADDRESS, 32)
                .addDnsServer(FAKE_DNS)
                .addRoute(FAKE_DNS, 32)        // ONLY the fake DNS address enters the tunnel
                .setBlocking(true)
                .setConfigureIntent(openAppIntent());
        // Without this, Android blocks all IPv6 traffic because we configured no IPv6 at all.
        b.allowFamily(OsConstants.AF_INET6);
        // Otherwise Android 10+ would treat every network as metered while we run.
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false);

        // Optional: hand browsers a local proxy that splits the TLS ClientHello (API 29+).
        splitStatus = "";
        if (Settings.splitHello(this)) {
            if (Build.VERSION.SDK_INT < 29) {
                splitStatus = "Android 10 이상에서만 지원됩니다";
            } else {
                SplitProxy.mode = Settings.splitMode(this);
                SplitProxy p = new SplitProxy(this::protect);
                int port = p.start();
                if (port > 0) {
                    b.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", port));
                    splitProxy = p;
                    splitStatus = "VPN에 프록시 지정됨: 127.0.0.1:" + port;
                } else {
                    splitStatus = "중계기를 시작하지 못했습니다 (프록시 지정 안 됨)";
                }
            }
        }

        try {
            tun = b.establish();
        } catch (Exception e) {
            Log.w(TAG, "establish failed", e);
            tun = null;
        }
        if (tun == null) {
            stopSplitProxy();
            return false;
        }
        tunOut = new FileOutputStream(tun.getFileDescriptor());

        SplitProxy.connections.set(0);
        SplitProxy.splits.set(0);
        SplitProxy.splitFails.set(0);
        queries.set(0);
        blocked.set(0);
        dohFailures.set(0);
        Settings.load(this);
        running = true;
        SamsungBlocker.notifyUpdate(this);   // Samsung Internet: load the rules again, they apply only while we run

        cm = getSystemService(ConnectivityManager.class);
        refreshUpstream();
        // Default NetworkRequest excludes VPNs, so these are the real Wi-Fi / mobile networks.
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { refreshUpstream(); }
            @Override public void onLost(Network n) { refreshUpstream(); }
            @Override public void onLinkPropertiesChanged(Network n, LinkProperties lp) { refreshUpstream(); }
        };
        try {
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), netCallback);
        } catch (RuntimeException e) {
            netCallback = null;
        }

        // Small pool for upstream lookups; if it is ever saturated, extra queries are dropped
        // (the asking app simply retries) instead of piling up without limit.
        pool = new ThreadPoolExecutor(8, 8, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(256), new ThreadPoolExecutor.DiscardPolicy());
        pool.allowCoreThreadTimeOut(true);
        // One thread for blocked answers + app lookup, so they never wait behind slow upstream queries.
        local = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(256), new ThreadPoolExecutor.DiscardPolicy());
        local.allowCoreThreadTimeOut(true);

        reader = new Thread(this::readLoop, "dns-reader");
        reader.start();
        main.post(notificationUpdater);
        QsTile.refresh(this);
        return true;
    }

    /** Idempotent: safe to call from STOP, onRevoke and onDestroy. */
    private synchronized void shutdown() {
        boolean wasRunning = running;
        running = false;
        main.removeCallbacks(notificationUpdater);
        if (netCallback != null && cm != null) {
            try { cm.unregisterNetworkCallback(netCallback); } catch (RuntimeException ignored) {}
            netCallback = null;
        }
        if (pool != null) { pool.shutdownNow(); pool = null; }
        if (local != null) { local.shutdownNow(); local = null; }
        stopSplitProxy();
        if (tun != null) {
            try { tun.close(); } catch (IOException ignored) {}
            tun = null;
        }
        reader = null;   // it exits on its own within one poll interval
        QsTile.refresh(this);
        if (wasRunning) SamsungBlocker.notifyUpdate(this);   // rules switch off together with the filter
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    static volatile String splitStatus = "";
    private SplitProxy splitProxy;

    private void stopSplitProxy() {
        if (splitProxy != null) { splitProxy.stop(); splitProxy = null; }
    }

    // ---------------------------------------------------------------- packet handling

    private void readLoop() {
        ParcelFileDescriptor fd = tun;
        if (fd == null) return;
        FileInputStream in = new FileInputStream(fd.getFileDescriptor());
        StructPollfd pfd = new StructPollfd();
        pfd.fd = fd.getFileDescriptor();
        pfd.events = (short) OsConstants.POLLIN;
        byte[] buf = new byte[32767];
        while (running) {
            try {
                // Wait at most 1 s so that "running = false" is noticed promptly.
                if (Os.poll(new StructPollfd[]{pfd}, 1000) <= 0) continue;
                int n = in.read(buf);
                if (n > 0) handle(Arrays.copyOf(buf, n), n);
            } catch (ErrnoException e) {
                if (e.errno != OsConstants.EINTR) break;
            } catch (IOException e) {
                break;   // tunnel closed
            }
        }
    }

    private void handle(byte[] packet, int len) {
        Dns.Query q = Dns.parse(packet, len);
        if (q == null) return;                 // not a UDP DNS query to our fake server: drop
        queries.incrementAndGet();
        if (q.name != null) {
            QueryLog.record(q.name);
            Wildcards.seen(this, q.name);   // a new mirror of an "aa*.com##..." browser rule?
            if (Lists.isBlocked(q.name)) {
                blocked.incrementAndGet();
                byte[] answer = Dns.blockedAnswer(q);
                byte[] reply = Dns.wrapReply(q, answer, answer.length);
                ThreadPoolExecutor l = local;
                if (Build.VERSION.SDK_INT >= 29 && l != null) {
                    // Look up the app first: the asking socket is surely still open before we answer.
                    l.execute(() -> { attribute(q); writeTun(reply); });
                } else {
                    writeTun(reply);
                }
                return;
            }
        }
        ThreadPoolExecutor p = pool;
        if (p != null) p.execute(() -> forward(q));
    }

    /**
     * The network calls of this app: send the unchanged DNS query upstream.
     * 1) If an encrypted DNS is selected: DNS-over-HTTPS to 1.1.1.1 / 8.8.8.8.
     * 2) Otherwise, or if that fails: plain UDP to the carrier / Wi-Fi DNS server.
     */
    private void forward(Dns.Query q) {
        attribute(q);                          // local binder call, before the asking socket can close
        String doh = Settings.dohUrl;
        if (doh != null && System.currentTimeMillis() >= dohPausedUntil) {
            // Try the IPv4/IPv6 address that worked last, then the other one.
            String[] urls = Doh.endpoints(doh);
            int first = dohPreferred;
            for (int i = 0; i < urls.length; i++) {
                int idx = (first + i) % urls.length;
                try {
                    URL url = new URL(urls[idx]);
                    Network net = upstreamNet;     // bind to the real network: can never enter our tunnel
                    HttpURLConnection c = (HttpURLConnection) (net != null ? net.openConnection(url) : url.openConnection());
                    byte[] reply = Doh.exchange(c, q.packet, q.dnsOffset, q.dnsLength);
                    writeTun(Dns.wrapReply(q, reply, reply.length));
                    dohPreferred = idx;
                    dohStreak = 0;
                    dohStatus = idx == 0 ? "IPv4" : "IPv6";
                    return;
                } catch (IOException | RuntimeException e) {
                    dohFailures.incrementAndGet();
                    lastDohError = (idx == 0 ? "IPv4: " : "IPv6: ") + e.getClass().getSimpleName() + " " + e.getMessage()
                            + " · net=" + (upstreamNet == null ? "없음" : String.valueOf(upstreamNet));
                }
            }
            // Both failed: after 3 queries in a row, stop trying for a minute so every lookup
            // doesn't wait for timeouts (that is what makes "the internet stop").
            if (++dohStreak >= 3) {
                dohPausedUntil = System.currentTimeMillis() + 60_000;
                dohStreak = 0;
                dohStatus = "실패 반복 → 1분간 통신사 DNS";
            }
        }
        // Plain DNS: the network's own servers (IPv4 or IPv6), then public fallbacks.
        for (InetAddress up : upstreams) {
            try (DatagramSocket s = new DatagramSocket(new InetSocketAddress(
                    up instanceof Inet4Address ? InetAddress.getByName("0.0.0.0") : InetAddress.getByName("::"), 0))) {
                protect(s);                        // make sure it never loops back into our tunnel
                s.setSoTimeout(UPSTREAM_TIMEOUT_MS);
                s.send(new DatagramPacket(q.packet, q.dnsOffset, q.dnsLength, up, 53));
                byte[] buf = new byte[8192];
                DatagramPacket reply = new DatagramPacket(buf, buf.length);
                s.receive(reply);
                writeTun(Dns.wrapReply(q, buf, reply.getLength()));
                return;
            } catch (IOException e) {
                // timeout / unreachable: try the next server
            }
        }
    }

    private volatile int dohPreferred, dohStreak;
    private volatile long dohPausedUntil;
    static volatile String dohStatus = "";
    static volatile String lastDohError = "";

    // ---------------------------------------------------------------- which app asked (Android 10+)

    private final HashMap<Integer, String> appNames = new HashMap<>();

    /**
     * Asks Android which app owns the UDP socket that sent this query (allowed for the active
     * VPN app) and records it in the query log. No network traffic: local system call only.
     */
    private void attribute(Dns.Query q) {
        if (Build.VERSION.SDK_INT < 29 || q.name == null || !QueryLog.isRecording()) return;
        int uid = Process.INVALID_UID;
        try {
            byte[] p = q.packet;
            InetAddress src = InetAddress.getByAddress(Arrays.copyOfRange(p, 12, 16));
            InetAddress dst = InetAddress.getByAddress(Arrays.copyOfRange(p, 16, 20));
            uid = cm.getConnectionOwnerUid(OsConstants.IPPROTO_UDP,
                    new InetSocketAddress(src, Dns.u16(p, q.ipHeaderLen)),
                    new InetSocketAddress(dst, 53));
        } catch (IOException | RuntimeException ignored) {}
        QueryLog.addApp(q.name, appName(uid));
    }

    private String appName(int uid) {
        if (uid == Process.INVALID_UID || uid < Process.FIRST_APPLICATION_UID) return "시스템";  // incl. 1000, 1051
        synchronized (appNames) {
            String name = appNames.get(uid);
            if (name != null) return name;
            PackageManager pm = getPackageManager();
            String[] pkgs = pm.getPackagesForUid(uid);
            if (pkgs == null || pkgs.length == 0) {
                name = "uid " + uid;                     // app hidden from us by package visibility
            } else {
                try {
                    name = pm.getApplicationInfo(pkgs[0], 0).loadLabel(pm).toString();
                } catch (PackageManager.NameNotFoundException e) {
                    name = pkgs[0];
                }
            }
            appNames.put(uid, name);
            return name;
        }
    }

    private void writeTun(byte[] packet) {
        FileOutputStream out = tunOut;
        if (out == null || !running) return;
        synchronized (out) {
            try { out.write(packet); } catch (IOException ignored) {}
        }
    }

    /**
     * Picks the real (non-VPN) network to use, preferring validated Wi-Fi: its first IPv4 DNS
     * server for plain forwarding, and the Network itself for binding DoH connections.
     */
    private void refreshUpstream() {
        Network bestNet = null;
        LinkProperties bestLp = null;
        int bestScore = -1;
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                LinkProperties lp = cm.getLinkProperties(n);
                if (nc == null || lp == null) continue;
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                if (!nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                int score = (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? 2 : 0)
                        + (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? 1 : 0);
                if (score > bestScore) { bestNet = n; bestLp = lp; bestScore = score; }
            }
        } catch (RuntimeException ignored) {}

        // The network's DNS servers, IPv4 first, then IPv6 (5G often has only IPv6 ones),
        // then public fallbacks for both families. Literal IPs: no lookup happens here.
        java.util.List<InetAddress> list = new java.util.ArrayList<>();
        if (bestLp != null) {
            for (InetAddress a : bestLp.getDnsServers())
                if (a instanceof Inet4Address && !a.getHostAddress().equals(FAKE_DNS)) list.add(a);
            for (InetAddress a : bestLp.getDnsServers())
                if (!(a instanceof Inet4Address)) list.add(a);
        }
        try {
            list.add(InetAddress.getByName(FALLBACK_DNS));
            list.add(InetAddress.getByName("2606:4700:4700::1111"));
        } catch (IOException ignored) {}
        upstreams = list.toArray(new InetAddress[0]);
        upstreamNet = bestNet;
        dohPausedUntil = 0;   // new network: give encrypted DNS another chance
    }

    // ---------------------------------------------------------------- notification

    private final Runnable notificationUpdater = new Runnable() {
        @Override public void run() {
            if (!running) return;
            int n = blocked.get();
            if (n != shownBlocked) {
                shownBlocked = n;
                getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
            }
            main.postDelayed(this, 2000);
        }
    };

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "차단 상태", NotificationManager.IMPORTANCE_LOW));
        shownBlocked = 0;
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private Notification buildNotification() {
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, BlockerService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("광고 차단 중 · " + blocked.get() + "개 차단")
                .setContentIntent(openAppIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(null, "끄기", stop).build())
                .build();
    }

    private PendingIntent openAppIntent() {
        return PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
