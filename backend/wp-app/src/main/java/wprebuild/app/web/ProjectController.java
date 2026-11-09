package wprebuild.app.web;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.semanticweb.owlapi.model.IRI;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import wprebuild.app.bus.CommandBus;
import wprebuild.app.ontology.CreateEmptyProject;
import wprebuild.app.ontology.DownloadFormat;
import wprebuild.app.ontology.GetProjectDownload;
import wprebuild.app.ontology.ImportOntologyFromUpload;
import wprebuild.app.ontology.ProjectDownload;
import wprebuild.app.ontology.UploadProperties;
import wprebuild.app.project.GetProjectDetails;
import wprebuild.app.project.ListMyProjects;
import wprebuild.app.project.ListTrashedProjects;
import wprebuild.app.project.MoveProjectToTrash;
import wprebuild.app.project.ProjectDetails;
import wprebuild.app.project.ProjectText;
import wprebuild.app.project.RestoreProjectFromTrash;
import wprebuild.app.project.UpdateProjectDetails;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.id.UserId;

/** Project lifecycle. Permission and trash state are checked by {@link CommandBus}. */
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
}
