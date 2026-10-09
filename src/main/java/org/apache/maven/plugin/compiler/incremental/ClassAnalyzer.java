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
package org.apache.maven.plugin.compiler.incremental;

/**
 * Abstract base for analyzing compiled {@code .class} files.
 *
 * <p>The single built-in implementation is:
 * <ul>
 *   <li>{@code ClassfileClassAnalyzer} — uses the standard
 *       {@code java.lang.classfile} API introduced in JDK 24; loaded
 *       reflectively at runtime. Requires JDK 24 or later.</li>
 * </ul>
 *
 * <p>Use {@link BytecodeAnalyzer#analyze(byte[])} which selects the best
 * available implementation automatically.
 *
 * @see BytecodeAnalyzer
 */
public abstract class ClassAnalyzer {

    // JVM spec access flag constants — shared by both implementations
    protected static final int ACC_PUBLIC = 0x0001;
    protected static final int ACC_PRIVATE = 0x0002;
    protected static final int ACC_PROTECTED = 0x0004;
    protected static final int ACC_STATIC = 0x0008;
    protected static final int ACC_FINAL = 0x0010;
    protected static final int ACC_INTERFACE = 0x0200;
    protected static final int ACC_ABSTRACT = 0x0400;
    protected static final int ACC_SYNTHETIC = 0x1000;
    protected static final int ACC_ENUM = 0x4000;

    /**
     * Resolves a JVM internal name (possibly an array descriptor) to a
     * fully-qualified Java name. Returns {@code null} for primitives and
     * empty/null inputs.
     *
     * @param internalName slash-separated internal name, possibly with array
     *                     prefix ({@code [}) or object wrapper ({@code L...;})
     * @return dot-separated Java name, or {@code null} if primitive/invalid
     */
    protected static String resolveInternalName(String internalName) {
        if (internalName == null || internalName.isEmpty()) {
            return null;
        }
        String name = internalName;
        while (name.startsWith("[")) {
            name = name.substring(1);
        }
        if (name.startsWith("L") && name.endsWith(";")) {
            name = name.substring(1, name.length() - 1);
        }
        if (name.isEmpty() || name.length() == 1) {
            return null;
        }
        return BytecodeAnalyzer.toJavaName(name);
    }

    protected static boolean isPrivateOrSynthetic(int access) {
        return (access & ACC_PRIVATE) != 0 || (access & ACC_SYNTHETIC) != 0;
    }
}
