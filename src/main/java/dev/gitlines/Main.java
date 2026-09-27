package dev.gitlines;

import dev.gitlines.cli.GitLinesCommand;
import picocli.CommandLine;

/**
 * Starts the command and translates execution failures into concise diagnostics.
 */
public final class Main {
    /**
     * Runs the CLI with picocli's usage exit code and execution exit code 1.
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        var commandLine = new CommandLine(new GitLinesCommand());
        commandLine.setExecutionExceptionHandler((error, command, result) -> {
            command.getErr().println("error: " + error.getMessage());
            return 1;
        });
        System.exit(commandLine.execute(args));
    }
}
