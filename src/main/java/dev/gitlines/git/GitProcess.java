package dev.gitlines.git;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns a read-only Git subprocess and kills it on premature close or JVM shutdown.
 * Stderr is kept separate from the machine protocol on stdout.
 */
final class GitProcess implements AutoCloseable {
    private final Process process;
    private final Thread shutdownHook;
    private Thread timeoutGuard;

    /**
     * Starts Git with deterministic output and no pager or optional index locks.
     * @param directory Git working directory
     * @param arguments individual Git arguments
     * @param diagnostics whether to forward Git stderr to the terminal
     * @throws IOException if Git cannot be started
     */
    GitProcess(Path directory, List<String> arguments, boolean diagnostics) throws IOException {
        var command = new ArrayList<String>();
        command.add("git");
        command.add("--no-pager");
        command.add("-C");
        command.add(directory.toString());
        command.addAll(arguments);
        var builder = new ProcessBuilder(command);
        builder.redirectError(diagnostics ? ProcessBuilder.Redirect.INHERIT : ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
        builder.environment().put("LC_ALL", "C");
        process = builder.start();
        shutdownHook = new Thread(process::destroyForcibly, "gitlines-git-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    /**
     * Bounds optional probes: packed deltas can otherwise inflate a whole large
     * object before returning its prefix. Expiry kills Git and causes fallback.
     * @param milliseconds maximum subprocess lifetime
     */
    void limitLifetime(long milliseconds) {
        timeoutGuard = Thread.ofPlatform().daemon().start(() -> {
            try {
                if (!process.waitFor(milliseconds, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException closed) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Exposes stdout exclusively to the metadata reader or history parser.
     * @return owned running process
     */
    Process process() {
        return process;
    }

    /**
     * Waits for Git while preserving interruption and preventing orphan processes.
     * @return Git exit status
     * @throws IOException if interrupted
     */
    int await() throws IOException {
        try {
            return process.waitFor();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Git analysis was interrupted", error);
        }
    }

    @Override
    public void close() {
        if (timeoutGuard != null) {
            timeoutGuard.interrupt();
        }
        if (process.isAlive()) {
            process.destroyForcibly();
        }
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException shutdownInProgress) {
            // The registered hook owns cleanup once shutdown has begun.
        }
    }
}
