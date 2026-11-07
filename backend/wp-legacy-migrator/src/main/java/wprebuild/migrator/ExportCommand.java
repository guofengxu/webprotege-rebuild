package wprebuild.migrator;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(name = "export", description = "Write a migration package from legacy data.")
public final class ExportCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        return Unimplemented.exit(spec);
    }
}
