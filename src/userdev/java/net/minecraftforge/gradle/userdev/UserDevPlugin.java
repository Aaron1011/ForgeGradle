/*
 * ForgeGradle
 * Copyright (C) 2018 Forge Development LLC
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */

package net.minecraftforge.gradle.userdev;

import com.amadornes.artifactural.api.artifact.ArtifactIdentifier;
import com.amadornes.artifactural.base.artifact.SimpleArtifactIdentifier;
import net.minecraftforge.gradle.common.util.*;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.NamedDomainObjectFactory;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencySet;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.logging.Logger;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;

import net.minecraftforge.gradle.common.task.DownloadAssets;
import net.minecraftforge.gradle.common.task.DownloadMCMeta;
import net.minecraftforge.gradle.common.task.DownloadMavenArtifact;
import net.minecraftforge.gradle.common.task.ExtractMCPData;
import net.minecraftforge.gradle.common.task.ExtractNatives;
import net.minecraftforge.gradle.mcp.MCPRepo;
import net.minecraftforge.gradle.userdev.tasks.GenerateSRG;
import net.minecraftforge.gradle.userdev.tasks.RenameJarInPlace;
import org.gradle.plugins.ide.eclipse.GenerateEclipseClasspath;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;

public class UserDevPlugin implements Plugin<Project> {
    private static String MINECRAFT = "minecraft";
    private static String DEOBF = "deobf";

    @Override
    public void apply(@Nonnull Project project) {
        @SuppressWarnings("unused")
        final Logger logger = project.getLogger();
        final UserDevExtension extension = project.getExtensions().create("minecraft", UserDevExtension.class, project);
        if (project.getPluginManager().findPlugin("java") == null) {
            project.getPluginManager().apply("java");
        }
        final File natives_folder = project.file("build/natives/");

        NamedDomainObjectContainer<RenameJarInPlace> reobf = project.container(RenameJarInPlace.class, new NamedDomainObjectFactory<RenameJarInPlace>() {
            @Override
            public RenameJarInPlace create(String jarName) {
                String name = Character.toUpperCase(jarName.charAt(0)) + jarName.substring(1);
                JavaPluginConvention java = (JavaPluginConvention)project.getConvention().getPlugins().get("java");

                final RenameJarInPlace task = project.getTasks().maybeCreate("reobf" + name, RenameJarInPlace.class);
                task.setClasspath(java.getSourceSets().getByName("main").getCompileClasspath());

                project.getTasks().getByName("assemble").dependsOn(task);

                // do after-Evaluate resolution, for the same of good error reporting
                project.afterEvaluate(p -> {
                    Task jar = project.getTasks().getByName(jarName);
                    if (!(jar instanceof Jar))
                        throw new IllegalStateException(jarName + "  is not a jar task. Can only reobf jars!");
                    task.setInput(((Jar)jar).getArchivePath());
                    task.dependsOn(jar);
                });

                return task;
            }
        });
        project.getExtensions().add("reobf", reobf);

        Configuration minecraft = project.getConfigurations().maybeCreate(MINECRAFT);
        Configuration compile = project.getConfigurations().maybeCreate("compile");
        Configuration deobf = project.getConfigurations().maybeCreate(DEOBF);
        compile.extendsFrom(minecraft);
        compile.extendsFrom(deobf);

        TaskProvider<DownloadMavenArtifact> downloadMcpConfig = project.getTasks().register("downloadMcpConfig", DownloadMavenArtifact.class);
        TaskProvider<ExtractMCPData> extractSrg = project.getTasks().register("extractSrg", ExtractMCPData.class);
        TaskProvider<GenerateSRG> createMcpToSrg = project.getTasks().register("createMcpToSrg", GenerateSRG.class);
        TaskProvider<DownloadMCMeta> downloadMCMeta = project.getTasks().register("downloadMCMeta", DownloadMCMeta.class);
        TaskProvider<ExtractNatives> extractNatives = project.getTasks().register("extractNatives", ExtractNatives.class);
        TaskProvider<DownloadAssets> downloadAssets = project.getTasks().register("downloadAssets", DownloadAssets.class);

        extractSrg.configure(task -> {
            task.dependsOn(downloadMcpConfig);
            task.setConfig(downloadMcpConfig.get().getOutput());
        });

        createMcpToSrg.configure(task -> {
            task.setReverse(true);
            task.dependsOn(extractSrg);
            task.setSrg(extractSrg.get().getOutput());
            task.setMappings(extension.getMappings());
        });

        extractNatives.configure(task -> {
            task.dependsOn(downloadMCMeta.get());
            task.setMeta(downloadMCMeta.get().getOutput());
            task.setOutput(natives_folder);
        });
        downloadAssets.configure(task -> {
            task.dependsOn(downloadMCMeta.get());
            task.setMeta(downloadMCMeta.get().getOutput());
        });

        project.afterEvaluate(p -> {
            MinecraftUserRepo mcrepo = null;
            ModRemapingRepo deobfrepo = null;

            //TODO: UserDevRepo deobf = new UserDevRepo(project);

            List<ArtifactIdentifier> toAdd = new ArrayList<>();

            DependencySet deps = minecraft.getDependencies();
            for (Dependency dep : deps.stream().collect(Collectors.toList())) {
                if (!(dep instanceof ExternalModuleDependency))
                    throw new IllegalArgumentException("minecraft dependency must be a maven dependency.");
                if (mcrepo != null)
                    throw new IllegalArgumentException("Only allows one minecraft dependancy.");
                deps.remove(dep);

                mcrepo = new MinecraftUserRepo(p, dep.getGroup(), dep.getName(), dep.getVersion(), extension.getAccessTransformers(), extension.getMappings());
                String newDep = mcrepo.getDependencyString();
                p.getLogger().lifecycle("New Dep: " + newDep);
                ExternalModuleDependency ext = (ExternalModuleDependency)p.getDependencies().create(newDep);
                {
                    ext.setChanging(true); //TODO: Remove when not in dev
                    minecraft.resolutionStrategy(strat -> {
                        strat.cacheChangingModulesFor(10, TimeUnit.SECONDS);
                    });
                }
                minecraft.getDependencies().add(ext);

                // Hack to avoid running tasks during dependency resolution
                // We explicitly 'look up' all of the possible dependencies here, during
                // plugin initialization. This will cause any uncached jars to be built (e.g. decomp,
                // recomp).
                //
                // This ensures that when we catually need to result dependencies via BaseRepo#getArtifact,
                // we'll have already built all of the jars we need to. This bypassess all of the nasty
                // deadlock issues that come from running Gradle tasks in a dependency resolver.

                toAdd.add(new SimpleArtifactIdentifier(ext.getGroup(), ext.getName(), ext.getVersion(), null, "pom"));
                toAdd.add(new SimpleArtifactIdentifier(ext.getGroup(), ext.getName(), ext.getVersion(), null, ""));
                toAdd.add(new SimpleArtifactIdentifier(ext.getGroup(), ext.getName(), ext.getVersion(), "sources", null));
            }

            deps = deobf.getDependencies();
            for (Dependency dep : deps.stream().collect(Collectors.toList())) {
                if (!(dep instanceof ExternalModuleDependency)) //TODO: File deps as well.
                    throw new IllegalArgumentException("deobf dependency must be a maven dependency. File deps are on the TODO");
                deps.remove(dep);

                if (deobfrepo == null)
                    deobfrepo = new ModRemapingRepo(p, extension.getMappings());
                String newDep = deobfrepo.addDep(dep.getGroup(), dep.getName(), dep.getVersion()); // Classifier?
                deobf.getDependencies().add(p.getDependencies().create(newDep));
            }

            // We have to add these AFTER our repo so that we get called first, this is annoying...
            new BaseRepo.Builder()
                .add(mcrepo)
                .add(deobfrepo)
                .add(MCPRepo.create(project))
                .add(MinecraftRepo.create(project)) //Provides vanilla extra/slim/data jars. These don't care about OBF names.
                .attach(project);
            project.getRepositories().maven(e -> {
                e.setUrl(Utils.FORGE_MAVEN);
            });
            project.getRepositories().maven(e -> {
                e.setUrl("https://libraries.minecraft.net/");
                e.metadataSources(src -> src.artifact());
            });
            project.getRepositories().mavenCentral(); //Needed for MCP Deps
            if (mcrepo == null)
                throw new IllegalStateException("Missing 'minecraft' dependency entry.");
            mcrepo.validate(minecraft, extension.getRuns(), extractNatives.get().getOutput(), downloadAssets.get().getOutput(), toAdd); //This will set the MC_VERSION property.

            String mcVer = (String)project.getExtensions().getExtraProperties().get("MC_VERSION");
            String mcpVer = (String)project.getExtensions().getExtraProperties().get("MCP_VERSION");
            downloadMcpConfig.get().setArtifact("de.oceanlabs.mcp:mcp_config:" + mcpVer + "@zip");
            downloadMCMeta.get().setMCVersion(mcVer);

            RenameJarInPlace reobfJar  = reobf.create("jar");
            reobfJar.dependsOn(createMcpToSrg);
            reobfJar.setMappings(createMcpToSrg.get().getOutput());

            createRunConfigsTasks(project, extractNatives.get(), downloadAssets.get(), extension.getRuns());
        });
    }

    private void createRunConfigsTasks(@Nonnull Project project, ExtractNatives extractNatives, DownloadAssets downloadAssets, Map<String, RunConfig> runs)
    {
        project.getTasks().withType(GenerateEclipseClasspath.class, t -> { t.dependsOn(extractNatives, downloadAssets); });
        // Utility task to abstract the prerequisites when using the intellij run generation
        TaskProvider<Task> prepareRun = project.getTasks().register("prepareRun", Task.class);
        prepareRun.configure(task -> {
            task.dependsOn(project.getTasks().getByName("classes"), extractNatives, downloadAssets);
        });

        VersionJson json = null;

        try {
            json = Utils.loadJson(extractNatives.getMeta(), VersionJson.class);
        }
        catch (IOException e) {}

        List<String> additionalClientArgs = json != null ? json.getPlatformJvmArgs() : Collections.emptyList();

        runs.forEach((name, runConfig) -> {
            if (runConfig.isClient())
                runConfig.jvmArgs(additionalClientArgs);

            String taskName = name.replaceAll("[^a-zA-Z0-9\\-_]","");
            if (!taskName.startsWith("run"))
                taskName = "run" + taskName.substring(0,1).toUpperCase() + taskName.substring(1);
            TaskProvider<JavaExec> runTask = project.getTasks().register(taskName, JavaExec.class);
            runTask.configure(task -> {
                task.dependsOn(prepareRun.get());

                task.setMain(runConfig.getMain());
                task.setArgs(runConfig.getArgs());
                task.systemProperties(runConfig.getProperties());
                task.environment(runConfig.getEnvironment());
                task.jvmArgs(runConfig.getJvmArgs());

                String workDir = runConfig.getWorkingDirectory();
                File file = new File(workDir);
                if(!file.exists())
                    file.mkdirs();

                task.setWorkingDir(workDir);

                JavaPluginConvention java = (JavaPluginConvention)project.getConvention().getPlugins().get("java");
                task.setClasspath(java.getSourceSets().getByName("main").getRuntimeClasspath());
            });
        });

        EclipseHacks.doEclipseFixes(project, extractNatives, downloadAssets, runs);
        IntellijUtils.createIntellijRunsTask(project, extractNatives, downloadAssets, prepareRun.get(), runs);
    }
}
