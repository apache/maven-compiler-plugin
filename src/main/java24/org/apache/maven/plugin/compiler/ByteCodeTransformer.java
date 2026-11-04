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

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModuleRequireInfo;
import java.util.ArrayList;
import java.util.HashSet;

import org.apache.maven.api.plugin.Log;

/**
 * JDK 24+ override of the root {@code ByteCodeTransformer} using the standard
 * {@code java.lang.classfile} API instead of ASM, removing the ASM dependency
 * from the bytecode transformation path on modern JVMs.
 *
 * <p>This class is loaded automatically by the JVM on JDK 24+ via the
 * multi-release JAR mechanism ({@code META-INF/versions/24/}). On JDK 17–23,
 * the root implementation backed by ASM is used instead.
 *
 * @see <a href="https://bugs.openjdk.org/browse/JDK-8318913">JDK-8318913</a>
 */
final class ByteCodeTransformer {
    private ByteCodeTransformer() {}

    /**
     * JDK-8318913 workaround: Patch module-info.class to set the java release version for java/jdk modules.
     * This patch is needed only for Java versions older than 22.
     *
     * <p>This implementation uses {@code java.lang.classfile} (JDK 24+ standard API) instead of ASM.
     *
     * @param originalBytecode the byte code to patch
     * @return the patched byte code, or {@code null} if no change is needed
     *
     * @see <a href="https://issues.apache.org/jira/browse/MCOMPILER-542">MCOMPILER-542</a>
     * @see <a href="https://bugs.openjdk.org/browse/JDK-8318913">JDK-8318913</a>
     */
    static byte[] patchJdkModuleVersion(byte[] originalBytecode, String javaVersion, Log log) {
        var cf = ClassFile.of();
        var classModel = cf.parse(originalBytecode);

        var moduleAttrOpt = classModel.findAttribute(Attributes.module());
        if (moduleAttrOpt.isEmpty()) {
            return null;
        }
        var orig = moduleAttrOpt.get();

        // Collect which requires entries need patching
        var modulesModified = new ArrayList<String>();
        var foundVersions = new HashSet<String>();
        for (ModuleRequireInfo req : orig.requires()) {
            String modName = req.requires().name().stringValue();
            if (req.requiresVersion().isPresent() && (modName.startsWith("java.") || modName.startsWith("jdk."))) {
                foundVersions.add(req.requiresVersion().get().stringValue());
                modulesModified.add(modName);
            }
        }
        if (modulesModified.isEmpty()) {
            return null;
        }

        // Rebuild the class, replacing the ModuleAttribute with patched requires versions
        byte[] result = cf.transformClass(classModel, (builder, element) -> {
            if (element instanceof ModuleAttribute moduleAttr) {
                // Build a new ModuleAttribute using the builder API, preserving all fields
                // except the requires versions for java.* / jdk.* modules.
                var newAttr = ModuleAttribute.of(orig.moduleName(), b -> {
                    b.moduleFlags(orig.moduleFlagsMask());
                    orig.moduleVersion().ifPresent(v -> b.moduleVersion(v.stringValue()));
                    for (ModuleRequireInfo req : moduleAttr.requires()) {
                        String modName = req.requires().name().stringValue();
                        if (req.requiresVersion().isPresent()
                                && (modName.startsWith("java.") || modName.startsWith("jdk."))) {
                            b.requires(req.requires().asSymbol(), req.requiresFlagsMask(), javaVersion);
                        } else {
                            b.requires(req);
                        }
                    }
                    orig.exports().forEach(b::exports);
                    orig.opens().forEach(b::opens);
                    orig.uses().forEach(b::uses);
                    orig.provides().forEach(b::provides);
                });
                builder.with(newAttr);
            } else {
                builder.with(element);
            }
        });

        log.info(String.format(
                "JDK-8318913 workaround: patched module-info.class requires version from %s to [%s] on %d JDK modules %s",
                foundVersions, javaVersion, modulesModified.size(), modulesModified));
        return result;
    }
}
