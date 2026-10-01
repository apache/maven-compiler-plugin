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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.maven.shared.utils.StringUtils;
import org.codehaus.plexus.languages.java.jpms.JavaModuleDescriptor;
import org.codehaus.plexus.languages.java.version.JavaVersion;

/**
 * Puts multi-release output directories of other modules on the module path through their versioned descriptor.
 * Such a directory, typically a reactor sibling that has not been packaged yet, has its {@code module-info.class}
 * only under {@code META-INF/versions/<N>}, while javac reads an exploded module only from its root. The versioned
 * directory therefore replaces it on the module path, and the directory itself patches the module.
 */
final class MultiReleaseModulePath {
    private MultiReleaseModulePath() {}

    /**
     * @param modulepathElements the resolved module path
     * @param pathElements the module descriptors of the path elements
     * @param excludedModules modules that the execution already patches
     * @param release the {@code release} of the execution, may be empty
     * @param target the {@code target} of the execution, may be empty
     * @param compilerArgs receives a {@code --patch-module} option for each rewritten element
     * @return the module path to pass to the compiler
     */
    static List<String> patch(
            Collection<String> modulepathElements,
            Map<String, JavaModuleDescriptor> pathElements,
            Collection<String> excludedModules,
            String release,
            String target,
            List<String> compilerArgs) {
        int javaVersion = javaVersion(release, target);

        List<String> result = new ArrayList<>(modulepathElements.size());
        for (String element : modulepathElements) {
            JavaModuleDescriptor descriptor = pathElements.get(element);
            File versionedDirectory = descriptor == null || excludedModules.contains(descriptor.name())
                    ? null
                    : findVersionedModuleDirectory(new File(element), javaVersion);
            if (versionedDirectory == null) {
                result.add(element);
            } else {
                result.add(versionedDirectory.getPath());
                compilerArgs.add("--patch-module");
                compilerArgs.add(descriptor.name() + '=' + element);
            }
        }
        return result;
    }

    /**
     * Returns the {@code META-INF/versions/<N>} directory holding the module descriptor of a multi-release output
     * directory, or {@code null} if the directory has a descriptor at its root or none at all.
     */
    static File findVersionedModuleDirectory(File directory, int javaVersion) {
        if (!directory.isDirectory() || new File(directory, "module-info.class").exists()) {
            return null;
        }
        for (int version = javaVersion; version >= 9; version--) {
            File versionedDirectory = new File(directory, "META-INF/versions/" + version);
            if (new File(versionedDirectory, "module-info.class").isFile()) {
                return versionedDirectory;
            }
        }
        return null;
    }

    private static int javaVersion(String release, String target) {
        String version = StringUtils.isNotEmpty(release) ? release : target;
        if (StringUtils.isEmpty(version)) {
            version = JavaVersion.JAVA_SPECIFICATION_VERSION.asMajor().getValue(1);
        }
        return Integer.parseInt(version.startsWith("1.") ? version.substring(2) : version);
    }
}
