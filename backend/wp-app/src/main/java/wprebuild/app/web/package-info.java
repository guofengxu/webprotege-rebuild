@org.springframework.modulith.ApplicationModule(
        displayName = "web",
        allowedDependencies = {
            "shared",
            "access",
            "bus",
            "project",
            "revision",
            "ontology",
            "form",
            "search",
            "events",
            "migration",
            "projection",
            "integration"
        })
package wprebuild.app.web;
