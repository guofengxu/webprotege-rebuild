package wprebuild.app.projection;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.RemoveAxiom;

/**
 * Collects entity IRIs whose projection rows may change after a set of ontology edits. Includes
 * annotation-assertion subjects, which are IRIs and are not part of the axiom signature.
 */
public final class AffectedSubjects {

    private AffectedSubjects() {}

    public static Set<IRI> of(List<? extends OntologyChange> changes) {
        Objects.requireNonNull(changes, "changes");
        Set<IRI> subjects = new LinkedHashSet<>();
        for (OntologyChange change : changes) {
            switch (change) {
                case AddAxiom add -> collectAxiom(add.owlAxiom(), subjects);
                case RemoveAxiom remove -> collectAxiom(remove.owlAxiom(), subjects);
                default -> {
                    // Imports, ontology annotations, and DeclareOntology do not affect projection rows.
                }
            }
        }
        return Set.copyOf(subjects);
    }

    private static void collectAxiom(OWLAxiom axiom, Set<IRI> subjects) {
        for (OWLEntity entity : axiom.getSignature()) {
            subjects.add(entity.getIRI());
        }
        if (axiom instanceof OWLAnnotationAssertionAxiom annotation && annotation.getSubject() instanceof IRI subject) {
            subjects.add(subject);
        }
    }
}
