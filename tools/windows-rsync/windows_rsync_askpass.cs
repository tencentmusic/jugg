using System;
using System.Text;

// OpenSSH reads this process's stdout directly; never route it through Jugg logging.
internal static class WindowsRsyncAskpass
{
    private static int Main()
    {
        string password = Environment.GetEnvironmentVariable("JUGG_RSYNC_PASSWORD");
        if (String.IsNullOrEmpty(password)) return 1;
        byte[] bytes = Encoding.UTF8.GetBytes(password + "\n");
        using (var output = Console.OpenStandardOutput()) output.Write(bytes, 0, bytes.Length);
        Array.Clear(bytes, 0, bytes.Length);
        return 0;
    }
}
