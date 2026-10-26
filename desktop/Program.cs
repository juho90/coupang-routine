using System.ComponentModel;
using System.Diagnostics;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;

namespace RebuyClipboard;

internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        if (args.Length > 0)
        {
            try { return RunCliAsync(Options.Parse(args)).GetAwaiter().GetResult(); }
            catch (UserMessageException failure) { Console.Error.WriteLine(failure.Message); return 2; }
            catch (OperationCanceledException) { Console.Error.WriteLine("작업이 중단되었거나 제한 시간을 넘었습니다."); return 3; }
            catch { Console.Error.WriteLine("이미지를 처리하지 못했습니다. 입력 이미지와 BlueStacks 실행 상태를 확인해 주세요."); return 4; }
        }
        ApplicationConfiguration.Initialize();
        Application.Run(new SendImageForm());
        return 0;
    }

    private static async Task<int> RunCliAsync(Options options)
    {
        if (options.Help)
        {
            Console.WriteLine("RebuyClipboard.exe --image <image-file> [--send] [--adb <adb.exe>] [--device 127.0.0.1:5555]");
            Console.WriteLine("--image 모드는 클립보드를 읽지 않습니다. --send가 있어야 Android 앱으로 전송합니다.");
            return 0;
        }
        if (options.ImageFile is null) throw new UserMessageException("CLI 시험에는 --image로 이미지 파일을 지정해 주세요.");
        var png = await Task.Run(() => ImagePayload.FromFile(options.ImageFile));
        if (!options.Send)
        {
            Console.WriteLine("이미지 확인 완료. 전송하지 않았습니다.");
            return 0;
        }
        using var limit = new CancellationTokenSource(TimeSpan.FromSeconds(45));
        await AndroidTransfer.SendAsync(png, options.Adb ?? AdbDiscovery.Find(), options.Device, null, limit.Token);
        Console.WriteLine("전송 완료. 다시담기 앱에서 읽은 내용을 검토한 뒤 적용해 주세요.");
        return 0;
    }
}

internal sealed record Options(string? ImageFile, bool Send, string? Adb, string Device, bool Help)
{
    public static Options Parse(string[] args)
    {
        string? image = null, adb = null;
        var device = "127.0.0.1:5555";
        bool send = false, help = false;
        for (var index = 0; index < args.Length; index++)
        {
            string Value()
            {
                if (++index >= args.Length) throw new UserMessageException("옵션 뒤에 필요한 값을 지정해 주세요.");
                return args[index];
            }
            switch (args[index])
            {
                case "--image": image = Value(); break;
                case "--adb": adb = Value(); break;
                case "--device": device = Value(); break;
                case "--send": send = true; break;
                case "--help": case "-h": help = true; break;
                default: throw new UserMessageException("지원하지 않는 실행 옵션입니다. --help로 사용법을 확인해 주세요.");
            }
        }
        return new Options(image, send, adb, device, help);
    }
}

internal sealed class SendImageForm : Form
{
    private readonly Button send = new() { Text = "클립보드 이미지 보내기", AutoSize = true, MinimumSize = new Size(300, 46) };
    private readonly Button chooseAdb = new() { Text = "ADB 선택", AutoSize = true };
    private readonly Button cancel = new() { Text = "전송 중단", AutoSize = true, Enabled = false };
    private readonly TextBox adbPath = new() { ReadOnly = true, Dock = DockStyle.Fill, AccessibleName = "ADB 실행 파일" };
    private readonly TextBox device = new() { Text = "127.0.0.1:5555", Dock = DockStyle.Fill, AccessibleName = "BlueStacks 연결 주소" };
    private readonly Label status = new() { Text = "캡처한 뒤 보내기 버튼이나 이 창에서 Ctrl+V를 눌러 주세요.", AutoSize = true, MaximumSize = new Size(500, 0) };
    private readonly ProgressBar progress = new() { Style = ProgressBarStyle.Marquee, Dock = DockStyle.Fill, Visible = false };
    private CancellationTokenSource? operation;
    private bool busy;

    public SendImageForm()
    {
        Text = "다시담기 PC 이미지 보내기";
        Font = new Font("맑은 고딕", 10F);
        AutoScaleMode = AutoScaleMode.Dpi;
        StartPosition = FormStartPosition.CenterScreen;
        MinimumSize = new Size(570, 470);
        ClientSize = new Size(620, 520);
        MaximizeBox = false;
        adbPath.Text = AdbDiscovery.Find() ?? "";
        var layout = new TableLayoutPanel { Dock = DockStyle.Fill, AutoScroll = true, Padding = new Padding(22), ColumnCount = 2, RowCount = 9 };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        for (var row = 0; row < 9; row++) layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        var heading = new Label { Text = "PC에서 캡처한 상품 이미지를 BlueStacks로", Font = new Font(Font, FontStyle.Bold), AutoSize = true, Margin = new Padding(0, 0, 0, 12) };
        layout.Controls.Add(heading, 0, 0); layout.SetColumnSpan(heading, 2);
        var guide = new Label { Text = "1. BlueStacks에서 다시담기 앱을 설치하고 실행하세요.\n2. Windows에서 Win+Shift+S로 상품을 캡처하세요.\n3. 이 창에서 보내기 또는 Ctrl+V를 누른 뒤 앱에서 검토하세요.", AutoSize = true, Margin = new Padding(0, 0, 0, 14) };
        layout.Controls.Add(guide, 0, 1); layout.SetColumnSpan(guide, 2);
        layout.Controls.Add(new Label { Text = "ADB", AutoSize = true }, 0, 2);
        layout.Controls.Add(adbPath, 0, 3); layout.Controls.Add(chooseAdb, 1, 3);
        layout.Controls.Add(new Label { Text = "BlueStacks 연결 주소", AutoSize = true, Margin = new Padding(0, 8, 0, 0) }, 0, 4);
        layout.Controls.Add(device, 0, 5); layout.SetColumnSpan(device, 2);
        var actions = new FlowLayoutPanel { AutoSize = true, Dock = DockStyle.Fill, Margin = new Padding(0, 16, 0, 10) };
        actions.Controls.Add(send); actions.Controls.Add(cancel);
        layout.Controls.Add(actions, 0, 6); layout.SetColumnSpan(actions, 2);
        layout.Controls.Add(progress, 0, 7); layout.SetColumnSpan(progress, 2);
        layout.Controls.Add(status, 0, 8); layout.SetColumnSpan(status, 2);
        Controls.Add(layout);
        send.Click += async (_, _) => await SendClipboardAsync();
        cancel.Click += (_, _) => operation?.Cancel();
        chooseAdb.Click += (_, _) => ChooseAdb();
        FormClosing += (_, _) => operation?.Cancel();
        AcceptButton = send;
    }

    protected override bool ProcessCmdKey(ref Message msg, Keys keyData)
    {
        if (keyData == (Keys.Control | Keys.V))
        {
            if (!busy) _ = SendClipboardAsync();
            return true;
        }
        return base.ProcessCmdKey(ref msg, keyData);
    }

    private void ChooseAdb()
    {
        using var picker = new OpenFileDialog { Title = "adb.exe 선택", Filter = "Android Debug Bridge (adb.exe)|adb.exe", CheckFileExists = true, Multiselect = false };
        if (picker.ShowDialog(this) == DialogResult.OK) adbPath.Text = picker.FileName;
    }

    private async Task SendClipboardAsync()
    {
        if (busy) return;
        busy = true;
        send.Enabled = chooseAdb.Enabled = device.Enabled = false;
        cancel.Enabled = progress.Visible = true;
        operation = new CancellationTokenSource(TimeSpan.FromSeconds(45));
        try
        {
            status.Text = "클립보드 이미지를 확인하고 있습니다.";
            // This is the only clipboard read in the program, reached only by a local user action.
            using var image = Clipboard.GetImage();
            if (image is null) throw new UserMessageException("Windows 클립보드에 이미지가 없습니다. Win+Shift+S로 캡처한 뒤 다시 눌러 주세요.");
            var png = await Task.Run(() => ImagePayload.FromImage(image), operation.Token);
            var updates = new Progress<string>(message => { if (!IsDisposed) status.Text = message; });
            await AndroidTransfer.SendAsync(png, adbPath.Text, device.Text, updates, operation.Token);
            if (!IsDisposed) status.Text = "전송했습니다. 다시담기 앱에서 내용을 검토한 뒤 입력란에 적용해 주세요.";
        }
        catch (UserMessageException failure) { if (!IsDisposed) status.Text = failure.Message; }
        catch (OperationCanceledException) { if (!IsDisposed) status.Text = "전송이 중단되었거나 제한 시간을 넘었습니다. 이미 전달됐다면 다시담기 화면을 확인해 주세요."; }
        catch (ExternalException) { if (!IsDisposed) status.Text = "클립보드 이미지를 열 수 없습니다. 이미지를 다시 복사한 뒤 보내 주세요."; }
        catch (OutOfMemoryException) { if (!IsDisposed) status.Text = "이미지가 너무 커서 처리하지 못했습니다. 캡처 범위를 줄여 주세요."; }
        catch { if (!IsDisposed) status.Text = "전송하지 못했습니다. 이미지와 BlueStacks 실행 상태를 확인해 주세요."; }
        finally
        {
            operation.Dispose(); operation = null;
            busy = false;
            if (!IsDisposed) { send.Enabled = chooseAdb.Enabled = device.Enabled = true; cancel.Enabled = progress.Visible = false; }
        }
    }
}

internal static class ImagePayload
{
    private const int MaxSide = 60_000;
    private const long MaxPixels = 160_000_000;
    private const int MaxBytes = 24 * 1024 * 1024;

    public static byte[] FromFile(string path)
    {
        try
        {
            using var file = File.OpenRead(path);
            using var image = Image.FromStream(file, useEmbeddedColorManagement: false, validateImageData: true);
            return FromImage(image);
        }
        catch (UserMessageException) { throw; }
        catch { throw new UserMessageException("이미지 파일을 해석하지 못했습니다. 일반 PNG 또는 JPEG 이미지로 다시 시도해 주세요."); }
    }

    public static byte[] FromImage(Image image)
    {
        if (image.Width <= 0 || image.Height <= 0 || image.Width > MaxSide || image.Height > MaxSide || (long)image.Width * image.Height > MaxPixels)
            throw new UserMessageException("이미지가 너무 큽니다. 가로·세로 60,000픽셀 및 전체 1억6천만 픽셀 이하로 캡처해 주세요.");
        try
        {
            ApplyOrientation(image);
            using var bitmap = new Bitmap(image.Width, image.Height, PixelFormat.Format32bppArgb);
            using (var graphics = Graphics.FromImage(bitmap)) graphics.DrawImage(image, new Rectangle(0, 0, bitmap.Width, bitmap.Height));
            using var encoded = new LimitedMemoryStream(MaxBytes);
            try { bitmap.Save(encoded, ImageFormat.Png); }
            catch when (encoded.Exceeded) { throw new UserMessageException("PNG 이미지가 24MB를 넘습니다. 캡처 범위를 줄여 주세요."); }
            return encoded.ToArray();
        }
        catch (UserMessageException) { throw; }
        catch (OutOfMemoryException) { throw new UserMessageException("이미지가 너무 커서 처리하지 못했습니다. 캡처 범위를 줄여 주세요."); }
        catch { throw new UserMessageException("이미지를 PNG로 변환하지 못했습니다. 이미지를 다시 복사해 주세요."); }
    }

    private static void ApplyOrientation(Image image)
    {
        try
        {
            if (!image.PropertyIdList.Contains(0x112)) return;
            var raw = image.GetPropertyItem(0x112)?.Value;
            if (raw is null || raw.Length < 2) return;
            var orientation = BitConverter.ToUInt16(raw, 0);
            var flip = orientation switch
            {
                2 => RotateFlipType.RotateNoneFlipX, 3 => RotateFlipType.Rotate180FlipNone,
                4 => RotateFlipType.Rotate180FlipX, 5 => RotateFlipType.Rotate90FlipX,
                6 => RotateFlipType.Rotate90FlipNone, 7 => RotateFlipType.Rotate270FlipX,
                8 => RotateFlipType.Rotate270FlipNone, _ => RotateFlipType.RotateNoneFlipNone,
            };
            image.RotateFlip(flip);
        }
        catch (ArgumentException) { /* Missing orientation does not block an ordinary screenshot. */ }
    }

    private sealed class LimitedMemoryStream(int maximum) : MemoryStream
    {
        public bool Exceeded { get; private set; }
        private void Check(long length)
        {
            if (length <= maximum) return;
            Exceeded = true;
            throw new IOException("Encoded image exceeds limit.");
        }
        public override void Write(byte[] buffer, int offset, int count) { Check(Position + count); base.Write(buffer, offset, count); }
        public override void Write(ReadOnlySpan<byte> buffer) { Check(Position + buffer.Length); base.Write(buffer); }
        public override void WriteByte(byte value) { Check(Position + 1); base.WriteByte(value); }
        public override void SetLength(long value) { Check(value); base.SetLength(value); }
    }
}

internal static class AdbDiscovery
{
    public static string? Find()
    {
        var candidates = new List<string> { Path.Combine(AppContext.BaseDirectory, "adb.exe") };
        foreach (var part in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(Path.PathSeparator))
        {
            if (!string.IsNullOrWhiteSpace(part)) candidates.Add(Path.Combine(part.Trim().Trim('"'), "adb.exe"));
        }
        candidates.Add(@"C:\platform-tools\adb.exe");
        candidates.Add(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Android", "Sdk", "platform-tools", "adb.exe"));
        candidates.Add(@"C:\Android\platform-tools\adb.exe");
        return candidates.FirstOrDefault(File.Exists);
    }
}

internal static class AndroidTransfer
{
    private const string Provider = "content://dev.local.rebuy.pc-images";

    public static async Task SendAsync(byte[] png, string? adb, string serial, IProgress<string>? progress, CancellationToken cancellation)
    {
        if (string.IsNullOrWhiteSpace(adb) || !File.Exists(adb) || !string.Equals(Path.GetFileName(adb), "adb.exe", StringComparison.OrdinalIgnoreCase))
            throw new UserMessageException("ADB를 찾지 못했습니다. ADB 선택에서 adb.exe를 지정해 주세요.");
        var localConnection = Regex.Match(serial, @"^(?:127\.0\.0\.1|localhost):([0-9]{1,5})$");
        if (localConnection.Success && !(int.TryParse(localConnection.Groups[1].Value, out var port) && port is > 0 and <= 65535))
            throw new UserMessageException("올바른 연결 포트를 입력해 주세요. 기본값은 127.0.0.1:5555입니다.");
        if (!Regex.IsMatch(serial, @"^[a-zA-Z0-9._:-]{1,128}$"))
            throw new UserMessageException("올바른 BlueStacks 연결 주소 또는 연결된 Android 장치 번호를 입력해 주세요.");
        progress?.Report("BlueStacks 연결과 다시담기 앱 설치를 확인하고 있습니다.");
        if (localConnection.Success)
        {
            var connect = await AdbProcess.RunAsync(adb, ["connect", serial], null, cancellation, TimeSpan.FromSeconds(8));
            if (connect.ExitCode != 0) throw new UserMessageException("BlueStacks에 연결하지 못했습니다. 실행 상태와 ADB 연결 주소를 확인해 주세요.");
        }
        var state = await AdbProcess.RunAsync(adb, ["-s", serial, "get-state"], null, cancellation, TimeSpan.FromSeconds(8));
        if (state.ExitCode != 0 || state.Output.Trim() != "device") throw new UserMessageException("Android 에뮬레이터를 찾지 못했습니다. BlueStacks를 실행하고 ADB 연결 주소를 확인해 주세요.");
        // BlueStacks can report a phone hardware name (for example qcom), so verify the
        // chosen connected device and our own installed package instead of guessing by model.
        var installed = await AdbProcess.RunAsync(adb, ["-s", serial, "shell", "pm", "path", "dev.local.rebuy"], null, cancellation, TimeSpan.FromSeconds(5));
        if (installed.ExitCode != 0 || !Regex.IsMatch(installed.Output, @"(?m)^package:"))
            throw new UserMessageException("선택한 Android 장치에 다시담기가 설치되어 있지 않습니다. BlueStacks 인스턴스와 앱 설치를 확인해 주세요.");

        var id = Guid.NewGuid().ToString("D").ToLowerInvariant();
        var uri = Provider + "/" + id;
        progress?.Report("이미지를 다시담기 앱으로 전달하고 있습니다.");
        // Windows adb shell stdin uses CRT text mode. Base64 avoids PNG's Ctrl-Z/CRLF,
        // while shell-v2 closes stdin and waits for the remote writer to finish.
        var encoded = Encoding.ASCII.GetBytes(Convert.ToBase64String(png, Base64FormattingOptions.InsertLineBreaks));
        var write = await AdbProcess.RunAsync(adb, ["-s", serial, "shell", "-T", "-e", "none", "base64 -d | content write --uri " + uri], encoded, cancellation, TimeSpan.FromSeconds(15));
        if (write.ExitCode != 0 || write.Output.Contains("Exception", StringComparison.OrdinalIgnoreCase) || write.Error.Contains("Exception", StringComparison.OrdinalIgnoreCase))
            throw new UserMessageException("다시담기 앱으로 이미지를 전달하지 못했습니다. PC 이미지 받기를 지원하는 최신 Android 앱을 설치해 주세요.");
        await AwaitReadyAsync(adb, serial, id, cancellation);
        progress?.Report("다시담기 앱의 이미지 검토 화면을 열고 있습니다.");
        var start = await AdbProcess.RunAsync(adb, ["-s", serial, "shell", "am", "start", "-W", "-n", "dev.local.rebuy/.MainActivity", "-a", "android.intent.action.SEND", "-t", "image/png", "--eu", "android.intent.extra.STREAM", uri], null, cancellation, TimeSpan.FromSeconds(10));
        if (start.ExitCode != 0 || !Regex.IsMatch(start.Output, @"(?m)^\s*Status:\s*ok\s*$"))
            throw new UserMessageException("이미지는 전달했지만 앱을 열지 못했습니다. BlueStacks에서 다시담기를 열어 상태를 확인해 주세요.");
    }

    private static async Task AwaitReadyAsync(string adb, string serial, string id, CancellationToken cancellation)
    {
        using var readyLimit = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
        readyLimit.CancelAfter(TimeSpan.FromSeconds(5));
        try
        {
            while (true)
            {
                var result = await AdbProcess.RunAsync(adb, ["-s", serial, "shell", "content", "call", "--uri", Provider, "--method", "status", "--arg", id], null, readyLimit.Token, TimeSpan.FromSeconds(5));
                if (result.ExitCode != 0) throw new UserMessageException("전달한 이미지의 준비 상태를 확인하지 못했습니다. 최신 다시담기 앱을 확인해 주세요.");
                var status = Regex.Match(result.Output, @"(?:^|[\s{,])status=(READY|WRITING|REJECTED|MISSING)(?=[\s,}\]])");
                if (!status.Success) throw new UserMessageException("다시담기 앱이 이미지 준비 상태를 응답하지 않았습니다. 최신 앱을 확인해 주세요.");
                if (status.Groups[1].Value == "READY") return;
                if (status.Groups[1].Value == "REJECTED") throw new UserMessageException("Android 앱이 이미지를 받지 못했습니다. 24MB 이하의 일반 PNG 이미지인지 확인해 주세요.");
                await Task.Delay(200, readyLimit.Token);
            }
        }
        catch (OperationCanceledException) when (!cancellation.IsCancellationRequested)
        { throw new UserMessageException("Android 앱의 이미지 준비 시간이 초과됐습니다. 잠시 뒤 다시 보내 주세요."); }
    }
}

internal sealed record AdbResult(int ExitCode, string Output, string Error);

internal static class AdbProcess
{
    public static async Task<AdbResult> RunAsync(string adb, IReadOnlyList<string> arguments, byte[]? input, CancellationToken cancellation, TimeSpan timeout)
    {
        var info = new ProcessStartInfo(adb)
        {
            UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardOutput = true, RedirectStandardError = true, RedirectStandardInput = input is not null,
            StandardOutputEncoding = Encoding.UTF8, StandardErrorEncoding = Encoding.UTF8,
        };
        foreach (var argument in arguments) info.ArgumentList.Add(argument);
        using var process = new Process { StartInfo = info };
        using var limit = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
        limit.CancelAfter(timeout);
        try
        {
            if (!process.Start()) throw new UserMessageException("ADB를 실행하지 못했습니다.");
            var output = CaptureAsync(process.StandardOutput, limit.Token);
            var error = CaptureAsync(process.StandardError, limit.Token);
            try
            {
                if (input is not null)
                {
                    await process.StandardInput.BaseStream.WriteAsync(input, limit.Token);
                    await process.StandardInput.BaseStream.FlushAsync(limit.Token);
                    process.StandardInput.Close();
                }
                await process.WaitForExitAsync(limit.Token);
                return new AdbResult(process.ExitCode, await output, await error);
            }
            catch
            {
                try { if (!process.HasExited) process.Kill(entireProcessTree: true); } catch { }
                try { await process.WaitForExitAsync(CancellationToken.None); } catch { }
                // Observe both readers even after cancellation so no stream task is left unobserved.
                try { await Task.WhenAll(output, error); } catch { }
                throw;
            }
        }
        catch (Win32Exception) { throw new UserMessageException("ADB를 실행하지 못했습니다. 올바른 Windows adb.exe를 선택해 주세요."); }
        catch (OperationCanceledException) when (!cancellation.IsCancellationRequested)
        { throw new UserMessageException("ADB 응답 시간이 초과됐습니다. BlueStacks 실행 상태를 확인해 주세요."); }
        catch (IOException) { throw new UserMessageException("ADB로 이미지를 전달하지 못했습니다. Android 앱과 BlueStacks 연결을 확인해 주세요."); }
    }

    private static async Task<string> CaptureAsync(StreamReader reader, CancellationToken cancellation)
    {
        var result = new StringBuilder();
        var buffer = new char[1024];
        while (true)
        {
            var count = await reader.ReadAsync(buffer.AsMemory(), cancellation);
            if (count == 0) return result.ToString();
            var remaining = 65_536 - result.Length;
            if (remaining > 0) result.Append(buffer, 0, Math.Min(remaining, count));
        }
    }
}

internal sealed class UserMessageException(string message) : Exception(message);
