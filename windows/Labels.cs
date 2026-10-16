namespace Haechi;

/// <summary>
/// 기록 화면용 분류 꼬리표 (차단 여부와는 무관).
/// 1) labels.txt 에 있는 도메인 → 광고 / 추적 / CDN
/// 2) 없으면 이름을 '.' '-' 로 자른 낱말에 광고·추적 단어가 통째로 있으면 → 광고? / 추적?
///    (낱말 단위라 download, address 같은 건 걸리지 않는다)
/// </summary>
static class Labels
{
    static readonly HashSet<string> AdWords = new()
    {
        "ad", "ads", "adx", "adv", "advert", "adverts", "advertising", "adserver", "adservice", "adsrv",
        "adnet", "adtech", "pagead", "banner", "banners", "adfit", "admob",
    };
    static readonly HashSet<string> TrackWords = new()
    {
        "track", "tracker", "tracking", "analytics", "pixel", "beacon", "metrics", "telemetry",
        "stats", "stat", "log", "logs", "collect",
    };

    static readonly Dictionary<string, string> Known = Load();

    static Dictionary<string, string> Load()
    {
        var map = new Dictionary<string, string>(StringComparer.Ordinal);
        using var s = typeof(Labels).Assembly.GetManifestResourceStream("labels.txt")!;
        using var r = new StreamReader(s);
        string category = "";
        while (r.ReadLine() is { } raw)
        {
            var line = raw.Trim();
            if (line.Length == 0 || line[0] == '#') continue;
            if (line[0] == '[' && line[^1] == ']') { category = line[1..^1]; continue; }
            if (category.Length > 0) map[line.ToLowerInvariant()] = category;
        }
        return map;
    }

    public static string Of(string name)
    {
        for (var d = name; ; )   // 가장 구체적인 것부터
        {
            if (Known.TryGetValue(d, out var c)) return c;
            int dot = d.IndexOf('.');
            if (dot < 0) break;
            d = d[(dot + 1)..];
        }
        var words = name.Split('.', '-');
        if (words.Any(AdWords.Contains)) return "광고?";
        if (words.Any(TrackWords.Contains)) return "추적?";
        return "";
    }

    public static bool IsAdOrTrack(string label) => label is "광고" or "추적" or "광고?" or "추적?";

    public static Color ColorOf(string label) => label switch
    {
        "광고" => Color.Firebrick,
        "광고?" => Color.IndianRed,
        "추적" => Color.DarkOrange,
        "추적?" => Color.Orange,
        "CDN" => Color.Gray,
        _ => SystemColors.WindowText,
    };
}
