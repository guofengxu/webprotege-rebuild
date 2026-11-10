package wprebuild.app.web;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.semanticweb.owlapi.model.IRI;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import wprebuild.app.bus.CommandBus;
import wprebuild.app.ontology.CreateEmptyProject;
import wprebuild.app.ontology.CreateEntities;
import wprebuild.app.ontology.DeleteEntities;
import wprebuild.app.ontology.DownloadFormat;
import wprebuild.app.ontology.EntityActions;
import wprebuild.app.ontology.GetAnnotationPropertyFrame;
import wprebuild.app.ontology.GetClassFrame;
import wprebuild.app.ontology.GetDataPropertyFrame;
import wprebuild.app.ontology.GetHierarchyChildren;
import wprebuild.app.ontology.GetHierarchyPaths;
import wprebuild.app.ontology.GetHierarchyRoots;
import wprebuild.app.ontology.GetNamedIndividualFrame;
import wprebuild.app.ontology.GetObjectPropertyFrame;
import wprebuild.app.ontology.GetProjectDownload;
import wprebuild.app.ontology.HierarchyKind;
import wprebuild.app.ontology.ImportOntologyFromUpload;
import wprebuild.app.ontology.ListIndividualsByType;
import wprebuild.app.ontology.ProjectDownload;
import wprebuild.app.ontology.UpdateAnnotationPropertyFrame;
import wprebuild.app.ontology.UpdateClassFrame;
import wprebuild.app.ontology.UpdateDataPropertyFrame;
import wprebuild.app.ontology.UpdateNamedIndividualFrame;
import wprebuild.app.ontology.UpdateObjectPropertyFrame;
import wprebuild.app.ontology.UploadProperties;
import wprebuild.app.project.GetProjectDetails;
import wprebuild.app.project.ListMyProjects;
import wprebuild.app.project.ListTrashedProjects;
import wprebuild.app.project.MoveProjectToTrash;
import wprebuild.app.project.ProjectDetails;
import wprebuild.app.project.ProjectText;
import wprebuild.app.project.RestoreProjectFromTrash;
import wprebuild.app.project.UpdateProjectDetails;
import wprebuild.app.search.SearchEntities;
import wprebuild.app.search.SearchTypes;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.id.UserId;

/**
 * Project lifecycle, hierarchy queries, entity search, frames, and edits. Permission is checked by
 * {@link CommandBus}.
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final CommandBus bus;

    private final UploadProperties uploads;

    public ProjectController(CommandBus bus, UploadProperties uploads) {
        this.bus = Objects.requireNonNull(bus, "bus");
        this.uploads = Objects.requireNonNull(uploads, "uploads");
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> create(@RequestBody CreateProjectRequest body) {
        UserId user = currentUser();
        if (user == null) {
            return ApiProblems.of(ApiProblems.authenticationRequired());
        }
        if (body == null) {
            return invalid("request body is required");
        }
        try {
            ProjectId projectId = bus.execute(
                    user, new CreateEmptyProject(body.name(), body.description(), ontologyIri(body.ontologyIri())));
            ProjectDetails details = bus.execute(user, new GetProjectDetails(projectId));
            return ResponseEntity.created(URI.create("/api/projects/" + projectId))
                    .body(ProjectResponse.from(details));
        } catch (IllegalArgumentException ex) {
            return invalid(ex.getMessage());
        }
    }

    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("name") String name,
            @RequestParam(value = "description", required = false) String description)
            throws IOException {
        UserId user = currentUser();
        if (user == null) {
            return ApiProblems.of(ApiProblems.authenticationRequired());
        }
        final String displayName;
        final String text;
        try {
            displayName = ProjectText.displayName(name);
            text = ProjectText.description(description);
        } catch (IllegalArgumentException ex) {
            return invalid(ex.getMessage());
        }
        if (file == null || file.isEmpty() || file.getSize() < 0) {
            return invalid("upload file is empty");
        }
        if (file.getSize() > uploads.maxSize().toBytes()) {
            return ApiProblems.of(ApiProblems.payloadTooLarge());
        }
        Path stored = Files.createTempFile("wp-upload-", ".bin");
        try {
            file.transferTo(stored);
            ProjectId projectId = bus.execute(user, new ImportOntologyFromUpload(displayName, text, stored));
            ProjectDetails details = bus.execute(user, new GetProjectDetails(projectId));
            return ResponseEntity.created(URI.create("/api/projects/" + projectId))
                    .body(ProjectResponse.from(details));
        } catch (IllegalArgumentException ex) {
            return invalid(ex.getMessage());
        } finally {
            Files.deleteIfExists(stored);
        }
    }

    @GetMapping
    public ResponseEntity<?> list() {
        UserId user = currentUser();
        if (user == null) {
            return ApiProblems.of(ApiProblems.authenticationRequired());
        }
        List<ProjectDetails> projects = bus.execute(user, new ListMyProjects());
        return ResponseEntity.ok(projects.stream().map(ProjectResponse::from).toList());
    }

    @GetMapping("/trash")
    public ResponseEntity<?> listTrash() {
        UserId user = currentUser();
        if (user == null) {
            return ApiProblems.of(ApiProblems.authenticationRequired());
        }
        List<ProjectDetails> projects = bus.execute(user, new ListTrashedProjects());
        return ResponseEntity.ok(projects.stream().map(ProjectResponse::from).toList());
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<?> download(
            @PathVariable String id,
            @RequestParam(name = "format", required = false) String format,
            @RequestParam(name = "revision", required = false) Long revision) {
        UserId user = currentUser();
        if (user == null) {
            return ApiProblems.of(ApiProblems.authenticationRequired());
        }
        final ProjectId projectId;
        try {
            projectId = ProjectId.of(id);
        } catch (IllegalArgumentException ex) {
            return invalid("project id is not a UUID");
        }
        final DownloadFormat parsed;
        final Optional<RevisionNumber> number;
        try {
            parsed = DownloadFormat.parse(format);
            number = revision == null ? Optional.empty() : Optional.of(RevisionNumber.of(revision));
        } catch (IllegalArgumentException ex) {
            return invalid(ex.getMessage());
        }
        ProjectDownload file = bus.execute(user, new GetProjectDownload(projectId, number, parsed));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(file.fileName()))
                .body(file.body());
    }

    @GetMapping("/{id}/hierarchies/{kind}/roots")
    public ResponseEntity<?> hierarchyRoots(@PathVariable String id, @PathVariable String kind) {
        return withProject(
                id,
                (user, projectId) -> ResponseEntity.ok(
                        bus.execute(user, new GetHierarchyRoots(projectId, HierarchyKind.parse(kind)))));
    }

    @GetMapping("/{id}/hierarchies/{kind}/children")
    public ResponseEntity<?> hierarchyChildren(
            @PathVariable String id, @PathVariable String kind, @RequestParam("parent") String parent) {
        return withProject(
                id,
                (user, projectId) -> ResponseEntity.ok(bus.execute(
                        user, new GetHierarchyChildren(projectId, HierarchyKind.parse(kind), entityIri(parent)))));
    }

    @GetMapping("/{id}/hierarchies/{kind}/paths")
    public ResponseEntity<?> hierarchyPaths(
            @PathVariable String id, @PathVariable String kind, @RequestParam("entity") String entity) {
        return withProject(
                id,
                (user, projectId) -> ResponseEntity.ok(bus.execute(
                        user, new GetHierarchyPaths(projectId, HierarchyKind.parse(kind), entityIri(entity)))));
    }

    @GetMapping("/{id}/search")
    public ResponseEntity<?> search(
            @PathVariable String id,
            @RequestParam("q") String query,
            @RequestParam(name = "types", required = false) String types,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return withProject(
                id,
                (user, projectId) -> ResponseEntity.ok(
                        bus.execute(user, new SearchEntities(projectId, query, SearchTypes.parse(types), limit))));
    }

    @GetMapping("/{id}/frames/class")
    public ResponseEntity<?> classFrame(@PathVariable String id, @RequestParam("iri") String iri) {
        return withProject(
                id,
                (user, projectId) ->
                        ResponseEntity.ok(bus.execute(user, new GetClassFrame(projectId, entityIri(iri)))));
    }

    @PutMapping(path = "/{id}/frames/class", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updateClassFrame(@PathVariable String id, @RequestBody UpdateClassFrame.Request body) {
        if (body == null || body.from() == null || body.to() == null) {
            return invalid("request body is required");
        }
        return withProject(id, (user, projectId) -> {
            UpdateClassFrame.Result updated =
                    bus.execute(user, new UpdateClassFrame(projectId, body.from(), body.to()));
            return ResponseEntity.ok(updated.body());
        });
    }

    @GetMapping("/{id}/frames/object-property")
    public ResponseEntity<?> objectPropertyFrame(@PathVariable String id, @RequestParam("iri") String iri) {
        return withProject(
                id,
                (user, projectId) ->
                        ResponseEntity.ok(bus.execute(user, new GetObjectPropertyFrame(projectId, entityIri(iri)))));
    }

    @PutMapping(path = "/{id}/frames/object-property", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updateObjectPropertyFrame(
            @PathVariable String id, @RequestBody UpdateObjectPropertyFrame.Request body) {
        if (body == null || body.from() == null || body.to() == null) {
            return invalid("request body is required");
        }
        return withProject(
                id,
                (user, projectId) -> ResponseEntity.ok(
                        bus.execute(user, new UpdateObjectPropertyFrame(projectId, body.from(), body.to()))
                                .body()));
    }

    @GetMapping("/{id}/frames/data-property")
    public ResponseEntity<?> dataPropertyFrame(@PathVariable String id, @RequestParam("iri") String iri) {
        return withProject(
                id,
                (user, projectId) ->
                        ResponseEntity.ok(bus.execute(user, new GetDataPropertyFrame(projectId, entityIri(iri)))));
    }

    @PutMapping(path = "/{id}/frames/data-property", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updateDataPropertyFrame(
            @PathVariable String id, @RequestBody UpdateDataPropertyFrame.Request body) {
        if (body == null || body.from() == null || body.to() == null) {
            return invalid("request body is required");
        }
        return withProject(
                id,
                (user, projectId) -> ResponseEntity.ok(
                        bus.execute(user, new UpdateDataPropertyFrame(projectId, body.from(), body.to()))
                                .body()));
    }

    @GetMapping("/{id}/frames/annotation-property")
    public ResponseEntity<?> annotationPropertyFrame(@PathVariable String id, @RequestParam("iri") String iri) {
        return withProject(
                id,
                (user, projectId) -> ResponseEntity.ok(
                        bus.execute(user, new GetAnnotationPropertyFrame(projectId, entityIri(iri)))));
    }

    @PutMapping(path = "/{id}/frames/annotation-property", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updateAnnotationPropertyFrame(
            @PathVariable String id, @RequestBody UpdateAnnotationPropertyFrame.Request body) {
        if (body == null || body.from() == null || body.to() == null) {
            return invalid("request body is required");
        }
        return withProject(
                id,
                (user, projectId) -> ResponseEntity.ok(
                        bus.execute(user, new UpdateAnnotationPropertyFrame(projectId, body.from(), body.to()))
                                .body()));
    }

    @GetMapping("/{id}/frames/individual")
    public ResponseEntity<?> individualFrame(@PathVariable String id, @RequestParam("iri") String iri) {
        return withProject(
                id,
                (user, projectId) ->
                        ResponseEntity.ok(bus.execute(user, new GetNamedIndividualFrame(projectId, entityIri(iri)))));
    }

    @PutMapping(path = "/{id}/frames/individual", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updateIndividualFrame(
            @PathVariable String id, @RequestBody UpdateNamedIndividualFrame.Request body) {
        if (body == null || body.from() == null || body.to() == null) {
            return invalid("request body is required");
        }
        return withProject(
                id,
                (user, projectId) -> ResponseEntity.ok(
                        bus.execute(user, new UpdateNamedIndividualFrame(projectId, body.from(), body.to()))
                                .body()));
    }

    @GetMapping("/{id}/individuals")
    public ResponseEntity<?> individuals(
            @PathVariable String id,
            @RequestParam("type") String type,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        return withProject(
                id,
                (user, projectId) -> ResponseEntity.ok(
                        bus.execute(user, new ListIndividualsByType(projectId, entityIri(type), page, size))));
    }

    @PostMapping(path = "/{id}/entities", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createEntities(@PathVariable String id, @RequestBody CreateEntitiesRequest body) {
        if (body == null) {
            return invalid("request body is required");
        }
        return withProject(id, (user, projectId) -> {
            CreateEntities.Result created = bus.execute(
                    user,
                    new CreateEntities(
                            projectId,
                            EntityActions.parse(body.entityType()),
                            names(body.names()),
                            language(body.lang()),
                            iris(body.parents())));
            return ResponseEntity.status(HttpStatus.CREATED).body(created.body());
        });
    }

    @DeleteMapping(path = "/{id}/entities", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> deleteEntities(@PathVariable String id, @RequestBody DeleteEntitiesRequest body) {
        if (body == null) {
            return invalid("request body is required");
        }
        return withProject(id, (user, projectId) -> {
            RevisionNumber revision = bus.execute(user, new DeleteEntities(projectId, iris(body.iris())));
            return ResponseEntity.ok(new RevisionResponse(revision.value()));
        });
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        return withProject(id, (user, projectId) -> {
            ProjectDetails details = bus.execute(user, new GetProjectDetails(projectId));
            return ResponseEntity.ok(ProjectResponse.from(details));
        });
    }

    @PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody UpdateProjectRequest body) {
        if (body == null) {
            return invalid("request body is required");
        }
        return withProject(id, (user, projectId) -> {
            ProjectDetails details =
                    bus.execute(user, new UpdateProjectDetails(projectId, body.name(), body.description()));
            return ResponseEntity.ok(ProjectResponse.from(details));
        });
    }

    @PostMapping("/{id}/trash")
    public ResponseEntity<?> trash(@PathVariable String id) {
        return withProject(id, (user, projectId) -> {
            ProjectDetails details = bus.execute(user, new MoveProjectToTrash(projectId));
            return ResponseEntity.ok(ProjectResponse.from(details));
        });
    }

    @DeleteMapping("/{id}/trash")
    public ResponseEntity<?> restore(@PathVariable String id) {
        return withProject(id, (user, projectId) -> {
            ProjectDetails details = bus.execute(user, new RestoreProjectFromTrash(projectId));
            return ResponseEntity.ok(ProjectResponse.from(details));
        });
    }

    private ResponseEntity<?> withProject(String id, ProjectAction action) {
        UserId user = currentUser();
        if (user == null) {
            return ApiProblems.of(ApiProblems.authenticationRequired());
        }
        final ProjectId projectId;
        try {
            projectId = ProjectId.of(id);
        } catch (IllegalArgumentException ex) {
            return invalid("project id is not a UUID");
        }
        try {
            return action.run(user, projectId);
        } catch (IllegalArgumentException ex) {
            return invalid(ex.getMessage());
        }
    }

    /** {@code filename*} carries the Unicode name. The quoted filename stays ISO-8859-1 for Tomcat. */
    private static String contentDisposition(String fileName) {
        return ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8)
                .build()
                .toString();
    }

    private static ResponseEntity<ProblemDetail> invalid(String detail) {
        ProblemDetail problem = ApiProblems.badRequest(detail);
        problem.setProperty("code", ProblemDetailsAdvice.VALIDATION_FAILED);
        return ApiProblems.of(problem);
    }

    private static List<String> names(List<String> names) {
        if (names == null || names.isEmpty()) {
            throw new IllegalArgumentException("names must not be empty");
        }
        return List.copyOf(names);
    }

    private static Optional<String> language(String lang) {
        if (lang == null || lang.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(lang.strip());
    }

    private static Set<IRI> iris(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        Set<IRI> iris = new LinkedHashSet<>();
        for (String value : raw) {
            iris.add(entityIri(value));
        }
        return Set.copyOf(iris);
    }

    private static IRI entityIri(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("IRI is required");
        }
        final IRI iri;
        try {
            iri = IRI.create(raw.strip());
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("IRI is invalid");
        }
        if (!iri.isAbsolute()) {
            throw new IllegalArgumentException("IRI must be absolute");
        }
        return iri;
    }

    private static Optional<IRI> ontologyIri(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(IRI.create(raw.strip()));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("ontology IRI is invalid");
        }
    }

    private static UserId currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        try {
            return new UserId(authentication.getName());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    @FunctionalInterface
    private interface ProjectAction {
        ResponseEntity<?> run(UserId user, ProjectId projectId);
    }

    public record CreateProjectRequest(String name, String description, String ontologyIri) {}

    public record UpdateProjectRequest(String name, String description) {}

    public record CreateEntitiesRequest(String entityType, List<String> names, String lang, List<String> parents) {}

    public record DeleteEntitiesRequest(List<String> iris) {}

    public record RevisionResponse(long revision) {}
}
