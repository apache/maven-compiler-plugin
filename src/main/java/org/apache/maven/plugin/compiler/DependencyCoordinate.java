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

import java.util.Objects;
import java.util.Set;

/**
 * Simple representation of Maven-coordinates of a dependency.
 *
 * @author Andreas Gudian
 * @since 3.4
 */
public class DependencyCoordinate {
    private String groupId;

    private String artifactId;

    private String version;

    private String classifier;

    private String type = "jar";

    private Set<DependencyExclusion> exclusions;

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public void setArtifactId(String artifactId) {
        this.artifactId = artifactId;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getClassifier() {
        return classifier;
    }

    public void setClassifier(String classifier) {
        this.classifier = classifier;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Set<DependencyExclusion> getExclusions() {
        return exclusions;
    }

    public void setExclusions(Set<DependencyExclusion> exclusions) {
        this.exclusions = exclusions;
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupId, artifactId, version, classifier, type, exclusions);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        DependencyCoordinate other = (DependencyCoordinate) obj;
        return Objects.equals(groupId, other.groupId)
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
}
