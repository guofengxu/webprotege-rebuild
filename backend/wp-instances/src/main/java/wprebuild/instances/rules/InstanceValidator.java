package wprebuild.instances.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import wprebuild.instances.InstanceRecord;
import wprebuild.instances.LinkRecord;
import wprebuild.instances.rules.AttributesJson.ValueKind;
import wprebuild.instances.rules.PropertyRange.RangeKind;

/**
 * Closed-world write gate for the instance store. OWL domain and range axioms normally drive type
 * inference; here they are treated as constraints that must already hold on the written class IRIs.
 *
 * <p>Multiple domain (or class-range) entries are interpreted as an intersection: the instance class
 * must belong to every declared class (via subclass closure). Properties with no declared domain or
 * range are not checked for that aspect. Projection tables only expose named class domains / ranges
 * declared on the property itself — not inherited from parent properties, and not complex class
 * expressions.
 *
 * <p>Cardinality, required properties, and enumeration value constraints are out of scope until the
 * projection schema is extended.
 */
public final class InstanceValidator {

    private static final String XSD = "http://www.w3.org/2001/XMLSchema#";
    private static final String XSD_STRING = XSD + "string";
    private static final String XSD_INTEGER = XSD + "integer";
    private static final String XSD_DECIMAL = XSD + "decimal";
    private static final String XSD_BOOLEAN = XSD + "boolean";
    private static final String XSD_DATE_TIME = XSD + "dateTime";

    public List<Violation> validate(InstanceRecord record, ValidationRules rules) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(rules, "rules");
        List<Violation> violations = new ArrayList<>();
        String classIri = record.classIri();
        if (!rules.classExists(classIri)) {
            violations.add(new Violation(
                    "UNKNOWN_CLASS", record.iri(), "", "class " + classIri + " is not in the ontology projection"));
            return violations;
        }
        if (rules.isDeprecatedClass(classIri)) {
            violations.add(new Violation("DEPRECATED_CLASS", record.iri(), "", "class " + classIri + " is deprecated"));
        }
        Map<String, ValueKind> attributes;
        try {
            attributes = AttributesJson.parseObjectOrThrow(record.attributesJson());
        } catch (IllegalArgumentException e) {
            violations.add(new Violation(
                    "DATATYPE_MISMATCH",
                    record.iri(),
                    "",
                    "attributesJson must be a JSON object of data-property values"));
            return List.copyOf(violations);
        }
        for (Map.Entry<String, ValueKind> entry : attributes.entrySet()) {
            String propertyIri = entry.getKey();
            if (!rules.dataPropertyExists(propertyIri)) {
                violations.add(new Violation(
                        "UNKNOWN_PROPERTY", record.iri(), propertyIri, "data property " + propertyIri + " is unknown"));
                continue;
            }
            checkDomains(record.iri(), propertyIri, classIri, rules, violations);
            checkDatatypeRanges(record.iri(), propertyIri, entry.getValue(), rules, violations);
        }
        return List.copyOf(violations);
    }

    public List<Violation> validateLink(
            LinkRecord link, String subjectClassIri, String objectClassIri, ValidationRules rules) {
        Objects.requireNonNull(link, "link");
        Objects.requireNonNull(subjectClassIri, "subjectClassIri");
        Objects.requireNonNull(objectClassIri, "objectClassIri");
        Objects.requireNonNull(rules, "rules");
        List<Violation> violations = new ArrayList<>();
        String propertyIri = link.propertyIri();
        if (!rules.objectPropertyExists(propertyIri)) {
            violations.add(new Violation(
                    "UNKNOWN_PROPERTY",
                    link.subjectIri(),
                    propertyIri,
                    "object property " + propertyIri + " is unknown"));
            return List.copyOf(violations);
        }
        if (!rules.classExists(subjectClassIri)) {
            violations.add(new Violation(
                    "UNKNOWN_CLASS",
                    link.subjectIri(),
                    propertyIri,
                    "subject class " + subjectClassIri + " is unknown"));
        } else {
            checkDomains(link.subjectIri(), propertyIri, subjectClassIri, rules, violations);
        }
        if (!rules.classExists(objectClassIri)) {
            violations.add(new Violation(
                    "UNKNOWN_CLASS", link.objectIri(), propertyIri, "object class " + objectClassIri + " is unknown"));
        } else {
            checkClassRanges(link.subjectIri(), propertyIri, objectClassIri, rules, violations);
        }
        return List.copyOf(violations);
    }

    private static void checkDomains(
            String subjectIri, String propertyIri, String classIri, ValidationRules rules, List<Violation> violations) {
        Set<String> domains = rules.domains(propertyIri);
        if (domains.isEmpty()) {
            return;
        }
        Set<String> closure = rules.ancestorClosure(classIri);
        for (String domain : domains) {
            if (!closure.contains(domain)) {
                violations.add(new Violation(
                        "DOMAIN_MISMATCH",
                        subjectIri,
                        propertyIri,
                        "class " + classIri + " is outside domain " + domain));
            }
        }
    }

    private static void checkClassRanges(
            String subjectIri,
            String propertyIri,
            String objectClassIri,
            ValidationRules rules,
            List<Violation> violations) {
        List<PropertyRange> ranges = rules.ranges(propertyIri);
        List<PropertyRange> classRanges =
                ranges.stream().filter(r -> r.kind() == RangeKind.CLASS).toList();
        if (classRanges.isEmpty()) {
            return;
        }
        Set<String> closure = rules.ancestorClosure(objectClassIri);
        for (PropertyRange range : classRanges) {
            if (!closure.contains(range.rangeIri())) {
                violations.add(new Violation(
                        "RANGE_MISMATCH",
                        subjectIri,
                        propertyIri,
                        "object class " + objectClassIri + " is outside range " + range.rangeIri()));
            }
        }
    }

    private static void checkDatatypeRanges(
            String subjectIri, String propertyIri, ValueKind kind, ValidationRules rules, List<Violation> violations) {
        List<PropertyRange> ranges = rules.ranges(propertyIri);
        List<PropertyRange> datatypeRanges =
                ranges.stream().filter(r -> r.kind() == RangeKind.DATATYPE).toList();
        if (datatypeRanges.isEmpty()) {
            return;
        }
        for (PropertyRange range : datatypeRanges) {
            if (!matchesDatatype(range.rangeIri(), kind)) {
                violations.add(new Violation(
                        "DATATYPE_MISMATCH",
                        subjectIri,
                        propertyIri,
                        "value kind " + kind + " does not match " + range.rangeIri()));
            }
        }
    }

    private static boolean matchesDatatype(String datatypeIri, ValueKind kind) {
        return switch (datatypeIri) {
            case XSD_STRING, XSD_DATE_TIME -> kind == ValueKind.STRING;
            case XSD_INTEGER -> kind == ValueKind.INTEGER;
            case XSD_DECIMAL -> kind == ValueKind.INTEGER || kind == ValueKind.DECIMAL;
            case XSD_BOOLEAN -> kind == ValueKind.BOOLEAN;
            default -> kind == ValueKind.STRING;
        };
    }
}
