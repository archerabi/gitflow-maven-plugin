/*
 * Copyright 2014-2017 Aleksandr Mashchenko.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.amashchenko.maven.plugin.gitflow;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.shared.release.versions.DefaultVersionInfo;
import org.apache.maven.shared.release.versions.VersionInfo;
import org.apache.maven.shared.release.versions.VersionParseException;
import org.codehaus.plexus.components.interactivity.PrompterException;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.cli.CommandLineException;

/**
 * The git flow release start mojo.
 * 
 * @author Aleksandr Mashchenko
 * 
 */
@Mojo(name = "release-start", aggregator = true)
public class GitFlowReleaseStartMojo extends AbstractGitFlowMojo {

    /**
     * Whether to use the same name of the release branch for every release.
     * Default is <code>false</code>, i.e. project version will be added to
     * release branch prefix. <br/>
     * <br/>
     * 
     * Note: By itself the default releaseBranchPrefix is not a valid branch
     * name. You must change it when setting sameBranchName to <code>true</code>
     * .
     * 
     * @since 1.2.0
     */
    @Parameter(property = "sameBranchName", defaultValue = "false")
    private boolean sameBranchName = false;

    /**
     * Release version to use instead of the default next release version in non
     * interactive mode.
     * 
     * @since 1.3.1
     */
    @Parameter(property = "releaseVersion", defaultValue = "")
    private String releaseVersion = "";

    /**
     *
     */
    @Parameter(property = "useReleaseCandidate", defaultValue = "false")
    private boolean useReleaseCandidate = false;

    /** {@inheritDoc} */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            // set git flow configuration
            initGitFlowConfig();

            // check uncommitted changes
            checkUncommittedChanges();

            // check snapshots dependencies
            if (!allowSnapshots) {
                checkSnapshotDependencies();
            }

            // git for-each-ref --count=1 refs/heads/release/*
            final String releaseBranch = gitFindBranches(
                    gitFlowConfig.getReleaseBranchPrefix(), true);

            if (StringUtils.isNotBlank(releaseBranch)) {
                throw new MojoFailureException(
                        "Release branch already exists. Cannot start release.");
            }

            // fetch and check remote
            if (fetchRemote) {
                gitFetchRemoteAndCompare(gitFlowConfig.getDevelopmentBranch());
            }

            // need to be in develop to get correct project version
            // git checkout develop
            gitCheckout(gitFlowConfig.getDevelopmentBranch());

            // get current project version from pom
            final String currentVersion = getCurrentProjectVersion();

            String defaultVersion = null;
            String rcVersion = null;
            if (tychoBuild) {
                defaultVersion = currentVersion;
            } else {
                // get default release version
                try {
                    final DefaultVersionInfo versionInfo = new DefaultVersionInfo(
                            currentVersion);
                    if(useReleaseCandidate) {
                        rcVersion = versionInfo.getReleaseVersionString() + "-RC";
                    }
                    defaultVersion = versionInfo.getReleaseVersionString();
                } catch (VersionParseException e) {
                    if (getLog().isDebugEnabled()) {
                        getLog().debug(e);
                    }
                }
            }

            if (defaultVersion == null) {
                throw new MojoFailureException(
                        "Cannot get default project version.");
            }

            String version = null;
            if (settings.isInteractiveMode()) {
                try {
                    while (version == null) {
                        version = prompter.prompt("What is release version? ["
                                + defaultVersion + "]");

                        if (!"".equals(version) && !validBranchName(version)) {
                            getLog().info(
                                    "The name of the branch is not valid.");
                            version = null;
                        }
                    }
                } catch (PrompterException e) {
                    getLog().error(e);
                }
            } else {
                version = releaseVersion;
            }

            if (StringUtils.isBlank(version)) {
                version = defaultVersion;
            }

            if(useReleaseCandidate) {
                // mvn versions:set -DnewVersion=... -DgenerateBackupPoms=false
                mvnSetVersions(rcVersion);

                // git commit -a -m updating versions for release
                gitCommit(commitMessages.getReleaseCandidateMessage());
            }

            String branchName = gitFlowConfig.getReleaseBranchPrefix();
            if (!sameBranchName) {
                branchName += version;
            }

            // git checkout -b release/... develop
            gitCreateAndCheckout(branchName,
                    gitFlowConfig.getDevelopmentBranch());

            // execute if version changed
            if (!version.equals(currentVersion) && !useReleaseCandidate) {
                // mvn versions:set -DnewVersion=... -DgenerateBackupPoms=false
                mvnSetVersions(version);

                // git commit -a -m updating versions for release
                gitCommit(commitMessages.getReleaseStartMessage());
            }

            try {
                // git checkout -b develop
                gitCheckout(gitFlowConfig.getDevelopmentBranch());

                final VersionInfo nextVersion = new DefaultVersionInfo(
                        currentVersion).getNextVersion();
                // mvn versions:set -DnewVersion=... -DgenerateBackupPoms=false
                mvnSetVersions(nextVersion.toString());

                // git commit -a -m updating versions for release
                gitCommit(commitMessages.getUpdateVersion());
            } catch (VersionParseException e) {
                getLog().error(e);
            }

            if (installProject) {
                // mvn clean install
                mvnCleanInstall();
            }
        } catch (CommandLineException e) {
            getLog().error(e);
        }
    }
}
