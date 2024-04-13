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
package org.apache.maven.plugin.junit.modern.support;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import com.google.inject.Module;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.plugin.Mojo;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptorBuilder;
import org.apache.maven.plugin.testing.ConfigurationException;
import org.codehaus.plexus.ContainerConfiguration;
import org.codehaus.plexus.DefaultContainerConfiguration;
import org.codehaus.plexus.DefaultPlexusContainer;
import org.codehaus.plexus.PlexusConstants;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.PlexusContainerException;
import org.codehaus.plexus.classworlds.ClassWorld;
import org.codehaus.plexus.component.configurator.ComponentConfigurator;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluator;
import org.codehaus.plexus.component.repository.ComponentDescriptor;
import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.codehaus.plexus.configuration.xml.XmlPlexusConfiguration;
import org.codehaus.plexus.context.Context;
import org.codehaus.plexus.util.InterpolationFilterReader;
import org.codehaus.plexus.util.ReaderFactory;
import org.codehaus.plexus.util.xml.XmlStreamReader;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * @author jesse
 * @author Mikhail Deviatov. Copied and adapted from <maven-plugin-testing-harness> plugin
 */
public abstract class JUnit5AbstractMojoTestCase extends JUnit5PlexusTestCase {

    private static final String PLUGIN_DESCRIPTOR_LOCATION = "META-INF/maven/plugin.xml";
    private ComponentConfigurator configurator;
    private PlexusContainer container;

    public JUnit5AbstractMojoTestCase() {
        checkDefaultArtifactVersion();
    }

    private void checkDefaultArtifactVersion() {
        String path = "/META-INF/maven/org.apache.maven/maven-core/pom.properties";
        try (InputStream is = JUnit5PlexusTestCase.class.getResourceAsStream(path)) {
            Properties properties = new Properties();
            if (is != null) {
                properties.load(is);
            }

            DefaultArtifactVersion version = null;
            String property = properties.getProperty("version");
            if (property != null) {
                version = new DefaultArtifactVersion(property);
            }

            assertTrue(new DefaultArtifactVersion("3.2.3").compareTo(version) < 0, "Maven 3.2.4 or better is required");
        } catch (IOException e) {
            fail("Failed to to get default artifact version.", e);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        if (container == null) {
            setupContainer();
        }

        configurator = container.lookup(ComponentConfigurator.class, "basic");
        Context context = container.getContext();
        Map<Object, Object> map = context.getContextData();
        try (InputStream is = getClass().getResourceAsStream("/" + PLUGIN_DESCRIPTOR_LOCATION);
                Reader reader = new BufferedReader(new XmlStreamReader(is));
                InterpolationFilterReader interpolationReader = new InterpolationFilterReader(reader, map, "${", "}")) {

            PluginDescriptor pluginDescriptor = new PluginDescriptorBuilder().build(interpolationReader);
            Artifact artifact = new DefaultArtifact(
                    pluginDescriptor.getGroupId(),
                    pluginDescriptor.getArtifactId(),
                    pluginDescriptor.getVersion(),
                    null,
                    "jar",
                    null,
                    new DefaultArtifactHandler("jar"));

            artifact.setFile(getPluginArtifactFile());
            pluginDescriptor.setPluginArtifact(artifact);
            pluginDescriptor.setArtifacts(Arrays.asList(artifact));

            for (ComponentDescriptor<?> desc : pluginDescriptor.getComponents()) {
                container.addComponentDescriptor(desc);
            }
        }
    }

    @AfterEach
    void tearDown() {
        container.dispose();
        container = null;
    }

    private void setupContainer() {
        try {
            ClassWorld classWorld =
                    new ClassWorld("plexus.core", Thread.currentThread().getContextClassLoader());
            ContainerConfiguration cc = new DefaultContainerConfiguration()
                    .setClassWorld(classWorld)
                    .setClassPathScanning(PlexusConstants.SCANNING_INDEX)
                    .setAutoWiring(true)
                    .setName("maven");

            container = new DefaultPlexusContainer(cc, new Module[0]);
        } catch (PlexusContainerException e) {
            fail("Failed to create plexus container.", e);
        }
    }

    private File getPluginArtifactFile() throws IOException {
        final String pluginDescriptorLocation = PLUGIN_DESCRIPTOR_LOCATION;
        final URL resource = getClass().getResource("/" + pluginDescriptorLocation);

        File file = null;
        // attempt to resolve relative to META-INF/maven/plugin.xml first
        if (resource != null) {
            if ("file".equalsIgnoreCase(resource.getProtocol())) {
                String path = resource.getPath();
                if (path.endsWith(pluginDescriptorLocation)) {
                    file = new File(path.substring(0, path.length() - pluginDescriptorLocation.length()));
                }
            } else if ("jar".equalsIgnoreCase(resource.getProtocol())) {
                // TODO is there a helper for this somewhere?
                try {
                    URL jarfile = new URL(resource.getPath());
                    if ("file".equalsIgnoreCase(jarfile.getProtocol())) {
                        String path = jarfile.getPath();
                        if (path.endsWith(pluginDescriptorLocation)) {
                            file = new File(path.substring(0, path.length() - pluginDescriptorLocation.length() - 2));
                        }
                    }
                } catch (MalformedURLException e) {
                    // not jar:file:/ URL, too bad
                }
            }
        }

        // fallback to test project basedir if couldn't resolve relative to META-INF/maven/plugin.xml
        if (file == null || !file.exists()) {
            file = new File(getBaseDir());
        }

        return file.getCanonicalFile();
    }

    protected <T extends Mojo> T lookupMojo(String goal, File pom) throws Exception {
        File pluginPom = new File(getBaseDir(), "pom.xml");
        Xpp3Dom pluginPomDom = Xpp3DomBuilder.build(ReaderFactory.newXmlReader(pluginPom));
        String artifactId = pluginPomDom.getChild("artifactId").getValue();
        String groupId = resolveFromRootThenParent(pluginPomDom, "groupId");
        String version = resolveFromRootThenParent(pluginPomDom, "version");
        PlexusConfiguration pluginConfiguration = extractPluginConfiguration(artifactId, pom);

        return lookupMojo(groupId, artifactId, version, goal, pluginConfiguration);
    }

    private String resolveFromRootThenParent(Xpp3Dom pluginPomDom, String element) throws Exception {
        Xpp3Dom elementDom = pluginPomDom.getChild(element);

        // parent might have the group Id so resolve it
        if (elementDom == null) {
            Xpp3Dom pluginParentDom = pluginPomDom.getChild("parent");
            if (pluginParentDom != null) {
                elementDom = pluginParentDom.getChild(element);
                if (elementDom == null) {
                    throw new Exception("unable to determine " + element);
                }
                return elementDom.getValue();
            }
            throw new Exception("unable to determine " + element);
        }

        return elementDom.getValue();
    }

    private PlexusConfiguration extractPluginConfiguration(String artifactId, File pom) throws Exception {
        try (Reader reader = ReaderFactory.newXmlReader(pom)) {
            Xpp3Dom pomDom = Xpp3DomBuilder.build(reader);
            return extractPluginConfiguration(artifactId, pomDom);
        }
    }

    private PlexusConfiguration extractPluginConfiguration(String artifactId, Xpp3Dom pomDom) throws Exception {
        Xpp3Dom pluginConfigurationElement = null;

        Xpp3Dom buildElement = pomDom.getChild("build");
        if (buildElement != null) {
            Xpp3Dom pluginsRootElement = buildElement.getChild("plugins");
            if (pluginsRootElement != null) {
                Xpp3Dom[] pluginElements = pluginsRootElement.getChildren();
                for (Xpp3Dom pluginElement : pluginElements) {
                    String pluginElementArtifactId =
                            pluginElement.getChild("artifactId").getValue();
                    if (pluginElementArtifactId.equals(artifactId)) {
                        pluginConfigurationElement = pluginElement.getChild("configuration");
                        break;
                    }
                }

                if (pluginConfigurationElement == null) {
                    throw new ConfigurationException("Cannot find a configuration element for a plugin with an "
                            + "artifactId of " + artifactId + ".");
                }
            }
        }

        if (pluginConfigurationElement == null) {
            throw new ConfigurationException(
                    "Cannot find a configuration element for a plugin with an artifactId of " + artifactId + ".");
        }

        return new XmlPlexusConfiguration(pluginConfigurationElement);
    }

    protected <T extends Mojo> T lookupMojo(
            String groupId, String artifactId, String version, String goal, PlexusConfiguration pluginConfiguration)
            throws Exception {
        Objects.requireNonNull(container);

        T mojo = (T) container.lookup(Mojo.class, groupId + ":" + artifactId + ":" + version + ":" + goal);
        if (pluginConfiguration != null) {
            /* requires v10 of plexus container for lookup on expression evaluator
            ExpressionEvaluator evaluator = (ExpressionEvaluator) container.lookup( ExpressionEvaluator.ROLE,
                                                                                        "stub-evaluator" );
            */
            ExpressionEvaluator evaluator = new JUnit5ResolverExpressionEvaluatorStub();

            configurator.configureComponent(mojo, pluginConfiguration, evaluator, container.getContainerRealm());
        }

        return mojo;
    }
}
