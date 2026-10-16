using System.Diagnostics;
using Microsoft.Win32;

namespace AdBlock;

sealed class MainForm : Form
{
    const string TaskName = "AdBlockLocalDns";
    static string SettingsPath => Path.Combine(DnsFilter.Dir, "enabled.txt");

    readonly DnsFilter _filter = new();
    DnsServer? _server;
    bool _exiting;
    bool _startHidden;

    readonly NotifyIcon _tray = new();
    readonly ToolStripMenuItem _trayToggle = new();
    readonly Label _status = new() { AutoSize = true, Font = new Font("Segoe UI", 14f, FontStyle.Bold) };
    readonly Label _counts = new() { AutoSize = true };
    readonly Button _toggle = new() { Width = 140, Height = 40 };
    readonly ListView _log = new() { View = View.Details, FullRowSelect = true, MultiSelect = false, Dock = DockStyle.Fill };
    readonly TextBox _search = new() { Width = 180, PlaceholderText = "도메인 검색" };
    readonly ComboBox _show = new() { DropDownStyle = ComboBoxStyle.DropDownList, Width = 80 };
    readonly Button _rec = new() { AutoSize = true, Height = 28 };
    readonly CheckBox _autostart = new() { Text = "Windows 시작 시 자동 실행", AutoSize = true };
    readonly System.Windows.Forms.Timer _timer = new() { Interval = 1000 };
    readonly FileSystemWatcher _watcher;
    readonly Stats _stats = new();
    int _shownVersion = -1;

    public MainForm(bool startHidden)
    {
        _startHidden = startHidden;
        Text = "Haechi";
        Width = 640; Height = 680;
        StartPosition = FormStartPosition.CenterScreen;
        Icon = SystemIcons.Shield;

        // --- 위쪽: 상태 + 버튼 ---
        var top = new FlowLayoutPanel { Dock = DockStyle.Top, Height = 130, Padding = new Padding(12), FlowDirection = FlowDirection.TopDown, WrapContents = false };
        var row = new FlowLayoutPanel { AutoSize = true, WrapContents = false };
        _toggle.Click += (_, _) => SetEnabled(_server == null);
        row.Controls.Add(_toggle);
        row.Controls.Add(_status);
        _status.Padding = new Padding(8, 8, 0, 0);
        top.Controls.Add(row);
        top.Controls.Add(_counts);
        top.Controls.Add(_autostart);

        // --- 아래쪽: 목록 편집 버튼 ---
        var bottom = new FlowLayoutPanel { Dock = DockStyle.Bottom, Height = 48, Padding = new Padding(8) };
        bottom.Controls.Add(MakeButton("목록 관리 (차단·허용·공개 목록)", () =>
        {
            using var f = new ListManagerForm(_filter, () => { ReloadLists(); RefreshLog(force: true); });
            f.ShowDialog(this);
        }));

        // --- 가운데: 모든 질의 기록 ---
        _log.Columns.Add("마지막", 75);
        _log.Columns.Add("도메인", 260);
        _log.Columns.Add("횟수", 50, HorizontalAlignment.Right);
        _log.Columns.Add("상태", 45);
        _log.Columns.Add("분류", 50);
        _log.Columns.Add("어느 목록", 100);
        var menu = new ContextMenuStrip();
        menu.Opening += (_, e) => e.Cancel = !BuildMenu(menu);
        _log.ContextMenuStrip = menu;
        _log.MouseDoubleClick += (_, e) => { if (BuildMenu(menu)) menu.Show(_log, e.Location); };

        _show.Items.AddRange(new object[] { "전체", "통과만", "차단만", "광고·추적·의심만" });
        _show.Width = 130;
        _show.SelectedIndex = 0;
        _show.SelectedIndexChanged += (_, _) => RefreshLog(force: true);
        _search.TextChanged += (_, _) => RefreshLog(force: true);
        _rec.Click += (_, _) => ToggleRecording();
        var tools = new FlowLayoutPanel { Dock = DockStyle.Top, Height = 34, WrapContents = false };
        tools.Controls.Add(_rec);
        tools.Controls.Add(MakeButton("지우기", () => { _stats.Clear(); RefreshLog(force: true); }));
        tools.Controls.Add(_show);
        tools.Controls.Add(_search);

        var mid = new GroupBox { Text = "모든 기록 (더블클릭 / 오른쪽 클릭 → 차단·허용)", Dock = DockStyle.Fill, Padding = new Padding(8) };
        mid.Controls.Add(_log);
        mid.Controls.Add(tools);
        UpdateRecButton();

        Controls.Add(mid);
        Controls.Add(bottom);
        Controls.Add(top);

        // --- 트레이 ---
        var trayMenu = new ContextMenuStrip();
        _trayToggle.Click += (_, _) => SetEnabled(_server == null);
        trayMenu.Items.Add(_trayToggle);
        trayMenu.Items.Add("열기", null, (_, _) => ShowWindow());
        trayMenu.Items.Add(new ToolStripSeparator());
        trayMenu.Items.Add("종료 (DNS 원래대로)", null, (_, _) => ExitApp());
        _tray.ContextMenuStrip = trayMenu;
        _tray.DoubleClick += (_, _) => ShowWindow();
        _tray.Visible = true;

        _autostart.Checked = AutostartRegistered();
        _autostart.CheckedChanged += (_, _) => SetAutostart(_autostart.Checked);

        // 목록 파일을 메모장에서 저장하면 바로 다시 읽는다
        _filter.Load();
        _filter.LoadBundled();
        _watcher = new FileSystemWatcher(DnsFilter.Dir, "*.txt") { NotifyFilter = NotifyFilters.LastWrite | NotifyFilters.Size, EnableRaisingEvents = true };
        _watcher.Changed += (_, _) => BeginInvoke(ReloadLists);

        _timer.Tick += (_, _) => RefreshStats();
        _timer.Start();

        SystemEvents.SessionEnding += (_, _) => StopBlocking();

        // 지난번에 켜 둔 상태였다면 다시 켠다 (첫 실행은 켠 상태로 시작)
        SetEnabled(!File.Exists(SettingsPath) || File.ReadAllText(SettingsPath).Trim() == "1");
    }

    protected override void SetVisibleCore(bool value)
    {
        if (_startHidden) { _startHidden = false; value = false; CreateHandle(); }
        base.SetVisibleCore(value);
    }

    static Button MakeButton(string text, Action onClick)
    {
        var b = new Button { Text = text, AutoSize = true, Height = 30 };
        b.Click += (_, _) => onClick();
        return b;
    }

    void SetEnabled(bool on)
    {
        if (on && _server == null)
        {
            try
            {
                // 포트부터 열어 보고(실패하면 DNS 는 건드리지 않는다), 그다음 DNS 를 바꾼다
                var probe = new DnsServer(_filter, Array.Empty<System.Net.IPAddress>(), new Stats());
                probe.Start();
                probe.Dispose();
                _server = new DnsServer(_filter, NetConfig.Apply(), _stats);
                _server.Start();
            }
            catch (Exception ex)
            {
                _server?.Dispose();
                _server = null;
                NetConfig.Restore();
                Log.Write("켜기 실패: " + ex);
                MessageBox.Show("켜지 못했어요. 53번 포트를 다른 프로그램이 쓰고 있을 수 있어요.\n\n" + ex.Message, "Haechi");
            }
        }
        else if (!on && _server != null)
        {
            StopBlocking();
        }
        File.WriteAllText(SettingsPath, _server != null ? "1" : "0");
        UpdateUi();
    }

    void StopBlocking()
    {
        NetConfig.Restore();   // DNS 를 먼저 되돌리고 서버를 내린다
        _server?.Dispose();
        _server = null;
    }

    void UpdateUi()
    {
        bool on = _server != null;
        _status.Text = on ? "차단 중" : "꺼짐";
        _status.ForeColor = on ? Color.SeaGreen : Color.Gray;
        _toggle.Text = on ? "끄기" : "켜기";
        _trayToggle.Text = on ? "끄기" : "켜기";
        _tray.Icon = on ? SystemIcons.Shield : SystemIcons.Application;
        _tray.Text = on ? "Haechi 켜짐" : "Haechi 꺼짐";
        RefreshStats();
    }

    void RefreshStats()
    {
        _counts.Text = _server == null
            ? $"차단 도메인 {_filter.UserBlockCount + _filter.BundledBlockCount:N0}개"
            : $"차단 도메인 {_filter.UserBlockCount + _filter.BundledBlockCount:N0}개 · 질의 {_stats.Queries:N0}건 중 {_stats.Blocked:N0}건 차단";
        RefreshLog(force: false);
    }

    /// <summary>기록 목록을 다시 그린다. 메뉴가 열려 있을 땐 건드리지 않는다.</summary>
    void RefreshLog(bool force)
    {
        if (!Visible || _log.ContextMenuStrip!.Visible) return;
        if (!force && _stats.Version == _shownVersion) return;
        _shownVersion = _stats.Version;

        var query = _search.Text.Trim().ToLowerInvariant();
        var selected = _log.SelectedItems.Count > 0 ? _log.SelectedItems[0].SubItems[1].Text : null;
        var rows = _stats.Log.ToArray()
            .Select(kv => (Name: kv.Key, kv.Value.Last, kv.Value.Count, Match: _filter.Classify(kv.Key), Label: Labels.Of(kv.Key)))
            .Select(r => (r.Name, r.Last, r.Count, r.Match.Verdict, r.Match.Source, r.Label))
            .Where(r => query.Length == 0 || r.Name.Contains(query))
            .Where(r => _show.SelectedIndex switch
            {
                1 => r.Verdict != Verdict.Blocked,
                2 => r.Verdict == Verdict.Blocked,
                3 => Labels.IsAdOrTrack(r.Label),
                _ => true,
            })
            .OrderByDescending(r => r.Last)
            .Take(1000);

        _log.BeginUpdate();
        _log.Items.Clear();
        foreach (var r in rows)
        {
            var item = new ListViewItem(new[] { r.Last.ToString("HH:mm:ss"), r.Name, r.Count.ToString("N0"), VerdictText.Of(r.Verdict), r.Label, r.Source })
            {
                UseItemStyleForSubItems = false,
            };
            item.SubItems[3].ForeColor = r.Verdict switch { Verdict.Blocked => Color.Firebrick, Verdict.Allowed => Color.RoyalBlue, _ => SystemColors.WindowText };
            item.SubItems[4].ForeColor = Labels.ColorOf(r.Label);
            if (r.Name == selected) item.Selected = true;
            _log.Items.Add(item);
        }
        _log.EndUpdate();
    }

    void ToggleRecording()
    {
        if (_stats.Recording) _stats.Recording = false;              // ■ 정지: 지금까지 기록을 그대로 둔다
        else { _stats.Clear(); _stats.Recording = true; }            // ● 녹화: 비우고 새로 쌓는다
        UpdateRecButton();
        RefreshLog(force: true);
    }

    void UpdateRecButton()
    {
        _rec.Text = _stats.Recording ? "■ 정지" : "● 새로 녹화";
        _rec.ForeColor = _stats.Recording ? Color.Firebrick : SystemColors.ControlText;
    }

    /// <summary>선택한 도메인의 상태에 맞는 메뉴를 만든다. 선택이 없으면 false.</summary>
    bool BuildMenu(ContextMenuStrip menu)
    {
        if (_log.SelectedItems.Count == 0) return false;
        var name = _log.SelectedItems[0].SubItems[1].Text;
        menu.Items.Clear();
        var match = _filter.Classify(name);
        menu.Items.Add(new ToolStripMenuItem(name) { Enabled = false });
        if (match.Source.Length > 0)
            menu.Items.Add(new ToolStripMenuItem($"{VerdictText.Of(match.Verdict)} · {match.Source} ({match.Rule} 규칙)") { Enabled = false });
        menu.Items.Add(new ToolStripSeparator());

        switch (match.Verdict)
        {
            case Verdict.Passed:
                foreach (var scope in DnsFilter.Scopes(name))
                {
                    var label = scope == name ? $"차단: {scope}" : $"차단: {scope}  (하위 전부)";
                    menu.Items.Add(label, null, (_, _) => EditList(() => DnsFilter.AddToBlock(scope)));
                }
                break;
            case Verdict.Blocked:
                menu.Items.Add($"허용하기 (차단 풀기): {name}", null, (_, _) => EditList(() => DnsFilter.AddToAllow(name)));
                if (_filter.BlockListHas(name))
                    menu.Items.Add("차단 목록에서 삭제", null, (_, _) => EditList(() => DnsFilter.RemoveFromBlock(name)));
                break;
            case Verdict.Allowed:
                menu.Items.Add($"허용 취소: {name}", null, (_, _) => EditList(() => DnsFilter.RemoveFromAllow(name)));
                break;
        }
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("도메인 복사", null, (_, _) => Clipboard.SetText(name));
        return true;
    }

    void EditList(Action edit)
    {
        try { edit(); }
        catch (IOException ex) { MessageBox.Show("목록 파일을 저장하지 못했어요.\n" + ex.Message, "Haechi"); return; }
        ReloadLists();
        RefreshLog(force: true);
    }

    void ReloadLists()
    {
        try { _filter.Load(); } catch (IOException) { return; } // 메모장이 아직 쓰는 중
        RefreshStats();
    }

    static bool AutostartRegistered() => RunSchtasks($"/Query /TN {TaskName}") == 0;

    void SetAutostart(bool on)
    {
        // 관리자 권한이 필요한 앱이라 '시작프로그램' 대신 작업 스케줄러(가장 높은 권한)로 등록
        int code = on
            ? RunSchtasks($"/Create /TN {TaskName} /TR \"\\\"{Environment.ProcessPath}\\\" --tray\" /SC ONLOGON /RL HIGHEST /F")
            : RunSchtasks($"/Delete /TN {TaskName} /F");
        if (code != 0) Log.Write($"schtasks {(on ? "create" : "delete")} 실패: {code}");
    }

    static int RunSchtasks(string args)
    {
        var p = Process.Start(new ProcessStartInfo("schtasks.exe", args) { CreateNoWindow = true, UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true })!;
        p.WaitForExit();
        return p.ExitCode;
    }

    void ShowWindow()
    {
        Show();
        WindowState = FormWindowState.Normal;
        Activate();
        RefreshLog(force: true);
    }

    void ExitApp()
    {
        _exiting = true;
        Close();
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        // X 버튼은 트레이로 숨기기만. 종료는 트레이 메뉴에서.
        if (!_exiting && e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true;
            Hide();
            _tray.ShowBalloonTip(2000, "Haechi", "트레이에서 계속 실행 중이에요.", ToolTipIcon.Info);
            return;
        }
        StopBlocking();
        _tray.Visible = false;
        base.OnFormClosing(e);
    }
}
