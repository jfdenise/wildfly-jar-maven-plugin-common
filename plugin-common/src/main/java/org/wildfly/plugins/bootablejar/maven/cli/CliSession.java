/*
 * Copyright 2020 Red Hat, Inc. and/or its affiliates
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
package org.wildfly.plugins.bootablejar.maven.cli;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import org.wildfly.plugins.bootablejar.maven.common.PluginContext;
import org.wildfly.plugins.bootablejar.maven.common.Utils;
import org.wildfly.security.manager.WildFlySecurityManager;

/**
 * A CLI execution session.
 * @author jdenise
 */
public class CliSession {

    private List<String> scriptFiles = Collections.emptyList();
    private String propertiesFile;
    boolean resolveExpressions = true;

    /**
     * Set the list of CLI script files to execute.
     *
     * @param scriptFiles List of script file paths.
     */
    public void setScriptFiles(List<String> scriptFiles) {
        this.scriptFiles = scriptFiles;
    }

    /**
     * Get the list of CLI script files to execute.
     *
     * @return The list of file paths.
     */
    public List<String> getScriptFiles() {
        return scriptFiles;
    }

    /**
     * Set the properties file used when executing the CLI.
     *
     * @param propertiesFile Path to properties file.
     */
    public void setPropertiesFile(String propertiesFile) {
        this.propertiesFile = propertiesFile;
    }

    /**
     * Get the properties file used when executing the CLI.
     *
     * @return The properties file path.
     */
    public String getPropertiesFile() {
        return propertiesFile;
    }

    /**
     * By default, the CLI resolves expressions located in scripts locally. In order to have the expressions
     * resolved at server execution time, set this value to false.
     * @param resolveExpressions True to resolve locally, false to resolve at server execution time.
     */
    public void setResolveExpressions(boolean resolveExpressions) {
        this.resolveExpressions = resolveExpressions;
    }

    /**
     * Get the expression resolution value.
     * @return The expression resolution value.
     */
    public boolean getResolveExpression() {
        return resolveExpressions;
    }

    @Override
    public String toString() {
        return "CLI Session, scripts=" + this.scriptFiles +
                ", resolve-expressions="+this.resolveExpressions +", properties-file="+this.propertiesFile;
    }

    public void execute(PluginContext ctx, boolean startEmbedded, boolean forkCli, List<Path> cliArtifacts) throws Exception {
        List<String> commands = new ArrayList<>();
        for (String path : getScriptFiles()) {
            File f = new File(path);
            Path filePath = Utils.resolvePath(ctx.getProject(), f.toPath());
            if (Files.notExists(filePath)) {
                throw new RuntimeException("Cli script file " + filePath + " doesn't exist");
            }
            try (BufferedReader reader = new BufferedReader(new FileReader(filePath.toFile()))) {
                String line = reader.readLine();
                while (line != null) {
                    commands.add(line.trim());
                    line = reader.readLine();
                }
            }
        }
        if (!commands.isEmpty()) {
            executeCliScript(ctx, commands, getPropertiesFile(),
                    getResolveExpression(), this.toString(), startEmbedded, forkCli, cliArtifacts);
        }
    }

    public static void execute(PluginContext ctx, List<CliSession> sessions,
            boolean startEmbedded, boolean forkCli, List<Path> cliArtifacts) throws Exception {
        for (CliSession s : sessions) {
            s.execute(ctx, startEmbedded, forkCli, cliArtifacts);
        }
    }

    public static void executeCliScript(PluginContext ctx, List<String> commands, String propertiesFile,
            boolean resolveExpression, String message, boolean startEmbedded, boolean forkCli, List<Path> cliArtifacts) throws Exception {
        ctx.getLog().info("Executing CLI, " + message);
        Properties props = null;
        if (propertiesFile != null) {
            props = Utils.loadProperties(ctx, propertiesFile);
        }
        try {
            processCLI(ctx, commands, resolveExpression, startEmbedded, forkCli, cliArtifacts);
        } finally {
            if (props != null) {
                for (String key : props.stringPropertyNames()) {
                    WildFlySecurityManager.clearPropertyPrivileged(key);
                }
            }
        }
    }

    private static void processCLI(PluginContext ctx, List<String> commands,
            boolean resolveExpression, boolean startEmbedded, boolean forkCli, List<Path> cliArtifacts) throws Exception {

        List<String> allCommands = new ArrayList<>();
        if (startEmbedded) {
            allCommands.add("embed-server --jboss-home=" + ctx.getJBossHome() + " --std-out=discard");
        }
        for (String line : commands) {
            allCommands.add(line.trim());
        }
        if (startEmbedded) {
            allCommands.add("stop-embedded-server");
        }
        try (CLIExecutor executor = forkCli ? new RemoteCLIExecutor(ctx, cliArtifacts, resolveExpression)
                : new LocalCLIExecutor(ctx, cliArtifacts, resolveExpression)) {

            try {
                executor.execute(allCommands);
            } catch (Exception ex) {
                ctx.getLog().error("Error executing CLI script " + ex.getLocalizedMessage());
                ctx.getLog().error(executor.getOutput());
                throw ex;
            }
            if (ctx.isDisplayCliScriptsOutputEnabled()) {
                ctx.getLog().info(executor.getOutput());
            }
        }
        ctx.getLog().info("CLI scripts execution done.");
    }
}
