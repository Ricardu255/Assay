package io.assay.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CommandLineTest {

    private static CommandLine.Spec spec() {
        return new CommandLine.Spec("demo", "demo command")
                .option("name", CommandLine.Kind.STRING, null)
                .option("workers", CommandLine.Kind.INTEGER, "2")
                .flag("verbose");
    }

    @Test
    void optionAfterOptionIsNotConsumedAsValue() {
        CommandLine.UsageException error = assertThrows(
                CommandLine.UsageException.class,
                () -> CommandLine.parse(spec(), new String[] {"--name", "--verbose"}));

        assertTrue(error.getMessage().contains("expected one argument"));
        assertEquals(2, error.exitCode());
    }

    @Test
    void rejectsDuplicateOptionDeclarations() {
        CommandLine.Spec spec = spec();

        assertThrows(IllegalArgumentException.class,
                () -> spec.option("name", CommandLine.Kind.STRING, null));
    }

    @Test
    void rejectsInvalidTypedDefaults() {
        CommandLine.Spec spec = new CommandLine.Spec("demo", "demo command");

        assertThrows(IllegalArgumentException.class,
                () -> spec.option("workers", CommandLine.Kind.INTEGER, "many"));
        assertThrows(IllegalArgumentException.class,
                () -> spec.option("ratio", CommandLine.Kind.DECIMAL, "NaN"));
        assertThrows(IllegalArgumentException.class,
                () -> spec.option("mode", CommandLine.Kind.STRING, "other", "smoke", "full"));
    }

    @Test
    void rejectsFlagDefaults() {
        CommandLine.Spec spec = new CommandLine.Spec("demo", "demo command");

        assertThrows(IllegalArgumentException.class,
                () -> spec.option("verbose", CommandLine.Kind.FLAG, "true"));
    }

    @Test
    void invalidPathBecomesUsageError() {
        CommandLine.Spec spec = new CommandLine.Spec("demo", "demo command")
                .option("output", CommandLine.Kind.STRING, null);
        CommandLine.Namespace args = CommandLine.parse(spec, new String[] {"--output", "\u0000"});

        CommandLine.UsageException error =
                assertThrows(CommandLine.UsageException.class, () -> args.path("output"));
        assertTrue(error.getMessage().contains("invalid path"));
        assertEquals(2, error.exitCode());
    }
}
