/*
 * Copyright (c) 2021 by Gerrit Grunwald
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

package eu.hansolo.fx.jdkmon.tools;

import eu.hansolo.fx.jdkmon.Main.SemverUri;
import eu.hansolo.fx.jdkmon.tools.Records.JdkInfo;
import eu.hansolo.fx.jdkmon.tools.Records.SysInfo;
import eu.hansolo.jdktools.Architecture;
import eu.hansolo.jdktools.LibCType;
import eu.hansolo.jdktools.OperatingMode;
import eu.hansolo.jdktools.OperatingSystem;
import eu.hansolo.jdktools.scopes.BuildScope;
import eu.hansolo.jdktools.util.OutputFormat;
import eu.hansolo.jdktools.versioning.Semver;
import eu.hansolo.jdktools.versioning.VersionNumber;
import io.foojay.api.discoclient.DiscoClient;
import io.foojay.api.discoclient.pkg.Feature;
import io.foojay.api.discoclient.pkg.Pkg;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;


public class Finder {
    public static final  String          MACOS_JAVA_INSTALL_PATH   = "/System/Volumes/Data/Library/Java/JavaVirtualMachines/";
    public static final  String          WINDOWS_JAVA_INSTALL_PATH = "C:\\Program Files\\Java\\";
    public static final  String          LINUX_JAVA_INSTALL_PATH   = "/usr/lib/jvm";
    private static final Pattern         GRAALVM_VERSION_PATTERN   = Pattern.compile("(.*graalvm\\s)(.*)(\\s\\(.*)");
    private static final Matcher         GRAALVM_VERSION_MATCHER   = GRAALVM_VERSION_PATTERN.matcher("");
    private static final Pattern         ZULU_BUILD_PATTERN        = Pattern.compile("\\((build\\s)(.*)\\)");
    private static final Matcher         ZULU_BUILD_MATCHER        = ZULU_BUILD_PATTERN.matcher("");
    private static final String[]        MAC_JAVA_HOME_CMDS        = { "/bin/sh", "-c", "echo $JAVA_HOME" };
    private static final String[]        LINUX_JAVA_HOME_CMDS      = { "/bin/sh", "-c", "echo $JAVA_HOME" };
    private static final String[]        WIN_JAVA_HOME_CMDS        = { "cmd.exe", "/c", "echo %JAVA_HOME%" };
    private static final String[]        DETECT_ALPINE_CMDS        = { "/bin/sh", "-c", "cat /etc/os-release | grep 'NAME=' | grep -ic 'Alpine'" };
    private static final String[]        UX_DETECT_ARCH_CMDS       = { "/bin/sh", "-c", "uname -m" };
    private static final String[]        MAC_DETECT_ROSETTA2_CMDS  = { "/bin/sh", "-c", "sysctl -in sysctl.proc_translated" };
    private static final String[]        WIN_DETECT_ARCH_CMDS      = { "cmd.exe", "/c", "SET Processor" };
    private static final Pattern         ARCHITECTURE_PATTERN      = Pattern.compile("(PROCESSOR_ARCHITECTURE)=([a-zA-Z0-9_\\-]+)");
    private static final Matcher         ARCHITECTURE_MATCHER      = ARCHITECTURE_PATTERN.matcher("");
    private              ExecutorService service                   = Executors.newSingleThreadExecutor();
    private              Properties      releaseProperties         = new Properties();
    private              OperatingSystem operatingSystem           = detectOperatingSystem();
    private              Architecture    architecture              = detectArchitecture();
    private              String          javaFile                  = OperatingSystem.WINDOWS == operatingSystem ? "java.exe" : "java";
    private              String          javaHome                  = "";
    private              String          javafxPropertiesFile      = "javafx.properties";
    private              boolean         isAlpine                  = false;
    private              DiscoClient     discoclient;


    public Finder() {
        this(new DiscoClient("JDKMon"));
    }
    public Finder(final DiscoClient discoclient) {
        this.discoclient = discoclient;
        getJavaHome();
        if (this.javaHome.isEmpty()) { this.javaHome = System.getProperties().get("java.home").toString(); }
        checkIfAlpineLinux();
    }


    public Set<Distro> getDistributions(final List<String> searchPaths) {
        Set<Distro> distros = new HashSet<>();

        if (null == searchPaths || searchPaths.isEmpty()) { return distros; }

        if (service.isShutdown()) {
            service = Executors.newSingleThreadExecutor();
        }

        searchPaths.forEach(searchPath -> {
            final Path       path            = Paths.get(searchPath);
            final boolean    handledBySdkman = searchPath.equals(Detector.SDKMAN_FOLDER);
            final List<Path> javaFiles       = findByFileName(path, javaFile);
            // TODO: make sure that no duplicates are detected e.g. Zulu JDK+FX also detects a JRE in the subfolder, check for existing folders
            javaFiles.stream().filter(java -> !java.toString().contains("jre")).forEach(java -> checkForDistribution(java.toString(), distros, handledBySdkman));
            //javaFiles.stream().forEach(java -> checkForDistribution(java.toString(), distros, handledBySdkman));
        });
        service.shutdown();
        try {
            service.awaitTermination(5000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return distros;
    }

    public Map<Distro, List<Pkg>> getAvailableUpdates(final List<Distro> distributions) {
        Map<Distro, List<Pkg>> distrosToUpdate = new ConcurrentHashMap<>();
        //List<CompletableFuture<Void>> updateFutures   = Collections.synchronizedList(new ArrayList<>());
        //distributions.forEach(distribution -> updateFutures.add(discoclient.updateAvailableForAsync(DiscoClient.getDistributionFromText(distribution.getApiString()), Semver.fromText(distribution.getVersion()).getSemver1(), Architecture.fromText(distribution.getArchitecture()), distribution.getFxBundled(), null).thenAccept(pkgs -> distrosToUpdate.put(distribution, pkgs))));
        //CompletableFuture.allOf(updateFutures.toArray(new CompletableFuture[updateFutures.size()])).join();

        // Show unknown builds of OpenJDK
        final boolean showUnknownBuildsOfOpenJDK = PropertyManager.INSTANCE.getBoolean(PropertyManager.PROPERTY_SHOW_UNKNOWN_BUILDS, false);
        distributions.stream()
                     .filter(Objects::nonNull)
                     .filter(distro ->  showUnknownBuildsOfOpenJDK ? distro.getName() != null : !distro.getName().equals(Constants.UNKNOWN_BUILD_OF_OPENJDK))
                     .forEach(distribution -> {
            List<Pkg> availableUpdates = discoclient.updateAvailableFor(DiscoClient.getDistributionFromText(distribution.getApiString()), Semver.fromText(distribution.getVersion()).getSemver1(), operatingSystem, Architecture.fromText(distribution.getArchitecture()), distribution.getFxBundled(), null, distribution.getFeature().getApiString());

            if (null != availableUpdates) {
                distrosToUpdate.put(distribution, availableUpdates);
            }

            if (OperatingSystem.ALPINE_LINUX == operatingSystem) {
                availableUpdates = availableUpdates.stream().filter(pkg -> pkg.getLibCType() == LibCType.MUSL).collect(Collectors.toList());
            } else if (OperatingSystem.LINUX == operatingSystem) {
                availableUpdates = availableUpdates.stream().filter(pkg -> pkg.getLibCType() != LibCType.MUSL).collect(Collectors.toList());
            }

            if (Architecture.NOT_FOUND != architecture && !architecture.getSynonyms().isEmpty()) {
                availableUpdates = availableUpdates.stream().filter(pkg -> architecture.getSynonyms().contains(pkg.getArchitecture()) | pkg.getArchitecture() == architecture).collect(Collectors.toList());
            }

            distrosToUpdate.put(distribution, availableUpdates);
        });

        // Check if there are newer versions from other distributions
        distrosToUpdate.entrySet()
                       .stream()
                       .filter(entry -> !entry.getKey().getApiString().startsWith("graal"))
                       .filter(entry -> !entry.getKey().getApiString().equals("mandrel"))
                       .filter(entry -> !entry.getKey().getApiString().equals("liberica_native"))
                       .forEach(entry -> {
                            if (entry.getValue().isEmpty()) {
                                Distro distro = entry.getKey();
                                entry.setValue(discoclient.updateAvailableFor(null, Semver.fromText(distro.getVersion()).getSemver1(), Architecture.fromText(distro.getArchitecture()), distro.getFxBundled()));
                            }
                       });

        LinkedHashMap<Distro, List < Pkg >> sorted = new LinkedHashMap<>();
        distrosToUpdate.entrySet()
                       .stream()
                       .sorted(Map.Entry.comparingByKey(Comparator.comparing(Distro::getName)))
                       .forEachOrdered(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return sorted;
    }

    public OperatingSystem getOperatingSystem() { return operatingSystem; }

    public Architecture getArchitecture() { return architecture; }

    public static final OperatingSystem detectOperatingSystem() {
        final String os = Constants.OS_NAME_PROPERTY.toLowerCase();
        if (os.indexOf("win") >= 0) {
            return OperatingSystem.WINDOWS;
        } else if (os.indexOf("mac") >= 0) {
            return OperatingSystem.MACOS;
        } else if (os.indexOf("nix") >= 0 || os.indexOf("nux") >= 0) {
            try {
                final ProcessBuilder processBuilder = new ProcessBuilder(DETECT_ALPINE_CMDS);
                final Process        process        = processBuilder.start();
                final String         result         = new BufferedReader(new InputStreamReader(process.getInputStream())).lines().collect(Collectors.joining("\n"));
                return null == result ? OperatingSystem.LINUX : result.equals("1") ? OperatingSystem.ALPINE_LINUX : OperatingSystem.LINUX;
            } catch (IOException e) {
                e.printStackTrace();
                return OperatingSystem.LINUX;
            }
        } else if (os.indexOf("sunos") >= 0) {
            return OperatingSystem.SOLARIS;
        } else {
            return OperatingSystem.NOT_FOUND;
        }
    }

    public static final Architecture detectArchitecture() {
        final OperatingSystem operatingSystem = detectOperatingSystem();
        try {
            final ProcessBuilder processBuilder = OperatingSystem.WINDOWS == operatingSystem ? new ProcessBuilder(WIN_DETECT_ARCH_CMDS) : new ProcessBuilder(UX_DETECT_ARCH_CMDS);
            final Process        process        = processBuilder.start();
            final String         result         = new BufferedReader(new InputStreamReader(process.getInputStream())).lines().collect(Collectors.joining("\n"));
            switch(operatingSystem) {
                case WINDOWS -> {
                    ARCHITECTURE_MATCHER.reset(result);
                    final List<MatchResult> results     = ARCHITECTURE_MATCHER.results().collect(Collectors.toList());
                    final int               noOfResults = results.size();
                    if (noOfResults > 0) {
                        final MatchResult   res = results.get(0);
                        return Architecture.fromText(res.group(2));
                    } else {
                        return Architecture.NOT_FOUND;
                    }
                }
                case MACOS -> {
                    return Architecture.fromText(result);
                }
                case LINUX -> {
                    return Architecture.fromText(result);
                }
            }

            // If not found yet try via system property
            final String arch = Constants.OS_ARCH_PROPERTY.toLowerCase(Locale.ENGLISH);
            if (arch.contains("sparc")) return Architecture.SPARC;
            if (arch.contains("amd64") || arch.contains("86_64")) return Architecture.AMD64;
            if (arch.contains("86")) return Architecture.X86;
            if (arch.contains("s390x")) return Architecture.S390X;
            if (arch.contains("ppc64")) return Architecture.PPC64;
            if (arch.contains("arm") && arch.contains("64")) return Architecture.AARCH64;
            if (arch.contains("arm")) return Architecture.ARM;
            if (arch.contains("aarch64")) return Architecture.AARCH64;
            return Architecture.NOT_FOUND;
        } catch (IOException e) {
            e.printStackTrace();
            return Architecture.NOT_FOUND;
        }
    }

    public static final SysInfo getOperaringSystemArchitectureOperatingMode() {
        final OperatingSystem operatingSystem = detectOperatingSystem();
        try {
            final ProcessBuilder processBuilder = OperatingSystem.WINDOWS == operatingSystem ? new ProcessBuilder(WIN_DETECT_ARCH_CMDS) : new ProcessBuilder(UX_DETECT_ARCH_CMDS);
            final Process        process        = processBuilder.start();
            final String         result         = new BufferedReader(new InputStreamReader(process.getInputStream())).lines().collect(Collectors.joining("\n"));
            switch(operatingSystem) {
                case WINDOWS -> {
                    ARCHITECTURE_MATCHER.reset(result);
                    final List<MatchResult> results     = ARCHITECTURE_MATCHER.results().collect(Collectors.toList());
                    final int               noOfResults = results.size();
                    if (noOfResults > 0) {
                        final MatchResult   res = results.get(0);
                        return new SysInfo(operatingSystem, Architecture.fromText(res.group(2)), OperatingMode.NATIVE);
                    } else {
                        return new SysInfo(operatingSystem, Architecture.NOT_FOUND, OperatingMode.NOT_FOUND);
                    }
                }
                case MACOS -> {
                    Architecture architecture = Architecture.fromText(result);
                    final ProcessBuilder processBuilder1 = new ProcessBuilder(MAC_DETECT_ROSETTA2_CMDS);
                    final Process        process1        = processBuilder1.start();
                    final String         result1         = new BufferedReader(new InputStreamReader(process1.getInputStream())).lines().collect(Collectors.joining("\n"));
                    return new SysInfo(operatingSystem, architecture, result1.equals("1") ? OperatingMode.EMULATED : OperatingMode.NATIVE);
                }
                case LINUX -> {
                    return new SysInfo(operatingSystem, Architecture.fromText(result), OperatingMode.NATIVE);
                }
            }

            // If not found yet try via system property
            final String arch = System.getProperty("os.arch").toLowerCase(Locale.ENGLISH);
            if (arch.contains("sparc"))                           { return new SysInfo(operatingSystem, Architecture.SPARC, OperatingMode.NATIVE); }
            if (arch.contains("amd64") || arch.contains("86_64")) { return new SysInfo(operatingSystem, Architecture.AMD64, OperatingMode.NATIVE); }
            if (arch.contains("86"))                              { return new SysInfo(operatingSystem, Architecture.X86, OperatingMode.NATIVE); }
            if (arch.contains("s390x"))                           { return new SysInfo(operatingSystem, Architecture.S390X, OperatingMode.NATIVE); }
            if (arch.contains("ppc64"))                           { return new SysInfo(operatingSystem, Architecture.PPC64, OperatingMode.NATIVE); }
            if (arch.contains("arm") && arch.contains("64"))      { return new SysInfo(operatingSystem, Architecture.AARCH64, OperatingMode.NATIVE); }
            if (arch.contains("arm"))                             { return new SysInfo(operatingSystem, Architecture.ARM, OperatingMode.NATIVE); }
            if (arch.contains("aarch64"))                         { return new SysInfo(operatingSystem, Architecture.AARCH64, OperatingMode.NATIVE); }
            return new SysInfo(operatingSystem, Architecture.NOT_FOUND, OperatingMode.NATIVE);
        } catch (IOException e) {
            e.printStackTrace();
            return new SysInfo(operatingSystem, Architecture.NOT_FOUND, OperatingMode.NATIVE);
        }
    }

    private JdkInfo getJDKFromJar(final String jarFileName) {
        try {
            final JarFile                        jarFile      = new JarFile(jarFileName);
            final Manifest                       manifest     = jarFile.getManifest();
            final Attributes                     attributes   = manifest.getMainAttributes();
            final Optional<Entry<Object,Object>> optCreatedBy = attributes.entrySet().stream().filter(entry -> entry.getKey().toString().equalsIgnoreCase("Created-By")).findFirst();
            final Optional<Entry<Object,Object>> optBuildJdk  = attributes.entrySet().stream().filter(entry -> entry.getKey().toString().equalsIgnoreCase("Build-Jdk")).findFirst();
            final String                         createdBy    = optCreatedBy.isPresent() ? optCreatedBy.get().getValue().toString() : "";
            final String                         buildJdk     = optBuildJdk.isPresent()  ? optBuildJdk.get().getValue().toString()  : "";
            return new JdkInfo(createdBy, buildJdk);
        } catch(IOException e) {
            return new JdkInfo("", "");
        }
    }

    private List<Path> findByFileName(final Path path, final String fileName) {
        List<Path> result;
        try (Stream<Path> pathStream = Files.find(path, Integer.MAX_VALUE, (p, basicFileAttributes) -> {
                                                      // if directory or no-read permission, ignore
                                                      if(Files.isDirectory(p) || !Files.isReadable(p)) { return false; }
                                                      return p.getFileName().toString().equalsIgnoreCase(fileName);
                                                  })
        ) {
            result = pathStream.collect(Collectors.toList());
        } catch (IOException e) {
            result = new ArrayList<>();
        }
        return result;
    }

    private List<Path> findFileByName(final Path path, final String filename) {
        final List<Path> result = new ArrayList<>();
        try {
            Files.walkFileTree(path, new HashSet<>(Arrays.asList(FileVisitOption.FOLLOW_LINKS)), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    final String name = file.getFileName().toString().toLowerCase();
                    if (filename.equals(name) && !Files.isSymbolicLink(file.toAbsolutePath())) { result.add(file); }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(final Path file, final IOException e) throws IOException { return FileVisitResult.SKIP_SUBTREE; }
                @Override public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) throws IOException {
                    return Files.isSymbolicLink(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            System.out.println(e);
            return result;
        }
        return result;
    }

    private void checkForDistribution(final String java, final Set<Distro> distros, final boolean handledBySdkman) {
        AtomicBoolean inUse = new AtomicBoolean(false);
        try {
            List<String> commands = new ArrayList<>();
            commands.add(java);
            commands.add("-version");

            final String fileSeparator = File.separator;
            final String binFolder     = new StringBuilder(fileSeparator).append("bin").append(fileSeparator).append(".*").toString();

            ProcessBuilder builder  = new ProcessBuilder(commands).redirectErrorStream(true);
            Process        process  = builder.start();
            Streamer streamer = new Streamer(process.getInputStream(), d -> {
                final String parentPath       = OperatingSystem.WINDOWS == operatingSystem ? java.replaceAll("bin\\\\java.exe", "") : java.replaceAll(binFolder, fileSeparator);
                final File   releaseFile      = new File(parentPath + "release");
                String[]     lines            = d.split("\\|");
                String       name             = "Unknown build of OpenJDK";
                String       apiString        = "";
                String       operatingSystem  = "";
                String       architecture     = "";
                Feature      feature          = Feature.NONE;
                Boolean      fxBundled        = Boolean.FALSE;
                List<String> modules          = new ArrayList<>();
                
                if (!this.javaHome.isEmpty() && !inUse.get() && parentPath.contains(javaHome)) {
                    inUse.set(true);
                }

                final File   jreLibExtFolder  = new File(new StringBuilder(parentPath).append("jre").append(fileSeparator).append("lib").append(fileSeparator).append("ext").toString());
                if (jreLibExtFolder.exists()) {
                    fxBundled = Stream.of(jreLibExtFolder.listFiles()).filter(file -> !file.isDirectory()).map(File::getName).collect(Collectors.toSet()).stream().filter(filename -> filename.equalsIgnoreCase("jfxrt.jar")).count() > 0;
                }
                final File   jmodsFolder      = new File(new StringBuilder(parentPath).append("jmods").toString());
                if (jmodsFolder.exists()) {
                    fxBundled = Stream.of(jmodsFolder.listFiles()).filter(file -> !file.isDirectory()).map(File::getName).collect(Collectors.toSet()).stream().filter(filename -> filename.startsWith("javafx")).count() > 0;
                }

                VersionNumber version    = null;
                VersionNumber jdkVersion = null;
                BuildScope    buildScope = BuildScope.BUILD_OF_OPEN_JDK;

                String        line1         = lines[0];
                String        line2         = lines[1];
                String        withoutPrefix = line1;
                if (line1.startsWith("openjdk")) {
                    withoutPrefix = line1.replaceFirst("openjdk version", "");
                } else if (line1.startsWith("java")) {
                    withoutPrefix = line1.replaceFirst("java version", "");
                    // Find new GraalVM build (former enterprise edition)
                    if (line2.contains("GraalVM")) {
                        name       = "GraalVM";
                        apiString  = "graalvm";
                        buildScope = BuildScope.BUILD_OF_GRAALVM;
                    } else {
                        name       = "Oracle";
                        apiString  = "oracle";
                    }
                }

                // Find new GraalVM community builds
                if (!apiString.equals("graalvm") && line2.contains("jvmci")) {
                    VersionNumber newGraalVMBuild = VersionNumber.fromText("23.0-b12");
                    VersionNumber graalvmBuildFound = VersionNumber.fromText(line2.substring(line2.indexOf("jvmci"), line2.length() - 1).replace("jvmci-", ""));
                    if (graalvmBuildFound.compareTo(newGraalVMBuild) >= 0) {
                        name       = "GraalVM Community";
                        apiString  = "graalvm_community";
                        buildScope = BuildScope.BUILD_OF_GRAALVM;
                    }
                }
                if (line2.contains("Zulu")) {
                    name      = "Zulu";
                    apiString = "zulu";
                    ZULU_BUILD_MATCHER.reset(line2);
                    final List<MatchResult> results = ZULU_BUILD_MATCHER.results().collect(Collectors.toList());
                    if (!results.isEmpty()) {
                        MatchResult result = results.get(0);
                        version = VersionNumber.fromText(result.group(2));
                    }
                } else if(line2.contains("Zing") || line2.contains("Prime")) {
                    name      = "ZuluPrime";
                    apiString = "zulu_prime";
                    final List<MatchResult> results = ZULU_BUILD_MATCHER.results().collect(Collectors.toList());
                    if (!results.isEmpty()) {
                        MatchResult result = results.get(0);
                        version = VersionNumber.fromText(result.group(2));
                    }
                } else if (line2.contains("Semeru")) {
                    if (line2.contains("Certified")) {
                        name      = "Semeru certified";
                        apiString = "semeru_certified";
                    } else {
                        name      = "Semeru";
                        apiString = "semeru";
                    }
                } else if (line2.contains("Tencent")) {
                    name      = "Kona";
                    apiString = "kona";
                } else if (line2.contains("Bisheng")) {
                    name      = "Bishenq";
                    apiString = "bisheng";
                } else if (line2.contains("Homebrew")) {
                    name      = "Homebrew";
                    apiString = "homebrew";
                } else if (line2.startsWith("Java(TM) SE")) {
                    name      = "Oracle";
                    apiString = "oracle";
                }

                if (null == version) {
                    final String versionNumberText = withoutPrefix.substring(withoutPrefix.indexOf("\"") + 1, withoutPrefix.lastIndexOf("\""));
                    final Semver semver            = Semver.fromText(versionNumberText).getSemver1();
                    version = VersionNumber.fromText(semver.toString(true));
                }
                VersionNumber graalVersion = version;

                releaseProperties.clear();
                if (releaseFile.exists()) {
                    try (FileInputStream propFile = new FileInputStream(releaseFile)) {
                        releaseProperties.load(propFile);
                    } catch (IOException ex) {
                        System.out.println("Error reading release properties file. " + ex);
                    }
                    if (!releaseProperties.isEmpty()) {
                        if (releaseProperties.containsKey("IMPLEMENTOR") && name.equals(Constants.UNKNOWN_BUILD_OF_OPENJDK)) {
                            switch(releaseProperties.getProperty("IMPLEMENTOR").replaceAll("\"", "")) {
                                case "AdoptOpenJDK"      -> { name = "Adopt OpenJDK";  apiString = "aoj"; }
                                case "Alibaba"           -> { name = "Dragonwell";     apiString = "dragonwell"; }
                                case "Amazon.com Inc."   -> { name = "Corretto";       apiString = "corretto"; }
                                case "Azul Systems, Inc."-> {
                                    if (releaseProperties.containsKey("IMPLEMENTOR_VERSION")) {
                                        final String implementorVersion = releaseProperties.getProperty("IMPLEMENTOR_VERSION");
                                        if (implementorVersion.startsWith("Zulu")) {
                                            name      = "Zulu";
                                            apiString = "zulu";
                                        } else if (implementorVersion.startsWith("Zing") || implementorVersion.startsWith("Prime")) {
                                            name      = "ZuluPrime";
                                            apiString = "zulu_prime";
                                        }
                                    }
                                }
                                case "mandrel"           -> { name = "Mandrel";        apiString = "mandrel"; }
                                case "Microsoft"         -> { name = "Microsoft";      apiString = "microsoft"; }
                                case "ojdkbuild"         -> { name = "OJDK Build";     apiString = "ojdk_build"; }
                                case "Oracle Corporation"-> { name = "Oracle OpenJDK"; apiString = "oracle_openjdk"; }
                                case "Red Hat, Inc."     -> { name = "Red Hat";        apiString = "redhat"; }
                                case "SAP SE"            -> { name = "SAP Machine";    apiString = "sap_machine"; }
                                case "OpenLogic"         -> { name = "OpenLogic";      apiString = "openlogic"; }
                                case "JetBrains s.r.o."  -> { name = "JetBrains";      apiString = "jetbrains"; }
                                case "Eclipse Foundation"-> { name = "Temurin";        apiString = "temurin"; }
                                case "Tencent"           -> { name = "Kona";           apiString = "kona"; }
                                case "Bisheng"           -> { name = "Bisheng";        apiString = "bisheng"; }
                                case "Debian"            -> { name = "Debian";         apiString = "debian"; }
                                case "Ubuntu"            -> { name = "Ubuntu";         apiString = "ubuntu"; }
                                case "Homebrew"          -> { name = "Homebrew";       apiString = "homebrew"; }
                                case "N/A"               -> { }/* Unknown */
                            }
                        }

                        if (releaseProperties.containsKey("OS_ARCH")) {
                            architecture = releaseProperties.getProperty("OS_ARCH").toLowerCase().replaceAll("\"", "");
                        }
                        
                        if (releaseProperties.containsKey("BUILD_TYPE")) {
                            switch(releaseProperties.getProperty("BUILD_TYPE").replaceAll("\"", "")) {
                                case "commercial" -> {
                                    name      = "Oracle";
                                    apiString = "oracle";
                                }
                            }
                        }
                        
                        if (releaseProperties.containsKey("JVM_VARIANT")) {
                            if (name == "Adopt OpenJDK") {
                                String jvmVariant = releaseProperties.getProperty("JVM_VARIANT").toLowerCase().replaceAll("\"", "");
                                if (jvmVariant.equals("dcevm")) {
                                    name      = "Trava OpenJDK";
                                    apiString = "trava";
                                } else if (jvmVariant.equals("openj9")) {
                                    name      = "Adopt OpenJDK J9";
                                    apiString = "aoj_openj9";
                                }
                            }
                        }

                        if (releaseProperties.containsKey("OS_NAME")) {
                            switch(releaseProperties.getProperty("OS_NAME").toLowerCase().replaceAll("\"", "")) {
                                case "darwin"  -> operatingSystem = "macos";
                                case "linux"   -> operatingSystem = "linux";
                                case "windows" -> operatingSystem = "windows";
                            }
                        }
                        if (releaseProperties.containsKey("MODULES") && !fxBundled) {
                            fxBundled = (releaseProperties.getProperty("MODULES").contains("javafx"));
                            String[] modulesArray = releaseProperties.getProperty("MODULES").split("\s");
                            modules.addAll(Arrays.asList(modulesArray));
                        }
                        /*
                        if (releaseProperties.containsKey("SUN_ARCH_ABI")) {
                            String abi = releaseProperties.get("SUN_ARCH_ABI").toString();
                            switch (abi) {
                                case "gnueabi"   -> fpu = FPU.SOFT_FLOAT;
                                case "gnueabihf" -> fpu = FPU.HARD_FLOAT;
                            }
                        }
                        */
                    }
                }

                if (lines.length > 2) {
                    String line3 = lines[2].toLowerCase();
                    if (!PropertyManager.INSTANCE.hasKey(PropertyManager.PROPERTY_FEATURES)) {
                        PropertyManager.INSTANCE.setString(PropertyManager.PROPERTY_FEATURES, "loom,panama,metropolis,valhalla,lanai,kona_fiber,crac");
                        PropertyManager.INSTANCE.storeProperties();
                    }

                    String[] features = PropertyManager.INSTANCE.getString(PropertyManager.PROPERTY_FEATURES).split(",");
                    for (String feat : features) {
                            feat = feat.trim().toLowerCase();
                            if (line3.contains(feat)) {
                                feature = Feature.fromText(feat);
                                break;
                            }
                        }

                }

                if (name.equalsIgnoreCase("Mandrel")) {
                    buildScope = BuildScope.BUILD_OF_GRAALVM;
                    if (releaseProperties.containsKey("JAVA_VERSION")) {
                        final String javaVersion = releaseProperties.getProperty("JAVA_VERSION");
                        if (null == jdkVersion) { jdkVersion = VersionNumber.fromText(javaVersion); }
                    }
                }

                if (name.equals(Constants.UNKNOWN_BUILD_OF_OPENJDK) && lines.length > 2) {
                    String line3      = lines[2].toLowerCase();
                    File   readmeFile = new File(parentPath + "readme.txt");
                    if (readmeFile.exists()) {
                        try {
                            List<String> readmeLines = Helper.readTextFileToList(readmeFile.getAbsolutePath());
                            if (readmeLines.stream().filter(l -> l.toLowerCase().contains("liberica native image kit")).count() > 0) {
                                name       = "Liberica Native";
                                apiString  = "liberica_native";
                                buildScope = BuildScope.BUILD_OF_GRAALVM;

                                GRAALVM_VERSION_MATCHER.reset(line3);
                                final List<MatchResult> results = GRAALVM_VERSION_MATCHER.results().collect(Collectors.toList());
                                if (!results.isEmpty()) {
                                    MatchResult result = results.get(0);
                                    version = VersionNumber.fromText(result.group(2));
                                }
                                if (releaseProperties.containsKey("JAVA_VERSION")) {
                                    final String javaVersion = releaseProperties.getProperty("JAVA_VERSION");
                                    if (null == jdkVersion) { jdkVersion = VersionNumber.fromText(javaVersion); }
                                }
                            } else if (readmeLines.stream().filter(l -> l.toLowerCase().contains("liberica")).count() > 0) {
                                name      = "Liberica";
                                apiString = "liberica";
                            }
                        } catch (IOException e) {

                        }
                    } else {
                        if (line3.contains("graalvm") && !apiString.equals("graalvm_community") && !apiString.equals("graalvm")) {
                            name = "GraalVM CE";
                            String distroPreFix = "graalvm_ce";
                            if (releaseProperties.containsKey("IMPLEMENTOR")) {
                                switch(releaseProperties.getProperty("IMPLEMENTOR").replaceAll("\"", "")) {
                                    case "GraalVM Community"  -> {
                                        name         = "GraalVM CE";
                                        distroPreFix = "graalvm_ce";
                                    }
                                    case "GraalVM Enterprise" -> {
                                        name         = "GraalVM";
                                        distroPreFix = "graalvm";
                                    }
                                }
                            }
                            apiString  = graalVersion.getMajorVersion().getAsInt() >= 8 ? distroPreFix + graalVersion.getMajorVersion().getAsInt() : "";
                            buildScope = BuildScope.BUILD_OF_GRAALVM;

                            GRAALVM_VERSION_MATCHER.reset(line3);
                            final List<MatchResult> results = GRAALVM_VERSION_MATCHER.results().collect(Collectors.toList());
                            if (!results.isEmpty()) {
                                MatchResult result = results.get(0);
                                version = VersionNumber.fromText(result.group(2));
                            }

                            if (releaseProperties.containsKey("VENDOR")) {
                                final String vendor = releaseProperties.getProperty("VENDOR").toLowerCase().replaceAll("\"", "");
                                if (vendor.equalsIgnoreCase("Gluon")) {
                                    name       = "Gluon GraalVM CE";
                                    apiString  = "gluon_graalvm";
                                    buildScope = BuildScope.BUILD_OF_GRAALVM;
                                }
                            }
                            if (releaseProperties.containsKey("JAVA_VERSION")) {
                                final String javaVersion = releaseProperties.getProperty("JAVA_VERSION");
                                if (null == jdkVersion) { jdkVersion = VersionNumber.fromText(javaVersion); }
                            }
                        } else if (line3.contains("microsoft")) {
                            name      = "Microsoft";
                            apiString = "microsoft";
                        } else if (line3.contains("corretto")) {
                            name      = "Corretto";
                            apiString = "corretto";
                        } else if (line3.contains("temurin")) {
                            name      = "Temurin";
                            apiString = "temurin";
                        }
                    }
                }

                if (null == jdkVersion) { jdkVersion = version; }

                if (architecture.isEmpty()) { architecture = this.architecture.name().toLowerCase(); }

                // @ToDo: Assuming an unknown build of OpenJDK is a build of Oracle OpenJDK
                final boolean showUnknownBuildsOfOpenJDK = PropertyManager.INSTANCE.getBoolean(PropertyManager.PROPERTY_SHOW_UNKNOWN_BUILDS, false);
                if (showUnknownBuildsOfOpenJDK && name.equals(Constants.UNKNOWN_BUILD_OF_OPENJDK) && apiString.isEmpty()) {
                    apiString = "oracle_open_jdk";
                }

                Distro distributionFound = new Distro(name, apiString, version.toString(OutputFormat.REDUCED_COMPRESSED, true, true), Integer.toString(jdkVersion.getMajorVersion().getAsInt()), operatingSystem, architecture, fxBundled, parentPath, feature, buildScope, handledBySdkman, parentPath.substring(0, parentPath.lastIndexOf(File.separator)));
                distributionFound.setModules(modules);
                if (inUse.get()) { distributionFound.setInUse(true); }
                distros.add(distributionFound);
            });
            service.submit(streamer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void checkIfAlpineLinux() {
        if (OperatingSystem.WINDOWS == operatingSystem || OperatingSystem.MACOS == operatingSystem) { return; }
        try {
            Process p      = Runtime.getRuntime().exec(DETECT_ALPINE_CMDS);
            String  result = new BufferedReader(new InputStreamReader(p.getInputStream())).lines().collect(Collectors.joining("\n"));
            this.isAlpine  = null == result ? false : result.equals("1");
            if (this.isAlpine) { this.operatingSystem = OperatingSystem.ALPINE_LINUX; }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void getJavaHome() {
        try {
            ProcessBuilder processBuilder = OperatingSystem.WINDOWS == operatingSystem ? new ProcessBuilder(WIN_JAVA_HOME_CMDS) : OperatingSystem.MACOS == operatingSystem ? new ProcessBuilder(MAC_JAVA_HOME_CMDS) : new ProcessBuilder(LINUX_JAVA_HOME_CMDS);
            Process        process        = processBuilder.start();
            Streamer       streamer       = new Streamer(process.getInputStream(), d -> this.javaHome = d);
            service.submit(streamer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Map<Semver, SemverUri> checkForJavaFXUpdates(final List<String> javafxSearchPaths) {
        // Find the javafx sdk folders starting at the folder given by JAVAFX_SEARCH_PATH
        if (null == javafxSearchPaths || javafxSearchPaths.isEmpty()) { return new HashMap<>(); }

        Set<String> searchPaths = new HashSet<>();
        javafxSearchPaths.forEach(searchPath -> {
            try {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(searchPath))) {
                    for (Path path : stream) {
                        if (Files.isDirectory(path)) {
                            String folderName = path.getFileName().toString();
                            if (folderName.toLowerCase().startsWith("javafx")) {
                                searchPaths.add(String.join(File.separator, searchPath, folderName, "lib"));
                            }
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });

        // Check every update found for validity and only return the ones that are valid
        Map<Semver, SemverUri> validUpdatesFound  = new HashMap<>();
        Map<Semver, String>    javafxUpdatesFound = findJavaFX(searchPaths);
        javafxUpdatesFound.entrySet().forEach(entry -> {
            validUpdatesFound.put(entry.getKey(), checkForJavaFXUpdate(entry.getKey()));
        });
        return validUpdatesFound;
    }

    private Map<Semver, String> findJavaFX(final Set<String> searchPaths) {
        Map<Semver, String> versionsFound = new HashMap<>();
        searchPaths.forEach(searchPath -> {
            final String javafxPropertiesFilePath = new StringBuilder(searchPath).append(File.separator).append(javafxPropertiesFile).toString();
            Path path = Paths.get(javafxPropertiesFilePath);
            if (!Files.exists(path)) { return; }

            Properties javafxPropertes = new Properties();
            try (FileInputStream javafxPropertiesFileIS = new FileInputStream(javafxPropertiesFilePath)) {
                javafxPropertes.load(javafxPropertiesFileIS);
                String runtimeVersion = javafxPropertes.getProperty(Constants.JAVAFX_RUNTIME_VERSION, "");
                if (!runtimeVersion.isEmpty()) {
                    Semver versionFound = Semver.fromText(runtimeVersion).getSemver1();
                    versionsFound.put(versionFound, javafxPropertiesFilePath);
                }
            } catch (IOException ex) {
                System.out.println("Error reading javafx properties file. " + ex);
            }
        });
        return versionsFound;
    }

    private SemverUri checkForJavaFXUpdate(final Semver versionToCheck) {
       List<Semver> openjfxVersions         = getAvailableOpenJfxVersions();
       List<Semver> filteredOpenjfxVersions = openjfxVersions.stream()
                                                             .filter(semver -> semver.getFeature() == versionToCheck.getFeature())
                                                             .sorted(Comparator.comparing(Semver::getVersionNumber).reversed())
                                                             .collect(Collectors.toList());

       if (!filteredOpenjfxVersions.isEmpty()) {
           Semver latestVersion = filteredOpenjfxVersions.get(0);
           OperatingSystem operatingSystem = getOperatingSystem();
           Architecture    architecture    = Detector.getArchitecture();
           if (latestVersion.greaterThan(versionToCheck)) {
               StringBuilder linkBuilder = new StringBuilder();
               linkBuilder.append("https://download2.gluonhq.com/openjfx/").append(latestVersion.getFeature());
               if (latestVersion.getUpdate() > 0) {
                   linkBuilder.append(".").append(latestVersion.getInterim()).append(".").append(latestVersion.getUpdate());
                   if (latestVersion.getPatch() > 0) {
                       linkBuilder.append(".").append(latestVersion.getPatch());
                   }
               }
               linkBuilder.append("/openjfx-");
               if (latestVersion.getPre().isEmpty()) {
                   linkBuilder.append(latestVersion.getFeature());
                   if (latestVersion.getUpdate() > 0) {
                       linkBuilder.append(".").append(latestVersion.getInterim()).append(".").append(latestVersion.getUpdate());
                       if (latestVersion.getPatch() > 0) {
                           linkBuilder.append(".").append(latestVersion.getPatch());
                       }
                   }
                   linkBuilder.append("_");
               } else {
                   linkBuilder.append(latestVersion.toString(true)).append("_");
               }
               switch(operatingSystem) {
                   case WINDOWS -> linkBuilder.append("windows");
                   case LINUX   -> linkBuilder.append("linux");
                   case MACOS   -> linkBuilder.append("osx");
                   default      -> { return new SemverUri(latestVersion, ""); }
               }
               linkBuilder.append("-");
               switch(architecture) {
                   case X86            -> linkBuilder.append("x86");
                   case X64, AMD64     -> linkBuilder.append("x64");
                   case AARCH64, ARM64 -> linkBuilder.append("aarch64");
                   case AARCH32, ARM   -> linkBuilder.append("arm32");
                   default             -> { return new SemverUri(latestVersion, ""); }
               }
               linkBuilder.append("_bin-sdk.zip");
               final String uri = linkBuilder.toString();
               return new SemverUri(latestVersion, Helper.isUriValid(uri) ? uri : "");
           } else {
               return new SemverUri(latestVersion, "");
           }
       } else {
           return new SemverUri(versionToCheck, "");
       }
    }

    public List<Semver> getAvailableOpenJfxVersions() {
        List<Semver> availableOpenJfxVersions = new ArrayList<>();
        try {
            final DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            final DocumentBuilder db  = dbf.newDocumentBuilder();
            final Document        doc = db.parse(Constants.OPENJFX_MAVEN_METADATA);
            doc.getDocumentElement().normalize();
            final NodeList list = doc.getElementsByTagName("version");
            for (int i = 0; i < list.getLength(); i++) {
                Node node = list.item(i);
                availableOpenJfxVersions.add(Semver.fromText(node.getTextContent()).getSemver1());
            }
        } catch (ParserConfigurationException | SAXException | IOException e) {
            e.printStackTrace();
        }
        return availableOpenJfxVersions;
    }

    private class Streamer implements Runnable {
        private InputStream      inputStream;
        private Consumer<String> consumer;

        public Streamer(final InputStream inputStream, final Consumer<String> consumer) {
            this.inputStream = inputStream;
            this.consumer    = consumer;
        }

        @Override public void run() {
            final StringBuilder builder = new StringBuilder();
            new BufferedReader(new InputStreamReader(inputStream)).lines().forEach(line -> builder.append(line).append("|"));
            if (builder.length() > 0) {
                builder.setLength(builder.length() - 1);
            }
            consumer.accept(builder.toString());
        }
    }
}
