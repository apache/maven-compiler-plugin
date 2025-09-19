/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugin.compiler;

import javax.lang.model.SourceVersion;
import javax.tools.DiagnosticListener;
import javax.tools.JavaFileObject;
import javax.tools.OptionChecker;
import javax.tools.StandardJavaFileManager;
import javax.tools.Tool;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Base class of tool executed by invoking a command-line tool.
 *
 * @author Martin Desruisseaux
 */
class ForkedTool implements Tool, OptionChecker {
    /**
     * The directory to run the compiler from, or {@code null} if none.
     */
    private final Path basedir;

    /**
     * The executable of the compiler to use.
     */
    private final String executable;

    /**
     * The file where to dump the command line, or {@code null} if none.
     */
    private final Path debugFilePath;

    /**
     * Creates a new forked compiler.
     *
     * @param mojo  the MOJO from which to get the configuration
     */
    ForkedTool(final AbstractCompilerMojo mojo) {
        basedir = mojo.basedir;
        executable = Objects.requireNonNull(mojo.executable);
        debugFilePath = mojo.getDebugFilePath();
    }

    /**
     * Returns the name of this tool.
     */
    @Override
    public String name() {
        return executable;
    }

    /**
     * Unconditionally returns -1, meaning that the given option is unsupported.
     * This implementation actually knows nothing about which options are supported.
     * Callers should ignore the return value.
     *
     * @param option ignored
     * @return -1
     */
    @Override
    public int isSupportedOption(String option) {
        return -1;
    }

    /**
     * Returns the source versions of the Java programming language supported by this tool.
     * This implementation arbitrarily returns the latest supported version of current JVM.
     * Actually, this method does not know the supported versions.
     */
    @Override
    public Set<SourceVersion> getSourceVersions() {
        return Set.of(SourceVersion.latestSupported());
    }

    /**
     * Returns a new instance of the object holding a collection of files to compile.
     */
    public StandardJavaFileManager getStandardFileManager(
            DiagnosticListener<? super JavaFileObject> diagnosticListener, Locale locale, Charset encoding) {
        return new ForkedToolSources(encoding);
    }

    /**
     * Creates a process builder without starting the process.
     * Callers can complete the builder configuration, then start the process.
     */
    private ProcessBuilder builder() {
        var builder = new ProcessBuilder(executable);
        if (basedir != null) {
            builder.directory(basedir.toFile());
        }
        return builder;
    }

    /**
     * Executes the command and waits for its completion.
     *
     * @param out where to send additional compiler output
     * @param fileManager the dependencies (JAR files)
     * @param options the tool options
     * @param compilationUnits the source files to process
     * @return whether the operation succeeded
     * @throws IOException if an I/O error occurred when starting the process
     */
    final boolean run(
            Writer out,
            ForkedToolSources fileManager,
            Iterable<String> options,
            Iterable<? extends JavaFileObject> compilationUnits)
            throws IOException {
        ProcessBuilder builder = builder();
        List<String> command = builder.command();
        for (String option : options) {
            command.add(option);
        }
        fileManager.addAllLocations(command);
        for (JavaFileObject source : compilationUnits) {
            Path path = fileManager.asPath(source);
            if (basedir != null) {
                try {
                    path = basedir.relativize(path);
                } catch (IllegalArgumentException e) {
                    // Ignore, keep the absolute path.
                }
            }
            command.add(path.toString());
        }
        File output = File.createTempFile("javac", null);
        try {
            var dest = ProcessBuilder.Redirect.appendTo(output);
            builder.redirectError(dest);
            builder.redirectOutput(dest);
            return start(builder, out) == 0;
        } finally {
            /*
             * Need to use the native encoding because it is the encoding used by the native process.
             * This is not necessarily the default encoding of the JVM, which is "file.encoding".
             * This property is available since Java 17.
             */
            String cs = System.getProperty("native.encoding");
            out.append(Files.readString(output.toPath(), Charset.forName(cs)));
            output.delete();
        }
    }

    /**
     * Runs the tool with the given arguments.
     * This method is implemented as a matter of principle but should not be invoked.
     */
    @Override
    public int run(InputStream in, OutputStream out, OutputStream err, String... arguments) {
        ProcessBuilder builder = builder();
        builder.command().addAll(Arrays.asList(arguments));
        try {
            return start(builder, System.err);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Starts the process and wait for its completion.
     * If a debug file has been specified, writes in that file the command which is about to be executed.
     *
     * @param builder builder of the process to start
     * @param out where to send additional compiler output
     */
    private int start(ProcessBuilder builder, Appendable out) throws IOException {
        if (debugFilePath != null) {
            // Use the path separator as a way to identify the operating system.
            final boolean windows = File.separatorChar == '\\';
            String filename = debugFilePath.getFileName().toString();
            filename = filename.substring(0, filename.lastIndexOf('.') + 1);
            filename += windows ? "bat" : "sh";
            boolean more = false;
            try (BufferedWriter debugFile = Files.newBufferedWriter(debugFilePath.resolveSibling(filename))) {
                if (basedir != null) {
                    debugFile.write(windows ? "chdir " : "cd ");
                    debugFile.write(basedir.toString());
                    debugFile.newLine();
                }
                for (String cmd : builder.command()) {
                    if (more) {
                        debugFile.append(' ');
                    }
                    debugFile.append(cmd);
                    more = true;
                }
                debugFile.newLine();
            }
        }
        Process process = builder.start();
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            out.append("Compilation has been interrupted by " + e).append(System.lineSeparator());
            process.destroy();
            return 1;
        }
    }
}
