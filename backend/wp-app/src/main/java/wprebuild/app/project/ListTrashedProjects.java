package wprebuild.app.project;

import java.util.List;
import wprebuild.app.bus.Command;

/** Trashed projects visible to the caller. A system administrator receives every trashed project. */
public record ListTrashedProjects() implements Command<List<ProjectDetails>> {}
