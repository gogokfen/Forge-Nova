// Forge Nova launcher: one click installs or updates Java, the pinned Forge build and Forge Nova, then starts the game.
//
// Everything it installs comes from the GitHub release marked "latest" in Repo (see nova/tools/release.ps1):
//   nova-manifest.json   which Nova version is current, and where its Forge build, Java and the launcher itself live
//   ForgeNova-<v>.zip    forge-nova.cmd + nova/ (prebuilt, so players need no JDK)
//   ForgeNova.exe        this launcher (it replaces itself when the manifest lists a newer LauncherVersion)
// The Forge build lives in its own "forge-<id>" release, because one Forge build serves many Nova versions.
//
// The launcher only ever writes into a folder it created (marked by nova-install.json) and refuses to touch a git
// checkout, so a developer's working copy is never overwritten. Build with nova/launcher/build.cmd (C# 5, .NET 4.5+).
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Net;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Forms;

[assembly: AssemblyTitle("Forge Nova")]
[assembly: AssemblyProduct("Forge Nova")]
[assembly: AssemblyDescription("Installs, updates and starts Forge Nova")]
[assembly: AssemblyVersion("1.0.0.0")]

namespace ForgeNova
{
    static class Program
    {
        /// <summary>Bump when the launcher changes; installed launchers replace themselves with a newer one.</summary>
        public const int LauncherVersion = 1;
        public const string Repo = "gogokfen/Forge-Nova";
        public const string AppName = "Forge Nova";
        public const string StateFile = "nova-install.json";

        [STAThread]
        static void Main(string[] args)
        {
            ServicePointManager.SecurityProtocol = (SecurityProtocolType)3072; // TLS 1.2 (GitHub requires it)
            AppDomain.CurrentDomain.UnhandledException += delegate (object o, UnhandledExceptionEventArgs e) { Log("crash: " + e.ExceptionObject); };
            Application.ThreadException += delegate (object o, ThreadExceptionEventArgs e) { Log("ui error: " + e.Exception); };
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new LauncherForm(args));
        }

        /// <summary>Appends to launcher.log next to the exe (what friends send when something goes wrong).</summary>
        public static void Log(string line)
        {
            try
            {
                string file = Path.Combine(Path.GetDirectoryName(Application.ExecutablePath), "launcher.log");
                var info = new FileInfo(file);
                if (info.Exists && info.Length > 512 * 1024) info.Delete();
                File.AppendAllText(file, DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + "  " + line + Environment.NewLine);
            }
            catch
            {
            }
        }

        public static string ManifestUrl
        {
            get
            {
                string over = Environment.GetEnvironmentVariable("NOVA_MANIFEST_URL"); // local testing of releases
                return string.IsNullOrEmpty(over)
                    ? "https://github.com/" + Repo + "/releases/latest/download/nova-manifest.json"
                    : over;
            }
        }

        /// <summary>A git checkout (the developer's working copy): never download into it.</summary>
        public static bool IsDevCheckout(string dir)
        {
            return Directory.Exists(Path.Combine(dir, ".git")) || File.Exists(Path.Combine(dir, ".git"))
                || File.Exists(Path.Combine(dir, "nova", ".dev"));
        }

        public static string DefaultInstallDir
        {
            get
            {
                return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), AppName);
            }
        }
    }

    sealed class UpdateCancelled : Exception
    {
    }

    sealed class LauncherForm : Form
    {
        static readonly Color Bg = Color.FromArgb(14, 17, 22);
        static readonly Color PanelBg = Color.FromArgb(24, 29, 38);
        static readonly Color Text1 = Color.FromArgb(232, 236, 242);
        static readonly Color Text2 = Color.FromArgb(150, 160, 175);
        static readonly Color Accent = Color.FromArgb(240, 128, 40);

        readonly string[] args;
        readonly string exePath;
        readonly string exeDir;
        string installDir;
        bool devMode;
        volatile bool closing;

        readonly Label status = new Label();
        readonly Label detail = new Label();
        readonly ProgressBar bar = new ProgressBar();
        readonly Button playBtn = new Button();
        readonly Button retryBtn = new Button();
        readonly LinkLabel folderLink = new LinkLabel();

        public LauncherForm(string[] args)
        {
            this.args = args;
            exePath = Application.ExecutablePath;
            exeDir = Path.GetDirectoryName(exePath);

            Text = Program.AppName;
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            AutoScaleMode = AutoScaleMode.Dpi;
            ClientSize = new Size(560, 300);
            BackColor = Bg;
            ForeColor = Text1;
            Font = new Font("Segoe UI", 9.5f);
            try { Icon = Icon.ExtractAssociatedIcon(exePath); } catch { }

            var logo = new PictureBox { Location = new Point(28, 28), Size = new Size(120, 120), SizeMode = PictureBoxSizeMode.Zoom, BackColor = Color.Transparent };
            using (Stream s = Assembly.GetExecutingAssembly().GetManifestResourceStream("ForgeNova.icon.png"))
            {
                if (s != null) logo.Image = Image.FromStream(s);
            }
            Controls.Add(logo);

            var title = new Label { Text = "Forge Nova", Location = new Point(168, 42), AutoSize = true, Font = new Font("Segoe UI Light", 26f), ForeColor = Text1 };
            Controls.Add(title);
            var sub = new Label { Text = "Magic: The Gathering on the Forge rules engine", Location = new Point(172, 94), AutoSize = true, ForeColor = Text2 };
            Controls.Add(sub);

            status.SetBounds(28, 170, 504, 24);
            status.Font = new Font("Segoe UI Semibold", 10.5f);
            status.Text = "Checking for updates...";
            Controls.Add(status);

            bar.SetBounds(28, 198, 504, 10);
            bar.Style = ProgressBarStyle.Marquee;
            bar.MarqueeAnimationSpeed = 30;
            Controls.Add(bar);

            detail.SetBounds(28, 214, 504, 36);
            detail.ForeColor = Text2;
            Controls.Add(detail);

            StyleButton(playBtn, "Play", true);
            playBtn.SetBounds(412, 254, 120, 34);
            playBtn.Visible = false;
            playBtn.Click += delegate { Launch(); };
            Controls.Add(playBtn);

            StyleButton(retryBtn, "Try again", false);
            retryBtn.SetBounds(282, 254, 120, 34);
            retryBtn.Visible = false;
            retryBtn.Click += delegate { StartWork(); };
            Controls.Add(retryBtn);

            folderLink.Text = "Open install folder";
            folderLink.SetBounds(28, 262, 200, 22);
            folderLink.LinkColor = Text2;
            folderLink.ActiveLinkColor = Accent;
            folderLink.Visible = false;
            folderLink.LinkClicked += delegate { if (installDir != null) Process.Start("explorer.exe", "\"" + installDir + "\""); };
            Controls.Add(folderLink);

            Shown += delegate { Begin(); };
            FormClosing += delegate (object o, FormClosingEventArgs e) { closing = true; Program.Log("window closed (" + e.CloseReason + ")"); };
        }

        static void StyleButton(Button b, string text, bool primary)
        {
            b.Text = text;
            b.FlatStyle = FlatStyle.Flat;
            b.FlatAppearance.BorderSize = primary ? 0 : 1;
            b.FlatAppearance.BorderColor = Color.FromArgb(60, 68, 82);
            b.BackColor = primary ? Accent : PanelBg;
            b.ForeColor = primary ? Color.FromArgb(20, 12, 4) : Text1;
            b.Font = new Font("Segoe UI Semibold", 10f);
            b.Cursor = Cursors.Hand;
        }

        // ------------------------------------------------------------------ flow

        void Begin()
        {
            Program.Log("launcher " + Program.LauncherVersion + " started from " + exePath);
            TryDelete(exePath + ".old"); // left behind by a self-update

            if (Program.IsDevCheckout(exeDir))
            {
                // Running inside the developer's git checkout: launch it as is, never update it.
                devMode = true;
                installDir = exeDir;
                SetStatus("Developer checkout - updates are off here.", "Starting your working copy without downloading anything.");
                Launch();
                return;
            }

            if (File.Exists(Path.Combine(exeDir, Program.StateFile)))
            {
                installDir = exeDir;
            }
            else
            {
                // First run from Downloads (or anywhere): install into our own folder and continue from there.
                installDir = Program.DefaultInstallDir;
                if (Program.IsDevCheckout(installDir))
                {
                    Fail("Not installing into " + installDir + " because it is a git checkout.", false);
                    return;
                }
                try
                {
                    Directory.CreateDirectory(installDir);
                    string target = Path.Combine(installDir, "ForgeNova.exe");
                    if (!string.Equals(Path.GetFullPath(target), Path.GetFullPath(exePath), StringComparison.OrdinalIgnoreCase))
                    {
                        File.Copy(exePath, target, true);
                    }
                    if (!File.Exists(Path.Combine(installDir, Program.StateFile)))
                    {
                        File.WriteAllText(Path.Combine(installDir, Program.StateFile), "{}");
                    }
                    CreateShortcuts(target);
                    Process.Start(new ProcessStartInfo(target) { WorkingDirectory = installDir, UseShellExecute = false });
                    Close();
                }
                catch (Exception e)
                {
                    Fail("Could not install into " + installDir + ": " + e.Message, false);
                }
                return;
            }
            StartWork();
        }

        void StartWork()
        {
            playBtn.Visible = false;
            retryBtn.Visible = false;
            folderLink.Visible = true;
            bar.Style = ProgressBarStyle.Marquee;
            SetStatus("Checking for updates...", "");
            var t = new Thread(Work) { IsBackground = true, Name = "update" };
            t.Start();
        }

        void Work()
        {
            try
            {
                var state = LoadState();
                Dictionary<string, object> manifest;
                try
                {
                    manifest = Json(DownloadString(Program.ManifestUrl));
                }
                catch (Exception e)
                {
                    if (IsInstalled(state))
                    {
                        UiOffline("Could not check for updates (" + Short(e) + ").");
                    }
                    else
                    {
                        UiFail("Could not reach the download server: " + Short(e), true);
                    }
                    return;
                }

                // 1. the launcher itself
                var launcher = Obj(manifest, "launcher");
                if (launcher != null && Int(launcher, "version") > Program.LauncherVersion && SelfUpdate(launcher))
                {
                    return;
                }

                string staging = Path.Combine(installDir, "_update");
                TryDeleteDir(staging);
                Directory.CreateDirectory(staging);

                // 2. Java
                var java = Obj(manifest, "java");
                if (java != null && (Str(state, "java") != Str(java, "id") || !File.Exists(Path.Combine(installDir, @"runtime\java\bin\java.exe"))))
                {
                    string dir = Fetch("Java " + Str(java, "id"), java, staging, "java");
                    string root = SingleChild(dir);
                    string dest = Path.Combine(installDir, @"runtime\java");
                    Directory.CreateDirectory(Path.Combine(installDir, "runtime"));
                    TryDeleteDir(dest);
                    Directory.Move(root, dest);
                    state["java"] = Str(java, "id");
                    SaveState(state);
                }

                // 3. Forge (the exact build the engine patches were compiled for)
                var forge = Obj(manifest, "forge");
                if (Str(state, "forge") != Str(forge, "id") || !Directory.Exists(Path.Combine(installDir, @"res\cardsfolder")))
                {
                    string dir = Fetch("Forge " + Str(forge, "id"), forge, staging, "forge");
                    UiStatus("Installing Forge " + Str(forge, "id") + "...", "Replacing the previous Forge files");
                    foreach (string old in Directory.GetFiles(installDir, "forge-gui-desktop-*.jar")) File.Delete(old);
                    TryDeleteDir(Path.Combine(installDir, "res"));
                    MergeMove(dir, installDir);
                    state["forge"] = Str(forge, "id");
                    state.Remove("nova"); // patches must be re-matched to the new Forge build
                    SaveState(state);
                }

                // 4. Forge Nova
                string version = Str(manifest, "version");
                var nova = Obj(manifest, "nova");
                if (Str(state, "nova") != version || !File.Exists(Path.Combine(installDir, @"nova\lib\nova-host.jar")))
                {
                    string dir = Fetch("Forge Nova " + version, nova, staging, "nova");
                    UiStatus("Installing Forge Nova " + version + "...", "");
                    // Replace the program folders whole; nova\cache, nova\logs and anything else of the player's stays.
                    TryDeleteDir(Path.Combine(installDir, @"nova\client"));
                    TryDeleteDir(Path.Combine(installDir, @"nova\lib"));
                    MergeMove(dir, installDir);
                    state["nova"] = version;
                    SaveState(state);
                }

                TryDeleteDir(staging);
                UiReady("Forge Nova " + version + " is up to date.");
            }
            catch (UpdateCancelled)
            {
            }
            catch (Exception e)
            {
                Program.Log("update error: " + e);
                if (IsInstalled(LoadState()))
                {
                    UiOffline("The update failed (" + Short(e) + ").");
                }
                else
                {
                    UiFail("Installation failed: " + Short(e), true);
                }
            }
        }

        /// <summary>Downloads a manifest entry ({url, sha256, size}), verifies it and unzips it; returns the folder.
        /// Staging names stay short: Forge's res folder has paths over 110 characters and .NET 4 stops at 260.</summary>
        string Fetch(string label, Dictionary<string, object> entry, string staging, string slot)
        {
            string url = Str(entry, "url");
            string name = Path.GetFileName(new Uri(url).LocalPath);
            string zip = Path.Combine(staging, slot + ".zip");
            string hash = Download(url, zip, "Downloading " + label + "...", Long(entry, "size"));
            string want = Str(entry, "sha256");
            if (!string.IsNullOrEmpty(want) && !string.Equals(want, hash, StringComparison.OrdinalIgnoreCase))
            {
                throw new IOException(name + " is damaged (checksum mismatch) - try again");
            }
            string dir = Path.Combine(staging, slot);
            Unzip(zip, dir, "Unpacking " + label + "...");
            File.Delete(zip);
            return dir;
        }

        bool SelfUpdate(Dictionary<string, object> launcher)
        {
            string fresh = exePath + ".new";
            string hash = Download(Str(launcher, "url"), fresh, "Updating the launcher...", Long(launcher, "size"));
            string want = Str(launcher, "sha256");
            if (!string.IsNullOrEmpty(want) && !string.Equals(want, hash, StringComparison.OrdinalIgnoreCase))
            {
                TryDelete(fresh);
                return false; // keep going with this launcher
            }
            TryDelete(exePath + ".old");
            File.Move(exePath, exePath + ".old"); // a running exe can be renamed, not overwritten
            File.Move(fresh, exePath);
            Process.Start(new ProcessStartInfo(exePath, JoinArgs(args)) { WorkingDirectory = installDir, UseShellExecute = false });
            Ui(delegate { Close(); });
            return true;
        }

        void Launch()
        {
            playBtn.Visible = false;
            retryBtn.Visible = false;
            string cmd = Path.Combine(installDir, "forge-nova.cmd");
            if (!File.Exists(cmd))
            {
                Fail("forge-nova.cmd is missing from " + installDir + ".", !devMode);
                return;
            }
            SetStatus("Starting Forge Nova...", "The game window opens in a few seconds.");
            bar.Style = ProgressBarStyle.Marquee;
            Process p;
            try
            {
                var psi = new ProcessStartInfo("cmd.exe", "/c \"\"" + cmd + "\" " + JoinArgs(args) + "\"")
                {
                    WorkingDirectory = installDir,
                    UseShellExecute = false,
                    CreateNoWindow = true
                };
                psi.EnvironmentVariables["NOVA_HIDDEN"] = "1";
                p = Process.Start(psi);
            }
            catch (Exception e)
            {
                Fail("Could not start Forge Nova: " + e.Message, false);
                return;
            }
            // The engine keeps running after this window closes; only report it if it dies right away.
            var t = new Thread(delegate ()
            {
                bool died = p.WaitForExit(9000);
                if (!died)
                {
                    Ui(delegate { Close(); });
                    return;
                }
                string log = Tail(Path.Combine(installDir, @"nova\logs\host.log"), 2);
                string hint = log.Contains("BindException") || log.Contains("Address already in use")
                    ? "Forge Nova seems to be running already."
                    : "Forge Nova stopped right after starting.";
                UiFail(hint + (log.Length > 0 ? "\n" + log : ""), false);
            }) { IsBackground = true };
            t.Start();
        }

        // ------------------------------------------------------------------ downloads

        string DownloadString(string url)
        {
            var req = WebRequest.Create(url);
            req.Timeout = 20000;
            var http = req as HttpWebRequest;
            if (http != null)
            {
                http.UserAgent = "ForgeNova-Launcher/" + Program.LauncherVersion;
                http.Headers["Cache-Control"] = "no-cache";
            }
            using (var resp = req.GetResponse())
            using (var r = new StreamReader(resp.GetResponseStream(), Encoding.UTF8))
            {
                return r.ReadToEnd();
            }
        }

        /// <summary>Streams url to file with progress; returns the SHA-256 (hex) of what was written.</summary>
        string Download(string url, string file, string label, long expected)
        {
            UiStatus(label, "");
            var req = WebRequest.Create(url);
            req.Timeout = 30000;
            var http = req as HttpWebRequest;
            if (http != null)
            {
                http.UserAgent = "ForgeNova-Launcher/" + Program.LauncherVersion;
                http.ReadWriteTimeout = 60000;
            }
            using (var resp = req.GetResponse())
            using (var input = resp.GetResponseStream())
            using (var output = File.Create(file))
            using (var sha = SHA256.Create())
            {
                long total = resp.ContentLength > 0 ? resp.ContentLength : expected;
                long done = 0;
                var buf = new byte[1 << 16];
                var clock = Stopwatch.StartNew();
                long lastUi = -1000;
                int n;
                while ((n = input.Read(buf, 0, buf.Length)) > 0)
                {
                    if (closing) throw new UpdateCancelled();
                    output.Write(buf, 0, n);
                    sha.TransformBlock(buf, 0, n, null, 0);
                    done += n;
                    if (clock.ElapsedMilliseconds - lastUi > 150)
                    {
                        lastUi = clock.ElapsedMilliseconds;
                        double secs = Math.Max(0.001, clock.Elapsed.TotalSeconds);
                        double speed = done / secs;
                        string text = Mb(done) + (total > 0 ? " of " + Mb(total) : "") + "   " + Mb((long)speed) + "/s";
                        if (total > 0 && speed > 0)
                        {
                            text += "   about " + Eta((total - done) / speed) + " left";
                        }
                        UiProgress(total > 0 ? (int)(done * 1000 / total) : -1, text);
                    }
                }
                sha.TransformFinalBlock(buf, 0, 0);
                return BitConverter.ToString(sha.Hash).Replace("-", "").ToLowerInvariant();
            }
        }

        void Unzip(string zip, string dir, string label)
        {
            UiStatus(label, "");
            TryDeleteDir(dir);
            Directory.CreateDirectory(dir);
            string root = Path.GetFullPath(dir) + Path.DirectorySeparatorChar;
            using (var archive = ZipFile.OpenRead(zip))
            {
                int count = archive.Entries.Count, i = 0;
                foreach (var entry in archive.Entries)
                {
                    if (closing) throw new UpdateCancelled();
                    i++;
                    string dest = Path.GetFullPath(Path.Combine(dir, entry.FullName.Replace('/', Path.DirectorySeparatorChar)));
                    if (!dest.StartsWith(root, StringComparison.OrdinalIgnoreCase))
                    {
                        throw new IOException("unsafe path in " + Path.GetFileName(zip) + ": " + entry.FullName);
                    }
                    if (entry.FullName.EndsWith("/") || entry.FullName.EndsWith("\\"))
                    {
                        Directory.CreateDirectory(dest);
                        continue;
                    }
                    Directory.CreateDirectory(Path.GetDirectoryName(dest));
                    entry.ExtractToFile(dest, true);
                    if (i % 200 == 0 || i == count)
                    {
                        UiProgress(i * 1000 / Math.Max(1, count), i + " of " + count + " files");
                    }
                }
            }
        }

        // ------------------------------------------------------------------ files

        /// <summary>Moves every file under src into dst (same relative path), replacing existing files.</summary>
        static void MergeMove(string src, string dst)
        {
            Directory.CreateDirectory(dst);
            foreach (string f in Directory.GetFiles(src))
            {
                string to = Path.Combine(dst, Path.GetFileName(f));
                if (File.Exists(to)) File.Delete(to);
                File.Move(f, to);
            }
            foreach (string d in Directory.GetDirectories(src))
            {
                string to = Path.Combine(dst, Path.GetFileName(d));
                if (!Directory.Exists(to))
                {
                    Directory.Move(d, to);
                }
                else
                {
                    MergeMove(d, to);
                }
            }
        }

        static string SingleChild(string dir)
        {
            string[] dirs = Directory.GetDirectories(dir);
            return dirs.Length == 1 && Directory.GetFiles(dir).Length == 0 ? dirs[0] : dir;
        }

        Dictionary<string, object> LoadState()
        {
            try
            {
                var d = Json(File.ReadAllText(Path.Combine(installDir, Program.StateFile)));
                if (d != null) return d;
            }
            catch
            {
            }
            return new Dictionary<string, object>();
        }

        void SaveState(Dictionary<string, object> state)
        {
            string path = Path.Combine(installDir, Program.StateFile);
            File.WriteAllText(path + ".tmp", new JavaScriptSerializer().Serialize(state));
            if (File.Exists(path)) File.Delete(path);
            File.Move(path + ".tmp", path);
        }

        bool IsInstalled(Dictionary<string, object> state)
        {
            return Str(state, "nova") != null
                && File.Exists(Path.Combine(installDir, "forge-nova.cmd"))
                && File.Exists(Path.Combine(installDir, @"nova\lib\nova-host.jar"))
                && Directory.Exists(Path.Combine(installDir, @"res\cardsfolder"));
        }

        static void CreateShortcuts(string target)
        {
            string[] places =
            {
                Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory),
                Environment.GetFolderPath(Environment.SpecialFolder.Programs)
            };
            Type shellType = Type.GetTypeFromProgID("WScript.Shell");
            if (shellType == null) return;
            object shell = Activator.CreateInstance(shellType);
            foreach (string place in places)
            {
                try
                {
                    if (string.IsNullOrEmpty(place)) continue;
                    object lnk = shellType.InvokeMember("CreateShortcut", BindingFlags.InvokeMethod, null, shell,
                        new object[] { Path.Combine(place, Program.AppName + ".lnk") });
                    Type lt = lnk.GetType();
                    lt.InvokeMember("TargetPath", BindingFlags.SetProperty, null, lnk, new object[] { target });
                    lt.InvokeMember("WorkingDirectory", BindingFlags.SetProperty, null, lnk, new object[] { Path.GetDirectoryName(target) });
                    lt.InvokeMember("IconLocation", BindingFlags.SetProperty, null, lnk, new object[] { target + ",0" });
                    lt.InvokeMember("Description", BindingFlags.SetProperty, null, lnk, new object[] { "Play Forge Nova" });
                    lt.InvokeMember("Save", BindingFlags.InvokeMethod, null, lnk, null);
                }
                catch
                {
                    // a missing shortcut is not worth failing the install over
                }
            }
        }

        static string Tail(string file, int lines)
        {
            try
            {
                string[] all;
                using (var fs = new FileStream(file, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
                using (var r = new StreamReader(fs))
                {
                    all = r.ReadToEnd().Replace("\r", "").TrimEnd('\n').Split('\n');
                }
                int from = Math.Max(0, all.Length - lines);
                return string.Join("\n", all, from, all.Length - from);
            }
            catch
            {
                return "";
            }
        }

        static void TryDelete(string file)
        {
            try { if (File.Exists(file)) File.Delete(file); } catch { }
        }

        static void TryDeleteDir(string dir)
        {
            if (!Directory.Exists(dir)) return;
            for (int attempt = 0; ; attempt++)
            {
                try
                {
                    Directory.Delete(dir, true);
                    return;
                }
                catch (IOException)
                {
                    if (attempt >= 4) throw; // antivirus or Explorer briefly holding a file
                    Thread.Sleep(400);
                }
                catch (UnauthorizedAccessException)
                {
                    if (attempt >= 4) throw;
                    Thread.Sleep(400);
                }
            }
        }

        // ------------------------------------------------------------------ JSON / formatting

        static Dictionary<string, object> Json(string text)
        {
            return new JavaScriptSerializer().Deserialize<Dictionary<string, object>>(text);
        }

        static Dictionary<string, object> Obj(Dictionary<string, object> d, string key)
        {
            object v;
            return d != null && d.TryGetValue(key, out v) ? v as Dictionary<string, object> : null;
        }

        static string Str(Dictionary<string, object> d, string key)
        {
            object v;
            return d != null && d.TryGetValue(key, out v) && v != null ? Convert.ToString(v) : null;
        }

        static long Long(Dictionary<string, object> d, string key)
        {
            object v;
            try { return d != null && d.TryGetValue(key, out v) && v != null ? Convert.ToInt64(v) : 0; } catch { return 0; }
        }

        static int Int(Dictionary<string, object> d, string key)
        {
            return (int)Long(d, key);
        }

        static string Mb(long bytes)
        {
            return (bytes / 1048576.0).ToString(bytes < 10 * 1048576 ? "0.0" : "0") + " MB";
        }

        static string Eta(double secs)
        {
            if (secs < 60) return Math.Max(1, (int)secs) + " s";
            return (int)(secs / 60) + " min";
        }

        static string Short(Exception e)
        {
            var we = e as WebException;
            var resp = we != null ? we.Response as HttpWebResponse : null;
            if (resp != null && resp.StatusCode == HttpStatusCode.NotFound) return "no release published yet";
            return e.Message;
        }

        static string JoinArgs(string[] a)
        {
            var sb = new StringBuilder();
            foreach (string s in a)
            {
                if (sb.Length > 0) sb.Append(' ');
                sb.Append('"').Append(s.Replace("\"", "")).Append('"');
            }
            return sb.ToString();
        }

        // ------------------------------------------------------------------ UI plumbing

        void Ui(MethodInvoker m)
        {
            if (closing || IsDisposed) return;
            try { BeginInvoke(m); } catch (InvalidOperationException) { }
        }

        void SetStatus(string s, string d)
        {
            Program.Log(s + (string.IsNullOrEmpty(d) ? "" : " | " + d));
            status.Text = s;
            detail.Text = d;
        }

        void UiStatus(string s, string d)
        {
            Ui(delegate
            {
                bar.Style = ProgressBarStyle.Marquee;
                SetStatus(s, d);
            });
        }

        void UiProgress(int permille, string d)
        {
            Ui(delegate
            {
                if (permille < 0)
                {
                    bar.Style = ProgressBarStyle.Marquee;
                }
                else
                {
                    bar.Style = ProgressBarStyle.Continuous;
                    bar.Maximum = 1000;
                    bar.Value = Math.Min(1000, Math.Max(0, permille));
                }
                detail.Text = d;
            });
        }

        void UiReady(string s)
        {
            Ui(delegate
            {
                SetStatus(s, "");
                Launch();
            });
        }

        void UiOffline(string why)
        {
            Ui(delegate
            {
                bar.Style = ProgressBarStyle.Continuous;
                bar.Value = 0;
                SetStatus("Playing the installed version", why);
                playBtn.Visible = true;
                retryBtn.Visible = true;
                AcceptButton = playBtn;
            });
        }

        void UiFail(string why, bool canRetry)
        {
            Ui(delegate { Fail(why, canRetry); });
        }

        void Fail(string why, bool canRetry)
        {
            Program.Log("FAILED: " + why);
            bar.Style = ProgressBarStyle.Continuous;
            bar.Value = 0;
            status.Text = "Something went wrong";
            detail.Text = why;
            retryBtn.Text = canRetry ? "Try again" : "Retry";
            retryBtn.Visible = canRetry;
            playBtn.Visible = !canRetry && installDir != null && File.Exists(Path.Combine(installDir, "forge-nova.cmd"));
            if (playBtn.Visible) playBtn.Text = "Start again";
            folderLink.Visible = installDir != null;
        }
    }
}
