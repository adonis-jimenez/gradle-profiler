package org.gradle.profiler;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigParseOptions;
import com.typesafe.config.ConfigValue;
import org.gradle.profiler.mutations.ApplyAbiChangeToJavaSourceFileMutator;
import org.gradle.profiler.mutations.ApplyChangetoAndroidManifestFileMutator;
import org.gradle.profiler.mutations.ApplyChangeToAndroidResourceFileMutator;
import org.gradle.profiler.mutations.ApplyChangeToPropertyResourceFileMutator;

import java.io.File;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

class ScenarioLoader {
    private static final String VERSIONS = "versions";
    private static final String TASKS = "tasks";
    private static final String GRADLE_ARGS = "gradle-args";
    private static final String RUN_USING = "run-using";
    private static final String SYSTEM_PROPERTIES = "system-properties";
    private static final String WARM_UP_COUNT = "warm-ups";
    private static final String APPLY_API_CHANGE_TO = "apply-abi-change-to";
    private static final String APPLY_ANDROID_RESOURCE_CHANGE_TO = "apply-android-resource-change-to";
    private static final String APPLY_PROPERTY_RESOURCE_CHANGE_TO = "apply-property-resource-change-to";
    private static final String APPLY_ANDROID_MANIFEST_CHANGE_TO = "apply-android-manifest-change-to";

    private static final List<String> ALL_SCENARIO_KEYS = Arrays.asList(
        VERSIONS, TASKS, GRADLE_ARGS, RUN_USING, SYSTEM_PROPERTIES, WARM_UP_COUNT,
        APPLY_API_CHANGE_TO, APPLY_ANDROID_RESOURCE_CHANGE_TO, APPLY_ANDROID_MANIFEST_CHANGE_TO,
        APPLY_PROPERTY_RESOURCE_CHANGE_TO
    );

    private final GradleVersionInspector gradleVersionInspector;

    public ScenarioLoader(GradleVersionInspector gradleVersionInspector) {
        this.gradleVersionInspector = gradleVersionInspector;
    }

    public List<ScenarioDefinition> loadScenarios(InvocationSettings settings) {
        List<ScenarioDefinition> scenarios = new ArrayList<>();
        if (settings.getScenarioFile() != null) {
            scenarios = loadScenarios(settings.getScenarioFile(), settings, gradleVersionInspector);
        } else {
            List<GradleVersion> versions = new ArrayList<>();
            for (String v : settings.getVersions()) {
                versions.add(gradleVersionInspector.resolve(v));
            }
            scenarios.add(new ScenarioDefinition("default", settings.getInvoker(), versions, settings.getTargets(), Collections.emptyList(), settings.getSystemProperties(), new BuildMutatorFactory(Collections.emptyList()), settings.getWarmUpCount()));
        }
        for (ScenarioDefinition scenario : scenarios) {
            if (scenario.getVersions().isEmpty()) {
                scenario.getVersions().add(gradleVersionInspector.defaultVersion());
            }
        }
        return scenarios;
    }

    private List<ScenarioDefinition> loadScenarios(File scenarioFile, InvocationSettings settings, GradleVersionInspector inspector) {
        List<ScenarioDefinition> definitions = new ArrayList<>();
        Config config = ConfigFactory.parseFile(scenarioFile, ConfigParseOptions.defaults().setAllowMissing(false));
        TreeSet<String> selectedScenarios = new TreeSet<>(config.root().keySet());
        if (!settings.getTargets().isEmpty()) {
            selectedScenarios.retainAll(settings.getTargets());
        }
        for (String scenarioName : selectedScenarios) {
            Config scenario = config.getConfig(scenarioName);
            for (String key : config.getObject(scenarioName).keySet()) {
                if (!ALL_SCENARIO_KEYS.contains(key)) {
                    throw new IllegalArgumentException("Unrecognized key '" + scenarioName + "." + key + "' found in scenario file " + scenarioFile);
                }
            }
            List<GradleVersion> versions = strings(scenario, VERSIONS, settings.getVersions()).stream().map(v -> inspector.resolve(v)).collect(
                    Collectors.toList());
            List<String> tasks = strings(scenario, TASKS, settings.getTargets());
            List<String> gradleArgs = strings(scenario, GRADLE_ARGS, Collections.emptyList());
            Invoker invoker = invoker(scenario, RUN_USING, settings.getInvoker());
            Map<String, String> systemProperties = map(scenario, SYSTEM_PROPERTIES, settings.getSystemProperties());
            int warmUpCount = integer(scenario, WARM_UP_COUNT, settings.getWarmUpCount());

            List<Supplier<BuildMutator>> mutators = new ArrayList<>();
            File sourceFileToChange = sourceFile(scenario, APPLY_API_CHANGE_TO, scenarioName, settings.getProjectDir());
            if (sourceFileToChange != null) {
                mutators.add(() -> new ApplyAbiChangeToJavaSourceFileMutator(sourceFileToChange));
            }

            File resourceFileToChange = sourceFile(scenario, APPLY_ANDROID_RESOURCE_CHANGE_TO, scenarioName, settings.getProjectDir());
            if (resourceFileToChange != null) {
                mutators.add(() -> new ApplyChangeToAndroidResourceFileMutator(resourceFileToChange));
            }

            File androidManifestToChange = sourceFile(scenario, APPLY_ANDROID_MANIFEST_CHANGE_TO, scenarioName, settings.getProjectDir());
            if(androidManifestToChange != null) {
                mutators.add(() -> new ApplyChangetoAndroidManifestFileMutator(androidManifestToChange));
            }

            File classpathResourceFileToChange = sourceFile(scenario, APPLY_PROPERTY_RESOURCE_CHANGE_TO, scenarioName, settings.getProjectDir());
            if (classpathResourceFileToChange != null) {
                mutators.add(() -> new ApplyChangeToPropertyResourceFileMutator(classpathResourceFileToChange));
            }

            definitions.add(new ScenarioDefinition(scenarioName, invoker, versions, tasks, gradleArgs, systemProperties, new BuildMutatorFactory(mutators), warmUpCount));
        }
        return definitions;
    }

    File sourceFile(Config config, String key, String scenarioName, File projectDir) {
        String sourceFileName = string(config, key, null);
        if (sourceFileName == null) {
            return null;
        }
        File sourceFile = new File(projectDir, sourceFileName);
        if (!sourceFile.isFile()) {
            throw new IllegalArgumentException("Source file " + sourceFile + " specified for scenario " + scenarioName + " does not exist.");
        }
        return sourceFile;
    }

    private Map<String, String> map(Config config, String key, Map<String, String> defaultValues) {
        if (config.hasPath(key)) {
            Map<String, String> props = new LinkedHashMap<>();
            for (Map.Entry<String, ConfigValue> entry : config.getConfig(key).entrySet()) {
                props.put(entry.getKey(), entry.getValue().unwrapped().toString());
            }
            return props;
        } else {
            return defaultValues;
        }
    }

    private static Invoker invoker(Config config, String key, Invoker defaultValue) {
        if (config.hasPath(key)) {
            String value = config.getAnyRef(key).toString();
            if (value.equals("no-daemon")) {
                return Invoker.NoDaemon;
            }
            if (value.equals("tooling-api")) {
                return Invoker.ToolingApi;
            }
            throw new IllegalArgumentException("Unexpected value for '" + key + "' provided: " + value);
        } else {
            return defaultValue;
        }
    }

    private static String string(Config config, String key, String defaultValue) {
        if (config.hasPath(key)) {
            return config.getString(key);
        } else {
            return defaultValue;
        }
    }

    private static int integer(Config config, String key, int defaultValue) {
        if (config.hasPath(key)) {
            return Integer.valueOf(config.getString(key));
        } else {
            return defaultValue;
        }
    }

    private static List<String> strings(Config config, String key, List<String> defaults) {
        if (config.hasPath(key)) {
            Object value = config.getAnyRef(key);
            if (value instanceof List) {
                List<?> list = (List) value;
                return list.stream().map(v -> v.toString()).collect(Collectors.toList());
            } else if (value.toString().length() > 0) {
                return Collections.singletonList(value.toString());
            }
        }
        return defaults;
    }
}
