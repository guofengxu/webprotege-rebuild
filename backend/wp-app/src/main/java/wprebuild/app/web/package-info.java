@org.springframework.modulith.ApplicationModule(
        displayName = "web",
        allowedDependencies = {
            "shared",
            "access",
            "bus",
            "project",
            "revision",
            "ontology",
            "search",
            "events",
            "migration",
            "projection"
        })
package wprebuild.app.web;
