using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;

namespace Haechi;

sealed class LogEntry
{
    public DateTime Last;
    public int Count;
}

/// <summary>모든 질의를 도메인별로 모은다 (메모리에만, 파일로 남기지 않음).</summary>
sealed class Stats
{
    const int MaxDomains = 2000;
    public long Queries, Blocked;
    public volatile bool Recording = true;
    public int Version;   // 기록이 바뀔 때마다 증가 → 화면 갱신 판단용
    public readonly ConcurrentDictionary<string, LogEntry> Log = new();

    public void Record(string name, bool blocked)
    {
        Interlocked.Increment(ref Queries);
        if (blocked) Interlocked.Increment(ref Blocked);
        if (!Recording) return;

        var e = Log.GetOrAdd(name, _ => new LogEntry());
        lock (e) { e.Last = DateTime.Now; e.Count++; }
        Interlocked.Increment(ref Version);

        if (Log.Count > MaxDomains)
            foreach (var old in Log.OrderBy(kv => kv.Value.Last).Take(Log.Count - MaxDomains).ToList())
                Log.TryRemove(old.Key, out _);
    }

    public void Clear()
    {
        Log.Clear();
        Interlocked.Increment(ref Version);
    }
}

/// <summary>
/// 127.0.0.1:53 / [::1]:53 에서 DNS 질의를 받는다.
/// 차단 목록에 있으면 바로 0.0.0.0 으로 답하고, 아니면 원래 DNS 서버(upstream)에 그대로 넘긴다.
/// 이 프로그램이 바깥으로 보내는 통신은 이 '넘기기'가 전부다.
/// </summary>
sealed class DnsServer : IDisposable
{
    const int SIO_UDP_CONNRESET = -1744830452;
    static readonly TimeSpan UpstreamTimeout = TimeSpan.FromSeconds(2);

    readonly DnsFilter _filter;
    readonly IPAddress[] _upstreams;
    public readonly Stats Stats;
    readonly CancellationTokenSource _cts = new();
    readonly List<Socket> _udp = new();
    readonly List<TcpListener> _tcp = new();

    public DnsServer(DnsFilter filter, IPAddress[] upstreams, Stats stats)
    {
        _filter = filter;
        _upstreams = upstreams;
        Stats = stats;
    }

    /// <summary>포트를 못 열면 예외를 던진다 (DNS 설정을 바꾸기 전에 호출).</summary>
    public void Start()
    {
        foreach (var ip in new[] { IPAddress.Loopback, IPAddress.IPv6Loopback })
        {
            var u = new Socket(ip.AddressFamily, SocketType.Dgram, ProtocolType.Udp);
            u.IOControl(SIO_UDP_CONNRESET, new byte[4], null); // 응답 대상이 사라져도 수신이 멈추지 않게
            u.Bind(new IPEndPoint(ip, 53));
            _udp.Add(u);
            var t = new TcpListener(ip, 53);
            t.Start();
            _tcp.Add(t);
        }
        foreach (var u in _udp) _ = UdpLoop(u);
        foreach (var t in _tcp) _ = TcpLoop(t);
    }

    async Task UdpLoop(Socket sock)
    {
        var ct = _cts.Token;
        var buf = new byte[4096];
        EndPoint any = sock.AddressFamily == AddressFamily.InterNetwork
            ? new IPEndPoint(IPAddress.Any, 0) : new IPEndPoint(IPAddress.IPv6Any, 0);
        while (!ct.IsCancellationRequested)
        {
            SocketReceiveFromResult r;
            try { r = await sock.ReceiveFromAsync(buf, SocketFlags.None, any, ct); }
            catch (OperationCanceledException) { return; }
            catch (ObjectDisposedException) { return; }
            catch (SocketException) { continue; }

            var query = buf.AsSpan(0, r.ReceivedBytes).ToArray();
            var client = r.RemoteEndPoint;
            _ = Task.Run(async () =>
            {
                var resp = await Handle(query, tcp: false, ct);
                if (resp == null) return;
                try { await sock.SendToAsync(resp, SocketFlags.None, client, ct); } catch { }
            });
        }
    }

    async Task TcpLoop(TcpListener listener)
    {
        var ct = _cts.Token;
        while (!ct.IsCancellationRequested)
        {
            TcpClient c;
            try { c = await listener.AcceptTcpClientAsync(ct); }
            catch { return; }
            _ = Task.Run(async () =>
            {
                using (c)
                {
                    try
                    {
                        var s = c.GetStream();
                        while (true)
                        {
                            var query = await ReadTcpMessage(s, ct);
                            if (query == null) return;
                            var resp = await Handle(query, tcp: true, ct);
                            if (resp == null) return;
                            await WriteTcpMessage(s, resp, ct);
                        }
                    }
                    catch { }
                }
            });
        }
    }

    async Task<byte[]?> Handle(byte[] query, bool tcp, CancellationToken ct)
    {
        if (DnsMessage.TryParseQuestion(query, out var name, out var qtype, out var qEnd))
        {
            bool blocked = _filter.IsBlocked(name);
            Stats.Record(name, blocked);
            if (blocked) return DnsMessage.BuildBlocked(query, qtype, qEnd);
        }
        foreach (var up in _upstreams)
        {
            var resp = tcp ? await ForwardTcp(query, up, ct) : await ForwardUdp(query, up, ct);
            if (resp != null) return resp;
        }
        return null; // 응답이 없으면 Windows 가 보조 DNS 로 다시 묻는다
    }

    static async Task<byte[]?> ForwardUdp(byte[] query, IPAddress up, CancellationToken ct)
    {
        try
        {
            using var c = new UdpClient(up.AddressFamily);
            c.Connect(up, 53);
            using var t = CancellationTokenSource.CreateLinkedTokenSource(ct);
            t.CancelAfter(UpstreamTimeout);
            await c.SendAsync(query, t.Token);
            while (true)
            {
                var r = await c.ReceiveAsync(t.Token);
                if (r.Buffer.Length >= 2 && r.Buffer[0] == query[0] && r.Buffer[1] == query[1]) return r.Buffer;
            }
        }
        catch { return null; }
    }

    static async Task<byte[]?> ForwardTcp(byte[] query, IPAddress up, CancellationToken ct)
    {
        try
        {
            using var t = CancellationTokenSource.CreateLinkedTokenSource(ct);
            t.CancelAfter(UpstreamTimeout * 2);
            using var c = new TcpClient(up.AddressFamily);
            await c.ConnectAsync(up, 53, t.Token);
            var s = c.GetStream();
            await WriteTcpMessage(s, query, t.Token);
            return await ReadTcpMessage(s, t.Token);
        }
        catch { return null; }
    }

    static async Task<byte[]?> ReadTcpMessage(NetworkStream s, CancellationToken ct)
    {
        var len = new byte[2];
        if (!await ReadExact(s, len, ct)) return null;
        var msg = new byte[(len[0] << 8) | len[1]];
        return await ReadExact(s, msg, ct) ? msg : null;
    }

    static async Task WriteTcpMessage(NetworkStream s, byte[] msg, CancellationToken ct)
    {
        var buf = new byte[msg.Length + 2];
        buf[0] = (byte)(msg.Length >> 8); buf[1] = (byte)msg.Length;
        msg.CopyTo(buf, 2);
        await s.WriteAsync(buf, ct);
    }

    static async Task<bool> ReadExact(NetworkStream s, byte[] buf, CancellationToken ct)
    {
        int n = 0;
        while (n < buf.Length)
        {
            int r = await s.ReadAsync(buf.AsMemory(n), ct);
            if (r == 0) return false;
            n += r;
        }
        return true;
    }

    public void Dispose()
    {
        _cts.Cancel();
        foreach (var u in _udp) u.Dispose();
        foreach (var t in _tcp) t.Stop();
    }
}
