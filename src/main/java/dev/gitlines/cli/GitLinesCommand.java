package dev.gitlines.cli;

import dev.gitlines.analysis.RepositoryAnalyzer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Parameters;

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

    @Override
    public Integer call() throws IOException {
        var result = new RepositoryAnalyzer().analyze(repository);
        var info = result.repository();
        System.out.println("Repository: " + info.name());
        System.out.println("Revision: " + (info.revision() == null ? "(no commits)" : info.revision()));
        System.out.println("Branch: " + (info.branch() == null ? "(detached HEAD)" : info.branch()));
        System.out.println("Shallow: " + info.shallow());
        System.out.println("Commits analyzed: " + result.summary().commits());
        for (var author : result.authors()) {
            System.out.printf("%s <%s>: %d %d %d %d%n", author.name(), author.email(),
                author.commits(), author.added(), author.deleted(), author.net());
        }
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
