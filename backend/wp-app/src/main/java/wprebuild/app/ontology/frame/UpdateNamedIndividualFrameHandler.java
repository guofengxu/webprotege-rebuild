package wprebuild.app.ontology.frame;

import java.util.Objects;
import org.semanticweb.owlapi.model.IRI;
import org.springframework.stereotype.Service;
import wprebuild.app.ontology.ProjectHandle;
import wprebuild.app.ontology.ProjectRuntimeRegistry;
import wprebuild.app.ontology.ProjectSettingsProvider;
import wprebuild.app.ontology.UpdateNamedIndividualFrame;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.runtime.ChangeListGenerator;

/** Applies a named-individual frame edit. */
@Service
public final class UpdateNamedIndividualFrameHandler
        extends AbstractPropertyFrameUpdateHandler<UpdateNamedIndividualFrame, NamedIndividualFrame> {

    private final IndividualFrameQuery frames;

    public UpdateNamedIndividualFrameHandler(
            ProjectRuntimeRegistry registry, ProjectSettingsProvider settings, IndividualFrameQuery frames) {
        super(registry, settings);
        this.frames = Objects.requireNonNull(frames, "frames");
    }

    @Override
    public Class<UpdateNamedIndividualFrame> commandType() {
        return UpdateNamedIndividualFrame.class;
    }

    @Override
    protected ProjectId projectId(UpdateNamedIndividualFrame command) {
        return command.projectId();
    }

    @Override
    protected ChangeListGenerator<NamedIndividualFrame> generator(
            UpdateNamedIndividualFrame command, ProjectHandle handle, OntologyKey target) {
        return new UpdateNamedIndividualFrameGenerator(command.from(), command.to(), frames, handle.runtime(), target);
    }

    @Override
    protected NamedIndividualFrame read(ProjectId projectId, IRI iri) {
        return frames.frame(projectId, iri);
    }

    @Override
    protected IRI subject(UpdateNamedIndividualFrame command) {
        return IRI.create(command.to().subject().iri());
    }

    @Override
    protected String kind() {
        return "named individual";
    }
}
