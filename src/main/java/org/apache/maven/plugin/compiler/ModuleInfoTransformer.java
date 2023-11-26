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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.plugin.logging.Log;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;

/**
 * {@code module-info.class} bytecode transformer for
 * <a href="https://issues.apache.org/jira/browse/MCOMPILER-542">MCOMPILER-542</a>:
 * drops detailed JDK version.
 */
final class ModuleInfoTransformer {

    private ModuleInfoTransformer() {}

    static byte[] transform(byte[] originalBytecode, String javaVersion, Log log) {
        List<String> modulesModified = new ArrayList<>();
        Set<String> foundVersions = new HashSet<>();
        ClassReader reader = new ClassReader(originalBytecode);
        ClassWriter writer = new ClassWriter(0);

        ClassVisitor classVisitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public ModuleVisitor visitModule(String name, int access, String version) {
                ModuleVisitor originalModuleVisitor = super.visitModule(name, access, version);
                return new ModuleVisitor(Opcodes.ASM9, originalModuleVisitor) {
                    @Override
                    public void visitRequire(String module, int access, String version) {
                        // Check if the module name matches the java/jdk modules
                        if (version != null && (module.startsWith("java.") || module.startsWith("jdk."))) {
                            // Patch the version from the java.* and jdk.* modules
                            // with the --release N version.
                            super.visitRequire(module, access, javaVersion);
                            foundVersions.add(version);
                            modulesModified.add(module);
                        } else {
                            // Keep the original require statement
                            super.visitRequire(module, access, version);
                        }
                    }
                };
            }
        };

        reader.accept(classVisitor, 0);

        if (modulesModified.isEmpty()) {
            return null;
        }

        log.info(String.format(
                "JDK-8318913 workaround: patched module-info.class requires version from %s to [%s] on %d JDK modules %s",
                foundVersions, javaVersion, modulesModified.size(), modulesModified));

        return writer.toByteArray();
    }
}
