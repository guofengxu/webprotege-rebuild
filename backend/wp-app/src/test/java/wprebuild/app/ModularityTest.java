package wprebuild.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class ModularityTest {

    private static final Path MODULE_DIAGRAM = Path.of("target/spring-modulith-docs/components.puml");

    /** 与 docs/02-architecture/overview.md 第 2 节的允许依赖表一致。displayName 使用表中的模块名。 */
    private static final Map<String, ModuleDeclaration> EXPECTED = Map.ofEntries(
            Map.entry("shared", new ModuleDeclaration(true)),
            Map.entry("access", new ModuleDeclaration(false, "shared")),
            Map.entry("bus", new ModuleDeclaration(false, "access", "shared")),
            Map.entry("project", new ModuleDeclaration(false, "bus", "access", "shared")),
            Map.entry("revision", new ModuleDeclaration(false, "shared")),
            Map.entry("ontology", new ModuleDeclaration(false, "bus", "project", "access", "revision", "shared")),
            Map.entry("search", new ModuleDeclaration(false, "bus", "ontology", "access", "shared")),
            Map.entry("events", new ModuleDeclaration(false, "bus", "ontology", "access", "shared")),
            Map.entry("migration", new ModuleDeclaration(false, "bus", "ontology", "project", "access", "shared")),
            Map.entry(
                    "web",
                    new ModuleDeclaration(
                            false,
                            "shared",
                            "access",
                            "bus",
                            "project",
                            "revision",
                            "ontology",
                            "search",
                            "events",
                            "migration")));

    private static final ApplicationModules MODULES = ApplicationModules.of(WpApplication.class);

    @Test
    void verifies_declared_module_boundaries() {
        assertThat(MODULES.stream().map(module -> module.getIdentifier().toString()))
                .containsExactlyInAnyOrderElementsOf(EXPECTED.keySet());

        EXPECTED.forEach((name, declaration) -> {
            ApplicationModule module = MODULES.getModuleByName(name).orElseThrow();
            org.springframework.modulith.ApplicationModule declared = module.getBasePackage()
                    .getAnnotation(org.springframework.modulith.ApplicationModule.class)
                    .orElseThrow();
            // 省略 allowedDependencies 时注解默认值是开放标记，解析后的依赖流也是空的。
            // 必须核对注解数组本身，shared 的显式空列表才会和“允许依赖任何模块”区分开。
            assertThat(declared.displayName()).isEqualTo(name);
            assertThat(declared.allowedDependencies()).containsExactlyInAnyOrder(declaration.allowedDependencies());
            assertThat(declared.type())
                    .isEqualTo(
                            declaration.open()
                                    ? org.springframework.modulith.ApplicationModule.Type.OPEN
                                    : org.springframework.modulith.ApplicationModule.Type.CLOSED);
            assertThat(module.isOpen()).isEqualTo(declaration.open());
            assertThat(module.getAllowedDependencies(MODULES).stream().map(Object::toString))
                    .containsExactlyInAnyOrder(declaration.allowedDependencies());
        });

        MODULES.verify();
    }

    @Test
    void writes_module_diagram() throws Exception {
        // 骨架还没有代码级依赖。默认选项会藏起没有关系的模块，图上就只剩标题。
        new Documenter(MODULES, Documenter.Options.defaults().withOutputFolder("target/spring-modulith-docs"))
                .writeModulesAsPlantUml(Documenter.DiagramOptions.defaults()
                        .withElementsWithoutRelationships(
                                Documenter.DiagramOptions.ElementsWithoutRelationships.VISIBLE));

        assertThat(Files.readString(MODULE_DIAGRAM)).contains(EXPECTED.keySet().toArray(String[]::new));
    }

    private record ModuleDeclaration(boolean open, String... allowedDependencies) {}
}
