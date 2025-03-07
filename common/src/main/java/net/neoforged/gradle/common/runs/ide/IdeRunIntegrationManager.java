package net.neoforged.gradle.common.runs.ide;

import net.neoforged.elc.configs.GradleLaunchConfig;
import net.neoforged.elc.configs.JavaApplicationLaunchConfig;
import net.neoforged.elc.configs.LaunchConfig;
import net.neoforged.elc.configs.LaunchGroup;
import net.neoforged.gradle.common.extensions.IdeManagementExtension;
import net.neoforged.gradle.common.runs.ide.extensions.IdeaRunExtensionImpl;
import net.neoforged.gradle.common.runs.run.RunImpl;
import net.neoforged.gradle.common.util.ProjectUtils;
import net.neoforged.gradle.common.util.SourceSetUtils;
import net.neoforged.gradle.common.util.constants.RunsConstants;
import net.neoforged.gradle.common.util.run.RunsUtil;
import net.neoforged.gradle.dsl.common.extensions.subsystems.conventions.ide.IDEA;
import net.neoforged.gradle.dsl.common.runs.ide.extensions.IdeaRunExtension;
import net.neoforged.gradle.dsl.common.runs.idea.extensions.IdeaRunsExtension;
import net.neoforged.gradle.dsl.common.runs.run.Run;
import net.neoforged.gradle.dsl.common.util.CommonRuntimeUtils;
import net.neoforged.gradle.util.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.jvm.tasks.ProcessResources;
import org.gradle.plugins.ide.eclipse.model.EclipseModel;
import org.gradle.plugins.ide.idea.model.IdeaModel;
import org.gradle.plugins.ide.idea.model.IdeaProject;
import org.jetbrains.gradle.ext.*;

import javax.xml.stream.XMLStreamException;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A simple manager which configures runs based on the IDE it is attached to.
 */
public class IdeRunIntegrationManager {
    
    private static final IdeRunIntegrationManager INSTANCE = new IdeRunIntegrationManager();
    
    public static IdeRunIntegrationManager getInstance() {
        return INSTANCE;
    }
    
    private IdeRunIntegrationManager() {
    }
    
    
    /**
     * Configures the IDE integration DSLs.
     *
     * @param project The project to configure.
     */
    public void setup(final Project project) {
        project.getExtensions().configure(RunsConstants.Extensions.RUNS, (Action<NamedDomainObjectContainer<Run>>) runs -> runs.configureEach(run -> {
            run.getExtensions().create(IdeaRunExtension.class, "idea", IdeaRunExtensionImpl.class, project, run);
        }));
        
        final Project rootProject = project.getRootProject();
        final IdeaModel ideaModel = rootProject.getExtensions().getByType(IdeaModel.class);
        final IdeaProject ideaProject = ideaModel.getProject();
        final ExtensionAware extensionAware = (ExtensionAware) ideaProject;
        if (extensionAware.getExtensions().findByType(IdeaRunsExtension.class) == null && extensionAware.getExtensions().findByName("runs") == null) {
            extensionAware.getExtensions().create("runs", IdeaRunsExtension.class, project);
        }
    }
    
    /**
     * Configures the IDE integration to run runs as tasks from the IDE.
     *
     * @param project The project to configure.
     */
    public void apply(final Project project) {
        final IdeManagementExtension ideManager = project.getExtensions().getByType(IdeManagementExtension.class);
        project.afterEvaluate(evaluatedProject -> {
            ideManager.apply(new RunsImportAction());
        });
    }

    public void configureIdeaConventions(Project project, IDEA ideaConventions) {
        final Project rootProject = project.getRootProject();
        final IdeaModel ideaModel = rootProject.getExtensions().getByType(IdeaModel.class);
        final IdeaProject ideaProject = ideaModel.getProject();
        final ExtensionAware extensionAware = (ExtensionAware) ideaProject;
        final IdeaRunsExtension runsExtension = extensionAware.getExtensions().getByType(IdeaRunsExtension.class);

        runsExtension.getRunWithIdea().convention(
                ideaConventions.getShouldUseCompilerDetection()
                        .map(useCompilerDetection -> {
                            if (!useCompilerDetection) {
                                return false;
                            }

                            final File DotIdeaDirectory = new File(project.getProjectDir(), ".idea");
                            final File GradleXml = new File(DotIdeaDirectory, "gradle.xml");
                            return FileUtils.contains(GradleXml, "<option name=\"delegatedBuild\" value=\"false\" />");
                        })
        );

        runsExtension.getOutDirectory().convention(
                ideaConventions.getCompilerOutputDir()
        );
    }

    private static final class RunsImportAction implements IdeManagementExtension.IdeImportAction {
        
        @Override
        public void idea(Project project, IdeaModel idea, ProjectSettings ideaExtension) {
            final RunConfigurationContainer ideaRuns = ((ExtensionAware) ideaExtension).getExtensions().getByType(RunConfigurationContainer.class);
            
            project.getExtensions().configure(RunsConstants.Extensions.RUNS, (Action<NamedDomainObjectContainer<Run>>) runs -> runs.getAsMap().forEach((name, run) -> {
                final String nameWithoutSpaces = name.replace(" ", "-");
                final String runName = StringUtils.capitalize(project.getName() + ": " + StringUtils.capitalize(nameWithoutSpaces));
                
                final RunImpl runImpl = (RunImpl) run;

                //Do not generate a run configuration for unit tests
                if (runImpl.getIsJUnit().get())
                    return;

                final IdeaRunExtension runIdeaConfig = run.getExtensions().getByType(IdeaRunExtension.class);
                final TaskProvider<?> ideBeforeRunTask = createIdeBeforeRunTask(project, nameWithoutSpaces, run, runImpl);
                
                ideaRuns.register(runName, Application.class, ideaRun -> {
                    runImpl.getWorkingDirectory().get().getAsFile().mkdirs();
                    
                    ideaRun.setMainClass(runImpl.getMainClass().get());
                    ideaRun.setWorkingDirectory(runImpl.getWorkingDirectory().get().getAsFile().getAbsolutePath());
                    ideaRun.setJvmArgs(quoteAndJoin(runImpl.realiseJvmArguments()));
                    ideaRun.moduleRef(project, runIdeaConfig.getPrimarySourceSet().get());
                    ideaRun.setProgramParameters(quoteAndJoin(runImpl.getProgramArguments().get()));
                    ideaRun.setEnvs(adaptEnvironment(runImpl, RunsUtil::buildRunWithIdeaModClasses));
                    ideaRun.setShortenCommandLine(ShortenCommandLine.ARGS_FILE);
                    
                    ideaRun.beforeRun(beforeRuns -> {
                        beforeRuns.create("Build", Make.class);
                        
                        beforeRuns.create("Prepare Run", GradleTask.class, gradleTask -> {
                            gradleTask.setTask(ideBeforeRunTask.get());
                        });
                    });
                });
            }));
            
            
        }

        @Override
        public void eclipse(Project project, EclipseModel eclipse) {
            ProjectUtils.afterEvaluate(project, () -> {
                project.getExtensions().configure(RunsConstants.Extensions.RUNS, (Action<NamedDomainObjectContainer<Run>>) runs -> runs.getAsMap().forEach((name, run) -> {
                    final String runName = StringUtils.capitalize(project.getName() + " - " + StringUtils.capitalize(name.replace(" ", "-")));
                    
                    final RunImpl runImpl = (RunImpl) run;

                    //Do not generate a run configuration for unit tests
                    if (runImpl.getIsJUnit().get())
                        return;

                    final TaskProvider<?> ideBeforeRunTask = createIdeBeforeRunTask(project, name, run, runImpl);
                    final List<TaskProvider<?>> copyProcessResourcesTasks = createEclipseCopyResourcesTasks(eclipse, run);
                    ideBeforeRunTask.configure(task -> copyProcessResourcesTasks.forEach(t -> task.dependsOn(t)));
                    
                    try {
                        final GradleLaunchConfig idePreRunTask = GradleLaunchConfig.builder(eclipse.getProject().getName())
                                                                         .tasks(ideBeforeRunTask.get().getName())
                                                                         .build();
                        
                        final String gradleName = "Prepare " + runName;
                        writeLaunchToFile(project, gradleName, idePreRunTask);
                        
                        final JavaApplicationLaunchConfig debugRun =
                                JavaApplicationLaunchConfig.builder(eclipse.getProject().getName())
                                        .workingDirectory(runImpl.getWorkingDirectory().get().getAsFile().getAbsolutePath())
                                        .vmArgs(quoteStream(runImpl.realiseJvmArguments()).toArray(String[]::new))
                                        .args(quoteStream(runImpl.getProgramArguments().get()).toArray(String[]::new))
                                        .envVar(adaptEnvironment(runImpl, RunsUtil::buildRunWithEclipseModClasses))
                                        .useArgumentsFile()
                                        .build(runImpl.getMainClass().get());
                        
                        final String debugName = "Run " + runName;
                        writeLaunchToFile(project, debugName, debugRun);
                        
                        writeLaunchToFile(project, runName,
                                LaunchGroup.builder()
                                        .entry(LaunchGroup.entry(gradleName)
                                                       .enabled(true)
                                                       .adoptIfRunning(false)
                                                       .mode(LaunchGroup.Mode.RUN)
                                                       .action(LaunchGroup.Action.delay(2)))
                                        .entry(LaunchGroup.entry(debugName)
                                                       .enabled(true)
                                                       .adoptIfRunning(false)
                                                       .mode(LaunchGroup.Mode.DEBUG)
                                                       .action(LaunchGroup.Action.none()))
                                        .build());
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to write launch files: " + runName, e);
                    }
                }));
            });
        }

        private static String quoteAndJoin(List<String> args) {
            return quoteStream(args).collect(Collectors.joining(" "));
        }

        private static Stream<String> quoteStream(List<String> args) {
            return args.stream().map(RunsImportAction::quote);
        }

        /**
         * This expects users to escape quotes in their system arguments on their own, which matches
         * Gradles own behavior when used in JavaExec.
         */
        private static String quote(String arg) {
            if (!arg.contains(" ")) {
                return arg;
            }
            return "\"" + arg + "\"";
        }

        private TaskProvider<?> createIdeBeforeRunTask(Project project, String name, Run run, RunImpl runImpl) {
            final TaskProvider<?> ideBeforeRunTask = project.getTasks().register(CommonRuntimeUtils.buildTaskName("ideBeforeRun", name), task -> {
                RunsUtil.addRunSourcesDependenciesToTask(task, run);
            });
            
            if (!runImpl.getTaskDependencies().isEmpty()) {
                ideBeforeRunTask.configure(task -> {
                    runImpl.getTaskDependencies().forEach(dep -> {
                        //noinspection Convert2MethodRef Creates a compiler error regarding incompatible types.
                        task.dependsOn(dep);
                    });
                });
            }
            
            return ideBeforeRunTask;
        }

        private List<TaskProvider<?>> createEclipseCopyResourcesTasks(EclipseModel eclipse, Run run) {
            final List<TaskProvider<?>> copyProcessResources = new ArrayList<>();
            for (SourceSet sourceSet : run.getModSources().get()) {
                final Project sourceSetProject = SourceSetUtils.getProject(sourceSet);

                final String taskName = CommonRuntimeUtils.buildTaskName("eclipseCopy", sourceSet.getProcessResourcesTaskName());
                final TaskProvider<?> eclipseResourcesTask;

                if (sourceSetProject.getTasks().findByName(taskName) != null) {
                    eclipseResourcesTask = sourceSetProject.getTasks().named(taskName);
                }
                else {
                    eclipseResourcesTask = sourceSetProject.getTasks().register(taskName, Copy.class, task -> {
                        final TaskProvider<ProcessResources> defaultProcessResources = sourceSetProject.getTasks().named(sourceSet.getProcessResourcesTaskName(), ProcessResources.class);
                        task.from(defaultProcessResources.get().getDestinationDir());
                        Path outputDir = eclipse.getClasspath().getDefaultOutputDir().toPath();
                        if (outputDir.endsWith("default")) {
                            // sometimes it has default value from org.gradle.plugins.ide.eclipse.internal.EclipsePluginConstants#DEFAULT_PROJECT_OUTPUT_PATH
                            // which has /default on end that is not present in the final outputDir in eclipse/buildship
                            // (output of getDefaultOutputDir() should be just project/bin/)
                            outputDir = outputDir.getParent();
                        }
                        task.into(outputDir.resolve(sourceSet.getName()));

                        task.dependsOn(defaultProcessResources);
                    });
                }

                copyProcessResources.add(eclipseResourcesTask);
            }
            return copyProcessResources;
        }

        private static void writeLaunchToFile(Project project, String fileName, LaunchConfig config) {
            final File file = project.file(String.format(".eclipse/configurations/%s.launch", fileName));
            file.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(file, false)) {
                config.write(writer);
            }
            catch (IOException e) {
                throw new UncheckedIOException("Failed to write launch file: " + fileName, e);
            }
            catch (XMLStreamException e) {
                throw new RuntimeException("Failed to write launch file: " + fileName, e);
            }
        }
        
        private static Map<String, String> adaptEnvironment(
                final RunImpl run,
                final Function<ListProperty<SourceSet>, Provider<String>> modClassesProvider
                ) {
            final Map<String, String> environment = new HashMap<>(run.getEnvironmentVariables().get());
            environment.put("MOD_CLASSES", modClassesProvider.apply(run.getModSources()).get());
            return environment;
        }
    }
}
