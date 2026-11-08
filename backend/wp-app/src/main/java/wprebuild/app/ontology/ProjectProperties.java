package wprebuild.app.ontology;

import java.util.Objects;
import org.semanticweb.owlapi.model.IRI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import wprebuild.kernel.id.ProjectId;

/** {@code wp.project.ontology-iri-base} plus the project id is the ontology IRI when the caller omits one. */
@ConfigurationProperties(prefix = "wp.project")
public record ProjectProperties(
        @DefaultValue("http://example.org/wp/") String ontologyIriBase) {

    public ProjectProperties {
        if (ontologyIriBase == null || ontologyIriBase.isBlank()) {
            throw new IllegalArgumentException("wp.project.ontology-iri-base must not be blank");
        }
        ontologyIriBase = ontologyIriBase.strip();
        if (!IRI.create(ontologyIriBase + "00000000-0000-0000-0000-000000000000")
                .isAbsolute()) {
            throw new IllegalArgumentException("wp.project.ontology-iri-base must produce an absolute IRI");
        }
    }

    public IRI ontologyIriFor(ProjectId projectId) {
        Objects.requireNonNull(projectId, "project id");
        IRI iri = IRI.create(ontologyIriBase + projectId.value());
        if (!iri.isAbsolute()) {
            throw new IllegalStateException("configured ontology IRI base produced a relative IRI");
        }
        return iri;
    }
}
