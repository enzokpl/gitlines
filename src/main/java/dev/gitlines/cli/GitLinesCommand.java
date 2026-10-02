package dev.gitlines.cli;

import dev.gitlines.analysis.RepositoryAnalyzer;
import dev.gitlines.output.AtomicOutput;
import dev.gitlines.output.HtmlRenderer;
import dev.gitlines.output.JsonRenderer;
import dev.gitlines.output.TerminalRenderer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Exposes local repository analysis as a single command.
 */
@Command(name = "gitlines", mixinStandardHelpOptions = true,
    description = "Analyze historical line contributions by Git author.",
    versionProvider = GitLinesCommand.Version.class)
public final class GitLinesCommand implements Callable<Integer> {
    @Parameters(index = "0", arity = "0..1", defaultValue = ".", paramLabel = "repository",
        description = "Repository root or a directory inside it (default: current directory).")
    private Path repository;

    @Option(names = "--json", paramLabel = "<file>",
        description = "Write a UTF-8 JSON report (replaces regular files).")
    private Path json;

    @Option(names = "--html", paramLabel = "<file>",
        description = "Write a standalone HTML report (replaces regular files).")
    private Path html;

    @Option(names = "--workers", defaultValue = "2", paramLabel = "<1..4>",
        description = "Persistent Git diff workers (default: 2; 1 uses serial log).")
    private int workers;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() throws IOException {
        if (workers < 1 || workers > 4) {
            throw new ParameterException(spec.commandLine(), "workers must be between 1 and 4");
        }
        validateDestinations();
        var result = new RepositoryAnalyzer().analyze(repository, workers);
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
     * Rejects destinations that resolve to the same file before writing either report.
     * @throws IOException if file identity cannot be checked
     */
    private void validateDestinations() throws IOException {
        if (json == null || html == null) {
            return;
        }
        var jsonPath = json.toAbsolutePath().normalize();
        var htmlPath = html.toAbsolutePath().normalize();
        boolean samePath = jsonPath.equals(htmlPath);
        boolean sameFile = Files.exists(json) && Files.exists(html) && Files.isSameFile(json, html);
        if (samePath || sameFile) {
            throw new ParameterException(spec.commandLine(), "JSON and HTML destinations must be different");
        }
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
