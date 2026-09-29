package dev.gitlines.cli;

import dev.gitlines.analysis.RepositoryAnalyzer;
import dev.gitlines.output.TerminalRenderer;
import dev.gitlines.output.AtomicOutput;
import dev.gitlines.output.JsonRenderer;
import dev.gitlines.output.HtmlRenderer;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import picocli.CommandLine.ParameterException;
import java.util.Properties;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

/**
 * Exposes local repository analysis as a single command.
 */
@Command(name = "gitlines", mixinStandardHelpOptions = true,
    description = "Analyze historical line contributions by Git author.",
    versionProvider = GitLinesCommand.Version.class)
public final class GitLinesCommand implements Callable<Integer> {
    @Parameters(index = "0", arity = "0..1", defaultValue = ".", paramLabel = "[repository]",
        description = "Repository root or a directory inside it (default: current directory).")
    private Path repository;

    @Option(names = "--json", paramLabel = "<file>", description = "Write a UTF-8 JSON report (replaces regular files).")
    private Path json;

    @Option(names = "--html", paramLabel = "<file>", description = "Write a standalone HTML report (replaces regular files).")
    private Path html;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() throws IOException {
        if (json != null && html != null && (json.toAbsolutePath().normalize().equals(html.toAbsolutePath().normalize())
            || (Files.exists(json) && Files.exists(html) && Files.isSameFile(json, html)))) {
            throw new ParameterException(spec.commandLine(), "JSON and HTML destinations must be different");
        }
        var result = new RepositoryAnalyzer().analyze(repository);
        if (json != null) {
            AtomicOutput.write(json, output -> new JsonRenderer().render(result, output));
        }
        if (html != null) {
            AtomicOutput.write(html, output -> new HtmlRenderer().render(result, output));
        }
        new TerminalRenderer().render(result, spec.commandLine().getOut());
        return 0;
    }

    /**
     * Reads the Maven-filtered version included in both JVM and native artifacts.
     */
    public static final class Version implements IVersionProvider {
        @Override
        public String[] getVersion() throws IOException {
            var properties = new Properties();
            try (var input = Version.class.getResourceAsStream("/version.properties")) {
                if (input == null) {
                    throw new IOException("application version resource is missing");
                }
                properties.load(input);
            }
            return new String[] {"gitlines " + properties.getProperty("version")};
        }
    }
}
