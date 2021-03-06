package io.github.lizhangqu.veridex

import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.internal.pipeline.TransformTask
import groovy.io.FileType
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskCollection

/**
 * @author lizhangqu
 */
class VeridexPlugin implements Plugin<Project> {

    @SuppressWarnings("UnnecessaryQualifiedReference")
    @Override
    void apply(Project project) {
        if (!project.plugins.hasPlugin('com.android.application')) {
            return
        }

        def variants = null;
        if (project.plugins.hasPlugin('com.android.application')) {
            variants = project.android.getApplicationVariants()
        } else if (project.plugins.hasPlugin('com.android.library')) {
            variants = project.android.getLibraryVariants()
        }

        VeridexHelper veridexHelper = new VeridexHelper(project)
        variants?.all { BaseVariant variant ->
            def variantName = variant.getName()
            project.tasks.create("veridex${variantName.capitalize()}") {
                setGroup("veridex")
                dependsOn project.tasks.findByName("assemble${variantName.capitalize()}")
                doLast {
                    def mergeNativeLibsTask = VeridexPlugin.findMergeNativeLibsTask(project, variantName)
                    Map<String, File> bundles = new HashMap<>()
                    mergeNativeLibsTask?.getOutputs()?.files?.each {
                        if (it.isFile()) {
                            VeridexPlugin.collectBundle(project, it, bundles)
                        } else if (it.isDirectory()) {
                            it.eachFileRecurse(FileType.FILES) {
                                VeridexPlugin.collectBundle(project, it, bundles)
                            }
                        }
                    }
                    bundles.each { String md5, File bundleFile ->
                        veridexHelper.execute(bundleFile)
                    }

                    File apkFile = VeridexPlugin.getApkFile(project, variant)
                    veridexHelper.execute(apkFile)
                }
            }

        }
    }

    static File getApkFile(Project project, BaseVariant variant) {
        def variantData = variant.getMetaClass().getProperty(variant, 'variantData')
        def variantScope = variantData.getScope()
        try {
            //>=3.3.0
            String taskName = variantScope.getTaskContainer().getPackageAndroidTask().getName()
            def packageApplication = project.getTasks().findByName(taskName)
            return new File(packageApplication.getOutputDirectory(), packageApplication.getMetaClass().getProperty(packageApplication, "outputScope").getMainSplit().getOutputFileName() - '-unaligned')
        } catch (Exception e) {

        }

        try {
            //>=3.0.0
            def packageApplication = variant.getPackageApplication()
            return new File(packageApplication.getOutputDirectory(), packageApplication.getMetaClass().getProperty(packageApplication, "outputScope").getMainSplit().getOutputFileName() - '-unaligned')
        } catch (Exception e) {

        }

        try {
            //>=2.3.3
            File apkFile = variantData.getOutputs().get(0).getScope().getFinalPackage()
            return new File(apkFile.getAbsolutePath() - "-unaligned")
        } catch (Exception e1) {

        }
        try {
            //>=2.2.3
            File apkFile = variantData.getOutputs().get(0).getScope().getFinalApk()
            return new File(apkFile.getAbsolutePath() - "-unaligned")
        } catch (Exception e2) {

        }
        try {
            //<2.2.3
            File apkFile = variantData.getOutputs().get(0).getScope().getPackageApk()
            return new File(apkFile.getAbsolutePath() - "-unaligned")
        } catch (Exception e3) {
        }

        return null
    }

    static boolean collectBundle(Project project, File file, Map<String, File> bundles) {
        if (file.getParentFile().getName() != 'zip-cache' && isBundle(file)) {
            String bundleMd5 = file.withInputStream {
                //noinspection UnnecessaryQualifiedReference
                new java.security.DigestInputStream(it, java.security.MessageDigest.getInstance('MD5')).withStream {
                    it.eachByte {}
                    it.messageDigest.digest().encodeHex() as String
                }
            }
            bundles.put(bundleMd5, file)
            return true
        }
        return false
    }

    static Task findMergeNativeLibsTask(Project project, String variantName) {
        def mergeNativeLibsTask = findTransformTaskByTransformName(project, variantName, "mergeJniLibs")
        if (mergeNativeLibsTask == null) {
            mergeNativeLibsTask = project.getTasks().findByName(("merge${variantName.capitalize()}NativeLibs"))
        }
        return mergeNativeLibsTask
    }

    static Task findTransformTaskByTransformName(Project project, String variantName, String name) {
        TaskCollection<TransformTask> transformTasks = project.getTasks().withType(TransformTask.class)
        Task transformTask = null
        transformTasks.each {
            if (it.getVariantName() == variantName && it.getTransform().getName() == name) {
                transformTask = it
            }
        }
        return transformTask
    }

    static boolean isBundle(File file) {
        if (file == null) {
            return false
        }
        if (file.length() < 4) {
            return false
        }
        FileInputStream fileInputStream = null
        try {
            fileInputStream = new FileInputStream(file)
            byte[] buffer = new byte[4];
            int read = fileInputStream.read(buffer, 0, 4)
            //0x504b0304
            if (read == 4
                    && buffer[0] == (byte) 0x50
                    && buffer[1] == (byte) 0x4b
                    && buffer[2] == (byte) 0x03
                    && buffer[3] == (byte) 0x04) {
                return true
            }
        } catch (IOException e) {
            e.printStackTrace()
        } finally {
            fileInputStream?.close()
        }
        return false
    }

}