package wprebuild.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAnnotationPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLFunctionalDataPropertyAxiom;
import org.semanticweb.owlapi.model.OWLFunctionalObjectPropertyAxiom;
import org.semanticweb.owlapi.model.OWLInverseObjectPropertiesAxiom;
import org.semanticweb.owlapi.vocab.OWL2Datatype;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import wprebuild.app.access.JdbcRoleAssignmentRepository;
import wprebuild.app.access.ProjectRole;
import wprebuild.app.access.UserService;
import wprebuild.app.ontology.ProjectRuntimeRegistry;
import wprebuild.app.ontology.dto.EntityNode;
import wprebuild.app.ontology.frame.AnnotationPropertyFrame;
import wprebuild.app.ontology.frame.AnnotationValue;
import wprebuild.app.ontology.frame.DataPropertyFrame;
import wprebuild.app.ontology.frame.LiteralValue;
import wprebuild.app.ontology.frame.ObjectPropertyCharacteristic;
import wprebuild.app.ontology.frame.ObjectPropertyFrame;
import wprebuild.app.ontology.frame.PropertyFrameAxioms;
import wprebuild.app.support.DatabaseTest;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.revision.Revision;
import wprebuild.kernel.revision.RevisionLog;
import wprebuild.kernel.runtime.GeneratedChanges;

/** Property frames: read, one edit, and optimistic conflict for each property kind (P3-17). */
@DatabaseTest
@AutoConfigureMockMvc
@Transactional
class PropertyFramesTest {

    private static final String PASSWORD = "correct-password";

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private static final String NS = "http://example.org/prop#";

    private static final String HAS_PART = NS + "hasPart";

    private static final String SPATIAL = NS + "spatialRelation";

    private static final String PART_OF = NS + "partOf";

    private static final String STORED_AT = NS + "storedAt";

    private static final String PALLET = NS + "Pallet";

    private static final String PART = NS + "Part";

    private static final String ZONE = NS + "Zone";

    private static final String CODE = NS + "code";

    private static final String IDENTIFIER = NS + "identifier";

    private static final String ALT_LABEL = NS + "altLabel";

    private static final String LABEL = OWLRDFVocabulary.RDFS_LABEL.getIRI().toString();

    private static final String COMMENT = OWLRDFVocabulary.RDFS_COMMENT.getIRI().toString();

    private static final String XSD_STRING = OWL2Datatype.XSD_STRING.getIRI().toString();

    private static final String LANG_STRING =
            OWL2Datatype.RDF_LANG_STRING.getIRI().toString();

    private final MockMvc mockMvc;

    private final tools.jackson.databind.json.JsonMapper json;

    private final UserService users;

    private final JdbcRoleAssignmentRepository roles;

    private final ProjectRuntimeRegistry registry;

    private final RevisionLog revisions;

    PropertyFramesTest(
            MockMvc mockMvc,
            tools.jackson.databind.json.JsonMapper json,
            UserService users,
            JdbcRoleAssignmentRepository roles,
            ProjectRuntimeRegistry registry,
            RevisionLog revisions) {
        this.mockMvc = mockMvc;
        this.json = json;
        this.users = users;
        this.roles = roles;
        this.registry = registry;
        this.revisions = revisions;
    }

    @Test
    void to_axioms_converts_editable_fields_and_ignores_other_axioms() {
        OWLDataFactory data = OWLManager.getOWLDataFactory();
        var hasPart = data.getOWLObjectProperty(IRI.create(HAS_PART));
        var partOf = data.getOWLObjectProperty(IRI.create(PART_OF));
        assertThat(data.getOWLInverseObjectPropertiesAxiom(hasPart, partOf))
                .isEqualTo(data.getOWLInverseObjectPropertiesAxiom(partOf, hasPart));

        EntityNode label = entity(LABEL, "ANNOTATION_PROPERTY", "rdfs:label");
        AnnotationValue annotation = new AnnotationValue(
                label, new LiteralValue("has part", LANG_STRING, Optional.of("en")), Optional.of("en"));
        ObjectPropertyFrame objectFrame = new ObjectPropertyFrame(
                entity(HAS_PART, "OBJECT_PROPERTY", "hasPart"),
                List.of(annotation),
                List.of(entity(SPATIAL, "OBJECT_PROPERTY", "spatialRelation")),
                List.of(entity(PALLET, "CLASS", "Pallet")),
                List.of(entity(PART, "CLASS", "Part")),
                List.of(ObjectPropertyCharacteristic.TRANSITIVE, ObjectPropertyCharacteristic.FUNCTIONAL),
                List.of(entity(PART_OF, "OBJECT_PROPERTY", "partOf")),
                List.of("unread"));
        Set<OWLAxiom> objectAxioms = PropertyFrameAxioms.toAxioms(objectFrame);
        assertThat(objectAxioms).hasSize(7);
        assertThat(objectAxioms).anyMatch(OWLFunctionalObjectPropertyAxiom.class::isInstance);
        assertThat(objectAxioms).anyMatch(OWLInverseObjectPropertiesAxiom.class::isInstance);
        assertThat(objectAxioms).noneMatch(axiom -> axiom.toString().contains("unread"));

        DataPropertyFrame dataFrame = new DataPropertyFrame(
                entity(CODE, "DATA_PROPERTY", "code"),
                List.of(annotation),
                List.of(entity(IDENTIFIER, "DATA_PROPERTY", "identifier")),
                List.of(entity(PALLET, "CLASS", "Pallet")),
                List.of(entity(XSD_STRING, "DATATYPE", "xsd:string")),
                true,
                List.of("unread"));
        assertThat(PropertyFrameAxioms.toAxioms(dataFrame))
                .hasSize(5)
                .anyMatch(OWLFunctionalDataPropertyAxiom.class::isInstance);
        assertThat(PropertyFrameAxioms.toAxioms(new DataPropertyFrame(
                        dataFrame.subject(),
                        dataFrame.annotations(),
                        dataFrame.parents(),
                        dataFrame.domains(),
                        dataFrame.ranges(),
                        false,
                        dataFrame.otherAxioms())))
                .noneMatch(OWLFunctionalDataPropertyAxiom.class::isInstance);

        AnnotationPropertyFrame annotationFrame = new AnnotationPropertyFrame(
                entity(ALT_LABEL, "ANNOTATION_PROPERTY", "altLabel"),
                List.of(annotation),
                List.of(entity(LABEL, "ANNOTATION_PROPERTY", "rdfs:label")),
                List.of(PALLET),
                List.of(XSD_STRING),
                List.of("unread"));
        Set<OWLAxiom> annotationAxioms = PropertyFrameAxioms.toAxioms(annotationFrame);
        assertThat(annotationAxioms).hasSize(4);
        assertThat(annotationAxioms)
                .anyMatch(axiom -> axiom instanceof OWLAnnotationPropertyDomainAxiom domain
                        && domain.getDomain().toString().equals(PALLET));
    }

    @Test
    void object_property_frame_reads_one_edit_and_rejects_a_stale_update() throws Exception {
        account("owner", "PROJECT_CREATOR");
        account("editor");
        account("viewer");
        account("stranger");
        SignedIn owner = login("owner");
        SignedIn editor = login("editor");
        SignedIn viewer = login("viewer");
        SignedIn stranger = login("stranger");
        ProjectId projectId = createProject(owner);
        roles.assign(projectId, new UserId("editor"), ProjectRole.CAN_EDIT);
        roles.assign(projectId, new UserId("viewer"), ProjectRole.CAN_VIEW);
        OWLAxiom chain = plantObject(projectId);

        JsonNode frame = frame(owner, projectId, "object-property", HAS_PART);
        assertNode(frame.path("subject"), HAS_PART, "OBJECT_PROPERTY", "hasPart", false, false);
        assertThat(lexicals(frame.path("annotations"))).containsExactly("has part", "包含");
        assertNode(frame.path("parents").get(0), SPATIAL, "OBJECT_PROPERTY", "spatialRelation", true, false);
        assertNode(frame.path("domains").get(0), PALLET, "CLASS", "Pallet", false, false);
        assertNode(frame.path("ranges").get(0), PART, "CLASS", "Part", false, false);
        assertThat(texts(frame.path("characteristics"))).containsExactly("TRANSITIVE");
        assertNode(frame.path("inverses").get(0), PART_OF, "OBJECT_PROPERTY", "partOf", false, false);
        assertThat(texts(frame.path("otherAxioms")))
                .hasSize(2)
                .anyMatch(text -> text.contains("SubPropertyOf") && text.contains("hasPart"));
        assertThat(names(frame(owner, projectId, "object-property", PART_OF).path("inverses")))
                .containsExactly("hasPart");
        assertThat(names(children(owner, projectId, "object-property", SPATIAL)))
                .containsExactly("hasPart");
        assertThat(frame(editor, projectId, "object-property", HAS_PART)).isEqualTo(frame);

        MvcResult missing = mockMvc.perform(get("/api/projects/{id}/frames/object-property", projectId.toString())
                        .param("iri", NS + "Missing")
                        .session(owner.session()))
                .andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        MvcResult forbiddenGet = mockMvc.perform(get("/api/projects/{id}/frames/object-property", projectId.toString())
                        .param("iri", HAS_PART)
                        .session(stranger.session()))
                .andReturn();
        assertThat(forbiddenGet.getResponse().getStatus()).isEqualTo(403);

        ObjectNode edited = copy(frame);
        ((ArrayNode) edited.get("characteristics")).add("FUNCTIONAL");
        MvcResult updated = putFrame(owner, projectId, "object-property", frame, edited);
        assertThat(updated.getResponse().getStatus())
                .as(updated.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode body = read(updated);
        assertThat(body.path("revision").asLong()).isEqualTo(3);
        assertThat(texts(body.path("frame").path("characteristics"))).containsExactly("FUNCTIONAL", "TRANSITIVE");
        assertThat(containsAxiom(projectId, chain)).isTrue();
        Revision revision = revisions.get(projectId, RevisionNumber.of(3)).orElseThrow();
        assertThat(revision.description()).isEqualTo("Edited object property hasPart");
        assertThat(revision.changes()).hasSize(1);
        assertThat(revision.changes().get(0)).isInstanceOf(AddAxiom.class);
        assertThat(revision.changes().get(0).axiom().orElseThrow())
                .isInstanceOf(OWLFunctionalObjectPropertyAxiom.class);

        ObjectNode stale = copy(frame);
        ((ArrayNode) stale.get("domains")).add(entityNode(ZONE, "CLASS", "Zone"));
        MvcResult conflict = putFrame(editor, projectId, "object-property", frame, stale);
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(read(conflict).path("code").asString()).isEqualTo("FRAME_CHANGED");
        assertThat(head(projectId)).isEqualTo(RevisionNumber.of(3));
        assertThat(names(frame(owner, projectId, "object-property", HAS_PART).path("domains")))
                .containsExactly("Pallet");

        MvcResult forbidden = putFrame(viewer, projectId, "object-property", frame, edited);
        assertThat(forbidden.getResponse().getStatus()).isEqualTo(403);
        assertThat(read(forbidden).path("code").asString()).isEqualTo("PERMISSION_DENIED");
    }

    @Test
    void data_property_frame_reads_one_edit_and_rejects_a_stale_update() throws Exception {
        account("owner", "PROJECT_CREATOR");
        account("editor");
        SignedIn owner = login("owner");
        SignedIn editor = login("editor");
        ProjectId projectId = createProject(owner);
        roles.assign(projectId, new UserId("editor"), ProjectRole.CAN_EDIT);
        OWLAxiom complement = plantData(projectId);

        JsonNode frame = frame(owner, projectId, "data-property", CODE);
        assertNode(frame.path("subject"), CODE, "DATA_PROPERTY", "code", false, false);
        assertThat(lexicals(frame.path("annotations"))).containsExactly("sku");
        assertNode(frame.path("parents").get(0), IDENTIFIER, "DATA_PROPERTY", "identifier", true, false);
        assertNode(frame.path("domains").get(0), PALLET, "CLASS", "Pallet", false, false);
        assertNode(frame.path("ranges").get(0), XSD_STRING, "DATATYPE", "xsd:string", false, false);
        assertThat(frame.path("functional").isBoolean()).isTrue();
        assertThat(frame.path("functional").asBoolean()).isFalse();
        assertThat(texts(frame.path("otherAxioms"))).containsExactly("code Range:  not xsd:integer");
        assertThat(names(children(owner, projectId, "data-property", IDENTIFIER)))
                .containsExactly("code");

        ObjectNode edited = copy(frame);
        edited.put("functional", true);
        MvcResult updated = putFrame(owner, projectId, "data-property", frame, edited);
        assertThat(updated.getResponse().getStatus())
                .as(updated.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(read(updated).path("frame").path("functional").asBoolean()).isTrue();
        assertThat(containsAxiom(projectId, complement)).isTrue();
        Revision revision = revisions.get(projectId, RevisionNumber.of(3)).orElseThrow();
        assertThat(revision.description()).isEqualTo("Edited data property code");
        assertThat(revision.changes()).hasSize(1);
        assertThat(revision.changes().get(0).axiom().orElseThrow()).isInstanceOf(OWLFunctionalDataPropertyAxiom.class);

        ObjectNode stale = copy(frame);
        ((ArrayNode) stale.get("parents")).add(entityNode(NS + "extra", "DATA_PROPERTY", "extra"));
        MvcResult conflict = putFrame(editor, projectId, "data-property", frame, stale);
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(read(conflict).path("code").asString()).isEqualTo("FRAME_CHANGED");
        assertThat(frame(owner, projectId, "data-property", CODE)
                        .path("functional")
                        .asBoolean())
                .isTrue();
        assertThat(names(frame(owner, projectId, "data-property", CODE).path("parents")))
                .containsExactly("identifier");
    }

    @Test
    void annotation_property_frame_reads_one_edit_and_rejects_a_stale_update() throws Exception {
        account("owner", "PROJECT_CREATOR");
        account("editor");
        SignedIn owner = login("owner");
        SignedIn editor = login("editor");
        ProjectId projectId = createProject(owner);
        roles.assign(projectId, new UserId("editor"), ProjectRole.CAN_EDIT);
        OWLAxiom anonymous = plantAnnotation(projectId);

        JsonNode frame = frame(owner, projectId, "annotation-property", ALT_LABEL);
        assertNode(frame.path("subject"), ALT_LABEL, "ANNOTATION_PROPERTY", "altLabel", false, false);
        assertThat(lexicals(frame.path("annotations"))).containsExactly("alternate");
        assertNode(
                frame.path("annotations").get(0).path("property"),
                COMMENT,
                "ANNOTATION_PROPERTY",
                "rdfs:comment",
                false,
                false);
        assertNode(frame.path("parents").get(0), LABEL, "ANNOTATION_PROPERTY", "rdfs:label", true, false);
        assertThat(texts(frame.path("domains"))).containsExactly(PALLET);
        assertThat(texts(frame.path("ranges"))).containsExactly(XSD_STRING);
        assertThat(texts(frame.path("otherAxioms"))).hasSize(1);
        assertThat(containsAxiom(projectId, anonymous)).isTrue();
        assertThat(names(children(owner, projectId, "annotation-property", LABEL)))
                .containsExactly("altLabel");

        ObjectNode edited = copy(frame);
        ((ArrayNode) edited.get("domains")).add(ZONE);
        MvcResult updated = putFrame(owner, projectId, "annotation-property", frame, edited);
        assertThat(updated.getResponse().getStatus())
                .as(updated.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(texts(read(updated).path("frame").path("domains"))).containsExactly(PALLET, ZONE);
        assertThat(containsAxiom(projectId, anonymous)).isTrue();
        Revision revision = revisions.get(projectId, RevisionNumber.of(3)).orElseThrow();
        assertThat(revision.description()).isEqualTo("Edited annotation property altLabel");
        assertThat(revision.changes()).hasSize(1);
        OWLAxiom added = revision.changes().get(0).axiom().orElseThrow();
        assertThat(added).isInstanceOf(OWLAnnotationPropertyDomainAxiom.class);
        assertThat(((OWLAnnotationPropertyDomainAxiom) added).getDomain().toString())
                .isEqualTo(ZONE);

        ObjectNode stale = copy(frame);
        ((ObjectNode) stale.path("annotations").get(0).path("value")).put("lexical", "changed");
        MvcResult conflict = putFrame(editor, projectId, "annotation-property", frame, stale);
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(read(conflict).path("code").asString()).isEqualTo("FRAME_CHANGED");
        JsonNode current = frame(owner, projectId, "annotation-property", ALT_LABEL);
        assertThat(lexicals(current.path("annotations"))).containsExactly("alternate");
        assertThat(texts(current.path("domains"))).containsExactly(PALLET, ZONE);
    }

    private OWLAxiom plantObject(ProjectId projectId) {
        OWLDataFactory data = OWLManager.getOWLDataFactory();
        var hasPart = data.getOWLObjectProperty(IRI.create(HAS_PART));
        var spatial = data.getOWLObjectProperty(IRI.create(SPATIAL));
        var partOf = data.getOWLObjectProperty(IRI.create(PART_OF));
        var storedAt = data.getOWLObjectProperty(IRI.create(STORED_AT));
        var pallet = data.getOWLClass(IRI.create(PALLET));
        var part = data.getOWLClass(IRI.create(PART));
        var label = data.getRDFSLabel();
        OWLAxiom chain = data.getOWLSubPropertyChainOfAxiom(List.of(partOf, spatial), hasPart);
        OWLAxiom disjoint = data.getOWLDisjointObjectPropertiesAxiom(hasPart, storedAt);
        plantAxioms(
                projectId,
                List.of(
                        data.getOWLAnnotationAssertionAxiom(
                                label, hasPart.getIRI(), data.getOWLLiteral("has part", "en")),
                        data.getOWLAnnotationAssertionAxiom(label, hasPart.getIRI(), data.getOWLLiteral("包含", "zh")),
                        data.getOWLSubObjectPropertyOfAxiom(hasPart, spatial),
                        data.getOWLObjectPropertyDomainAxiom(hasPart, pallet),
                        data.getOWLObjectPropertyRangeAxiom(hasPart, part),
                        data.getOWLTransitiveObjectPropertyAxiom(hasPart),
                        data.getOWLInverseObjectPropertiesAxiom(hasPart, partOf),
                        chain,
                        disjoint));
        return chain;
    }

    private OWLAxiom plantData(ProjectId projectId) {
        OWLDataFactory data = OWLManager.getOWLDataFactory();
        var code = data.getOWLDataProperty(IRI.create(CODE));
        var identifier = data.getOWLDataProperty(IRI.create(IDENTIFIER));
        var pallet = data.getOWLClass(IRI.create(PALLET));
        OWLAnnotationProperty comment = annotationProperty(data, OWLRDFVocabulary.RDFS_COMMENT);
        OWLAxiom complement =
                data.getOWLDataPropertyRangeAxiom(code, data.getOWLDataComplementOf(data.getIntegerOWLDatatype()));
        plantAxioms(
                projectId,
                List.of(
                        data.getOWLAnnotationAssertionAxiom(comment, code.getIRI(), data.getOWLLiteral("sku")),
                        data.getOWLSubDataPropertyOfAxiom(code, identifier),
                        data.getOWLDataPropertyDomainAxiom(code, pallet),
                        data.getOWLDataPropertyRangeAxiom(code, data.getStringOWLDatatype()),
                        complement));
        return complement;
    }

    private OWLAxiom plantAnnotation(ProjectId projectId) {
        OWLDataFactory data = OWLManager.getOWLDataFactory();
        var altLabel = data.getOWLAnnotationProperty(IRI.create(ALT_LABEL));
        OWLAnnotationProperty comment = annotationProperty(data, OWLRDFVocabulary.RDFS_COMMENT);
        OWLAnnotationProperty seeAlso = annotationProperty(data, OWLRDFVocabulary.RDFS_SEE_ALSO);
        OWLAxiom anonymous =
                data.getOWLAnnotationAssertionAxiom(seeAlso, altLabel.getIRI(), data.getOWLAnonymousIndividual());
        plantAxioms(
                projectId,
                List.of(
                        data.getOWLAnnotationAssertionAxiom(
                                comment, altLabel.getIRI(), data.getOWLLiteral("alternate")),
                        data.getOWLSubAnnotationPropertyOfAxiom(altLabel, data.getRDFSLabel()),
                        data.getOWLAnnotationPropertyDomainAxiom(altLabel, IRI.create(PALLET)),
                        data.getOWLAnnotationPropertyRangeAxiom(altLabel, IRI.create(XSD_STRING)),
                        anonymous));
        return anonymous;
    }

    private static OWLAnnotationProperty annotationProperty(OWLDataFactory data, OWLRDFVocabulary vocabulary) {
        return data.getOWLAnnotationProperty(vocabulary.getIRI());
    }

    private void plantAxioms(ProjectId projectId, List<OWLAxiom> axioms) {
        var handle = registry.get(projectId);
        Set<OntologyKey> keys = handle.runtime().ontologies().keys();
        assertThat(keys).hasSize(1);
        OntologyKey key = keys.iterator().next();
        List<OntologyChange> changes = new ArrayList<>();
        for (OWLAxiom axiom : axioms) {
            changes.add(new AddAxiom(key, axiom));
        }
        handle.pipeline().apply(new UserId("owner"), context -> new GeneratedChanges<>(changes, "frame"));
        assertThat(head(projectId)).isEqualTo(RevisionNumber.of(2));
    }

    private boolean containsAxiom(ProjectId projectId, OWLAxiom axiom) {
        var handle = registry.get(projectId);
        return handle.gate()
                .read(() -> handle.runtime().ontologies().keys().stream()
                        .anyMatch(key -> handle.runtime().ontologies().containsAxiom(key, axiom)));
    }

    private JsonNode frame(SignedIn user, ProjectId projectId, String kind, String iri) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/projects/{id}/frames/{kind}", projectId.toString(), kind)
                        .param("iri", iri)
                        .session(user.session()))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return read(result);
    }

    private MvcResult putFrame(SignedIn user, ProjectId projectId, String kind, JsonNode from, JsonNode to)
            throws Exception {
        return send(
                user,
                put("/api/projects/{id}/frames/{kind}", projectId.toString(), kind)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new UpdateBody(from, to))));
    }

    private JsonNode children(SignedIn user, ProjectId projectId, String kind, String parent) throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/api/projects/{id}/hierarchies/{kind}/children", projectId.toString(), kind)
                                .param("parent", parent)
                                .session(user.session()))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return read(result);
    }

    private ObjectNode copy(JsonNode node) throws Exception {
        return (ObjectNode) json.readTree(json.writeValueAsString(node));
    }

    private ObjectNode entityNode(String iri, String type, String name) {
        ObjectNode node = json.createObjectNode();
        node.put("iri", iri);
        node.put("entityType", type);
        node.put("displayName", name);
        node.put("hasChildren", false);
        node.put("deprecated", false);
        return node;
    }

    private static EntityNode entity(String iri, String type, String name) {
        return new EntityNode(iri, type, name, false, false);
    }

    private static void assertNode(
            JsonNode node, String iri, String type, String name, boolean hasChildren, boolean deprecated) {
        assertThat(node.path("iri").asString()).isEqualTo(iri);
        assertThat(node.path("entityType").asString()).isEqualTo(type);
        assertThat(node.path("displayName").asString()).isEqualTo(name);
        assertThat(node.path("hasChildren").asBoolean()).isEqualTo(hasChildren);
        assertThat(node.path("deprecated").asBoolean()).isEqualTo(deprecated);
    }

    private static List<String> lexicals(JsonNode annotations) {
        List<String> values = new ArrayList<>();
        annotations.forEach(
                node -> values.add(node.path("value").path("lexical").asString()));
        return values;
    }

    private static List<String> names(JsonNode nodes) {
        List<String> values = new ArrayList<>();
        nodes.forEach(node -> values.add(node.path("displayName").asString()));
        return values;
    }

    private static List<String> texts(JsonNode nodes) {
        List<String> values = new ArrayList<>();
        nodes.forEach(node -> values.add(node.asString()));
        return values;
    }

    private RevisionNumber head(ProjectId projectId) {
        RevisionNumber stored = revisions.head(projectId);
        assertThat(registry.get(projectId).runtime().head()).isEqualTo(stored);
        return stored;
    }

    private ProjectId createProject(SignedIn owner) throws Exception {
        MvcResult created = send(
                owner,
                post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new CreateProject("Warehouse", "Stock", null))));
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(201);
        return ProjectId.of(read(created).path("projectId").asString());
    }

    private MvcResult send(
            SignedIn user, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mockMvc.perform(request.session(user.session())
                        .cookie(user.csrf())
                        .header(CSRF_HEADER, user.csrf().getValue()))
                .andReturn();
    }

    private void account(String userId, String... appRoles) {
        users.create(userId, userId, userId + "@example.com", PASSWORD, List.of(appRoles));
    }

    private SignedIn login(String username) throws Exception {
        MockHttpSession session = new MockHttpSession();
        Cookie csrf = csrfCookie(session);
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .session(session)
                        .cookie(csrf)
                        .header(CSRF_HEADER, csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Login(username, PASSWORD))))
                .andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        return new SignedIn(session, csrfAfter(login, csrf));
    }

    private Cookie csrfCookie(MockHttpSession session) throws Exception {
        MvcResult primed = mockMvc.perform(get("/api/auth/me").session(session)).andReturn();
        Cookie csrf = primed.getResponse().getCookie(CSRF_COOKIE);
        assertThat(csrf).isNotNull();
        return csrf;
    }

    private static Cookie csrfAfter(MvcResult result, Cookie previous) {
        Cookie updated = result.getResponse().getCookie(CSRF_COOKIE);
        return updated == null ? previous : updated;
    }

    private JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private record SignedIn(MockHttpSession session, Cookie csrf) {}

    private record Login(String username, String password) {}

    private record CreateProject(String name, String description, String ontologyIri) {}

    private record UpdateBody(JsonNode from, JsonNode to) {}
}
