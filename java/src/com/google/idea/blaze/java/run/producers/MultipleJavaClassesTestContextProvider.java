/*
 * Copyright 2017 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.java.run.producers;

import static java.util.stream.Collectors.toList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.idea.blaze.base.dependencies.TargetInfo;
import com.google.idea.blaze.base.dependencies.TestSize;
import com.google.idea.blaze.base.lang.buildfile.search.BlazePackage;
import com.google.idea.blaze.base.run.ExecutorType;
import com.google.idea.blaze.base.run.TestTargetHeuristic;
import com.google.idea.blaze.base.run.producers.RunConfigurationContext;
import com.google.idea.blaze.base.run.producers.TestContext;
import com.google.idea.blaze.base.run.producers.TestContextProvider;
import com.google.idea.blaze.base.sync.projectview.WorkspaceFileFinder;
import com.intellij.execution.Location;
import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.psi.JavaDirectoryService;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiModifier;
import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.jetbrains.ide.PooledThreadExecutor;

/**
 * Runs tests in all selected java classes (or all classes below selected directory). Ignores
 * classes spread across multiple test targets.
 */
class MultipleJavaClassesTestContextProvider implements TestContextProvider {

  @Nullable
  @Override
  public RunConfigurationContext getTestContext(ConfigurationContext context) {
    boolean outsideProject = context.getModule() == null;
    if (outsideProject) {
      // TODO(brendandouglas): resolve PSI asynchronously for files outside the project
      return null;
    }
    PsiElement location = context.getPsiLocation();
    if (location instanceof PsiDirectory) {
      PsiDirectory dir = (PsiDirectory) location;
      ListenableFuture<TargetInfo> future = getTargetContext(dir);
      return future != null ? fromDirectory(future, dir) : null;
    }
    Set<PsiClass> testClasses = selectedTestClasses(context);
    if (testClasses.size() < 2) {
      return null;
    }
    TargetInfo target = getTestTargetIfUnique(testClasses);
    if (target == null) {
      return null;
    }
    testClasses = ProducerUtils.includeInnerTestClasses(testClasses);
    return fromClasses(target, testClasses);
  }

  @Nullable
  private static TestContext fromDirectory(ListenableFuture<TargetInfo> future, PsiDirectory dir) {
    String packagePrefix =
        ProjectFileIndex.SERVICE
            .getInstance(dir.getProject())
            .getPackageNameByDirectory(dir.getVirtualFile());
    if (packagePrefix == null) {
      return null;
    }
    String description = String.format("all in directory '%s'", dir.getName());
    String testFilter = packagePrefix.isEmpty() ? null : packagePrefix;
    return TestContext.builder(dir, ExecutorType.DEBUG_SUPPORTED_TYPES)
        .setTarget(future)
        .setTestFilter(testFilter)
        .setDescription(description)
        .build();
  }

  @Nullable
  private static TestContext fromClasses(TargetInfo target, Set<PsiClass> classes) {
    Map<PsiClass, Collection<Location<?>>> methodsPerClass =
        classes.stream().collect(Collectors.toMap(c -> c, c -> ImmutableList.of()));
    String filter = BlazeJUnitTestFilterFlags.testFilterForClassesAndMethods(methodsPerClass);
    if (filter == null || filter.isEmpty()) {
      return null;
    }

    PsiClass sampleClass =
        classes.stream()
            .min(
                Comparator.comparing(
                    PsiClass::getName, Comparator.nullsLast(Comparator.naturalOrder())))
            .orElse(null);
    if (sampleClass == null) {
      return null;
    }
    String name = sampleClass.getName();
    if (name != null && classes.size() > 1) {
      name += String.format(" and %s others", classes.size() - 1);
    }
    return TestContext.builder(sampleClass, ExecutorType.DEBUG_SUPPORTED_TYPES)
        .setTarget(target)
        .setTestFilter(filter)
        .setDescription(name)
        .build();
  }

  private static Set<PsiClass> selectedTestClasses(ConfigurationContext context) {
    DataContext dataContext = context.getDataContext();
    PsiElement[] elements = getSelectedPsiElements(dataContext);
    if (elements == null) {
      return ImmutableSet.of();
    }
    return Arrays.stream(elements)
        .map(ProducerUtils::getTestClass)
        .filter(Objects::nonNull)
        .filter(testClass -> !testClass.hasModifierProperty(PsiModifier.ABSTRACT))
        .collect(Collectors.toSet());
  }

  @Nullable
  private static PsiElement[] getSelectedPsiElements(DataContext context) {
    PsiElement[] elements = LangDataKeys.PSI_ELEMENT_ARRAY.getData(context);
    if (elements != null) {
      return elements;
    }
    PsiElement element = CommonDataKeys.PSI_ELEMENT.getData(context);
    return element != null ? new PsiElement[] {element} : null;
  }

  /**
   * Returns a {@link RunConfigurationContext} future setting up the relevant test target pattern,
   * if one can be found.
   */
  @Nullable
  private static ListenableFuture<TargetInfo> getTargetContext(PsiDirectory dir) {
    ProjectFileIndex index = ProjectFileIndex.SERVICE.getInstance(dir.getProject());
    if (!index.isInTestSourceContent(dir.getVirtualFile())) {
      return null;
    }
    if (BlazePackage.isBlazePackage(dir)) {
      // this case is handled by a separate run config producer
      return null;
    }
    ListenableFuture<Set<PsiClass>> classes = findAllTestClassesBeneathDirectory(dir);
    if (classes == null) {
      return null;
    }
    return Futures.transform(
        classes,
        set -> set == null ? null : ReadAction.compute(() -> getTestTargetIfUnique(set)),
        MoreExecutors.directExecutor());
  }

  private static final int MAX_DEPTH_TO_SEARCH = 8;
  private static final ListeningExecutorService EXECUTOR =
      ApplicationManager.getApplication().isUnitTestMode()
          ? MoreExecutors.newDirectExecutorService()
          : MoreExecutors.listeningDecorator(PooledThreadExecutor.INSTANCE);

  @Nullable
  private static ListenableFuture<Set<PsiClass>> findAllTestClassesBeneathDirectory(
      PsiDirectory dir) {
    Project project = dir.getProject();
    WorkspaceFileFinder finder =
        WorkspaceFileFinder.Provider.getInstance(project).getWorkspaceFileFinder();
    if (finder == null || !relevantDirectory(finder, dir)) {
      return null;
    }
    return EXECUTOR.submit(
        () -> {
          Set<PsiClass> classes = new HashSet<>();
          ReadAction.run(() -> addClassesInDirectory(finder, dir, classes, /* currentDepth= */ 0));
          return classes;
        });
  }

  private static boolean relevantDirectory(WorkspaceFileFinder finder, PsiDirectory dir) {
    return finder.isInProject(new File(dir.getVirtualFile().getPath()));
  }

  private static void addClassesInDirectory(
      WorkspaceFileFinder finder, PsiDirectory dir, Set<PsiClass> set, int currentDepth) {
    if (currentDepth > MAX_DEPTH_TO_SEARCH || !relevantDirectory(finder, dir)) {
      return;
    }
    PsiClass[] classes = JavaDirectoryService.getInstance().getClasses(dir);
    set.addAll(Arrays.stream(classes).filter(ProducerUtils::isTestClass).collect(toList()));
    for (PsiDirectory child : dir.getSubdirectories()) {
      addClassesInDirectory(finder, child, set, currentDepth + 1);
    }
  }

  @Nullable
  private static TargetInfo getTestTargetIfUnique(Set<PsiClass> classes) {
    TargetInfo testTarget = null;
    for (PsiClass psiClass : classes) {
      TargetInfo target = testTargetForClass(psiClass);
      if (target == null) {
        continue;
      }
      if (testTarget != null && !testTarget.equals(target)) {
        return null;
      }
      testTarget = target;
    }
    return testTarget;
  }

  @Nullable
  private static TargetInfo testTargetForClass(PsiClass psiClass) {
    PsiClass testClass = ProducerUtils.getTestClass(psiClass);
    if (testClass == null || testClass.hasModifierProperty(PsiModifier.ABSTRACT)) {
      return null;
    }
    TestSize testSize = TestSizeFinder.getTestSize(psiClass);
    return TestTargetHeuristic.testTargetForPsiElement(psiClass, testSize);
  }
}
