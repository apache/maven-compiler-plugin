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

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * A single source file, associated with the root directory from which it belong.
 * This class contains also the output file, because this information is needed
 * for determining whether a source file need to be recompiled.
 *
 * @author Martin Desruisseaux
 */
final class SourceFile {
    /**
     * The root directory which was walked for obtaining this file.
     */
    final SourceDirectory directory;

    /**
     * The source file found by walking under the directory.
     * This path is already resolved relative to {@link SourceDirectory#root}.
     */
    final Path file;

    /**
     * The time this file object was last modified, in milliseconds since January 1, 1970.
     */
    final long lastModified;

    /**
     * Whether this source has been flagged as new or modified since the last build.
     *
     * @see IncrementalBuildHelper#inputFileTreeChanges
     */
    boolean isNewOrModified;

    /**
     * Whether to ignore this file for incremental build calculation.
     * This flag is set to {@code true} if this file matches a filter
     * specified by {@link AbstractCompilerMojo#getIncrementalExcludes()}.
     *
     * <p>Note that a value of {@code true} should not prevent the {@link #isNewOrModified} flag to be
     * set to {@code true} if a modification is detected, because we want this file to be included in
     * compilation unit if a compilation is decided for another reason than a change of this file.</p>
     *
     * @see AbstractCompilerMojo#getIncrementalExcludes()
     */
    final boolean ignoreModification;

    /**
     * The path of the {@code .class} file, created when first requested.
     *
     * @see #getOutputFile()
     */
    private Path outputFile;

    /**
     * Creates a new source file.
     *
     * @param directory the root directory where the file come from
     * @param file a source file found by walking under the directory
     * @param attrs the source file attributes
     * @param ignoreModification whether to ignore this file for incremental build calculation
     */
    SourceFile(SourceDirectory directory, Path file, BasicFileAttributes attrs, boolean ignoreModification) {
        this.directory = directory;
        this.file = file;
        this.lastModified = attrs.lastModifiedTime().toMillis();
        this.ignoreModification = ignoreModification;
        directory.visit(file);
    }

    /**
     * {@return whether the output file is the same as the one that we would infer from heuristic rules}.
     *
     * <p>TODO: this is not yet implemented. We need to clarify how to get the output file information
     * from the compiler, maybe via the {@link javax.tools.JavaFileManager#getFileForOutput} method.
     * Then, {@link #getOutputFile} should compare that value with the inferred one and set a flag.</p>
     */
    boolean isStandardOutputFile() {
        // The constants below must match the ones in `IncrementalBuild.SourceInfo`.
        return SourceDirectory.JAVA_FILE_SUFFIX.equals(directory.fileKind.extension)
                && SourceDirectory.CLASS_FILE_SUFFIX.equals(directory.outputFileKind.extension);
    }

    /**
     * Returns the file resulting from the compilation of this source file. If the output file has been
     * {@linkplain javax.tools.JavaFileManager#getFileForOutput obtained from the compiler}, that value
     * if returned. Otherwise, output file is inferred using {@linkplain #toOutputFile heuristic rules}.
     *
     * @return path to the output file
     */
    Path getOutputFile() {
        if (outputFile == null) {
            outputFile = toOutputFile(
                    directory.root,
                    directory.getOutputDirectory(),
                    file,
                    directory.fileKind.extension,
                    directory.outputFileKind.extension);
            /*
             * TODO: compare with the file given by the compiler (if we can get that information)
             * and set a `isStandardOutputFile` flag with the comparison result.
             */
        }
        return outputFile;
    }

    /**
     * Infers the path to the output file using heuristic rules.
     * If the extension of the file is the one of {@linkplain SourceDirectory#fileKind source file kind}
     * (usually {@code ".java"}), then it is replaced by the extension specified in {@code outputFileKind}.
     * Otherwise the extension is left unmodified. Then, the path is made relative to the output directory.
     *
     * @param sourceDirectory root directory of the source file
     * @param outputDirectory output directory of the compiled file
     * @param file path to the source file
     * @param extension expected extension of the source file, leading dot included
     * @param outext extension of the output file, leading dot included
     * @return path to the target file
     */
    static Path toOutputFile(Path sourceDirectory, Path outputDirectory, Path file, String extension, String outext) {
        Path output = sourceDirectory.relativize(file);
        String filename = file.getFileName().toString();
        if (filename.endsWith(extension)) {
            filename = filename.substring(0, filename.length() - extension.length());
            filename = filename.concat(outext);
            output = output.resolveSibling(filename);
        }
        return outputDirectory.resolve(output);
    }

    /**
     * Compares the given object with this source file for equality.
     * This method compares only the file path. Metadata such as last modification time are ignored.
     *
     * @param obj the object to compare
     * @return whether the two objects have the same path and attributes
     */
    @Override
    public boolean equals(Object obj) {
        if (obj instanceof SourceFile other) {
            return file.equals(other.file) && directory.equals(other.directory);
        }
        return false;
    }

    /**
     * {@return a hash code value for this file}.
     */
    @Override
    public int hashCode() {
        return directory.hashCode() + 7 * file.hashCode();
    }

    /**
     * {@return a string representation of this source file for debugging purposes}.
     * This string representation is shown in Maven output if debug logs are enabled.
     */
    @Override
    public String toString() {
        return file.toString();
    }
}
