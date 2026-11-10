package wprebuild.app.ontology;

import java.util.Objects;
import org.semanticweb.owlapi.model.IRI;
import wprebuild.app.ontology.dto.EntityNode;

/** Subject check shared by property-frame updates. */
final class FrameSubjects {

    private FrameSubjects() {}

    /**
     * @param noun phrase after "the same", such as {@code object property}
     * @param subjectPhrase phrase after "must be", such as {@code an object property}
     */
    static void requireSame(EntityNode from, EntityNode to, String type, String noun, String subjectPhrase) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (!from.iri().equals(to.iri())) {
            throw new IllegalArgumentException("from and to must describe the same " + noun);
        }
        require(from, type, subjectPhrase);
        require(to, type, subjectPhrase);
        if (!IRI.create(from.iri()).isAbsolute()) {
            throw new IllegalArgumentException("IRI must be absolute");
        }
    }

    private static void require(EntityNode subject, String type, String subjectPhrase) {
        if (!type.equals(subject.entityType())) {
            throw new IllegalArgumentException("frame subject must be " + subjectPhrase);
        }
    }
}
