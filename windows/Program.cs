namespace Haechi;

static class Program
{
    [STAThread]
    static void Main(string[] args)
    {
        using var mutex = new Mutex(true, @"Local\Haechi.LocalDns", out bool first);
        if (!first)
        {
            MessageBox.Show("Haechi가 이미 실행 중이에요. 작업 표시줄 오른쪽 트레이 아이콘을 확인하세요.", "Haechi");
            return;
        }

        // 지난번에 비정상 종료돼 DNS 가 바뀐 채로 남았다면 먼저 원래대로
        if (NetConfig.HasPendingState) NetConfig.Restore();

        ApplicationConfiguration.Initialize();
        Application.Run(new MainForm(startHidden: args.Contains("--tray")));
    }
}
