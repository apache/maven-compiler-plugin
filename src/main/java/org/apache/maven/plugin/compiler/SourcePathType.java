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

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import java.util.StringJoiner;

import org.apache.maven.api.PathType;

/**
 * Key for declaring source files as an implementation convenience.
 * This type is not declared in {@link JavaPathType} because it is not about dependencies.
 *
 * @author Martin Desruisseaux
 */
final class SourcePathType implements PathType {
    /**
     * The singleton instance for non-modular source paths.
     */
    private static final SourcePathType SOURCE_PATH = new SourcePathType(null);

    /**
     * The name of the module, or {@code null} if none.
     */
    private final String moduleName;

    /**
     * Creates a new path type for the given module.
     *
     * @param moduleName the name of the module, or {@code null} if none
     */
    private SourcePathType(String moduleName) {
        this.moduleName = moduleName;
    }

    /**
     * Returns the source path type for the given module name.
     *
     * @param moduleName the name of the module, or {@code null} if none
     * @return the source path type
     */
    static SourcePathType valueOf(String moduleName) {
        return (moduleName == null || moduleName.isBlank()) ? SOURCE_PATH : new SourcePathType(moduleName);
    }

    /**
     * Returns the unique name of this path type, including the module to patch if any.
     *
     * @return the name of this path type, including module name
     */
    @Override
    public String id() {
        String id = name();
        if (moduleName != null) {
            id = id + ':' + moduleName;
        }
        return id;
    }

    /**
     * Returns the programmatic name of this path type, without module name.
     *
     * @return the programmatic name of this path type
     */
    @Override
    public String name() {
        return (moduleName == null) ? "SOURCE_PATH" : "MODULE_SOURCE_PATH";
    }

    /**
     * Returns the name of the tool option for this path.
     * It does not include the module name.
     */
    @Override
    public Optional<String> option() {
        return Optional.of((moduleName == null) ? "--source-path" : "--module-source-path");
    }

    /**
     * {@return the option followed by a string representation of the given path elements}.
     *
     * @param paths the path elements to format
     */
    @Override
    public String[] option(Iterable<? extends Path> paths) {
        var joiner = new StringJoiner(File.pathSeparator, (moduleName != null) ? moduleName + "=\"" : "\"", "\"");
        paths.forEach((path) -> joiner.add(path.toString()));
        return new String[] {option().get(), joiner.toString()};
    }

    /**
     * {@return a string representation for debugging purposes}.
     */
    @Override
    public String toString() {
        return getClass().getSimpleName() + '[' + id() + ']';
    }
}
