package wprebuild.migrator;

import picocli.CommandLine.Model.CommandSpec;

final class Unimplemented {

    static final int EXIT_CODE = 2;
    static final String MESSAGE = "not implemented";

    private Unimplemented() {}

    static int exit(CommandSpec spec) {
        spec.commandLine().getOut().println(MESSAGE);
        return EXIT_CODE;
    }
}
