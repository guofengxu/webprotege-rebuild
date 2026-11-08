package wprebuild.app.ontology;

import java.util.Objects;
import wprebuild.kernel.runtime.ChangePipeline;
import wprebuild.kernel.runtime.ProjectRuntime;
import wprebuild.kernel.runtime.ProjectWriteGate;

/** One loaded project: its memory, its locks, and its write pipeline. */
public record ProjectHandle(ProjectRuntime runtime, ProjectWriteGate gate, ChangePipeline pipeline) {

    public ProjectHandle {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(gate, "gate");
        Objects.requireNonNull(pipeline, "pipeline");
    }
}
