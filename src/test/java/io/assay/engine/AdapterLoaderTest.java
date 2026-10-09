package io.assay.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.model.ProjectAdapter;
import io.assay.support.AtomicFiles;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The extension point: a domain project ships its adapter as a compiled artifact, and this is how the
 * framework finds it.
 *
 * <p>The jars are built here rather than checked in, so the test exercises the real path — compiling
 * against the framework, registering through {@code ServiceLoader}, and resolving the class inside the
 * jar's own class loader.
 */
class AdapterLoaderTest {

    private static final String SERVICE_ENTRY = "META-INF/services/io.assay.engine.ProjectAdapterFactory";

    /** Compiles one tiny factory per name and packages them into a jar with a service registration. */
    private static Path buildJar(Path root, List<String> classNames) {
        return buildJar(root, classNames, true);
    }

    private static Path buildJar(Path root, List<String> classNames, boolean register) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Assumptions.assumeTrue(compiler != null, "a JDK is required to compile the fixture adapter");

        Path sources = root.resolve("src");
        Path classes = root.resolve("classes");
        AtomicFiles.createDirectories(classes);
        List<String> files = new ArrayList<>();
        for (String className : classNames) {
            String packageName = className.substring(0, className.lastIndexOf('.'));
            String simpleName = className.substring(className.lastIndexOf('.') + 1);
            Path file = sources.resolve(className.replace('.', '/') + ".java");
            AtomicFiles.writeText(
                    file,
                    """
                    package %s;

                    import io.assay.engine.ProjectAdapterFactory;
                    import io.assay.model.ProjectAdapter;
                    import java.util.List;

                    public final class %s implements ProjectAdapterFactory {
                        @Override
                        public ProjectAdapter create() {
                            return new ProjectAdapter("%s", (evalCase, context) -> null, (handle, evalCase) -> null,
                                    List.of(), List.of());
                        }
                    }
                    """
                            .formatted(packageName, simpleName, simpleName));
            files.add(file.toString());
        }

        List<String> arguments = new ArrayList<>();
        arguments.add("-classpath");
        arguments.add(System.getProperty("java.class.path"));
        arguments.add("-d");
        arguments.add(classes.toString());
        arguments.addAll(files);
        assertEquals(0, compiler.run(null, null, null, arguments.toArray(new String[0])));

        Path jar = root.resolve("plugin.jar");
        try (OutputStream output = Files.newOutputStream(jar);
                JarOutputStream archive = new JarOutputStream(output)) {
            for (Path file : collect(classes)) {
                String entry = classes.relativize(file).toString().replace('\\', '/');
                archive.putNextEntry(new JarEntry(entry));
                archive.write(Files.readAllBytes(file));
                archive.closeEntry();
            }
            if (register) {
                archive.putNextEntry(new JarEntry(SERVICE_ENTRY));
                archive.write(String.join("\n", classNames).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                archive.closeEntry();
            }
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        return jar;
    }

    private static List<Path> collect(Path root) {
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    @Test
    void loadsASingleRegisteredAdapterFromAJar(@TempDir Path root) {
        Path jar = buildJar(root, List.of("plugina.OnlyAdapter"));

        ProjectAdapter adapter = AdapterLoader.loadProjectAdapter(jar.toString(), null);

        assertEquals("OnlyAdapter", adapter.name());
    }

    @Test
    void loadsTheNamedImplementationWhenAJarRegistersSeveral(@TempDir Path root) {
        Path jar = buildJar(root, List.of("plugina.FirstAdapter", "pluginb.SecondAdapter"));

        ProjectAdapter chosen = AdapterLoader.loadProjectAdapter(jar.toString(), "pluginb.SecondAdapter");

        assertEquals("SecondAdapter", chosen.name());
    }

    /** Guessing between two registrations would silently evaluate the wrong thing. */
    @Test
    void refusesToGuessBetweenSeveralRegistrations(@TempDir Path root) {
        Path jar = buildJar(root, List.of("plugina.FirstAdapter", "pluginb.SecondAdapter"));

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> AdapterLoader.loadProjectAdapter(jar.toString(), null));

        assertTrue(error.getMessage().contains("registers 2"), error.getMessage());
        assertTrue(error.getMessage().contains("--adapter-class"), error.getMessage());
    }

    @Test
    void explainsAnUnregisteredJar(@TempDir Path root) {
        Path jar = buildJar(root, List.of("plugina.LonelyAdapter"), false);

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> AdapterLoader.loadProjectAdapter(jar.toString(), null));

        assertTrue(error.getMessage().contains("registers no"), error.getMessage());
    }

    /** Naming the implementation has to work even when the jar forgot to register it. */
    @Test
    void loadsANamedImplementationFromAnUnregisteredJar(@TempDir Path root) {
        Path jar = buildJar(root, List.of("plugina.LonelyAdapter"), false);

        ProjectAdapter adapter = AdapterLoader.loadProjectAdapter(jar.toString(), "plugina.LonelyAdapter");

        assertEquals("LonelyAdapter", adapter.name());
    }

    @Test
    void loadsAnAdapterThatIsMerelyOnTheClasspath(@TempDir Path root) {
        ProjectAdapter adapter =
                AdapterLoader.loadProjectAdapter("io.assay.examples.EchoAdapter", null);

        assertEquals("echo", adapter.name());
    }

    @Test
    void rejectsReferencesThatCannotNameAnAdapter(@TempDir Path root) {
        IllegalArgumentException missing =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> AdapterLoader.loadProjectAdapter(root.resolve("absent.jar").toString(), null));
        assertTrue(missing.getMessage().contains("does not exist"), missing.getMessage());

        Path file = root.resolve("adapter.txt");
        AtomicFiles.writeText(file, "not a jar");
        IllegalArgumentException notAJar =
                assertThrows(IllegalArgumentException.class, () -> AdapterLoader.loadProjectAdapter(file.toString(), null));
        assertTrue(notAJar.getMessage().contains("compiled class or a jar"), notAJar.getMessage());

        IllegalArgumentException unknown =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> AdapterLoader.loadProjectAdapter("com.example.NotOnTheClasspath", null));
        assertTrue(unknown.getMessage().contains("cannot load adapter class"), unknown.getMessage());

        IllegalArgumentException blank = assertThrows(IllegalArgumentException.class, () -> AdapterLoader.loadProjectAdapter("  ", null));
        assertTrue(blank.getMessage().contains("must not be empty"), blank.getMessage());
    }

    /** A class that is not a factory is rejected rather than cast at a random point later. */
    @Test
    void rejectsAClassThatIsNotAFactory(@TempDir Path root) {
        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> AdapterLoader.loadProjectAdapter("java.lang.String", null));

        assertTrue(error.getMessage().contains("does not implement"), error.getMessage());
    }
}
