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

import java.io.StreamTokenizer;

/**
 * Thrown when a {@code module-info-patch.maven} file cannot be parsed.
 *
 * @author Martin Desruisseaux
 */
@SuppressWarnings("serial")
public class ModuleInfoPatchException extends CompilationFailureException {
    /**
     * Creates a new exception with the given message.
     *
     * @param message the short message
     */
    public ModuleInfoPatchException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with the given message followed by "at line" and the line number.
     * This is not in public API because the use of {@link StreamTokenizer} is an implementation
     * details that may change in any future version.
     *
     * @param message the short message
     * @param reader the reader used for parsing the file
     */
    ModuleInfoPatchException(String message, StreamTokenizer reader) {
        super(message + " at line " + reader.lineno());
    }
}
