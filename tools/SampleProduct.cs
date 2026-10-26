using System.Text.Json;
using System.Text.RegularExpressions;

namespace RebuyClipboard;

internal sealed record SampleProduct(int SchemaVersion, string Id, string ProductUrl, string? ExpectedProductName, string? ExpectedOption)
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        WriteIndented = true,
    };

    public static SampleProduct Load()
    {
        using var stream = typeof(SampleProduct).Assembly.GetManifestResourceStream("RebuyClipboard.TestData.CoupangProduct.json")
            ?? throw new UserMessageException("공통 샘플 데이터가 없습니다. CLI를 다시 게시해 주세요.");
        try
        {
            var sample = JsonSerializer.Deserialize<SampleProduct>(stream, JsonOptions);
            if (sample is null || sample.SchemaVersion != 1 || string.IsNullOrWhiteSpace(sample.Id) ||
                string.IsNullOrWhiteSpace(sample.ProductUrl) || sample.ProductUrl.Any(char.IsControl) ||
                !Uri.TryCreate(sample.ProductUrl, UriKind.Absolute, out var uri) || uri.Scheme != "https" ||
                !uri.IsDefaultPort || !string.IsNullOrEmpty(uri.UserInfo) ||
                uri.Host is not ("link.coupang.com" or "www.coupang.com" or "coupang.com"))
                throw new UserMessageException("샘플 데이터의 버전·식별자·쿠팡 HTTPS URL을 확인해 주세요.");
            return sample;
        }
        catch (JsonException) { throw new UserMessageException("공통 샘플 JSON을 읽지 못했습니다. 테스트 데이터를 확인한 뒤 다시 게시해 주세요."); }
    }

    public string ToJson() => JsonSerializer.Serialize(this, JsonOptions);
}

internal static class SampleProductTransfer
{
    public static async Task OpenAsync(SampleProduct sample, string adb, string serial, IProgress<string>? progress, CancellationToken cancellation)
    {
        progress?.Report("공통 샘플 URL로 쿠팡 앱 연결을 확인하고 있습니다.");
        await AndroidConnection.ConnectAsync(adb, serial, cancellation);
        var installed = await AdbProcess.RunAsync(adb, ["-s", serial, "shell", "pm", "path", "com.coupang.mobile"], null, cancellation, TimeSpan.FromSeconds(5));
        if (installed.ExitCode != 0 || !Regex.IsMatch(installed.Output, @"(?m)^package:"))
            throw new UserMessageException("선택한 Android 장치에 쿠팡 앱이 설치되어 있지 않습니다.");
        // adb shell joins argv for the remote shell; quote the complete URL for that boundary.
        var quotedUrl = "'" + sample.ProductUrl.Replace("'", "'\"'\"'", StringComparison.Ordinal) + "'";
        var start = await AdbProcess.RunAsync(adb,
            ["-s", serial, "shell", "am", "start", "-W", "-a", "android.intent.action.VIEW", "-c", "android.intent.category.BROWSABLE", "-d", quotedUrl],
            null, cancellation, TimeSpan.FromSeconds(10));
        if (start.ExitCode != 0 || !Regex.IsMatch(start.Output, @"(?m)^\s*Status:\s*ok\s*$"))
            throw new UserMessageException("Android에서 샘플 URL 열기 요청을 처리하지 못했습니다. 연결 상태와 공유 링크를 확인해 주세요.");
    }
}
