// Copyright 2023 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.remote;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.devtools.build.lib.packages.TargetUtils.isTestRuleName;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.FileArtifactValue.RemoteFileArtifactValue;
import com.google.devtools.build.lib.actions.RemoteArtifactChecker;
import com.google.devtools.build.lib.analysis.AnalysisResult;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.TopLevelArtifactContext;
import com.google.devtools.build.lib.analysis.TopLevelArtifactHelper;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.clock.Clock;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** A {@link RemoteArtifactChecker} that checks the TTL of remote metadata. */
public class RemoteOutputChecker implements RemoteArtifactChecker {
  private enum CommandMode {
    UNKNOWN,
    BUILD,
    TEST,
    RUN,
    COVERAGE;
  }

  private final Clock clock;
  private final CommandMode commandMode;
  private final boolean downloadToplevel;
  private final ImmutableList<Pattern> patternsToDownload;
  private final Set<ActionInput> toplevelArtifactsToDownload = Sets.newConcurrentHashSet();
  private final Set<ActionInput> inputsToDownload = Sets.newConcurrentHashSet();

  public RemoteOutputChecker(
      Clock clock,
      String commandName,
      boolean downloadToplevel,
      ImmutableList<Pattern> patternsToDownload) {
    this.clock = clock;
    switch (commandName) {
      case "build":
        this.commandMode = CommandMode.BUILD;
        break;
      case "test":
        this.commandMode = CommandMode.TEST;
        break;
      case "run":
        this.commandMode = CommandMode.RUN;
        break;
      case "coverage":
        this.commandMode = CommandMode.COVERAGE;
        break;
      default:
        this.commandMode = CommandMode.UNKNOWN;
    }
    this.downloadToplevel = downloadToplevel;
    this.patternsToDownload = patternsToDownload;
  }

  // TODO(chiwang): Code path reserved for skymeld.
  public void afterTopLevelTargetAnalysis(
      ConfiguredTarget configuredTarget,
      Supplier<TopLevelArtifactContext> topLevelArtifactContextSupplier) {
    addTopLevelTarget(configuredTarget, topLevelArtifactContextSupplier);
  }

  public void afterAnalysis(AnalysisResult analysisResult) {
    for (var target : analysisResult.getTargetsToBuild()) {
      addTopLevelTarget(target, analysisResult::getTopLevelContext);
    }
    var targetsToTest = analysisResult.getTargetsToTest();
    if (targetsToTest != null) {
      for (var target : targetsToTest) {
        addTopLevelTarget(target, analysisResult::getTopLevelContext);
      }
    }
  }

  private void addTopLevelTarget(
      ConfiguredTarget toplevelTarget,
      Supplier<TopLevelArtifactContext> topLevelArtifactContextSupplier) {
    if (shouldDownloadToplevelOutputs(toplevelTarget)) {
      var topLevelArtifactContext = topLevelArtifactContextSupplier.get();
      var artifactsToBuild =
          TopLevelArtifactHelper.getAllArtifactsToBuild(toplevelTarget, topLevelArtifactContext)
              .getImportantArtifacts();
      toplevelArtifactsToDownload.addAll(artifactsToBuild.toList());

      addRunfiles(toplevelTarget);
    }
  }

  private void addRunfiles(ConfiguredTarget buildTarget) {
    var runfilesProvider = buildTarget.getProvider(FilesToRunProvider.class);
    if (runfilesProvider == null) {
      return;
    }
    var runfilesSupport = runfilesProvider.getRunfilesSupport();
    if (runfilesSupport == null) {
      return;
    }
    for (Artifact runfile : runfilesSupport.getRunfiles().getArtifacts().toList()) {
      if (runfile.isSourceArtifact()) {
        continue;
      }
      toplevelArtifactsToDownload.add(runfile);
    }
  }

  public void addInputToDownload(ActionInput file) {
    inputsToDownload.add(file);
  }

  private boolean shouldDownloadToplevelOutputs(ConfiguredTarget configuredTarget) {
    switch (commandMode) {
      case RUN:
        // Always download outputs of toplevel targets in RUN mode
        return true;
      case COVERAGE:
      case TEST:
        // Do not download test binary in test/coverage mode.
        if (configuredTarget instanceof RuleConfiguredTarget) {
          var ruleConfiguredTarget = (RuleConfiguredTarget) configuredTarget;
          var isTestRule = isTestRuleName(ruleConfiguredTarget.getRuleClassString());
          return !isTestRule && downloadToplevel;
        }
        return downloadToplevel;
      default:
        return downloadToplevel;
    }
  }

  private boolean shouldDownloadOutputForToplevel(ActionInput output) {
    return shouldDownloadOutputFor(output, toplevelArtifactsToDownload);
  }

  private boolean shouldDownloadOutputForLocalAction(ActionInput output) {
    return shouldDownloadOutputFor(output, inputsToDownload);
  }

  private boolean shouldDownloadFileForRegex(ActionInput file) {
    checkArgument(
        !(file instanceof Artifact && ((Artifact) file).isTreeArtifact()),
        "file must not be a tree.");

    for (var pattern : patternsToDownload) {
      if (pattern.matcher(file.getExecPathString()).matches()) {
        return true;
      }
    }

    return false;
  }

  private static boolean shouldDownloadOutputFor(
      ActionInput output, Set<ActionInput> artifactCollection) {
    if (output instanceof TreeFileArtifact) {
      if (artifactCollection.contains(((Artifact) output).getParent())) {
        return true;
      }
    } else if (artifactCollection.contains(output)) {
      return true;
    }

    return false;
  }

  /**
   * Returns {@code true} if Bazel should download this {@link ActionInput} during spawn execution.
   *
   * @param output output of the spawn. Tree is accepted since we can't know the content of tree
   *     before executing the spawn.
   */
  public boolean shouldDownloadOutputDuringActionExecution(ActionInput output) {
    // Download toplevel artifacts within action execution so that when the event TargetComplete is
    // emitted, related toplevel artifacts are downloaded.
    //
    // Download outputs that are inputs to local actions within action execution so that the local
    // actions don't need to wait for background downloads.
    return shouldDownloadOutputForToplevel(output) || shouldDownloadOutputForLocalAction(output);
  }

  /**
   * Returns {@code true} if Bazel should download this {@link ActionInput} after action execution.
   *
   * @param file file output of the action. Tree must be expanded to tree file.
   */
  public boolean shouldDownloadFileAfterActionExecution(ActionInput file) {
    // Download user requested blobs in background to finish action execution sooner so that other
    // actions can start sooner.
    return shouldDownloadFileForRegex(file);
  }

  @Override
  public boolean shouldTrustRemoteArtifact(ActionInput file, RemoteFileArtifactValue metadata) {
    if (shouldDownloadOutputForToplevel(file) || shouldDownloadFileForRegex(file)) {
      // If Bazel should download this file, but it does not exist locally, returns false to rerun
      // the generating action to trigger the download (just like in the normal build, when local
      // outputs are missing).
      return false;
    }

    return metadata.isAlive(clock.now());
  }
}
