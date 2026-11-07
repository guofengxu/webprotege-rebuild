package wprebuild.migrator;

import java.io.PrintStream;
import java.io.PrintWriter;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = MigratorInfo.NAME,
        description = "Read legacy WebProtege data and write a migration package.",
        mixinStandardHelpOptions = true,
        versionProvider = MigratorVersion.class,
        subcommands = {InspectCommand.class, ExportCommand.class, VerifyCommand.class})
public final class Main {

    public static void main(String[] args) {
        System.exit(execute(args, System.out, System.err));
    }

    static int execute(String[] args, PrintStream out, PrintStream err) {
        PrintWriter outWriter = new PrintWriter(out, true);
        PrintWriter errWriter = new PrintWriter(err, true);
        try {
            CommandLine commandLine = new CommandLine(new Main());
            commandLine.setOut(outWriter);
            commandLine.setErr(errWriter);
            return commandLine.execute(args);
        } finally {
            outWriter.flush();
            errWriter.flush();
        }
    }
}
