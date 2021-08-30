/*
 * Copyright 2016-2021 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wildfly.plugins.bootablejar.maven.common;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import org.apache.maven.plugin.MojoExecutionException;
import org.jboss.galleon.Errors;
import org.jboss.galleon.ProvisioningDescriptionException;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.runtime.FeaturePackRuntime;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.jboss.galleon.universe.maven.MavenUniverseException;
import org.jboss.galleon.universe.maven.repo.MavenRepoManager;
import org.jboss.galleon.util.CollectionUtils;
import static org.wildfly.plugins.bootablejar.maven.common.Constants.JBOSS_MAVEN_DIST;
import static org.wildfly.plugins.bootablejar.maven.common.Constants.JBOSS_PROVISIONING_MAVEN_REPO;
import static org.wildfly.plugins.bootablejar.maven.common.Constants.MAVEN_REPO_LOCAL;
import static org.wildfly.plugins.bootablejar.maven.common.Constants.MAVEN_REPO_PLUGIN_OPTION;

/**
 *
 * @author jdenise
 */
public class JakartaEE9Handler {

    private Path provisioningMavenRepo;
    private String jakartaTransformSuffix;
    private Set<String> transformExcluded = new HashSet<>();
    String originalLocalRepo = null;
    private final Map<String, String> pluginOptions;
    private final MavenRepoManager artifactResolver;

    public JakartaEE9Handler(Map<String, String> pluginOptions, MavenRepoManager artifactResolver) {
        this.pluginOptions = pluginOptions;
        this.artifactResolver = artifactResolver;
    }

    public void setup() throws ProvisioningException {
        // EE-9
        // In case we provision a slim server and a provisioningMavenRepo has been provided,
        // it must be used for the embedded server started in CLI scripts to resolve artifacts
        String provisioningRepo = pluginOptions.get(JBOSS_PROVISIONING_MAVEN_REPO);
        String generatedRepo = pluginOptions.get(MAVEN_REPO_PLUGIN_OPTION);
        if (isThinServer()) {
            if (generatedRepo != null) {
                Path repo = Paths.get(generatedRepo);
                originalLocalRepo = System.getProperty(MAVEN_REPO_LOCAL);
                System.setProperty(MAVEN_REPO_LOCAL, repo.toAbsolutePath().toString());
            } else if (provisioningRepo != null) {
                provisioningMavenRepo = Paths.get(provisioningRepo);
                originalLocalRepo = System.getProperty(MAVEN_REPO_LOCAL);
                System.setProperty(MAVEN_REPO_LOCAL, provisioningMavenRepo.toAbsolutePath().toString());
            }
        }
        // End EE-9
    }

    private boolean isThinServer() throws ProvisioningException {
        if (!pluginOptions.containsKey(JBOSS_MAVEN_DIST)) {
            return false;
        }
        final String value = pluginOptions.get(JBOSS_MAVEN_DIST);
        return value == null ? true : Boolean.parseBoolean(value);
    }

    public void done() {
        if (originalLocalRepo != null) {
            System.setProperty(MAVEN_REPO_LOCAL, originalLocalRepo);
        }
    }

    public void resolve(MavenArtifact artifact) throws MavenUniverseException, IOException {
        if (provisioningMavenRepo == null) {
            artifactResolver.resolve(artifact);
        } else {
            String grpid = artifact.getGroupId().replaceAll("\\.", Matcher.quoteReplacement(File.separator));
            Path grpidPath = provisioningMavenRepo.resolve(grpid);
            Path artifactidPath = grpidPath.resolve(artifact.getArtifactId());
            String version = getTransformedVersion(artifact);
            Path versionPath = artifactidPath.resolve(version);
            String classifier = (artifact.getClassifier() == null || artifact.getClassifier().isEmpty()) ? null : artifact.getClassifier();
            Path localPath = versionPath.resolve(artifact.getArtifactId() + "-"
                    + version
                    + (classifier == null ? "" : "-" + classifier)
                    + "." + artifact.getExtension());

            if (Files.exists(localPath)) {
                artifact.setPath(localPath);
            } else {
                artifactResolver.resolve(artifact);
            }
        }
    }

    private String getTransformedVersion(MavenArtifact artifact) {
        boolean transformed = !isExcludedFromTransformation(artifact);
        return artifact.getVersion() + (transformed ? jakartaTransformSuffix : "");
    }

    private boolean isExcludedFromTransformation(MavenArtifact artifact) {
        return transformExcluded.contains(artifactToString(artifact));
    }

    private String artifactToString(MavenArtifact artifact) {
        final StringBuilder buf = new StringBuilder();
        if (artifact.getGroupId() != null) {
            buf.append(artifact.getGroupId());
        }
        buf.append(':');
        if (artifact.getArtifactId() != null) {
            buf.append(artifact.getArtifactId());
        }
        if (artifact.getVersion() != null) {
            buf.append(':').append(artifact.getVersion());
        }
        return buf.toString();
    }

    public void lookupFeaturePack(FeaturePackRuntime fprt) throws MojoExecutionException, ProvisioningDescriptionException, ProvisioningException {
        // Lookup to retrieve ee-9 suffix.
        Path tasksProps = fprt.getResource("wildfly/wildfly-tasks.properties");
        final Map<String, String> tasksMap = new HashMap<>();
        try {
            Utils.readProperties(tasksProps, tasksMap);
        } catch (Exception ex) {
            throw new MojoExecutionException("Error reading artifact versions", ex);
        }
        jakartaTransformSuffix = tasksMap.get("jakarta.transform.artifacts.suffix");
        final Path excludedArtifacts = fprt.getResource("wildfly-jakarta-transform-excludes.txt");
        if (Files.exists(excludedArtifacts)) {
            try (BufferedReader reader = Files.newBufferedReader(excludedArtifacts, StandardCharsets.UTF_8)) {
                String line = reader.readLine();
                while (line != null) {
                    transformExcluded = CollectionUtils.add(transformExcluded, line);
                    line = reader.readLine();
                }
            } catch (IOException e) {
                throw new ProvisioningException(Errors.readFile(excludedArtifacts), e);
            }
        }
    }
}
