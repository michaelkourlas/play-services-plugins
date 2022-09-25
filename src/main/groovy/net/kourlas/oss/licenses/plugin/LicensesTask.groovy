/**
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.kourlas.oss.licenses.plugin

import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.slf4j.LoggerFactory

import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Task to find available licenses from the artifacts stored in the json
 * file generated by DependencyTask, and then generate the third_party_licenses
 * and third_party_license_metadata file.
 */
abstract class LicensesTask extends DefaultTask {
    private static final String UTF_8 = "UTF-8"
    private static final byte[] LINE_SEPARATOR = System
            .getProperty("line.separator").getBytes(UTF_8)
    private static final int GRANULAR_BASE_VERSION = 14
    private static final String GOOGLE_PLAY_SERVICES_GROUP =
            "com.google.android.gms"
    private static final String LICENSE_ARTIFACT_SUFFIX = "-license"
    private static final String FIREBASE_GROUP = "com.google.firebase"
    private static final String FAIL_READING_LICENSES_ERROR =
            "Failed to read license text."

    private static final logger = LoggerFactory.getLogger(LicensesTask.class)

    protected Set<ExtendedArtifactInfo> extendedArtifactInfoSet = []

    protected int start = 0
    protected Set<String> googleServiceLicenses = []
    protected Map<String, String> licensesMap = [:]
    protected Map<String, String> licenseOffsets = [:]
    protected static final String ABSENT_DEPENDENCY_KEY = "Debug License Info"
    protected static final String ABSENT_DEPENDENCY_TEXT = ("Licenses are " +
            "only provided in build variants " +
            "(e.g. release) where the Android Gradle Plugin " +
            "generates an app dependency list.")

    @InputFile
    abstract RegularFileProperty getDependenciesJson()

    @OutputFile
    File extendedDependenciesJson

    @OutputDirectory
    File rawResourceDir

    @OutputFile
    File licenses

    @OutputFile
    File licensesMetadata

    @TaskAction
    void action() {
        initOutputDir()
        initLicenseFile()
        initLicensesMetadata()
        initExtendedDependenciesJson()

        File dependenciesJsonFile = dependenciesJson.asFile.get()
        def artifactInfoSet = loadDependenciesJson(dependenciesJsonFile)

        if (DependencyUtil.ABSENT_ARTIFACT in artifactInfoSet) {
            if (artifactInfoSet.size() > 1) {
                throw new IllegalStateException("artifactInfoSet that contains EMPTY_ARTIFACT should not contain other artifacts.")
            }
            addDebugLicense()
        } else {
            for (artifactInfo in artifactInfoSet) {
                if (isGoogleServices(artifactInfo.group)) {
                    // Add license info for google-play-services itself
                    if (!artifactInfo.name.endsWith(LICENSE_ARTIFACT_SUFFIX)) {
                        addLicensesFromPom(artifactInfo)
                    }
                    // Add transitive licenses info for google-play-services. For
                    // post-granular versions, this is located in the artifact
                    // itself, whereas for pre-granular versions, this information
                    // is located at the complementary license artifact as a runtime
                    // dependency.
                    if (isGranularVersion(artifactInfo.version) || artifactInfo.name.endsWith(LICENSE_ARTIFACT_SUFFIX)) {
                        addGooglePlayServiceLicenses(artifactInfo)
                    }
                } else {
                    addLicensesFromPom(artifactInfo)
                }
            }
        }

        writeMetadata()
        writeExtendedDependenciesJson()
    }

    private static Set<ArtifactInfo> loadDependenciesJson(File jsonFile) {
        def allDependencies = new JsonSlurper().parse(jsonFile)
        def artifactInfoSet = new HashSet<ArtifactInfo>()
        for (entry in allDependencies) {
            ArtifactInfo artifactInfo = artifactInfoFromEntry(entry)
            artifactInfoSet.add(artifactInfo)
        }
        artifactInfoSet.asImmutable()
    }

    private void addDebugLicense() {
        appendDependency(
                ABSENT_DEPENDENCY_KEY,
                ABSENT_DEPENDENCY_TEXT.getBytes(UTF_8)
        )
    }

    protected void initOutputDir() {
        if (!rawResourceDir.exists()) {
            rawResourceDir.mkdirs()
        }
    }

    protected void initLicenseFile() {
        if (licenses == null) {
            logger.error("License file is undefined")
        }
        licenses.newWriter().withWriter { w ->
            w << ''
        }
    }

    protected void initLicensesMetadata() {
        licensesMetadata.newWriter().withWriter { w ->
            w << ''
        }
    }

    protected void initExtendedDependenciesJson() {
        extendedDependenciesJson.newWriter().withWriter { w ->
            w << ''
        }
    }

    protected static boolean isGoogleServices(String group) {
        return (GOOGLE_PLAY_SERVICES_GROUP.equalsIgnoreCase(group)
                || FIREBASE_GROUP.equalsIgnoreCase(group))
    }

    protected static boolean isGranularVersion(String version) {
        String[] versions = version.split("\\.")
        return (versions.length > 0
                && Integer.valueOf(versions[0]) >= GRANULAR_BASE_VERSION)
    }

    protected void addGooglePlayServiceLicenses(ArtifactInfo artifactInfo) {
        File artifactFile = DependencyUtil.getLibraryFile(getProject(), artifactInfo)
        if (artifactFile == null) {
            logger.warn("Unable to find Google Play Services Artifact for $artifactInfo")
            return
        }
        addGooglePlayServiceLicenses(artifactFile)
    }

    protected void addGooglePlayServiceLicenses(File artifactFile) {
        ZipFile licensesZip = new ZipFile(artifactFile)

        ZipEntry jsonFile = licensesZip.getEntry("third_party_licenses.json")
        ZipEntry txtFile = licensesZip.getEntry("third_party_licenses.txt")

        if (!jsonFile || !txtFile) {
            return
        }

        JsonSlurper jsonSlurper = new JsonSlurper()
        Object licensesObj = licensesZip.getInputStream(jsonFile).withCloseable {
            jsonSlurper.parse(it)
        }
        if (licensesObj == null) {
            return
        }

        for (entry in licensesObj) {
            String key = entry.key
            int startValue = entry.value.start
            int lengthValue = entry.value.length

            if (!googleServiceLicenses.contains(key)) {
                licensesZip.getInputStream(txtFile).withCloseable {
                    byte[] content = getBytesFromInputStream(
                            it,
                            startValue,
                            lengthValue)
                    googleServiceLicenses.add(key)
                    appendDependency(key, content)
                }
            }
        }
    }

    protected static byte[] getBytesFromInputStream(
            InputStream stream,
            long offset,
            int length) {
        try {
            byte[] buffer = new byte[1024]
            ByteArrayOutputStream textArray = new ByteArrayOutputStream()

            stream.skip(offset)
            int bytesRemaining = length > 0 ? length : Integer.MAX_VALUE
            int bytes = 0

            while (bytesRemaining > 0
                    && (bytes =
                    stream.read(
                            buffer,
                            0,
                            Math.min(bytesRemaining, buffer.length)))
                    != -1) {
                textArray.write(buffer, 0, bytes)
                bytesRemaining -= bytes
            }
            stream.close()

            return textArray.toByteArray()
        } catch (Exception e) {
            throw new RuntimeException(FAIL_READING_LICENSES_ERROR, e)
        }
    }

    protected void addLicensesFromPom(ArtifactInfo artifactInfo) {
        def pomFile = DependencyUtil.resolvePomFileArtifact(getProject(), artifactInfo)
        addLicensesFromPom((File) pomFile, artifactInfo.group, artifactInfo.name, artifactInfo.version)
    }

    protected void addLicensesFromPom(File pomFile, String group, String name, String version) {
        if (pomFile == null || !pomFile.exists()) {
            logger.error("POM file $pomFile for $group:$name does not exist.")
            return
        }

        def rootNode = new XmlSlurper().parse(pomFile)
        if (rootNode.licenses.size() == 0) {
            return
        }

        String libraryName = rootNode.name
        String licenseKey = "${group}:${name}"
        if (libraryName == null || libraryName.isBlank()) {
            libraryName = licenseKey
        }
        if (rootNode.licenses.license.size() > 1) {
            rootNode.licenses.license.each { license ->
                String licenseName = license.name
                String licenseUrl = license.url
                appendDependency(
                        new Dependency("${licenseKey} ${licenseName}", libraryName, group, name, version, licenseName),
                        licenseUrl.getBytes(UTF_8))
            }
        } else {
            String nodeName = rootNode.licenses.license.name
            String nodeUrl = rootNode.licenses.license.url
            appendDependency(new Dependency(licenseKey, libraryName, group, name, version, nodeName), nodeUrl.getBytes(UTF_8))
        }
    }

    protected void appendDependency(String key, byte[] license) {
        appendDependency(new Dependency(key, key, "", "", "", ""), license)
    }

    protected void appendDependency(Dependency dependency, byte[] license) {
        String licenseText = new String(license, UTF_8)
        if (licensesMap.containsKey(dependency.key)) {
            return
        }

        String offsets
        if (licenseOffsets.containsKey(licenseText)) {
            offsets = licenseOffsets.get(licenseText)
        } else {
            offsets = "${start}:${license.length}"
            licenseOffsets.put(licenseText, offsets)
            appendLicenseContent(license)
            appendLicenseContent(LINE_SEPARATOR)
        }
        licensesMap.put(dependency.key, dependency.buildLicensesMetadata(offsets))

        extendedArtifactInfoSet.add(
                new ExtendedArtifactInfo(
                        dependency.groupId,
                        dependency.artifactId,
                        dependency.version,
                        dependency.name,
                        dependency.licenseName))
    }

    protected void appendLicenseContent(byte[] content) {
        licenses.append(content)
        start += content.length
    }

    protected void writeMetadata() {
        for (entry in licensesMap) {
            licensesMetadata.append(entry.value, UTF_8)
            licensesMetadata.append(LINE_SEPARATOR)
        }
    }

    protected void writeExtendedDependenciesJson() {
        extendedDependenciesJson.newWriter().withWriter {
            it.write(new JsonBuilder(extendedArtifactInfoSet).toPrettyString())
        }
    }

    static ArtifactInfo artifactInfoFromEntry(Object entry) {
        return new ArtifactInfo(entry.group, entry.name, entry.version)
    }

    protected static class Dependency {
        String key
        String name
        String groupId
        String artifactId
        String version
        String licenseName

        Dependency(String key, String name, String groupId, String artifactId, String version, String licenseName) {
            this.key = key
            this.name = name
            this.groupId = groupId
            this.artifactId = artifactId
            this.version = version
            this.licenseName = licenseName
        }

        String buildLicensesMetadata(String offset) {
            return "$offset $name"
        }
    }
}
