package wprebuild.migrator;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(name = "inspect", description = "Summarise legacy projects in a data directory.")
public final class InspectCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        return Unimplemented.exit(spec);
    }
}
