// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.kotlin.tools.projectWizard.plugins.buildSystem

import com.intellij.openapi.util.text.StringUtil
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.kotlin.tools.projectWizard.KotlinNewProjectWizardBundle
import org.jetbrains.kotlin.tools.projectWizard.core.*
import org.jetbrains.kotlin.tools.projectWizard.core.entity.PipelineTask
import org.jetbrains.kotlin.tools.projectWizard.core.entity.properties.Property
import org.jetbrains.kotlin.tools.projectWizard.core.entity.ValidationResult
import org.jetbrains.kotlin.tools.projectWizard.core.entity.settings.PluginSetting
import org.jetbrains.kotlin.tools.projectWizard.core.entity.settings.SettingDefaultValue
import org.jetbrains.kotlin.tools.projectWizard.core.service.BuildSystemAvailabilityWizardService
import org.jetbrains.kotlin.tools.projectWizard.core.service.FileSystemWizardService
import org.jetbrains.kotlin.tools.projectWizard.core.service.ProjectImportingWizardService
import org.jetbrains.kotlin.tools.projectWizard.ir.buildsystem.*
import org.jetbrains.kotlin.tools.projectWizard.library.MavenArtifact
import org.jetbrains.kotlin.tools.projectWizard.phases.GenerationPhase
import org.jetbrains.kotlin.tools.projectWizard.plugins.StructurePlugin
import org.jetbrains.kotlin.tools.projectWizard.plugins.kotlin.KotlinPlugin
import org.jetbrains.kotlin.tools.projectWizard.plugins.kotlin.ProjectKind
import org.jetbrains.kotlin.tools.projectWizard.plugins.printer.BuildFilePrinter
import org.jetbrains.kotlin.tools.projectWizard.plugins.printer.printBuildFile
import org.jetbrains.kotlin.tools.projectWizard.plugins.projectPath
import org.jetbrains.kotlin.tools.projectWizard.plugins.templates.TemplatesPlugin
import org.jetbrains.kotlin.tools.projectWizard.settings.DisplayableSettingItem
import org.jetbrains.kotlin.tools.projectWizard.settings.buildsystem.DefaultRepository
import org.jetbrains.kotlin.tools.projectWizard.settings.buildsystem.Repository
import org.jetbrains.kotlin.tools.projectWizard.settings.buildsystem.updateBuildFiles

abstract class BuildSystemPlugin(context: Context) : Plugin(context) {
    override val path = pluginPath

    companion object : PluginSettingsOwner() {
        override val pluginPath = "buildSystem"

        val type by enumSetting<BuildSystemType>(
            KotlinNewProjectWizardBundle.message("plugin.buildsystem.setting.type"),
            GenerationPhase.FIRST_STEP,
        ) {
            fun Reader.isBuildSystemAvailable(type: BuildSystemType): Boolean =
                service<BuildSystemAvailabilityWizardService>().isAvailable(type)

            isSavable = true
            values = BuildSystemType.ALL_BY_PRIORITY.toImmutableList()
            defaultValue = SettingDefaultValue.Dynamic {
                values.firstOrNull { isBuildSystemAvailable(it) }
            }

            filter = { _, type -> isBuildSystemAvailable(type) }

            validate { buildSystemType ->
                val projectKind = KotlinPlugin.projectKind.notRequiredSettingValue ?: ProjectKind.Multiplatform
                if (buildSystemType in projectKind.supportedBuildSystems && isBuildSystemAvailable(buildSystemType)) {
                    ValidationResult.OK
                } else {
                    ValidationResult.ValidationError(
                        KotlinNewProjectWizardBundle.message(
                            "plugin.buildsystem.setting.type.error.wrong.project.kind",
                            StringUtil.capitalize(projectKind.shortName),
                            buildSystemType.fullText
                        )
                    )
                }
            }
        }

        val buildSystemData by property<List<BuildSystemData>>(emptyList())

        val buildFiles by listProperty<BuildFileIR>()

        val pluginRepositories by listProperty<Repository>()

        val takeRepositoriesFromDependencies by pipelineTask(GenerationPhase.PROJECT_GENERATION) {
            runBefore(createModules)
            runAfter(TemplatesPlugin.postApplyTemplatesToModules)

            withAction {
                updateBuildFiles { buildFile ->
                    val dependenciesOfModule = buildList<LibraryDependencyIR> {
                        buildFile.modules.modules.forEach { module ->
                            if (module is SingleplatformModuleIR) module.sourcesets.forEach { sourceset ->
                                +sourceset.irs.filterIsInstance<LibraryDependencyIR>()
                            }
                            +module.irs.filterIsInstance<LibraryDependencyIR>()
                        }
                    }
                    val repositoriesToAdd = dependenciesOfModule.mapNotNull { dependency ->
                        dependency.artifact.safeAs<MavenArtifact>()?.repositories?.map(::RepositoryIR)
                    }.flatten()
                    buildFile.withIrs(repositoriesToAdd).asSuccess()
                }
            }
        }

        val createModules by pipelineTask(GenerationPhase.PROJECT_GENERATION) {
            runAfter(StructurePlugin.createProjectDir)
            withAction {
                val fileSystem = service<FileSystemWizardService>()
                val data = buildSystemData.propertyValue.first { it.type == buildSystemType }
                val buildFileData = data.buildFileData ?: return@withAction UNIT_SUCCESS
                buildFiles.propertyValue.mapSequenceIgnore { buildFile ->
                    fileSystem.createFile(
                        buildFile.directoryPath / buildFileData.buildFileName,
                        buildFileData.createPrinter().printBuildFile { buildFile.render(this) }
                    )
                }
            }
        }

        val importProject by pipelineTask(GenerationPhase.PROJECT_IMPORT) {
            runAfter(createModules)
            withAction {
                val data = buildSystemData.propertyValue.first { it.type == buildSystemType }
                service<ProjectImportingWizardService> { service -> service.isSuitableFor(data.type) }
                    .importProject(this, StructurePlugin.projectPath.settingValue, allIRModules, buildSystemType)
            }
        }
    }

    override val settings: List<PluginSetting<*, *>> = listOf(
        type,
    )
    override val pipelineTasks: List<PipelineTask> = listOf(
        takeRepositoriesFromDependencies,
        createModules,
        importProject,
    )
    override val properties: List<Property<*>> = listOf(
        buildSystemData,
        buildFiles,
        pluginRepositories
    )
}

fun Reader.getPluginRepositoriesWithDefaultOnes(): List<Repository> {
    val allRepositories = BuildSystemPlugin.pluginRepositories.propertyValue + buildSystemType.getDefaultPluginRepositories()
    return allRepositories.filterOutOnlyDefaultPluginRepositories(buildSystemType)
}

private fun List<Repository>.filterOutOnlyDefaultPluginRepositories(buildSystem: BuildSystemType): List<Repository> {
    val isAllDefault = all { it.isDefaultPluginRepository(buildSystem) }
    return if (isAllDefault) emptyList() else this
}

private fun Repository.isDefaultPluginRepository(buildSystem: BuildSystemType) =
    this in buildSystem.getDefaultPluginRepositories()

fun PluginSettingsOwner.addBuildSystemData(data: BuildSystemData) = pipelineTask(GenerationPhase.PREPARE) {
    runBefore(BuildSystemPlugin.createModules)
    withAction {
        BuildSystemPlugin.buildSystemData.addValues(data)
    }
}

data class BuildSystemData(
    val type: BuildSystemType,
    val buildFileData: BuildFileData?
)

data class BuildFileData(
    val createPrinter: () -> BuildFilePrinter,
    @NonNls val buildFileName: String
)

enum class BuildSystemType(
    @Nls override val text: String,
    @NonNls val id: String, // used for FUS, should never be changed
    val fullText: String = text
) : DisplayableSettingItem {
    GradleKotlinDsl(
        text = KotlinNewProjectWizardBundle.message("buildsystem.type.gradle.kotlin"),
        id = "gradleKotlin"
    ),
    GradleGroovyDsl(
        text = KotlinNewProjectWizardBundle.message("buildsystem.type.gradle.groovy"),
        id = "gradleGroovy"
    ),
    Jps(
        text = KotlinNewProjectWizardBundle.message("buildsystem.type.intellij"),
        id = "jps",
        fullText = KotlinNewProjectWizardBundle.message("buildsystem.type.intellij.full")
    ),
    Maven(
        text = KotlinNewProjectWizardBundle.message("buildsystem.type.maven"),
        id = "maven"
    );

    override val greyText: String?
        get() = null

    companion object {
        val ALL_GRADLE = setOf(GradleKotlinDsl, GradleGroovyDsl)
        val ALL_BY_PRIORITY = setOf(GradleKotlinDsl, GradleGroovyDsl)
    }
}

val BuildSystemType.isGradle
    get() = this == BuildSystemType.GradleGroovyDsl
            || this == BuildSystemType.GradleKotlinDsl

val Reader.allIRModules
    get() = BuildSystemPlugin.buildFiles.propertyValue.flatMap { buildFile ->
        buildFile.modules.modules
    }

val Writer.allModulesPaths
    get() = BuildSystemPlugin.buildFiles.propertyValue.flatMap { buildFile ->
        val paths = when (val structure = buildFile.modules) {
            is MultiplatformModulesStructureIR -> listOf(buildFile.directoryPath)
            else -> structure.modules.map { it.path }
        }
        paths.mapNotNull { path ->
            projectPath.relativize(path)
                .takeIf { it.toString().isNotBlank() }
                ?.toList()
                ?.takeIf { it.isNotEmpty() }
        }
    }

fun BuildSystemType.getDefaultPluginRepositories(): List<DefaultRepository> = when (this) {
    BuildSystemType.GradleKotlinDsl, BuildSystemType.GradleGroovyDsl -> listOf(DefaultRepository.GRADLE_PLUGIN_PORTAL)
    BuildSystemType.Maven -> listOf(DefaultRepository.MAVEN_CENTRAL)
    BuildSystemType.Jps -> emptyList()
}

val Reader.buildSystemType: BuildSystemType
    get() = BuildSystemPlugin.type.settingValue

