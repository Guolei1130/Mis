package com.eastwood.tools.plugins.mis

import com.android.build.gradle.BaseExtension
import com.eastwood.tools.plugins.mis.core.*
import com.eastwood.tools.plugins.mis.core.extension.MisExtension
import com.eastwood.tools.plugins.mis.core.extension.OnPublicationListener
import com.eastwood.tools.plugins.mis.core.extension.Publication
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.maven.MavenPublication

class MisPlugin implements Plugin<Project> {

    PublicationManager publicationManager

    Project project
    boolean isMicroModule
    boolean initMisSrcDir
    boolean executePublish
    String publishPublication

    Map<String, Publication> publicationPublishMap

    void apply(Project project) {

        if (!MisUtil.hasAndroidPlugin(project)) {
            throw new GradleException("The android or android-library plugin must be applied to the project.")
        }

        this.project = project

        project.gradle.getStartParameter().taskNames.each {
            if(it.startsWith('publishMis')) {
                executePublish = true
                publishPublication = it.substring(it.indexOf('[') + 1, it.lastIndexOf(']'))
            }
        }

        initPublicationManager()

        this.publicationPublishMap = new HashMap<>()
        this.isMicroModule = MisUtil.isMicroModule(project)

        OnPublicationListener onPublicationListener = new OnPublicationListener() {
            @Override
            void onPublicationCreated(Publication publication) {
                if(!initMisSrcDir) {
                    MisUtil.addMisSourceSets(project)
                    initMisSrcDir = true
                }

                initPublication(publication)
                addPublicationDependencies(publication)

                if(executePublish && publishPublication == publication.artifactId) {
                    publicationPublishMap.put(publication.artifactId, publication)
                } else {
                    if (publication.version != null && !publication.version.isEmpty()) {
                        handleMavenJar(project, publication)
                    } else {
                        handleLocalJar(project, publication)
                    }
                    publicationManager.hitPublication(publication)
                }
            }
        }
        MisExtension misExtension = project.extensions.create('mis', MisExtension, project, onPublicationListener)

        project.dependencies.metaClass.misPublication { Object value ->
            if(executePublish) {
                return []
            }

            def groupId, artifactId, version
            if (value instanceof String) {
                String[] values = value.split(":")
                if (values.length >= 3) {
                    groupId = values[0]
                    artifactId = values[1]
                    version = values[2]
                } else if (values.length == 2) {
                    groupId = values[0]
                    artifactId = values[1]
                    version = null
                }
            } else if (value instanceof Map<String, ?>) {
                groupId = value.groupId
                artifactId = value.artifactId
                version = value.version
            }

            if (groupId != null && artifactId != null) {
                return handleMisPublication(groupId, artifactId, version)
            } else {
                throw new IllegalArgumentException("'${value}' is illege argument of misPublication(), the following types/formats are supported:" +
                        "\n  - String or CharSequence values, for example 'org.gradle:gradle-core:1.0'." +
                        "\n  - Maps, for example [groupId: 'org.gradle', artifactId: 'gradle-core', version: '1.0'].")
            }
        }

        project.afterEvaluate {
            MisUtil.addMisSourceSets(project)

            if (publicationPublishMap.size() == 0) {
                return
            }

            project.plugins.apply('maven-publish')
            def publishing = project.extensions.getByName('publishing')
            if (misExtension.configure != null) {
                publishing.repositories misExtension.configure
            }

            publicationPublishMap.each {
                createPublishTask(it.value)
            }
        }
    }

    def initPublicationManager() {
        publicationManager = PublicationManager.getInstance()

        if(publicationManager.hasLoadManifest) {
            return
        }
        publicationManager.loadManifest(project.rootProject, executePublish)
    }

    Object handleMisPublication(String groupId, String artifactId, String version) {
        String fileName = artifactId + ".jar"
        File target = project.rootProject.file(".gradle/mis/" + groupId + "/" + fileName)
        if (target.exists()) {
            return project.files(target)
        } else {
            def result, resultVersion
            Publication publication = publicationManager.getPublication(groupId, artifactId)
            if (publication == null || publication.version == "") {
                if (version == null) {
                    result = []
                    resultVersion = null
                } else {
                    result = "${groupId}:${artifactId}:${version}"
                    resultVersion = version
                }
            } else {
                result = "${groupId}:${artifactId}:${publication.version}"
                resultVersion = publication.version
            }

            project.gradle.buildFinished {
                publication = publicationManager.getPublication(groupId, artifactId)
                if (publication == null) {
                    if(version == null) {
                        throw new GradleException("Could not find " + groupId + ":" + artifactId + ".")
                    }
                } else if (result == [] || publication.version != resultVersion) {
                    if (!publication.invalid) {
                        throw new GradleException("Please Sync Project with Gradle files again.")
                    }
                }
            }
            return result
        }
    }

    def handleLocalJar(Project project, Publication publication) {
        File targetGroup = project.rootProject.file(".gradle/mis/" + publication.groupId)
        if (!targetGroup.exists()) {
            targetGroup.mkdirs()
        }

        File target = new File(targetGroup, publication.artifactId + ".jar")
        if (target.exists()) {
            boolean hasModifiedSource = publicationManager.hasModified(publication)
            if (!hasModifiedSource) {
                project.dependencies {
                    implementation project.files(target)
                }
                return
            }
        }

        File releaseJar = JarUtil.packJavaSourceJar(project, publication)
        if (releaseJar == null) {
            publication.invalid = true
            publicationManager.addPublication(publication)
            if (target.exists()) {
                target.delete()
            }
            return
        }

        MisUtil.copyFile(releaseJar, target)
        project.dependencies {
            implementation project.files(target)
        }
        publicationManager.addPublication(publication)
    }

    def handleMavenJar(Project project, Publication publication) {
        publicationPublishMap.put(publication.artifactId, publication)
        boolean hasModifiedSource = publicationManager.hasModified(publication)
        File targetGroup = project.rootProject.file(".gradle/mis/" + publication.groupId)
        File target = new File(targetGroup, publication.artifactId + ".jar")
        if (target.exists()) {
            if (!hasModifiedSource) {
                project.dependencies {
                    implementation project.files(target)
                }
                return
            }
        } else if (!hasModifiedSource) {
            project.dependencies {
                implementation publication.groupId + ':' + publication.artifactId + ':' + publication.version
            }
            return
        }

        def releaseJar = JarUtil.packJavaSourceJar(project, publication)
        if (releaseJar == null) {
            publication.invalid = true
            if (target.exists()) {
                target.delete()
            }
            return
        }

        boolean equals = JarUtil.compareMavenJar(project, publication, releaseJar.absolutePath)
        if (equals) {
            target.delete()
            project.dependencies {
                implementation publication.groupId + ':' + publication.artifactId + ':' + publication.version
            }
        } else {
            targetGroup = project.rootProject.file(".gradle/mis/" + publication.groupId)
            if (!targetGroup.exists()) {
                targetGroup.mkdirs()
            }
            target = new File(targetGroup, publication.artifactId + ".jar")
            MisUtil.copyFile(releaseJar, target)
            project.dependencies {
                implementation project.files(target)
            }
        }
        publicationManager.addPublication(publication)
    }

    void initPublication(Publication publication) {
        String displayName = project.getDisplayName()
        publication.project = displayName.substring(displayName.indexOf("'") + 1, displayName.lastIndexOf("'"))
        def buildMis = new File(project.projectDir, 'build/mis')
        if (isMicroModule) {
            def result = publication.name.split(":")
            if (result.length >= 1) {
                publication.microModuleName = result[0]
            }
            if (publication.microModuleName == null || publication.microModuleName == "") {
                throw new IllegalArgumentException("Publication name '${publication.name}' is illegal. The correct format is '\${MicroModule Name}:\${SourceSet Name}', e.g. 'base:main' or 'base'.")
            }

            publication.sourceSetName = result.length >= 1 ? "main" : result[1]
            if (publication.sourceSetName == '') {
                publication.sourceSetName = 'main'
            }
            publication.buildDir = new File(buildMis, publication.microModuleName + '/' + publication.sourceSetName)
        } else {
            publication.sourceSetName = publication.name
            publication.buildDir = new File(buildMis, publication.name)
        }

        List<String> paths = new ArrayList<>()
        BaseExtension android = project.extensions.getByName('android')
        def sourceSets = android.sourceSets.getByName(publication.sourceSetName)
        sourceSets.aidl.srcDirs.each {
            if (!it.absolutePath.endsWith("mis")) return

            if (publication.microModuleName != null) {
                if (it.absolutePath.endsWith(publication.microModuleName + "${File.separator}src${File.separator + publication.sourceSetName + File.separator}mis")) {
                    paths.add(it.absolutePath)
                }
            } else {
                paths.add(it.absolutePath)
            }
        }

        publication.sourceSets = new HashMap<>()
        paths.each {
            SourceSet sourceSet = new SourceSet()
            sourceSet.path = it
            sourceSet.lastModifiedSourceFile = new HashMap<>()
            project.fileTree(it).each {
                if (it.name.endsWith('.java')) {
                    SourceFile sourceFile = new SourceFile()
                    sourceFile.path = it.path
                    sourceFile.lastModified = it.lastModified()
                    sourceSet.lastModifiedSourceFile.put(sourceFile.path, sourceFile)
                }
            }
            publication.sourceSets.put(sourceSet.path, sourceSet)
        }
    }

    void addPublicationDependencies(Publication publication) {
        if(publication.dependencies == null) return
        project.dependencies {
            if(publication.dependencies.compileOnly != null) {
                publication.dependencies.compileOnly.each {
                    implementation it
                }
            }
            if(publication.dependencies.implementation != null) {
                publication.dependencies.implementation.each {
                    implementation it
                }
            }
        }
    }

    void createPublishTask(Publication publication) {
        def publicationName = 'Mis[' + publication.artifactId + "]"
        createPublishingPublication(publication, publicationName)
        String publishMavenRepositoryTaskName = "publish" + publicationName + "PublicationToMavenRepository"
        String publishMavenLocalTaskName = "publish" + publicationName + "PublicationToMavenLocal"
        project.tasks.whenTaskAdded {
            if (it.name == publishMavenRepositoryTaskName || it.name == publishMavenLocalTaskName) {
                def taskName = 'compileMis[' + publication.artifactId + ']Source'
                def compileTask = project.getTasks().findByName(taskName)
                if (compileTask == null) {
                    compileTask = project.getTasks().create(taskName, CompileMisTask.class)
                    compileTask.publication = publication
                    compileTask.dependsOn 'clean'
                    it.dependsOn compileTask
                    it.doLast {
                        File groupDir = project.rootProject.file(".gradle/mis/" + publication.groupId)
                        new File(groupDir, publication.artifactId + ".jar").delete()
                    }
                }
            }
        }
    }

    void createPublishingPublication(Publication publication, String publicationName) {
        def publishing = project.extensions.getByName('publishing')
        MavenPublication mavenPublication = publishing.publications.maybeCreate(publicationName, MavenPublication)
        mavenPublication.groupId = publication.groupId
        mavenPublication.artifactId = publication.artifactId
        mavenPublication.version = publication.version
        mavenPublication.pom.packaging = 'jar'

        def outputsDir = new File(publication.buildDir, "outputs")
        mavenPublication.artifact source: new File(outputsDir, "classes.jar")
        mavenPublication.artifact source: new File(outputsDir, "classes-source.jar"), classifier: 'sources'

        if (publication.dependencies != null) {
            mavenPublication.pom.withXml {
                def dependenciesNode = asNode().appendNode('dependencies')
                if (publication.dependencies.implementation != null) {
                    publication.dependencies.implementation.each {
                        def gav = it.split(":")
                        def dependencyNode = dependenciesNode.appendNode('dependency')
                        dependencyNode.appendNode('groupId', gav[0])
                        dependencyNode.appendNode('artifactId', gav[1])
                        dependencyNode.appendNode('version', gav[2])
                        dependencyNode.appendNode('scope', 'implementation')
                    }
                }
            }
        }

    }

}