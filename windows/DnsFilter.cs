namespace Haechi;

/// <summary>판정 결과와, 어느 목록의 어느 규칙 때문인지.</summary>
record Match(Verdict Verdict, string Source, string Rule);

/// <summary>앱에 들어 있는 공개 목록 (읽기 전용, 켜기/끄기만).</summary>
sealed class BundledList
{
    public required string Id, Name;
    public bool Enabled = true;
    public string Header = "";
    public int Count;
}

/// <summary>
/// 차단/허용 목록을 읽고, 도메인이 막혀야 하는지 판단한다.
/// 우선순위: 내 허용 목록 > 내 차단 목록 > 공개 목록의 예외(@@) > 공개 목록 차단 > 통과
/// </summary>
sealed class DnsFilter
{
    public static readonly string Dir =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Haechi");
    public static string BlockPath => Path.Combine(Dir, "block.txt");
    public static string AllowPath => Path.Combine(Dir, "allow.txt");
    static string TogglesPath => Path.Combine(Dir, "lists.txt");

    public readonly List<BundledList> Bundled = new()
    {
        new() { Id = "adguard-dns", Name = "AdGuard DNS" },
        new() { Id = "list-kr-dns", Name = "List-KR" },
    };

    volatile HashSet<string> _block = new();
    volatile HashSet<string> _allow = new();
    volatile Dictionary<string, BundledList> _bundledBlock = new();
    volatile Dictionary<string, BundledList> _bundledAllow = new();

    public int UserBlockCount => _block.Count;
    public int BundledBlockCount => _bundledBlock.Count;

    /// <summary>내 목록만 다시 읽는다 (빠름).</summary>
    public void Load()
    {
        Directory.CreateDirectory(Dir);
        if (!File.Exists(BlockPath)) ResetToDefault();
        if (!File.Exists(AllowPath))
            File.WriteAllText(AllowPath, "# 허용 목록 — 여기 적은 도메인(과 하위 도메인)은 어느 목록에 있어도 통과한다.\n");
        _block = File.ReadAllLines(BlockPath).Select(ParseLine).OfType<string>().ToHashSet(StringComparer.Ordinal);
        _allow = File.ReadAllLines(AllowPath).Select(ParseLine).OfType<string>().ToHashSet(StringComparer.Ordinal);
    }

    /// <summary>공개 목록을 켜기/끄기 설정에 맞춰 다시 읽는다 (18만 개, 수백 ms).</summary>
    public void LoadBundled()
    {
        if (File.Exists(TogglesPath))
            foreach (var line in File.ReadAllLines(TogglesPath))
            {
                var kv = line.Split('=');
                var list = Bundled.FirstOrDefault(b => b.Id == kv[0]);
                if (list != null && kv.Length == 2) list.Enabled = kv[1].Trim() == "1";
            }

        var block = new Dictionary<string, BundledList>(StringComparer.Ordinal);
        var allow = new Dictionary<string, BundledList>(StringComparer.Ordinal);
        foreach (var list in Bundled)
        {
            using var s = typeof(DnsFilter).Assembly.GetManifestResourceStream($"bundled/{list.Id}.txt")!;
            using var r = new StreamReader(s);
            var header = new List<string>();
            int count = 0;
            while (r.ReadLine() is { } line)
            {
                if (line.StartsWith('#')) { header.Add(line.TrimStart('#', ' ')); continue; }
                if (line.Length == 0) continue;
                count++;
                if (!list.Enabled) continue;
                if (line.StartsWith("@@")) allow.TryAdd(line[2..], list);
                else block.TryAdd(line, list);
            }
            list.Header = string.Join(" · ", header.Skip(1));
            list.Count = count;
        }
        _bundledBlock = block;
        _bundledAllow = allow;
    }

    public void SetBundledEnabled(string id, bool on)
    {
        Bundled.First(b => b.Id == id).Enabled = on;
        File.WriteAllLines(TogglesPath, Bundled.Select(b => $"{b.Id}={(b.Enabled ? 1 : 0)}"));
        LoadBundled();
    }

    /// <summary>켜진 공개 목록에서 검색 (보기용, 앞쪽 limit 개만).</summary>
    public List<(string Domain, string List)> SearchBundled(string query, int limit)
    {
        return _bundledBlock.Where(kv => kv.Key.Contains(query))
            .Take(limit).Select(kv => (kv.Key, kv.Value.Name)).ToList();
    }

    public static void ResetToDefault()
    {
        Directory.CreateDirectory(Dir);
        using var s = typeof(DnsFilter).Assembly.GetManifestResourceStream("default.txt")!;
        using var f = File.Create(BlockPath);
        s.CopyTo(f);
    }

    public static void AddToAllow(string domain) => AppendLines(AllowPath, new[] { domain });
    public static void AddToBlock(string domain) => AppendLines(BlockPath, new[] { domain });
    public static void RemoveFromAllow(string domain) => RemoveDomains(AllowPath, new[] { domain });
    public static void RemoveFromBlock(string domain) => RemoveDomains(BlockPath, new[] { domain });

    public bool BlockListHas(string domain) => _block.Contains(domain);

    /// <summary>파일에 적힌 도메인들 (주석 제외, 적힌 순서).</summary>
    public static List<string> Entries(string path) =>
        File.ReadAllLines(path).Select(ParseLine).OfType<string>().Distinct().ToList();

    public static void AppendLines(string path, IEnumerable<string> domains)
    {
        var existing = Entries(path).ToHashSet();
        var add = domains.Where(d => !existing.Contains(d)).Distinct().ToList();
        if (add.Count == 0) return;
        var text = File.ReadAllText(path);
        var sep = text.Length == 0 || text.EndsWith('\n') ? "" : Environment.NewLine;
        File.AppendAllText(path, sep + string.Join(Environment.NewLine, add) + Environment.NewLine);
    }

    /// <summary>파싱했을 때 그 도메인이 되는 줄만 지운다 (주석·다른 줄은 그대로).</summary>
    public static void RemoveDomains(string path, IEnumerable<string> domains)
    {
        var remove = domains.ToHashSet();
        var lines = File.ReadAllLines(path).Where(l => ParseLine(l) is not { } d || !remove.Contains(d));
        File.WriteAllLines(path, lines);
    }

    public static void ReplaceDomain(string path, string oldDomain, string newDomain)
    {
        var lines = File.ReadAllLines(path).Select(l => ParseLine(l) == oldDomain ? newDomain : l);
        File.WriteAllLines(path, lines);
    }

    /// <summary>
    /// 한 줄 → 도메인. 주석·빈 줄이면 null.
    /// 받는 형식: example.com / 0.0.0.0 example.com (hosts) / ||example.com^ / https://example.com/경로
    /// </summary>
    public static string? ParseLine(string raw)
    {
        var line = raw.Trim();
        if (line.Length == 0 || line[0] == '#' || line[0] == '!') return null;
        int hash = line.IndexOf(" #", StringComparison.Ordinal);
        if (hash > 0) line = line[..hash].Trim();
        if (line.Contains("://") && Uri.TryCreate(line, UriKind.Absolute, out var uri)) return uri.Host.ToLowerInvariant();
        var parts = line.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);
        if (parts.Length == 0) return null;
        var d = parts[^1].ToLowerInvariant();
        if (d.StartsWith("||")) d = d[2..];
        d = d.TrimEnd('^', '.');
        int slash = d.IndexOf('/');
        if (slash >= 0) d = d[..slash];
        if (d.Length == 0 || d == "localhost" || d == "0.0.0.0" || d == "127.0.0.1" || !d.Contains('.')) return null;
        return d;
    }

    public bool IsBlocked(string name) => Classify(name).Verdict == Verdict.Blocked;

    /// <summary>이름 자신과 모든 상위 도메인을 단계별로 본다.</summary>
    public Match Classify(string name)
    {
        if (Find(name, _allow) is { } a) return new(Verdict.Allowed, "내 허용 목록", a);
        if (Find(name, _block) is { } b) return new(Verdict.Blocked, "내 차단 목록", b);
        var bundledAllow = _bundledAllow;
        if (Find(name, bundledAllow.ContainsKey) is { } e) return new(Verdict.Passed, bundledAllow[e].Name + " 예외", e);
        var bundledBlock = _bundledBlock;
        if (Find(name, bundledBlock.ContainsKey) is { } c) return new(Verdict.Blocked, bundledBlock[c].Name, c);
        return new(Verdict.Passed, "", "");
    }

    static string? Find(string name, HashSet<string> set) => Find(name, set.Contains);

    static string? Find(string name, Func<string, bool> has)
    {
        for (var d = name; ; )
        {
            if (has(d)) return d;
            int dot = d.IndexOf('.');
            if (dot < 0) return null;
            d = d[(dot + 1)..];
        }
    }

    static readonly HashSet<string> TwoLevelSuffixes = new()
    {
        "co.kr", "or.kr", "go.kr", "ne.kr", "ac.kr", "re.kr", "pe.kr",
        "co.jp", "ne.jp", "or.jp", "co.uk", "org.uk", "com.au", "com.cn", "com.tw", "com.br",
    };

    /// <summary>차단 범위 후보: ads.x.example.com → [ads.x.example.com, x.example.com, example.com]</summary>
    public static List<string> Scopes(string name)
    {
        var labels = name.Split('.');
        int minLabels = labels.Length >= 3 && TwoLevelSuffixes.Contains(string.Join('.', labels[^2..])) ? 3 : 2;
        var result = new List<string>();
        for (int i = 0; labels.Length - i >= minLabels; i++)
            result.Add(string.Join('.', labels[i..]));
        if (result.Count == 0) result.Add(name);
        return result;
    }
}

enum Verdict { Passed, Blocked, Allowed }

static class VerdictText
{
    public static string Of(Verdict v) => v switch { Verdict.Blocked => "차단", Verdict.Allowed => "허용", _ => "통과" };
}
