package io.assay.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A small option parser for the CLI surface.
 *
 * <p>It covers what the framework's contract needs — long options, {@code --opt value} and
 * {@code --opt=value}, typed defaults, choice validation, required options, and exit code 2 for a usage
 * error — without pulling in a CLI library, because the framework core is dependency-free.
 */
public final class CommandLine {

    private CommandLine() {
    }

    public enum Kind {
        STRING,
        INTEGER,
        DECIMAL,
        FLAG
    }

    /** Declares one subcommand and the options it accepts. */
    public static final class Spec {

        private final String name;
        private final String help;
        private final Map<String, Kind> kinds = new LinkedHashMap<>();
        private final Map<String, String> defaults = new LinkedHashMap<>();
        private final Map<String, List<String>> choices = new LinkedHashMap<>();
        private final Set<String> required = new LinkedHashSet<>();

        public Spec(String name, String help) {
            this.name = name;
            this.help = help;
        }

        public String name() {
            return name;
        }

        public String help() {
            return help;
        }

        public Spec option(String optionName, Kind kind, String defaultValue, String... allowed) {
            kinds.put(optionName, kind);
            if (defaultValue != null) {
                defaults.put(optionName, defaultValue);
            }
            if (allowed.length > 0) {
                choices.put(optionName, List.of(allowed));
            }
            return this;
        }

        public Spec required(String optionName, Kind kind, String... allowed) {
            option(optionName, kind, null, allowed);
            required.add(optionName);
            return this;
        }

        public Spec flag(String optionName) {
            kinds.put(optionName, Kind.FLAG);
            return this;
        }
    }

    /** Parsed values for one command. */
    public static final class Namespace {

        private final Spec spec;
        private final Map<String, String> values = new LinkedHashMap<>();
        private final Set<String> seen = new LinkedHashSet<>();

        private Namespace(Spec spec) {
            this.spec = spec;
        }

        public boolean provided(String name) {
            return seen.contains(name);
        }

        public String string(String name) {
            return values.containsKey(name) ? values.get(name) : spec.defaults.get(name);
        }

        public Path path(String name) {
            String value = string(name);
            return value == null || value.isEmpty() ? null : Path.of(value);
        }

        public int integer(String name) {
            String value = string(name);
            return value == null ? 0 : Integer.parseInt(value);
        }

        public Integer integerOrNull(String name) {
            String value = string(name);
            return value == null || value.isEmpty() ? null : Integer.parseInt(value);
        }

        public double decimal(String name) {
            String value = string(name);
            return value == null ? 0 : Double.parseDouble(value);
        }

        public Double decimalOrNull(String name) {
            String value = string(name);
            return value == null || value.isEmpty() ? null : Double.parseDouble(value);
        }

        public boolean flag(String name) {
            return "true".equals(string(name));
        }
    }

    /** Raised for a usage error; carries the exit code the process should report. */
    public static final class UsageException extends RuntimeException {

        private final int exitCode;
        private final boolean showUsage;

        public UsageException(String message, int exitCode, boolean showUsage) {
            super(message);
            this.exitCode = exitCode;
            this.showUsage = showUsage;
        }

        public int exitCode() {
            return exitCode;
        }

        public boolean showUsage() {
            return showUsage;
        }
    }

    public static Namespace parse(Spec spec, String[] argv) {
        Namespace namespace = new Namespace(spec);
        for (int index = 0; index < argv.length; index++) {
            String argument = argv[index];
            if (!argument.startsWith("--")) {
                throw new UsageException("unrecognized arguments: " + argument, 2, true);
            }
            String name = argument.substring(2);
            String inline = null;
            int equals = name.indexOf('=');
            if (equals >= 0) {
                inline = name.substring(equals + 1);
                name = name.substring(0, equals);
            }
            String optionName = name;
            if (!spec.kinds.containsKey(optionName)) {
                throw new UsageException("unrecognized arguments: " + argument, 2, true);
            }
            Kind kind = spec.kinds.get(optionName);
            if (kind == Kind.FLAG) {
                if (inline != null) {
                    throw new UsageException(
                            "argument --" + name + ": ignored explicit argument '" + inline + "'", 2, true);
                }
                namespace.values.put(optionName, "true");
                namespace.seen.add(optionName);
                continue;
            }
            String value = inline;
            if (value == null) {
                if (index + 1 >= argv.length) {
                    throw new UsageException("argument --" + name + ": expected one argument", 2, true);
                }
                value = argv[++index];
            }
            List<String> allowed = spec.choices.get(optionName);
            if (allowed != null && !allowed.contains(value)) {
                throw new UsageException(
                        "argument --" + name + ": invalid choice: '" + value + "' (choose from "
                                + String.join(", ", allowed) + ")",
                        2,
                        true);
            }
            if (kind == Kind.INTEGER) {
                requireParsable(name, value, true);
            } else if (kind == Kind.DECIMAL) {
                requireParsable(name, value, false);
            }
            namespace.values.put(optionName, value);
            namespace.seen.add(optionName);
        }
        List<String> missing = new ArrayList<>();
        for (String optionName : spec.required) {
            if (!namespace.seen.contains(optionName)) {
                missing.add("--" + optionName);
            }
        }
        if (!missing.isEmpty()) {
            throw new UsageException(
                    "the following arguments are required: " + String.join(", ", missing), 2, true);
        }
        return namespace;
    }

    private static void requireParsable(String name, String value, boolean integer) {
        try {
            if (integer) {
                Integer.parseInt(value);
            } else {
                Double.parseDouble(value);
            }
        } catch (NumberFormatException error) {
            throw new UsageException("argument --" + name + ": invalid value: '" + value + "'", 2, true);
        }
    }

    /** The top-level usage banner, listing the available subcommands. */
    public static String usage(String program, List<Spec> commands) {
        StringBuilder builder = new StringBuilder();
        builder.append("usage: ").append(program).append(" <command> [options]\n\n");
        builder.append("commands:\n");
        for (Spec spec : commands) {
            builder.append("  ").append(spec.name()).append("  ").append(spec.help()).append('\n');
        }
        return builder.toString();
    }
}
