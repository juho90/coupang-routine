using System.Drawing;
using System.Text.Json;
using System.Text.RegularExpressions;

var root = Environment.GetEnvironmentVariable("REBUY_MOCK_STATE")
    ?? throw new InvalidOperationException("Mock state path is required.");
Directory.CreateDirectory(root);
File.AppendAllText(Path.Combine(root, "argv.jsonl"), JsonSerializer.Serialize(args) + Environment.NewLine);
var serial = "127.0.0.1:5555";
var provider = "content://dev.local.rebuy.pc-images";
var productUrl = Environment.GetEnvironmentVariable("REBUY_MOCK_PRODUCT_URL");
bool Equal(params string[] expected) => args.SequenceEqual(expected);
int Fail(string message) { Console.Error.WriteLine("MOCK: " + message); return 91; }

if (Equal("devices", "-l"))
{
    Console.WriteLine("List of devices attached");
    Console.WriteLine(serial + " device product:synthetic model:Mock_Android device:mock");
    return 0;
}
if (Equal("connect", serial)) { Console.WriteLine("connected to " + serial); return 0; }
if (Equal("-s", serial, "get-state")) { Console.WriteLine("device"); return 0; }
if (Equal("-s", serial, "shell", "pm", "path", "dev.local.rebuy"))
{ Console.WriteLine("package:/synthetic/base.apk"); return 0; }


if (Equal("-s", serial, "shell", "pm", "path", "com.coupang.mobile"))
{ Console.WriteLine("package:/synthetic/coupang.apk"); return 0; }
if (productUrl is not null && Equal("-s", serial, "shell", "am", "start", "-W", "-a", "android.intent.action.VIEW",
    "-d", "'" + productUrl + "'", "-p", "com.coupang.mobile"))
{ Console.WriteLine("Status: ok"); return 0; }
if (args.Length == 7 && args.Take(6).SequenceEqual(new[] { "-s", serial, "shell", "-T", "-e", "none" }))
{
    var match = Regex.Match(args[6], @"^base64 -d \| content write --uri content://dev\.local\.rebuy\.pc-images/([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})$");
    if (!match.Success) return Fail("Unexpected write command or URI.");
    byte[] png;
    try { png = Convert.FromBase64String(Console.In.ReadToEnd()); }
    catch (FormatException) { return Fail("Input is not valid base64."); }
    byte[] signature = [137, 80, 78, 71, 13, 10, 26, 10];
    if (!png.AsSpan().StartsWith(signature)) return Fail("Payload is not PNG.");
    using var memory = new MemoryStream(png);
    using var image = Image.FromStream(memory, false, true);
    using var bitmap = new Bitmap(image);
    if (bitmap.Width != 32 || bitmap.Height != 24) return Fail("Payload dimensions changed.");
    if (bitmap.GetPixel(0, 0).ToArgb() != Color.Red.ToArgb() ||
        bitmap.GetPixel(31, 23).ToArgb() != Color.Blue.ToArgb())
        return Fail("Payload pixel content changed.");
    File.WriteAllBytes(Path.Combine(root, "received.png"), png);
    File.WriteAllText(Path.Combine(root, "id.txt"), match.Groups[1].Value);
    return 0;
}
var idFile = Path.Combine(root, "id.txt");
if (File.Exists(idFile))
{
    var id = File.ReadAllText(idFile);
    var uri = provider + "/" + id;
    if (Equal("-s", serial, "shell", "content", "call", "--uri", provider, "--method", "status", "--arg", id))
    { Console.WriteLine("Result: Bundle[{status=READY}]"); return 0; }
    if (Equal("-s", serial, "shell", "am", "start", "-W", "-n", "dev.local.rebuy/.MainActivity",
        "-a", "android.intent.action.SEND", "-t", "image/png", "--eu", "android.intent.extra.STREAM", uri))
    { Console.WriteLine("Status: ok"); return 0; }
}
return Fail("Unexpected argv: " + JsonSerializer.Serialize(args));
