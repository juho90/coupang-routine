using System.Runtime.InteropServices;
using System.Text;

namespace RebuyClipboard;

internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        Console.OutputEncoding = new UTF8Encoding(false);
        using var cancellation = new CancellationTokenSource(TimeSpan.FromSeconds(45));
        ConsoleCancelEventHandler cancel = (_, eventArgs) => { eventArgs.Cancel = true; cancellation.Cancel(); };
        Console.CancelKeyPress += cancel;
        try { return RunAsync(CliOptions.Parse(args), cancellation.Token).GetAwaiter().GetResult(); }
        catch (UserMessageException failure) { Console.Error.WriteLine(failure.Message); return 2; }
        catch (OperationCanceledException) { Console.Error.WriteLine("작업이 중단되었거나 제한 시간을 넘었습니다."); return 3; }
        catch (ExternalException) { Console.Error.WriteLine("클립보드 이미지를 읽지 못했습니다. 이미지를 다시 복사해 주세요."); return 2; }
        catch { Console.Error.WriteLine("이미지를 처리하지 못했습니다. 입력 이미지와 Android 연결 상태를 확인해 주세요."); return 4; }
        finally { Console.CancelKeyPress -= cancel; }
    }

    private static async Task<int> RunAsync(CliOptions options, CancellationToken cancellation)
    {
        if (options.Command == CliCommand.Help) { PrintHelp(); return 0; }
        if (options.Command == CliCommand.Version)
        {
            Console.WriteLine("RebuyClipboard " + typeof(Program).Assembly.GetName().Version?.ToString(3));
            return 0;
        }
        if (options.Command is CliCommand.SampleShow or CliCommand.SampleOpen)
        {
            var sample = SampleProduct.Load();
            if (options.Command == CliCommand.SampleShow) Console.WriteLine(sample.ToJson());
            else
            {
                await SampleProductTransfer.OpenAsync(sample, AdbDiscovery.Resolve(options.Adb), options.Device, new ConsoleProgress(), cancellation);
                Console.WriteLine("샘플 URL 열기 요청이 Android에 전달됐습니다. 브라우저를 거쳐 쿠팡 앱으로 연결될 수 있으므로 실제 상품 화면을 확인해 주세요. 담기·구매는 실행하지 않았습니다.");
            }
            return 0;
        }

        if (options.Command == CliCommand.Devices)
        {
            var result = await AdbProcess.RunAsync(AdbDiscovery.Resolve(options.Adb), ["devices", "-l"], null, cancellation, TimeSpan.FromSeconds(8));
            if (result.ExitCode != 0) throw new UserMessageException("연결된 Android 장치를 조회하지 못했습니다. ADB 실행 상태를 확인해 주세요.");
            Console.Write(result.Output);
            return 0;
        }

        // Run before the first await: Clipboard.GetImage requires Main's STA thread.
        byte[] png;
        if (options.Clipboard)
        {
            using var image = Clipboard.GetImage();
            if (image is null) throw new UserMessageException("Windows 클립보드에 이미지가 없습니다. Win+Shift+S로 캡처한 뒤 다시 실행해 주세요.");
            png = ImagePayload.FromImage(image);
        }
        else png = ImagePayload.FromFile(options.ImageFile!);

        if (options.Command == CliCommand.Check)
        {
            Console.WriteLine($"이미지 확인 완료 ({png.Length:N0} PNG bytes). 전송하지 않았습니다.");
            return 0;
        }
        await AndroidTransfer.SendAsync(png, AdbDiscovery.Resolve(options.Adb), options.Device, new ConsoleProgress(), cancellation);
        Console.WriteLine("전송 완료. 다시담기 앱에서 읽은 내용을 검토한 뒤 적용해 주세요.");
        return 0;
    }

    private static void PrintHelp() => Console.WriteLine("""
        다시담기 이미지 전송 CLI
        사용법:
          RebuyClipboard.exe check --image <파일>
          RebuyClipboard.exe send --image <파일> [--adb <adb.exe>] [--device <기기>]
          RebuyClipboard.exe send --clipboard [--adb <adb.exe>] [--device <기기>]
          RebuyClipboard.exe devices [--adb <adb.exe>]
          RebuyClipboard.exe sample show
          RebuyClipboard.exe sample open [--adb <adb.exe>] [--device <기기>]
          RebuyClipboard.exe --help
          RebuyClipboard.exe --version

        check는 이미지 확인만 합니다. send는 Android의 이미지 검토 화면을 엽니다.
        클립보드는 send --clipboard를 실행할 때만 읽습니다. 상품 저장과 구매는 하지 않습니다.
        기본 기기: 127.0.0.1:5555. 인자 없이 실행하면 이 도움말을 표시합니다.
        종료 코드: 0 성공, 2 입력·전송 오류, 3 중단·전체 시간 초과, 4 예상하지 못한 오류
        """);

    private sealed class ConsoleProgress : IProgress<string>
    {
        public void Report(string value) => Console.Error.WriteLine(value);
    }
}

internal enum CliCommand { Help, Version, Check, Send, Devices, SampleShow, SampleOpen }

internal sealed record CliOptions(CliCommand Command, string? ImageFile = null, bool Clipboard = false, string? Adb = null, string Device = "127.0.0.1:5555")
{
    public static CliOptions Parse(string[] args)
    {
        if (args.Length == 0) return new(CliCommand.Help);
        if (args.Length == 1 && args[0] is "--help" or "-h" or "help") return new(CliCommand.Help);
        if (args.Length == 1 && args[0] == "--version") return new(CliCommand.Version);
        if (args[0] == "sample")
        {
            if (args.Length == 1 || args[1] is "--help" or "-h") return new(CliCommand.Help);
            var sampleCommand = args[1] switch
            {
                "show" => CliCommand.SampleShow,
                "open" => CliCommand.SampleOpen,
                _ => throw new UserMessageException("sample show 또는 sample open을 사용해 주세요."),
            };
            string? sampleAdb = null;
            var sampleDevice = "127.0.0.1:5555";
            var sampleSeen = new HashSet<string>(StringComparer.Ordinal);
            for (var index = 2; index < args.Length; index++)
            {
                var option = args[index];
                if (option is "--help" or "-h") return new(CliCommand.Help);
                if (sampleCommand == CliCommand.SampleShow || option is not ("--adb" or "--device") || !sampleSeen.Add(option))
                    throw new UserMessageException("sample show에는 추가 옵션이 없고 sample open에는 --adb와 --device만 사용할 수 있습니다.");
                if (++index >= args.Length || string.IsNullOrWhiteSpace(args[index]) || args[index].StartsWith("--", StringComparison.Ordinal))
                    throw new UserMessageException($"{option} 뒤에 필요한 값을 지정해 주세요.");
                if (option == "--adb") sampleAdb = args[index]; else sampleDevice = args[index];
            }
            return new(sampleCommand, Adb: sampleAdb, Device: sampleDevice);
        }

        var command = args[0] switch
        {
            "check" => CliCommand.Check,
            "send" => CliCommand.Send,
            "devices" => CliCommand.Devices,
            _ => throw new UserMessageException("지원하지 않는 명령입니다. --help로 사용법을 확인해 주세요."),
        };
        string? image = null, adb = null;
        var device = "127.0.0.1:5555";
        bool clipboard = false, help = false;
        var seen = new HashSet<string>(StringComparer.Ordinal);
        for (var index = 1; index < args.Length; index++)
        {
            var option = args[index];
            if (!seen.Add(option)) throw new UserMessageException($"중복된 옵션입니다: {option}");
            string Value()
            {
                if (++index >= args.Length || string.IsNullOrWhiteSpace(args[index]) || args[index].StartsWith("--", StringComparison.Ordinal))
                    throw new UserMessageException($"{option} 뒤에 필요한 값을 지정해 주세요.");
                return args[index];
            }
            switch (option)
            {
                case "--image": image = Value(); break;
                case "--adb": adb = Value(); break;
                case "--device": device = Value(); break;
                case "--clipboard": clipboard = true; break;
                case "--help": case "-h": help = true; break;
                default: throw new UserMessageException($"지원하지 않는 옵션입니다: {option}. --help로 사용법을 확인해 주세요.");
            }
        }
        if (help) return new(CliCommand.Help);
        if (command == CliCommand.Devices)
        {
            if (image is not null || clipboard || seen.Contains("--device"))
                throw new UserMessageException("devices에는 --adb 옵션만 사용할 수 있습니다.");
        }
        else
        {
            if ((image is not null) == clipboard)
                throw new UserMessageException("--image <파일> 또는 --clipboard 중 하나만 지정해 주세요.");
            if (command == CliCommand.Check && (clipboard || adb is not null || seen.Contains("--device")))
                throw new UserMessageException("check에는 --image <파일>만 사용할 수 있습니다.");
        }
        return new(command, image, clipboard, adb, device);
    }
}
