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

import org.apache.maven.api.plugin.Log;

/**
 * JDK 22+ override of the root {@code ByteCodeTransformer}.
 * Since there is nothing to patch for version 22 and older, this method does nothing.
 *
 * <p>This class is loaded automatically by the JVM on JDK 22+ via
 * the multi-release JAR mechanism ({@code META-INF/versions/22/}).
 * On JDK 17–21, the root implementation backed by ASM is used instead.</p>
 *
 * @see <a href="https://bugs.openjdk.org/browse/JDK-8318913">JDK-8318913</a>
 */
final class ByteCodeTransformer {
    private ByteCodeTransformer() {}

    /**
     * Does nothing since JDK-8318913 is fixed in JDK 22+.
     *
     * @param originalBytecode the byte code to patch
     * @return {@code null} since no change is needed
     */
    static byte[] patchJdkModuleVersion(byte[] originalBytecode, String javaVersion, Log log) {
        return null;
    }
}
