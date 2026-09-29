using System.Collections.Concurrent;
using System.Buffers.Binary;
using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace FlashBox;

internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        Console.InputEncoding = new UTF8Encoding(false);
        Console.OutputEncoding = new UTF8Encoding(false);
        try
        {
            var options = Options.Parse(args);
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            using var host = new PlayerHost(options);
            Application.Run(host);
            return host.Failed ? 1 : 0;
        }
        catch (Exception error)
        {
            Console.WriteLine(JsonSerializer.Serialize(new { kind = "error", message = error.Message, fatal = true }));
            return 1;
        }
    }
}

internal sealed record KeyBinding(string Key, string Code, int VirtualKey);
internal sealed record Options(string Swf, int Width, int Height, bool Preview, KeyBinding[] P1, KeyBinding[] P2, string Format, bool Metrics)
{
    public static Options Parse(string[] args)
    {
        string? swf = null, bindings = null;
        int width = 640, height = 480;
        bool preview = false, metrics = false;
        string format = "jpeg";
        for (int i = 0; i < args.Length; i++)
        {
            string value() => ++i < args.Length ? args[i] : throw new ArgumentException("Missing argument value.");
            switch (args[i])
            {
                case "--swf": swf = value(); break;
                case "--width": width = int.Parse(value()); break;
                case "--height": height = int.Parse(value()); break;
                case "--bindings": bindings = value(); break;
                case "--preview": preview = true; break;
                case "--format": format = value(); break;
                case "--metrics": metrics = true; break;
                default: throw new ArgumentException("Unknown helper argument.");
            }
        }
        if (swf == null || !Path.IsPathFullyQualified(swf) || !File.Exists(swf))
            throw new ArgumentException("--swf must name an existing absolute local path.");
        if (width < 160 || width > 1280 || height < 120 || height > 960)
            throw new ArgumentException("Viewport out of range (160..1280 x 120..960).");
        if (format != "png" && format != "jpeg") throw new ArgumentException("Frame format must be png or jpeg.");
        KeyBinding[] p1 = { new("ArrowLeft", "ArrowLeft", 37), new("ArrowRight", "ArrowRight", 39), new("ArrowUp", "ArrowUp", 38), new("ArrowDown", "ArrowDown", 40), new(" ", "Space", 32) };
        KeyBinding[] p2 = { new("a", "KeyA", 65), new("d", "KeyD", 68), new("w", "KeyW", 87), new("s", "KeyS", 83), new("Shift", "ShiftLeft", 16) };
        if (bindings != null)
        {
            if (new FileInfo(bindings).Length > 8192) throw new ArgumentException("Bindings file exceeds 8 KiB.");
            using var doc = JsonDocument.Parse(File.ReadAllText(bindings));
            KeyBinding[] read(string name)
            {
                var result = doc.RootElement.GetProperty(name).EnumerateArray().Select(k => new KeyBinding(
                    k.GetProperty("key").GetString() ?? "", k.GetProperty("code").GetString() ?? "", k.GetProperty("virtualKey").GetInt32())).ToArray();
                if (result.Length != 5 || result.Any(k => k.Key.Length is < 1 or > 24 || k.Code.Length is < 1 or > 24 || k.VirtualKey is < 1 or > 255))
                    throw new ArgumentException("Each player binding must contain exactly five valid keys.");
                return result;
            }
            p1 = read("p1"); p2 = read("p2");
        }
        return new Options(Path.GetFullPath(swf), width, height, preview, p1, p2, format, metrics);
    }
}

internal sealed class PlayerHost : Form
{
    private const string Origin = "https://flashbox.invalid";
    private readonly Options options;
    private readonly WebView2 browser = new() { Dock = DockStyle.Fill };
    private readonly ConcurrentQueue<string> commands = new();
    private readonly object outputLock = new();
    private readonly Queue<(string Line, long Sequence)> controlOutput = new();
    private readonly Queue<(string Line, long Sequence)> audioOutput = new();
    private readonly SemaphoreSlim outputWake = new(0, 1);
    private readonly CancellationTokenSource lifetime = new();
    private (string Line, long Sequence)? latestOutputFrame;
    private bool outputComplete;
    private readonly Dictionary<string, (byte[] Bytes, string Mime)> assets = new(StringComparer.Ordinal);
    private readonly Dictionary<string, KeyBinding> pressed = new(StringComparer.Ordinal);
    private readonly string profile = Path.Combine(Path.GetTempPath(), "GameConsoleFlashBox", Guid.NewGuid().ToString("N"));
    private Task? writer;
    private bool ready, commandBusy, shuttingDown, mouseDown, paused;
    private int queued, commandPumpPosted;
    private long sequence;
    private string swfHash = "";
    public bool Failed { get; private set; }

    public PlayerHost(Options options)
    {
        this.options = options;
        Text = "方块电玩 Flash 实验播放器";
        ClientSize = new Size(options.Width, options.Height);
        FormBorderStyle = FormBorderStyle.FixedToolWindow;
        MaximizeBox = false;
        ShowInTaskbar = options.Preview;
        StartPosition = FormStartPosition.Manual;
        Location = options.Preview ? new Point(100, 100) : new Point(-32000, -32000);
        // Keep a real, non-minimized compositor surface. This window never takes focus in hidden mode.
        Controls.Add(browser);
        Load += async (_, _) => await StartAsync();
        FormClosing += (_, e) => { if (!shuttingDown) { e.Cancel = true; _ = ShutdownAsync(); } };
        FormClosed += (_, _) => Cleanup();
    }

    protected override bool ShowWithoutActivation => !options.Preview;

    private async Task StartAsync()
    {
        writer = Task.Run(async () =>
        {
            try
            {
                while (true)
                {
                    await outputWake.WaitAsync();
                    while (true)
                    {
                        (string Line, long Sequence)? line;
                        lock (outputLock)
                        {
                            if (controlOutput.Count != 0) line = controlOutput.Dequeue();
                            else if (audioOutput.Count != 0) line = audioOutput.Dequeue();
                            else { line = latestOutputFrame; latestOutputFrame = null; }
                            if (line == null && outputComplete) return;
                        }
                        if (line == null) break;
                        long writeStarted = Stopwatch.GetTimestamp();
                        await Console.Out.WriteLineAsync(line.Value.Line);
                        if (options.Metrics && line.Value.Sequence > 0)
                            Console.Error.WriteLine("PERF_WRITE " + JsonSerializer.Serialize(new { seq = line.Value.Sequence, writeMs = ElapsedMs(writeStarted), characters = line.Value.Line.Length }));
                    }
                }
            }
            catch { Failed = true; if (!IsDisposed && IsHandleCreated) BeginInvoke(Close); }
        });
        try
        {
            LoadAssets();
            var settings = new CoreWebView2EnvironmentOptions("--disable-background-timer-throttling --disable-renderer-backgrounding --disable-backgrounding-occluded-windows");
            var environment = await CoreWebView2Environment.CreateAsync(null, profile, settings);
            await browser.EnsureCoreWebView2Async(environment);
            var core = browser.CoreWebView2;
            core.Settings.AreDevToolsEnabled = false;
            core.Settings.AreDefaultContextMenusEnabled = false;
            core.Settings.AreBrowserAcceleratorKeysEnabled = false;
            core.Settings.IsStatusBarEnabled = false;
            core.Settings.IsZoomControlEnabled = false;
            core.Settings.AreHostObjectsAllowed = false;
            core.Settings.IsGeneralAutofillEnabled = false;
            core.Settings.IsPasswordAutosaveEnabled = false;
            core.PermissionRequested += (_, e) => { e.State = CoreWebView2PermissionState.Deny; e.Handled = true; };
            core.NewWindowRequested += (_, e) => e.Handled = true;
            core.DownloadStarting += (_, e) => e.Cancel = true;
            core.NavigationStarting += (_, e) => e.Cancel = e.Uri != Origin + "/index.html";
            core.FrameNavigationStarting += (_, e) => e.Cancel = true;
            core.AddWebResourceRequestedFilter("*", CoreWebView2WebResourceContext.All);
            core.WebResourceRequested += (_, e) =>
            {
                var uri = new Uri(e.Request.Uri);
                if (e.Request.Method == "GET" && uri.GetLeftPart(UriPartial.Authority) == Origin && uri.Query.Length == 0 && assets.TryGetValue(uri.AbsolutePath, out var asset))
                    e.Response = environment.CreateWebResourceResponse(new MemoryStream(asset.Bytes, false), 200, "OK", "Content-Type: " + asset.Mime + "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff");
                else
                    e.Response = environment.CreateWebResourceResponse(new MemoryStream(Array.Empty<byte>()), 403, "Blocked", "Content-Type: text/plain");
            };
            core.WebMessageReceived += async (_, e) =>
            {
                if (e.Source != Origin + "/index.html") return;
                try
                {
                    using var message = JsonDocument.Parse(e.WebMessageAsJson);
                    var kind = message.RootElement.GetProperty("type").GetString();
                    if (kind == "ready" && !ready)
                    {
                        ready = true;
                        if (paused) await SetPausedAsync(true);
                        Emit(new { kind = "ready", version = "0.1.3", runtime = "ruffle-nightly-2026-09-16-webview2", width = options.Width, height = options.Height, swfSha256 = swfHash, audio = "game-only-pcm-24000-mono", preview = options.Preview, format = options.Format });
                    }
                    else if (kind == "error") Fatal("Ruffle: " + message.RootElement.GetProperty("message").GetString());
                    else if (kind == "audio" && ready && !paused) {
                        string pcm = message.RootElement.GetProperty("pcm").GetString() ?? "";
                        if (pcm.Length != 3200 || Convert.FromBase64String(pcm).Length != 2400) throw new IOException("PCM bounds");
                        string line = JsonSerializer.Serialize(new { kind="audio",rate=24000,pcm });
                        lock(outputLock){if(audioOutput.Count>=4)audioOutput.Dequeue();audioOutput.Enqueue((line,0));}WakeOutput();
                    }
                    else if (kind == "audio-error") Emit(new { kind="audio-error",message="Game audio tap unavailable" });
                }
                catch (Exception e2) { Fatal("Invalid runtime message: " + e2.Message); }
            };
            core.ProcessFailed += (_, e) => Fatal("WebView process failed: " + e.ProcessFailedKind);
            core.Navigate(Origin + "/index.html");
            _ = Task.Run(ReadCommands);
            _ = CaptureLoopAsync();
        }
        catch (Exception e) { Fatal(e.Message); }
    }

    private void LoadAssets()
    {
        var source = new FileInfo(options.Swf);
        if (source.Length < 8 || source.Length > 64L * 1024 * 1024) throw new IOException("SWF size out of range.");
        var swf = File.ReadAllBytes(options.Swf);
        if (swf[1] != (byte)'W' || swf[2] != (byte)'S' || (swf[0] != (byte)'C' && swf[0] != (byte)'F' && swf[0] != (byte)'Z')) throw new IOException("Not a SWF file.");
        uint declared = BitConverter.ToUInt32(swf, 4);
        if (declared < 8 || declared > 128 * 1024 * 1024) throw new IOException("SWF declared size exceeds limit.");
        swfHash = Convert.ToHexString(SHA256.HashData(swf));
        assets.Add("/game.swf", (swf, "application/x-shockwave-flash"));
        var basePath = AppContext.BaseDirectory;
        assets.Add("/index.html", (File.ReadAllBytes(Path.Combine(basePath, "web", "index.html")), "text/html; charset=utf-8"));
        assets.Add("/player.js", (File.ReadAllBytes(Path.Combine(basePath, "web", "player.js")), "application/javascript"));
        foreach (string name in new[]{"audio-tap.js","audio-worklet.js"}) assets.Add("/"+name,(File.ReadAllBytes(Path.Combine(basePath,"web",name)),"application/javascript"));
        foreach (string file in Directory.EnumerateFiles(Path.Combine(basePath, "vendor"), "*", SearchOption.TopDirectoryOnly))
        {
            string extension = Path.GetExtension(file);
            if (extension != ".js" && extension != ".wasm") continue;
            assets.Add("/vendor/" + Path.GetFileName(file), (File.ReadAllBytes(file), extension == ".wasm" ? "application/wasm" : "application/javascript"));
        }
        if (!assets.ContainsKey("/vendor/ruffle.js")) throw new IOException("Fixed Ruffle runtime is missing.");
    }

    private void ReadCommands()
    {
        try
        {
            var buffer = new StringBuilder();
            while (!shuttingDown)
            {
                int ch = Console.In.Read();
                if (ch == -1) { EnqueueCommand("{\"op\":\"close\"}"); return; }
                if (ch == '\n')
                {
                    if (buffer.Length != 0)
                    {
                        EnqueueCommand(buffer.ToString()); buffer.Clear();
                    }
                }
                else if (ch != '\r')
                {
                    if (buffer.Length >= 8192) throw new IOException("Command exceeds 8 KiB.");
                    buffer.Append((char)ch);
                }
            }
        }
        catch (Exception e) { if (!IsDisposed && IsHandleCreated) BeginInvoke(() => Fatal(e.Message)); }
    }

    private void EnqueueCommand(string command)
    {
        if (shuttingDown) return;
        if (Interlocked.Increment(ref queued) > 64) throw new IOException("Command queue overflow.");
        commands.Enqueue(command);
        RequestCommandPump();
    }

    private void RequestCommandPump()
    {
        if (shuttingDown || IsDisposed || !IsHandleCreated) return;
        if (Interlocked.CompareExchange(ref commandPumpPosted, 1, 0) != 0) return;
        BeginInvoke(async () =>
        {
            Interlocked.Exchange(ref commandPumpPosted, 0);
            await DrainCommandsAsync();
        });
    }

    private async Task DrainCommandsAsync()
    {
        if (commandBusy || shuttingDown) return;
        commandBusy = true;
        try
        {
            for (int i = 0; i < 16 && commands.TryDequeue(out var command); i++)
            {
                Interlocked.Decrement(ref queued);
                await CommandAsync(command);
                if (shuttingDown) return;
            }
        }
        catch (Exception e) { Fatal(e.Message); }
        finally
        {
            commandBusy = false;
            if (!commands.IsEmpty && !shuttingDown) RequestCommandPump();
        }
    }

    private async Task CaptureLoopAsync()
    {
        // One capture at a time; the ordered input pump stays independent of it.
        try
        {
            while (!shuttingDown)
            {
                long started = Stopwatch.GetTimestamp();
                if (ready) await CaptureFrameAsync();
                double elapsedMs = (Stopwatch.GetTimestamp() - started) * 1000.0 / Stopwatch.Frequency;
                await Task.Delay(TimeSpan.FromMilliseconds(Math.Max(1, 1000.0 / 30 - elapsedMs)), lifetime.Token);
            }
        }
        catch (OperationCanceledException) when (shuttingDown) { }
        catch (Exception e) { if (!shuttingDown) Fatal(e.Message); }
    }

    private async Task CaptureFrameAsync()
    {
        bool jpeg = options.Format == "jpeg";
        long started = Stopwatch.GetTimestamp();
        using var capture = new MemoryStream();
        await browser.CoreWebView2.CapturePreviewAsync(jpeg ? CoreWebView2CapturePreviewImageFormat.Jpeg : CoreWebView2CapturePreviewImageFormat.Png, capture);
        if (shuttingDown) return;
        double captureMs = ElapsedMs(started);
        if (capture.Length < 24 || capture.Length > 6 * 1024 * 1024) throw new IOException("Captured frame size out of range.");
        long normalizeStarted = Stopwatch.GetTimestamp();
        var bytes = capture.GetBuffer();
        int captureWidth, captureHeight;
        if (jpeg)
        {
            capture.Position = 0;
            using var header = Image.FromStream(capture);
            captureWidth = header.Width; captureHeight = header.Height;
        }
        else
        {
            captureWidth = BinaryPrimitives.ReadInt32BigEndian(bytes.AsSpan(16, 4));
            captureHeight = BinaryPrimitives.ReadInt32BigEndian(bytes.AsSpan(20, 4));
        }
        using var resized = new MemoryStream();
        MemoryStream encodedImage = capture;
        if (captureWidth != options.Width || captureHeight != options.Height)
        {
            capture.Position = 0;
            using var captured = Image.FromStream(capture);
            using var normalized = new Bitmap(captured, options.Width, options.Height);
            if (jpeg)
            {
                var codec = System.Drawing.Imaging.ImageCodecInfo.GetImageEncoders().Single(c => c.FormatID == System.Drawing.Imaging.ImageFormat.Jpeg.Guid);
                using var quality = new System.Drawing.Imaging.EncoderParameters(1);
                quality.Param[0] = new System.Drawing.Imaging.EncoderParameter(System.Drawing.Imaging.Encoder.Quality, 90L);
                normalized.Save(resized, codec, quality);
            }
            else normalized.Save(resized, System.Drawing.Imaging.ImageFormat.Png);
            encodedImage = resized;
        }
        double normalizeMs = ElapsedMs(normalizeStarted);
        long encodeStarted = Stopwatch.GetTimestamp();
        string encoded = Convert.ToBase64String(encodedImage.GetBuffer(), 0, (int)encodedImage.Length);
        long seq = ++sequence;
        if (jpeg) Emit(new { kind = "frame", seq, width = options.Width, height = options.Height, jpeg = encoded }, frame: true);
        else Emit(new { kind = "frame", seq, width = options.Width, height = options.Height, png = encoded }, frame: true);
        if (options.Metrics)
            Console.Error.WriteLine("PERF_CAPTURE " + JsonSerializer.Serialize(new { seq, captureMs, normalizeMs, encodeAndQueueMs = ElapsedMs(encodeStarted), captureWidth, captureHeight, bytes = encodedImage.Length, format = options.Format }));
    }

    private static double ElapsedMs(long started) => (Stopwatch.GetTimestamp() - started) * 1000.0 / Stopwatch.Frequency;

    private async Task CommandAsync(string line)
    {
        using var doc = JsonDocument.Parse(line, new JsonDocumentOptions { MaxDepth = 8 });
        var command = doc.RootElement;
        switch (command.GetProperty("op").GetString())
        {
            case "keys":
                int p1 = command.GetProperty("p1").GetInt32(), p2 = command.GetProperty("p2").GetInt32();
                if (p1 < 0 || p1 > 31 || p2 < 0 || p2 > 31) throw new IOException("Invalid input mask.");
                if (ready && !paused) await SetKeysAsync(p1, p2);
                break;
            case "mouse":
                int x = command.GetProperty("x").GetInt32(), y = command.GetProperty("y").GetInt32();
                bool down = command.GetProperty("down").GetBoolean();
                if (x < 0 || x >= options.Width || y < 0 || y >= options.Height) throw new IOException("Mouse outside viewport.");
                if (!ready || paused) break;
                await DevTools("Input.dispatchMouseEvent", new { type = "mouseMoved", x, y, button = mouseDown ? "left" : "none", buttons = mouseDown ? 1 : 0 });
                if (down != mouseDown) await DevTools("Input.dispatchMouseEvent", new { type = down ? "mousePressed" : "mouseReleased", x, y, button = "left", buttons = down ? 1 : 0, clickCount = 1 });
                mouseDown = down;
                break;
            case "pause":
                lock(outputLock)audioOutput.Clear();
                await ReleaseAsync(); paused = true;
                if (ready) await SetPausedAsync(true);
                break;
            case "resume":
                await ReleaseAsync();
                if (ready) await SetPausedAsync(false);
                paused = false;
                break;
            case "close": await ShutdownAsync(); break;
            default: throw new IOException("Unknown IPC operation.");
        }
    }

    private async Task SetKeysAsync(int p1, int p2)
    {
        var next = new Dictionary<string, KeyBinding>(StringComparer.Ordinal);
        void add(int mask, KeyBinding[] bindings) { for (int i = 0; i < 5; i++) if ((mask & (1 << i)) != 0) next[bindings[i].Code] = bindings[i]; }
        add(p1, options.P1); add(p2, options.P2);
        foreach (var key in pressed.Where(k => !next.ContainsKey(k.Key)).Select(k => k.Value).ToArray()) await DispatchKeyAsync(key, false);
        foreach (var key in next.Where(k => !pressed.ContainsKey(k.Key)).Select(k => k.Value)) await DispatchKeyAsync(key, true);
        pressed.Clear(); foreach (var item in next) pressed.Add(item.Key, item.Value);
    }

    private Task DispatchKeyAsync(KeyBinding key, bool down) => DevTools("Input.dispatchKeyEvent", new { type = down ? "keyDown" : "keyUp", key = key.Key, code = key.Code, windowsVirtualKeyCode = key.VirtualKey, nativeVirtualKeyCode = key.VirtualKey, autoRepeat = false });
    private Task DevTools(string method, object parameters) => browser.CoreWebView2.CallDevToolsProtocolMethodAsync(method, JsonSerializer.Serialize(parameters));

    private async Task SetPausedAsync(bool value)
    {
        string result = await browser.CoreWebView2.ExecuteScriptAsync(value ? "window.flashBoxPause()" : "window.flashBoxResume()");
        if (result != "true") throw new IOException("Ruffle did not confirm " + (value ? "pause" : "resume") + ".");
    }

    private async Task ReleaseAsync()
    {
        if (browser.CoreWebView2 == null) return;
        foreach (var key in pressed.Values) await DispatchKeyAsync(key, false);
        pressed.Clear();
        if (mouseDown) await DevTools("Input.dispatchMouseEvent", new { type = "mouseReleased", x = 0, y = 0, button = "left", buttons = 0, clickCount = 1 });
        mouseDown = false;
    }

    private async Task ShutdownAsync()
    {
        if (shuttingDown) return;
        shuttingDown = true; lifetime.Cancel();
        try { await ReleaseAsync(); } catch { }
        browser.Dispose();
        GC.Collect();
        await Task.Delay(150);
        // Keep the UI message pump alive while WebView releases profile file handles.
        string root = Path.GetFullPath(Path.Combine(Path.GetTempPath(), "GameConsoleFlashBox")) + Path.DirectorySeparatorChar;
        string target = Path.GetFullPath(profile);
        if (target.StartsWith(root, StringComparison.OrdinalIgnoreCase))
        {
            for (int i = 0; i < 40; i++)
            {
                try { if (Directory.Exists(target)) Directory.Delete(target, true); break; }
                catch { await Task.Delay(100); }
            }
        }
        Close();
    }

    private void WakeOutput()
    {
        try { outputWake.Release(); } catch (SemaphoreFullException) { }
    }

    private void Emit(object value, bool frame = false)
    {
        string line = JsonSerializer.Serialize(value);
        lock (outputLock)
        {
            if (outputComplete) return;
            if (frame) latestOutputFrame = (line, sequence);
            else controlOutput.Enqueue((line, 0));
        }
        // One pending PNG plus the in-flight pipe write. Slow readers get the
        // newest frame rather than accumulating increasingly old game frames.
        WakeOutput();
    }
    private void Fatal(string message)
    {
        if (shuttingDown) return;
        Failed = true;
        string safe = message.Length > 1000 ? message[..1000] : message;
        Console.Error.WriteLine(safe);
        lock (outputLock) { controlOutput.Clear(); latestOutputFrame = null; }
        Emit(new { kind = "error", message = safe, fatal = true });
        _ = ShutdownAsync();
    }

    private void Cleanup()
    {
        lifetime.Cancel(); browser.Dispose();
        lock (outputLock) { latestOutputFrame = null; audioOutput.Clear(); outputComplete = true; }
        WakeOutput();
        try { writer?.Wait(1000); } catch { }
        if (Directory.Exists(profile)) Console.Error.WriteLine("Temporary browser profile remains locked; no game saves are imported.");
    }
}
