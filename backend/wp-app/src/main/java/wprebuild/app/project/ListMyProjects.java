package wprebuild.app.project;

import java.util.List;
import wprebuild.app.bus.Command;

/** Active projects visible to the caller. A system administrator receives every active project. */
public record ListMyProjects() implements Command<List<ProjectDetails>> {}
