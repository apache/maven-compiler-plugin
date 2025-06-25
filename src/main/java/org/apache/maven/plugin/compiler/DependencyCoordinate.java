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

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.apache.maven.api.Exclusion;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.services.DependencyCoordinatesFactory;
import org.apache.maven.api.services.DependencyCoordinatesFactoryRequest;

/**
 * Simple representation of Maven-coordinates of a dependency.
 *
 * @author Andreas Gudian
 * @since 3.4
 *
 * @deprecated Used for {@link AbstractCompilerMojo#annotationProcessorPaths}, which is deprecated.
 */
@Deprecated(since = "4.0.0")
public final class DependencyCoordinate {
    private String groupId;

    private String artifactId;

    private String version;

    private String classifier;

    private String type = "jar";

    private Set<DependencyExclusion> exclusions;

    @Override
    public int hashCode() {
        return Objects.hash(groupId, artifactId, version, classifier, type, exclusions);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        return obj instanceof DependencyCoordinate other
                && Objects.equals(groupId, other.groupId)
                && Objects.equals(artifactId, other.artifactId)
                && Objects.equals(version, other.version)
                && Objects.equals(classifier, other.classifier)
                && Objects.equals(type, other.type)
                && Objects.equals(exclusions, other.exclusions);
    }

    @Override
    public String toString() {
        return groupId + ":" + artifactId + (version != null ? ":" + version : "")
                + (classifier != null ? ":" + classifier : "") + (type != null ? "." + type : "");
    }

    /**
     * Converts this coordinate to the Maven API.
     *
     * @param project the current project
     * @param session the current build session instance
     * @return this coordinate as Maven API
     */
    org.apache.maven.api.DependencyCoordinates toCoordinate(Project project, Session session) {
        return session.getService(DependencyCoordinatesFactory.class)
                .create(DependencyCoordinatesFactoryRequest.builder()
                        .session(session)
                        .groupId(groupId)
                        .artifactId(artifactId)
                        .classifier(classifier)
                        .type(type)
                        .version(version)
                        .version(getAnnotationProcessorPathVersion(project))
                        .exclusions(toExclusions(exclusions))
                        .build());
    }

    private String getAnnotationProcessorPathVersion(Project project) throws MojoException {
        if (version != null) {
            return version;
        } else {
            if (classifier == null) {
                classifier = ""; // Needed for comparison with dep.getClassifier() because of method contract.
            }
            List<org.apache.maven.api.DependencyCoordinates> managedDependencies = project.getManagedDependencies();
            return findManagedVersion(managedDependencies)
                    .orElseThrow(() -> new CompilationFailureException(String.format(
                            "Cannot find version for annotation processor path '%s'.%nThe version needs to be either"
                                    + " provided directly in the plugin configuration or via dependency management.",
                            this)));
        }
    }

    private Optional<String> findManagedVersion(List<org.apache.maven.api.DependencyCoordinates> managedDependencies) {
        return managedDependencies.stream()
                .filter(dep -> Objects.equals(dep.getGroupId(), groupId)
                        && Objects.equals(dep.getArtifactId(), artifactId)
                        && Objects.equals(dep.getClassifier(), classifier)
                        && Objects.equals(dep.getType().id(), type))
                .findAny()
                .map(d -> d.getVersionConstraint().toString());
    }

    private static Collection<Exclusion> toExclusions(Set<DependencyExclusion> exclusions) {
        if (exclusions == null || exclusions.isEmpty()) {
            return List.of();
        }
        return exclusions.stream()
                .map(e -> (Exclusion) new Exclusion() {
                    @Override
                    public String getGroupId() {
                        return e.groupId;
                    }

                    @Override
                    public String getArtifactId() {
                        return e.artifactId;
                    }
                })
                .toList();
    }
}
