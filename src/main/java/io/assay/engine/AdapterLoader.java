package io.assay.engine;

import io.assay.model.AutoEvolutionAdapter;
import io.assay.model.ProjectAdapter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Resolves a domain adapter from a command-line reference.
 *
 * <p>Three forms are accepted:
 * <ol>
 *   <li>a {@code .jar} containing a {@code META-INF/services} registration;
 *   <li>a fully-qualified class name on the current classpath;
 *   <li>a {@code .jar} plus {@code --adapter-class} naming the factory inside it.
 * </ol>
 *
 * <p>A domain adapter is a compiled artifact, so this loader resolves a class or a jar rather than a
 * script. Everything downstream depends only on the {@link ProjectAdapter} it returns.
 */
public final class AdapterLoader {

    private AdapterLoader() {
    }

    /** Loads a {@link ProjectAdapterFactory} from a jar or from a class name. */
    public static ProjectAdapter loadProjectAdapter(String reference, String adapterClass) {
        ProjectAdapterFactory factory = loadFactory(reference, adapterClass, ProjectAdapterFactory.class);
        ProjectAdapter adapter = factory.create();
        if (adapter == null) {
            throw new IllegalArgumentException(reference + " returned a null ProjectAdapter");
        }
        return adapter;
    }

    /** Loads an {@link AutoEvolutionAdapterFactory} from a jar or from a class name. */
    public static AutoEvolutionAdapter loadAutoEvolutionAdapter(String reference, String adapterClass) {
        AutoEvolutionAdapterFactory factory =
                loadFactory(reference, adapterClass, AutoEvolutionAdapterFactory.class);
        AutoEvolutionAdapter adapter = factory.create();
        if (adapter == null) {
            throw new IllegalArgumentException(reference + " returned a null AutoEvolutionAdapter");
        }
        return adapter;
    }

    private static <T> T loadFactory(String reference, String adapterClass, Class<T> factoryType) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("adapter reference must not be empty");
        }
        Path path = Path.of(reference);
        if (reference.endsWith(".jar")) {
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException("adapter jar does not exist: " + path);
            }
            return loadFromJar(path, adapterClass, factoryType);
        }
        if (Files.isRegularFile(path) && !reference.endsWith(".jar")) {
            throw new IllegalArgumentException(
                    "a domain adapter is a compiled class or a jar, but " + reference + " is a file; "
                            + "pass a class name, or a jar registering a ProjectAdapterFactory");
        }
        return instantiate(reference, factoryType);
    }

    private static <T> T loadFromJar(Path jar, String adapterClass, Class<T> factoryType) {
        Map<String, byte[]> entries = readEntries(jar);
        InMemoryLoader loader = new InMemoryLoader(entries, AdapterLoader.class.getClassLoader());

        List<String> names;
        if (adapterClass != null && !adapterClass.isBlank()) {
            names = List.of(adapterClass);
        } else {
            names = readServiceNames(entries, factoryType);
        }
        if (names.isEmpty()) {
            throw new IllegalArgumentException(
                    jar + " registers no " + factoryType.getSimpleName()
                            + "; add a META-INF/services entry or pass --adapter-class");
        }
        if (names.size() > 1) {
            throw new IllegalArgumentException(
                    jar + " registers " + names.size() + " " + factoryType.getSimpleName()
                            + " implementations; pass --adapter-class to disambiguate");
        }
        return instantiate(names.get(0), factoryType, loader);
    }

    /** Reads every entry of the jar, so the adapter can be loaded without keeping the file open. */
    private static Map<String, byte[]> readEntries(Path jar) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (JarFile archive = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> items = archive.entries();
            while (items.hasMoreElements()) {
                JarEntry entry = items.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                try (InputStream stream = archive.getInputStream(entry)) {
                    entries.put(entry.getName(), stream.readAllBytes());
                }
            }
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        return entries;
    }

    /** Reads the standard {@code META-INF/services} registration, comments and blanks ignored. */
    private static List<String> readServiceNames(Map<String, byte[]> entries, Class<?> factoryType) {
        byte[] registration = entries.get("META-INF/services/" + factoryType.getName());
        if (registration == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String line : new String(registration, StandardCharsets.UTF_8).split("\r?\n")) {
            int comment = line.indexOf('#');
            String name = (comment >= 0 ? line.substring(0, comment) : line).strip();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Defines the adapter's own classes straight from the jar's bytes.
     *
     * <p>A {@code URLClassLoader} would hold the jar open for as long as the process lives, which on
     * Windows stops the operator from rebuilding or replacing their adapter between runs.
     */
    private static final class InMemoryLoader extends ClassLoader {

        private final Map<String, byte[]> entries;

        private InMemoryLoader(Map<String, byte[]> entries, ClassLoader parent) {
            super(parent);
            this.entries = entries;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = entries.get(name.replace('.', '/') + ".class");
            if (bytes == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static <T> T instantiate(String className, Class<T> factoryType) {
        return instantiate(className, factoryType, AdapterLoader.class.getClassLoader());
    }

    private static <T> T instantiate(String className, Class<T> factoryType, ClassLoader loader) {
        try {
            Class<?> type = Class.forName(className, true, loader);
            if (!factoryType.isAssignableFrom(type)) {
                throw new IllegalArgumentException(
                        className + " does not implement " + factoryType.getName());
            }
            return factoryType.cast(type.getDeclaredConstructor().newInstance());
        } catch (ReflectiveOperationException error) {
            throw new IllegalArgumentException(
                    "cannot load adapter class " + className + ": " + error, error);
        }
    }
}
