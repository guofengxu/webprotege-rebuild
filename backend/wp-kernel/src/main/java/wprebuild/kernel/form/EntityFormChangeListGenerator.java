package wprebuild.kernel.form;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDataPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.change.RemoveAxiom;
import wprebuild.kernel.ontology.ProjectOntologies;
import wprebuild.kernel.runtime.ChangeContext;
import wprebuild.kernel.runtime.ChangeListGenerator;
import wprebuild.kernel.runtime.GeneratedChanges;

/**
 * Builds axiom changes from pristine/edited entity form data (BS-18 first slice: TEXT/NUMBER +
 * PROPERTY).
 *
 * <p>Under the generation lock: sanity-check maps, reject unsupported control edits, anchor pristine
 * assertions against the runtime, then diff writable fields. Submit via {@code
 * ChangePipeline.apply(..., expectedHead)}.
 */
public final class EntityFormChangeListGenerator implements ChangeListGenerator<OWLEntity> {

    private final OWLEntity subject;

    private final Map<FormId, FormData> pristineForms;

    private final Map<FormId, FormData> editedForms;

    private final OntologyKey targetOntology;

    public EntityFormChangeListGenerator(
            OWLEntity subject,
            Map<FormId, FormData> pristineForms,
            Map<FormId, FormData> editedForms,
            OntologyKey targetOntology) {
        this.subject = Objects.requireNonNull(subject, "subject");
        this.pristineForms = Map.copyOf(Objects.requireNonNull(pristineForms, "pristine forms"));
        this.editedForms = Map.copyOf(Objects.requireNonNull(editedForms, "edited forms"));
        this.targetOntology = Objects.requireNonNull(targetOntology, "target ontology");
        FormDataUpdateSanityChecker.check(this.pristineForms, this.editedForms);
    }

    @Override
    public GeneratedChanges<OWLEntity> generate(ChangeContext context) {
        Objects.requireNonNull(context, "context");
        ProjectOntologies ontologies = context.ontologies();
        OWLDataFactory dataFactory = ontologies.dataFactory();

        rejectUnsupportedEdits();

        Set<OWLAxiom> pristineAxioms = axiomsFrom(pristineForms, dataFactory);
        Set<OWLAxiom> runtimeAxioms = runtimeAxioms(ontologies, boundProperties());
        // Align with Frame update: logical equality ignores axiom annotations (BS-18 / FRAME_CHANGED).
        if (!sameIgnoringAnnotations(pristineAxioms, runtimeAxioms)) {
            throw new FormDataChangedException();
        }

        Set<OWLAxiom> editedAxioms = axiomsFrom(editedForms, dataFactory);
        List<OntologyChange> changes = diff(ontologies, pristineAxioms, editedAxioms);
        return new GeneratedChanges<>(changes, subject);
    }

    @Override
    public String describe(GeneratedChanges<OWLEntity> generated, List<OntologyChange> effective) {
        Objects.requireNonNull(generated, "generated changes");
        Objects.requireNonNull(effective, "effective changes");
        return "Edited form for " + subject.getIRI();
    }

    @Override
    public OWLEntity renameResult(OWLEntity result, Map<IRI, IRI> renameMap) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(renameMap, "rename map");
        // First slice has no fresh grid/sub-form subjects (BS-05 minting deferred).
        return result;
    }

    private void rejectUnsupportedEdits() {
        for (FormId formId : pristineForms.keySet()) {
            FormData pristine = pristineForms.get(formId);
            FormData edited = editedForms.get(formId);
            for (FormFieldDescriptor field : pristine.descriptor().fields()) {
                if (writable(field)) {
                    continue;
                }
                Optional<OWLLiteral> before = pristine.valueOf(field.id());
                Optional<OWLLiteral> after = edited.valueOf(field.id());
                if (!before.equals(after)) {
                    throw new UnsupportedFormControlEditException(field.id());
                }
            }
        }
    }

    /**
     * First-slice writable: TEXT/NUMBER + annotation property, or TEXT/NUMBER + data property on a
     * named individual. Object properties and data properties on classes are not writable yet.
     */
    private boolean writable(FormFieldDescriptor field) {
        if (!field.writableCandidate()) {
            return false;
        }
        OWLEntity property = ((OwlPropertyBinding) field.binding()).property();
        if (property.isOWLAnnotationProperty()) {
            return true;
        }
        return property.isOWLDataProperty() && subject.isOWLNamedIndividual();
    }

    private Set<OWLEntity> boundProperties() {
        LinkedHashSet<OWLEntity> properties = new LinkedHashSet<>();
        for (FormData form : pristineForms.values()) {
            for (FormFieldDescriptor field : form.descriptor().fields()) {
                if (writable(field) && field.binding() instanceof OwlPropertyBinding propertyBinding) {
                    properties.add(propertyBinding.property());
                }
            }
        }
        return Set.copyOf(properties);
    }

    private Set<OWLAxiom> axiomsFrom(Map<FormId, FormData> forms, OWLDataFactory dataFactory) {
        LinkedHashSet<OWLAxiom> axioms = new LinkedHashSet<>();
        for (FormData form : forms.values()) {
            for (FormFieldDescriptor field : form.descriptor().fields()) {
                if (!writable(field)) {
                    continue;
                }
                OwlPropertyBinding binding = (OwlPropertyBinding) field.binding();
                form.valueOf(field.id())
                        .ifPresent(literal -> axioms.add(axiom(dataFactory, binding.property(), literal)));
            }
        }
        return Set.copyOf(axioms);
    }

    private OWLAxiom axiom(OWLDataFactory dataFactory, OWLEntity property, OWLLiteral literal) {
        if (property.isOWLAnnotationProperty()) {
            return dataFactory.getOWLAnnotationAssertionAxiom(
                    property.asOWLAnnotationProperty(), subject.getIRI(), literal);
        }
        if (property.isOWLDataProperty() && subject.isOWLNamedIndividual()) {
            return dataFactory.getOWLDataPropertyAssertionAxiom(
                    property.asOWLDataProperty(), subject.asOWLNamedIndividual(), literal);
        }
        throw new IllegalStateException("writable field produced unsupported property: " + property.getIRI());
    }

    private static boolean sameIgnoringAnnotations(Set<OWLAxiom> left, Set<OWLAxiom> right) {
        if (left.size() != right.size()) {
            return false;
        }
        LinkedHashSet<OWLAxiom> remaining = new LinkedHashSet<>(right);
        for (OWLAxiom candidate : left) {
            OWLAxiom match = null;
            for (OWLAxiom stored : remaining) {
                if (candidate.equalsIgnoreAnnotations(stored)) {
                    match = stored;
                    break;
                }
            }
            if (match == null) {
                return false;
            }
            remaining.remove(match);
        }
        return remaining.isEmpty();
    }

    private Set<OWLAxiom> runtimeAxioms(ProjectOntologies ontologies, Set<OWLEntity> properties) {
        LinkedHashSet<OWLAxiom> axioms = new LinkedHashSet<>();
        for (OWLEntity property : properties) {
            if (property.isOWLAnnotationProperty()) {
                OWLAnnotationProperty annotationProperty = property.asOWLAnnotationProperty();
                ontologies
                        .annotationAssertions(subject.getIRI())
                        .filter(axiom -> axiom.getProperty().equals(annotationProperty))
                        .filter(axiom -> axiom.getValue() instanceof OWLLiteral)
                        .forEach(axioms::add);
            } else if (property.isOWLDataProperty() && subject.isOWLNamedIndividual()) {
                OWLDataProperty dataProperty = property.asOWLDataProperty();
                OWLNamedIndividual individual = subject.asOWLNamedIndividual();
                ontologies
                        .axiomsOfType(AxiomType.DATA_PROPERTY_ASSERTION)
                        .filter(axiom -> axiom.getProperty().equals(dataProperty))
                        .filter(axiom -> axiom.getSubject().equals(individual))
                        .forEach(axioms::add);
            }
        }
        return Set.copyOf(axioms);
    }

    private List<OntologyChange> diff(ProjectOntologies ontologies, Set<OWLAxiom> before, Set<OWLAxiom> after) {
        LinkedHashSet<OWLAxiom> removed = new LinkedHashSet<>(before);
        removed.removeAll(after);
        LinkedHashSet<OWLAxiom> added = new LinkedHashSet<>(after);
        added.removeAll(before);

        List<OntologyKey> keys = keys(ontologies);
        List<OntologyChange> changes = new ArrayList<>();
        LinkedHashSet<OntologyKey> touched = new LinkedHashSet<>();
        for (OWLAxiom axiom : removed) {
            for (OntologyKey key : keys) {
                if (ontologies.containsAxiom(key, axiom)) {
                    changes.add(new RemoveAxiom(key, axiom));
                    touched.add(key);
                } else {
                    for (OWLAxiom stored : matchingStored(ontologies, key, axiom)) {
                        changes.add(new RemoveAxiom(key, stored));
                        touched.add(key);
                    }
                }
            }
        }
        if (added.isEmpty()) {
            return List.copyOf(changes);
        }
        OntologyKey destination = destination(keys, touched);
        for (OWLAxiom axiom : added) {
            changes.add(new AddAxiom(destination, axiom));
        }
        return List.copyOf(changes);
    }

    private List<OWLAxiom> matchingStored(ProjectOntologies ontologies, OntologyKey key, OWLAxiom rebuilt) {
        if (rebuilt instanceof OWLAnnotationAssertionAxiom assertion) {
            return ontologies
                    .annotationAssertions(subject.getIRI())
                    .filter(stored -> ontologies.containsAxiom(key, stored))
                    .filter(stored -> stored.equalsIgnoreAnnotations(assertion))
                    .map(OWLAxiom.class::cast)
                    .toList();
        }
        if (rebuilt instanceof OWLDataPropertyAssertionAxiom assertion) {
            return ontologies
                    .axiomsOfType(AxiomType.DATA_PROPERTY_ASSERTION)
                    .filter(stored -> ontologies.containsAxiom(key, stored))
                    .filter(stored -> stored.equalsIgnoreAnnotations(assertion))
                    .map(OWLAxiom.class::cast)
                    .toList();
        }
        return List.of();
    }

    private OntologyKey destination(List<OntologyKey> keys, LinkedHashSet<OntologyKey> touched) {
        if (touched.size() == 1) {
            return touched.getFirst();
        }
        if (keys.contains(targetOntology)) {
            return targetOntology;
        }
        if (keys.isEmpty()) {
            throw new IllegalStateException("project has no ontology");
        }
        return keys.getFirst();
    }

    private static List<OntologyKey> keys(ProjectOntologies ontologies) {
        return ontologies.keys().stream()
                .sorted(Comparator.comparingInt(
                        key -> Integer.parseInt(key.value().substring(1))))
                .toList();
    }
}
