using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;

namespace Marketplace;

internal static class Program
{
    private const string Url = "http://localhost:4300";
    private static string root = "";
    private static readonly Dictionary<string, string> configuration = new();
    private static StreamWriter? log;

    private static async Task<int> Main(string[] args)
    {
        Console.OutputEncoding = Encoding.UTF8;
        Console.Title = "Marketplace — demostración académica";
        FileStream? launchLock = null;
        try
        {
            try { launchLock = new FileStream(Path.Combine(Path.GetTempPath(), "marketplace-demo-launcher.lock"),
                FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None); }
            catch (IOException) { throw new InvalidOperationException("Marketplace ya está preparando el sistema. Espere a que termine la otra ventana."); }
            root = FindRoot(AppContext.BaseDirectory);
            Directory.CreateDirectory(Path.Combine(root, "launcher-logs"));
            log = new StreamWriter(Path.Combine(root, "launcher-logs", "marketplace.log"), append: false) { AutoFlush = true };
            Say("Comprobando Docker...");
            await Docker(["--version"], TimeSpan.FromSeconds(20), "No se encontró Docker. Instale y abra Docker Desktop.");
            string engine = await Docker(["info", "--format", "{{.OSType}}"], TimeSpan.FromSeconds(30),
                "Docker Desktop no está listo. Ábralo, espere a que el motor esté ejecutándose y vuelva a abrir Marketplace.");
            if (engine.Trim() != "linux") throw new InvalidOperationException("Seleccione Linux containers en Docker Desktop y vuelva a intentar.");
            await Docker(["compose", "version"], TimeSpan.FromSeconds(20), "Actualice Docker Desktop: se necesita Docker Compose v2.");
            Say("Preparando entorno de demostración...");
            PrepareEnvironment();
            if (args.Contains("--stop"))
            {
                await Compose(["stop"], "No se pudo cerrar el sistema. Revise Docker Desktop.");
                Say("Marketplace se ha detenido. Sus datos se conservaron.");
            }
            else
            {
                Say("Iniciando base de datos...");
                await Compose(["up", "-d", "--build", "--wait", "--wait-timeout", "300", "mysql"], "No se pudo iniciar MySQL. Revise puertos libres y espacio en disco.");
                Say("Iniciando backend... La primera compilación puede tardar varios minutos.");
                // Cada doble clic inicia de nuevo el runner demo, incluso si el contenedor ya estaba sano.
                await Compose(["up", "-d", "--build", "--force-recreate", "--no-deps", "--wait", "--wait-timeout", "300", "backend"],
                    "El backend no pudo prepararse. Compruebe Internet, espacio disponible y el registro de diagnóstico.");
                Say("Iniciando frontend...");
                await Compose(["up", "-d", "--build", "--force-recreate", "--no-deps", "--wait", "--wait-timeout", "180", "frontend"],
                    "No se pudo iniciar el frontend. Compruebe que el puerto 4300 esté libre.");
                await WaitForHttp();
                Say("Marketplace está listo.");
                Say(Url);
                try { Process.Start(new ProcessStartInfo(Url) { UseShellExecute = true }); }
                catch { Say("No se pudo abrir el navegador automáticamente. Abra " + Url); }
            }
            Say("Puede cerrar esta ventana. Para detener el sistema, abra Cerrar Marketplace.cmd.");
        }
        catch (Exception error)
        {
            Say("No fue posible completar la operación: " + error.Message);
            Say("Abra Docker Desktop y compruebe que esté listo. Después cierre esta ventana y vuelva a intentar.");
            if (log != null) Say("Diagnóstico: launcher-logs/marketplace.log");
            log?.Dispose(); log = null;
            launchLock?.Dispose();
            // No requiere teclado ni desaparece el error al abrir por doble clic.
            if (OperatingSystem.IsWindows()) await Task.Delay(Timeout.Infinite);
            return 1;
        }
        finally { log?.Dispose(); launchLock?.Dispose(); }
        if (OperatingSystem.IsWindows()) await Task.Delay(Timeout.Infinite);
        return 0;
    }

    internal static string FindRoot(string start)
    {
        for (var directory = new DirectoryInfo(start); directory != null; directory = directory.Parent)
            if (File.Exists(Path.Combine(directory.FullName, "compose.yaml"))
                && File.Exists(Path.Combine(directory.FullName, "compose.demo.yaml"))
                && Directory.Exists(Path.Combine(directory.FullName, "frontend"))
                && Directory.Exists(Path.Combine(directory.FullName, "backend", "demo"))) return directory.FullName;
        throw new InvalidOperationException("Descomprima la entrega completa y coloque Marketplace.exe junto a compose.yaml.");
    }

    private static void PrepareEnvironment()
    {
        string path = Path.Combine(root, ".env.demo");
        if (!File.Exists(path))
        {
            string password = Convert.ToHexString(RandomNumberGenerator.GetBytes(32));
            string rootPassword = Convert.ToHexString(RandomNumberGenerator.GetBytes(32));
            // Escritura atómica; si se interrumpe no queda un archivo de contraseñas incompleto.
            string temp = path + ".tmp";
            File.WriteAllText(temp, $"DB_PASSWORD={password}\nMYSQL_ROOT_PASSWORD={rootPassword}\nDB_NAME=marketplace_demo\nDB_USER=marketplace_demo\nSPRING_PROFILES_ACTIVE=demo\nFRONTEND_PORT=4300\nBACKEND_HOST_PORT=8080\nMYSQL_HOST_PORT=3307\n", new UTF8Encoding(false));
            File.Move(temp, path);
        }
        foreach (string line in File.ReadLines(path))
        {
            if (string.IsNullOrWhiteSpace(line) || line.TrimStart().StartsWith('#')) continue;
            var pair = line.Split('=', 2);
            if (pair.Length != 2) throw new InvalidOperationException("El archivo .env.demo está incompleto. Restaure la copia original de ese archivo.");
            configuration[pair[0].Trim()] = pair[1].Trim();
        }
        foreach (string key in new[] { "DB_PASSWORD", "MYSQL_ROOT_PASSWORD" })
            if (!configuration.TryGetValue(key, out string? value) || value.Length < 16)
                throw new InvalidOperationException("Falta la configuración guardada de la base de datos en .env.demo. No elimine este archivo después del primer inicio.");
        // La demo no hereda credenciales ni puertos del .env de desarrollo.
        configuration["DB_NAME"] = "marketplace_demo"; configuration["DB_USER"] = "marketplace_demo";
        configuration["FRONTEND_PORT"] = "4300"; configuration["BACKEND_HOST_PORT"] = "8080";
        configuration["MYSQL_HOST_PORT"] = "3307";
        if (!File.Exists(Path.Combine(root, ".env"))) File.Copy(path, Path.Combine(root, ".env"));
    }

    private static Task<string> Compose(string[] args, string failure) => Docker(
        ["compose", "--project-directory", root, "--env-file", Path.Combine(root, ".env.demo"),
         "-p", "marketplace-demo", "-f", Path.Combine(root, "compose.yaml"), "-f", Path.Combine(root, "compose.demo.yaml"), ..args],
        TimeSpan.FromMinutes(45), failure);

    private static async Task<string> Docker(string[] args, TimeSpan timeout, string failure)
    {
        var start = new ProcessStartInfo("docker") { WorkingDirectory = root, UseShellExecute = false,
            RedirectStandardOutput = true, RedirectStandardError = true, CreateNoWindow = true };
        foreach (string arg in args) start.ArgumentList.Add(arg); // Rutas con espacios, sin shell.
        foreach (var pair in configuration) start.Environment[pair.Key] = pair.Value;
        using var process = new Process { StartInfo = start };
        try { process.Start(); } catch (Exception ex) when (ex is System.ComponentModel.Win32Exception or InvalidOperationException)
        { throw new InvalidOperationException(failure, ex); }
        Task<string> output = process.StandardOutput.ReadToEndAsync();
        Task<string> errors = process.StandardError.ReadToEndAsync();
        using var deadline = new CancellationTokenSource(timeout);
        try
        {
            var finished = process.WaitForExitAsync(deadline.Token);
            while (await Task.WhenAny(finished, Task.Delay(TimeSpan.FromSeconds(30), deadline.Token)) != finished)
                Say("Preparando Marketplace... espere, por favor.");
            await finished;
        }
        catch (OperationCanceledException)
        {
            process.Kill(entireProcessTree: true);
            throw new InvalidOperationException(failure + " Se agotó el tiempo de espera.");
        }
        string stdout = await output, stderr = await errors;
        // No se ejecuta compose config ni se registran variables/contraseñas.
        log?.WriteLine($"docker {string.Join(' ', args)}\n{Redact(stdout)}\n{Redact(stderr)}");
        if (process.ExitCode != 0) throw new InvalidOperationException(failure);
        return stdout;
    }

    private static string Redact(string value)
    {
        foreach (string key in new[] { "DB_PASSWORD", "MYSQL_ROOT_PASSWORD" })
            if (configuration.TryGetValue(key, out var secret) && secret.Length > 0) value = value.Replace(secret, "[oculto]");
        return value;
    }

    private static async Task WaitForHttp()
    {
        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(5) };
        var timer = Stopwatch.StartNew();
        while (timer.Elapsed < TimeSpan.FromMinutes(3))
        {
            try
            {
                using var frontend = await http.GetAsync(Url + "/healthz");
                using var backend = await http.GetAsync(Url + "/api/actuator/health/readiness");
                if (frontend.IsSuccessStatusCode && backend.IsSuccessStatusCode) return;
            }
            catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException) { }
            await Task.Delay(TimeSpan.FromSeconds(2));
        }
        throw new InvalidOperationException("El navegador todavía no puede comunicarse con el sistema. Revise Docker Desktop y los puertos locales.");
    }

    private static void Say(string message) { Console.WriteLine(message); log?.WriteLine(message); }
}
