using System.Diagnostics;

namespace AdBlock;

/// <summary>차단·허용 목록을 검색하고 고치는 창. 파일은 그대로 텍스트로 남는다.</summary>
sealed class ListManagerForm : Form
{
    readonly DnsFilter _filter;
    readonly Action _changed;
    readonly TextBox _check = new() { Width = 260, PlaceholderText = "도메인 확인 (예: ad.example.com)" };
    readonly Label _checkResult = new() { AutoSize = true, Padding = new Padding(6, 6, 0, 0) };

    public ListManagerForm(DnsFilter filter, Action changed)
    {
        _filter = filter;
        _changed = changed;
        Text = "목록 관리";
        Width = 620; Height = 640;
        StartPosition = FormStartPosition.CenterParent;
        Icon = SystemIcons.Shield;

        var top = new FlowLayoutPanel { Dock = DockStyle.Top, Height = 40, Padding = new Padding(8), WrapContents = false };
        top.Controls.Add(_check);
        top.Controls.Add(_checkResult);
        _check.TextChanged += (_, _) => UpdateCheck();

        var tabs = new TabControl { Dock = DockStyle.Fill };
        tabs.TabPages.Add(new EditableListTab("차단 목록", DnsFilter.BlockPath, isBlock: true, OnEdited));
        tabs.TabPages.Add(new EditableListTab("허용 목록", DnsFilter.AllowPath, isBlock: false, OnEdited));
        tabs.TabPages.Add(new BundledTab(filter, OnEdited));

        Controls.Add(tabs);
        Controls.Add(top);
    }

    void OnEdited()
    {
        _changed();
        UpdateCheck();
    }

    void UpdateCheck()
    {
        var d = DnsFilter.ParseLine(_check.Text);
        if (d == null) { _checkResult.Text = ""; return; }
        var m = _filter.Classify(d);
        _checkResult.Text = m.Source.Length == 0
            ? "통과 (어느 목록에도 없음)"
            : $"{VerdictText.Of(m.Verdict)} · {m.Source} ({m.Rule} 규칙)";
        _checkResult.ForeColor = m.Verdict switch { Verdict.Blocked => Color.Firebrick, Verdict.Allowed => Color.RoyalBlue, _ => SystemColors.ControlText };
    }

    /// <summary>검색 가능한 목록 + 추가/수정/삭제.</summary>
    sealed class EditableListTab : TabPage
    {
        readonly string _path;
        readonly bool _isBlock;
        readonly Action _edited;
        readonly TextBox _search = new() { Width = 220, PlaceholderText = "검색" };
        readonly Label _count = new() { AutoSize = true, Padding = new Padding(6, 6, 0, 0) };
        readonly ListBox _list = new() { Dock = DockStyle.Fill, SelectionMode = SelectionMode.MultiExtended, IntegralHeight = false };
        List<string> _all = new();

        public EditableListTab(string title, string path, bool isBlock, Action edited) : base(title)
        {
            _path = path;
            _isBlock = isBlock;
            _edited = edited;
            Padding = new Padding(6);

            var bar = new FlowLayoutPanel { Dock = DockStyle.Top, Height = 34, WrapContents = false };
            bar.Controls.Add(_search);
            bar.Controls.Add(_count);
            _search.TextChanged += (_, _) => Filter();

            var buttons = new FlowLayoutPanel { Dock = DockStyle.Bottom, Height = 40 };
            buttons.Controls.Add(Btn("+ 추가", Add));
            buttons.Controls.Add(Btn("수정", EditSelected));
            buttons.Controls.Add(Btn("삭제", DeleteSelected));
            buttons.Controls.Add(Btn("메모장으로 열기", () => Process.Start("notepad.exe", $"\"{_path}\"")));
            if (isBlock) buttons.Controls.Add(Btn("기본 목록으로", ResetDefault));

            _list.DoubleClick += (_, _) => EditSelected();
            _list.KeyDown += (_, e) => { if (e.KeyCode == Keys.Delete) DeleteSelected(); };

            Controls.Add(_list);
            Controls.Add(buttons);
            Controls.Add(bar);
            Reload();
        }

        static Button Btn(string text, Action a)
        {
            var b = new Button { Text = text, AutoSize = true };
            b.Click += (_, _) => a();
            return b;
        }

        void Reload()
        {
            _all = DnsFilter.Entries(_path);
            Filter();
        }

        void Filter()
        {
            var q = _search.Text.Trim().ToLowerInvariant();
            var shown = q.Length == 0 ? _all : _all.Where(d => d.Contains(q)).ToList();
            _list.BeginUpdate();
            _list.Items.Clear();
            _list.Items.AddRange(shown.Cast<object>().ToArray());
            _list.EndUpdate();
            _count.Text = q.Length == 0 ? $"{_all.Count:N0}개" : $"{shown.Count:N0} / {_all.Count:N0}개";
        }

        void Save(Action edit)
        {
            try { edit(); }
            catch (IOException ex) { MessageBox.Show("저장하지 못했어요.\n" + ex.Message, "목록 관리"); return; }
            Reload();
            _edited();
        }

        void Add()
        {
            var text = Prompt.Multiline(_isBlock ? "차단할 도메인 추가" : "허용할 도메인 추가",
                "한 줄에 하나씩. 주소(https://...)를 붙여넣어도 도메인만 뽑아요.", Clipboard.ContainsText() && Clipboard.GetText().Contains("://") ? Clipboard.GetText() : "");
            if (text == null) return;
            var domains = text.Split('\n').Select(DnsFilter.ParseLine).OfType<string>().Distinct().ToList();
            if (domains.Count == 0) return;

            // 하나만 넣을 땐 범위를 고르게 한다 (ads.x.example.com / example.com ...)
            if (domains.Count == 1 && _isBlock && DnsFilter.Scopes(domains[0]).Count > 1)
            {
                var pick = Prompt.Choose("차단 범위", "어디까지 막을까요? (위로 갈수록 좁게)", DnsFilter.Scopes(domains[0]));
                if (pick == null) return;
                domains[0] = pick;
            }
            Save(() => DnsFilter.AppendLines(_path, domains));
        }

        void EditSelected()
        {
            if (_list.SelectedItem is not string old) return;
            var input = Prompt.Line("수정", "도메인", old);
            if (input == null || DnsFilter.ParseLine(input) is not { } d || d == old) return;
            Save(() => DnsFilter.ReplaceDomain(_path, old, d));
        }

        void DeleteSelected()
        {
            var sel = _list.SelectedItems.Cast<string>().ToList();
            if (sel.Count == 0) return;
            var msg = sel.Count == 1 ? $"{sel[0]} 을(를) 지울까요?" : $"{sel.Count}개를 지울까요?";
            if (MessageBox.Show(msg, "목록 관리", MessageBoxButtons.YesNo) != DialogResult.Yes) return;
            Save(() => DnsFilter.RemoveDomains(_path, sel));
        }

        void ResetDefault()
        {
            if (MessageBox.Show("차단 목록을 기본값으로 되돌릴까요?\n직접 추가한 도메인은 사라집니다.", "목록 관리", MessageBoxButtons.YesNo) != DialogResult.Yes) return;
            Save(DnsFilter.ResetToDefault);
        }
    }

    /// <summary>앱에 들어 있는 공개 목록: 켜기/끄기 + 검색(보기 전용) + 허용하기.</summary>
    sealed class BundledTab : TabPage
    {
        readonly DnsFilter _filter;
        readonly Action _edited;
        readonly CheckedListBox _lists = new() { Dock = DockStyle.Top, Height = 64, CheckOnClick = true };
        readonly TextBox _search = new() { Width = 220, PlaceholderText = "켜진 목록에서 검색 (3글자 이상)" };
        readonly Label _count = new() { AutoSize = true, Padding = new Padding(6, 6, 0, 0) };
        readonly ListView _results = new() { View = View.Details, FullRowSelect = true, Dock = DockStyle.Fill };

        public BundledTab(DnsFilter filter, Action edited) : base("공개 목록")
        {
            _filter = filter;
            _edited = edited;
            Padding = new Padding(6);

            foreach (var b in filter.Bundled)
                _lists.Items.Add($"{b.Name} · {b.Count:N0}개 · {b.Header}", b.Enabled);
            _lists.ItemCheck += (_, e) => BeginInvoke(() => Toggle(e.Index, e.NewValue == CheckState.Checked));

            var bar = new FlowLayoutPanel { Dock = DockStyle.Top, Height = 34, WrapContents = false };
            bar.Controls.Add(_search);
            bar.Controls.Add(_count);
            _search.TextChanged += (_, _) => Search();

            _results.Columns.Add("도메인", 380);
            _results.Columns.Add("목록", 120);
            var allow = new Button { Text = "선택한 도메인 허용하기", AutoSize = true, Dock = DockStyle.Bottom };
            allow.Click += (_, _) => AllowSelected();

            Controls.Add(_results);
            Controls.Add(allow);
            Controls.Add(bar);
            Controls.Add(_lists);
        }

        void Toggle(int index, bool on)
        {
            Cursor = Cursors.WaitCursor;
            _filter.SetBundledEnabled(_filter.Bundled[index].Id, on);
            Cursor = Cursors.Default;
            _edited();
            Search();
        }

        void Search()
        {
            var q = _search.Text.Trim().ToLowerInvariant();
            _results.BeginUpdate();
            _results.Items.Clear();
            if (q.Length >= 3)
            {
                var found = _filter.SearchBundled(q, 300);
                foreach (var (d, list) in found) _results.Items.Add(new ListViewItem(new[] { d, list }));
                _count.Text = found.Count >= 300 ? "300개 이상 (앞쪽만 표시)" : $"{found.Count}개";
            }
            else _count.Text = "";
            _results.EndUpdate();
        }

        void AllowSelected()
        {
            var sel = _results.SelectedItems.Cast<ListViewItem>().Select(i => i.Text).ToList();
            if (sel.Count == 0) return;
            DnsFilter.AppendLines(DnsFilter.AllowPath, sel);
            _edited();
            MessageBox.Show($"{sel.Count}개를 허용 목록에 넣었어요.", "목록 관리");
        }
    }
}

/// <summary>간단한 입력 창들.</summary>
static class Prompt
{
    public static string? Line(string title, string label, string value)
    {
        var box = new TextBox { Text = value, Dock = DockStyle.Top };
        return Show(title, label, box, 140) ? box.Text : null;
    }

    public static string? Multiline(string title, string label, string value)
    {
        var box = new TextBox { Text = value, Multiline = true, ScrollBars = ScrollBars.Vertical, Dock = DockStyle.Fill, AcceptsReturn = true };
        return Show(title, label, box, 320) ? box.Text : null;
    }

    public static string? Choose(string title, string label, List<string> options)
    {
        var list = new ListBox { Dock = DockStyle.Fill, IntegralHeight = false };
        list.Items.AddRange(options.Cast<object>().ToArray());
        list.SelectedIndex = 0;
        return Show(title, label, list, 220) ? list.SelectedItem as string : null;
    }

    static bool Show(string title, string label, Control input, int height)
    {
        using var f = new Form { Text = title, Width = 460, Height = height, StartPosition = FormStartPosition.CenterParent, MinimizeBox = false, MaximizeBox = false };
        var ok = new Button { Text = "확인", DialogResult = DialogResult.OK, AutoSize = true };
        var cancel = new Button { Text = "취소", DialogResult = DialogResult.Cancel, AutoSize = true };
        var buttons = new FlowLayoutPanel { Dock = DockStyle.Bottom, Height = 38, FlowDirection = FlowDirection.RightToLeft };
        buttons.Controls.Add(cancel);
        buttons.Controls.Add(ok);
        var body = new Panel { Dock = DockStyle.Fill, Padding = new Padding(8) };
        body.Controls.Add(input);
        body.Controls.Add(new Label { Text = label, Dock = DockStyle.Top, Height = 24 });
        f.Controls.Add(body);
        f.Controls.Add(buttons);
        f.AcceptButton = input is TextBox { Multiline: true } ? null : ok;
        f.CancelButton = cancel;
        return f.ShowDialog() == DialogResult.OK;
    }
}
