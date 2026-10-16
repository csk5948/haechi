using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text.Json;
using Microsoft.Win32;

namespace AdBlock;

/// <summary>바꾸기 전 어댑터 DNS 설정. 비어 있으면(null) 원래 자동(DHCP)이었다는 뜻.</summary>
record AdapterState(string Id, int Index, string Name, string? V4Static, string? V6Static, bool V6Changed);

record SavedState(List<AdapterState> Adapters, List<string> Upstreams);

/// <summary>
/// 켤 때: 인터넷에 연결된 어댑터의 DNS 를 [127.0.0.1, 원래 DNS] 로 바꾼다.
///   - 1순위가 이 프로그램, 2순위가 원래 DNS 라서 프로그램이 죽어도 인터넷은 끊기지 않는다.
/// 끌 때: 저장해 둔 원래 설정으로 되돌린다. 비정상 종료 후 다시 실행하면 먼저 되돌린다.
/// </summary>
static class NetConfig
{
    static string StatePath => Path.Combine(DnsFilter.Dir, "state.json");

    public static bool HasPendingState => File.Exists(StatePath);

    [DllImport("dnsapi.dll")] static extern bool DnsFlushResolverCache();

    static IEnumerable<NetworkInterface> Targets() =>
        NetworkInterface.GetAllNetworkInterfaces().Where(n =>
            n.OperationalStatus == OperationalStatus.Up &&
            n.NetworkInterfaceType is not (NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel) &&
            n.Supports(NetworkInterfaceComponent.IPv4) &&
            n.GetIPProperties().GatewayAddresses.Any(g =>
                g.Address.AddressFamily == AddressFamily.InterNetwork && !g.Address.Equals(IPAddress.Any)));

    static bool IsRealDns(IPAddress a) =>
        !IPAddress.IsLoopback(a) &&
        !(a.AddressFamily == AddressFamily.InterNetworkV6 && (a.IsIPv6SiteLocal || a.IsIPv6LinkLocal));

    /// <summary>DNS 를 이 프로그램으로 돌리고, 넘겨줄 원래 DNS 목록을 돌려준다.</summary>
    public static IPAddress[] Apply()
    {
        var adapters = new List<AdapterState>();
        var upstreams = new List<IPAddress>();

        foreach (var n in Targets())
        {
            int idx = n.GetIPProperties().GetIPv4Properties().Index;
            var dns = n.GetIPProperties().DnsAddresses.Where(IsRealDns).ToList();
            var v4 = dns.Where(a => a.AddressFamily == AddressFamily.InterNetwork).ToList();
            var v6 = dns.Where(a => a.AddressFamily == AddressFamily.InterNetworkV6).ToList();
            bool changeV6 = v6.Count > 0;

            adapters.Add(new AdapterState(n.Id, idx, n.Name,
                ReadStatic(@"Tcpip", n.Id), ReadStatic(@"Tcpip6", n.Id), changeV6));
            upstreams.AddRange(v4);
            upstreams.AddRange(v6);
        }

        if (upstreams.Count == 0) upstreams.Add(IPAddress.Parse("1.1.1.1"));
        var up = upstreams.Distinct().ToArray();

        // 되돌릴 정보를 먼저 디스크에 남긴다 (도중에 꺼져도 복구 가능)
        Save(new SavedState(adapters, up.Select(a => a.ToString()).ToList()));

        var fallback4 = up.FirstOrDefault(a => a.AddressFamily == AddressFamily.InterNetwork);
        var fallback6 = up.FirstOrDefault(a => a.AddressFamily == AddressFamily.InterNetworkV6);
        foreach (var a in adapters)
        {
            SetServers("ipv4", a.Index, new[] { "127.0.0.1", fallback4?.ToString() });
            if (a.V6Changed) SetServers("ipv6", a.Index, new[] { "::1", fallback6?.ToString() });
        }
        DnsFlushResolverCache();
        return up;
    }

    /// <summary>저장된 원래 설정으로 되돌린다. 저장된 게 없으면 아무것도 안 한다.</summary>
    public static void Restore()
    {
        if (!File.Exists(StatePath)) return;
        SavedState? s;
        try { s = JsonSerializer.Deserialize<SavedState>(File.ReadAllText(StatePath)); }
        catch { s = null; }

        foreach (var a in s?.Adapters ?? new())
        {
            // 재부팅하면 인덱스가 바뀔 수 있어 ID(GUID)로 다시 찾는다
            int idx = NetworkInterface.GetAllNetworkInterfaces()
                .FirstOrDefault(n => n.Id == a.Id)?.GetIPProperties().GetIPv4Properties()?.Index ?? a.Index;

            RestoreFamily("ipv4", idx, a.V4Static);
            if (a.V6Changed) RestoreFamily("ipv6", idx, a.V6Static);
        }
        File.Delete(StatePath);
        DnsFlushResolverCache();
    }

    static void RestoreFamily(string family, int idx, string? staticList)
    {
        if (string.IsNullOrWhiteSpace(staticList))
            Netsh($"interface {family} set dnsservers name={idx} source=dhcp");
        else
            SetServers(family, idx, staticList.Split(new[] { ',', ' ' }, StringSplitOptions.RemoveEmptyEntries));
    }

    static void SetServers(string family, int idx, IEnumerable<string?> servers)
    {
        var list = servers.Where(s => !string.IsNullOrEmpty(s)).ToList();
        Netsh($"interface {family} set dnsservers name={idx} source=static address={list[0]} validate=no");
        for (int i = 1; i < list.Count; i++)
            Netsh($"interface {family} add dnsservers name={idx} address={list[i]} index={i + 1} validate=no");
    }

    /// <summary>사용자가 직접 넣은(고정) DNS. 레지스트리에 없으면 DHCP.</summary>
    static string? ReadStatic(string service, string id)
    {
        using var k = Registry.LocalMachine.OpenSubKey($@"SYSTEM\CurrentControlSet\Services\{service}\Parameters\Interfaces\{id}");
        var v = k?.GetValue("NameServer") as string;
        return string.IsNullOrWhiteSpace(v) ? null : v;
    }

    static void Save(SavedState s)
    {
        Directory.CreateDirectory(DnsFilter.Dir);
        File.WriteAllText(StatePath, JsonSerializer.Serialize(s, new JsonSerializerOptions { WriteIndented = true }));
    }

    static void Netsh(string args)
    {
        var psi = new ProcessStartInfo("netsh.exe", args)
        {
            CreateNoWindow = true,
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        using var p = Process.Start(psi)!;
        var output = p.StandardOutput.ReadToEnd();
        p.WaitForExit();
        if (p.ExitCode != 0) Log.Write($"netsh {args} → {p.ExitCode}: {output.Trim()}");
    }
}

static class Log
{
    public static void Write(string msg)
    {
        try
        {
            Directory.CreateDirectory(DnsFilter.Dir);
            File.AppendAllText(Path.Combine(DnsFilter.Dir, "error.log"), $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {msg}{Environment.NewLine}");
        }
        catch { }
    }
}
