package tools.dependencygraph;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import fr.xephi.authme.initialization.ConstructorInjection;
import fr.xephi.authme.initialization.FieldInjection;
import fr.xephi.authme.initialization.Injection;
import tools.utils.ToolTask;
import tools.utils.ToolsConstants;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * Creates a DOT file of the dependencies in AuthMe classes.
 */
public class DrawDependency implements ToolTask {

    private static final String DOT_FILE = ToolsConstants.TOOLS_SOURCE_ROOT + "dependencygraph/graph.dot";
    // Package root
    private static final String ROOT_PACKAGE = "fr.xephi.authme";

    // Map with the graph's nodes: value is one of the key's dependencies
    private Multimap<Class<?>, String> foundDependencies = ArrayListMultimap.create();

    @Override
    public String getTaskName() {
        return "drawDependencyGraph";
    }

    @Override
    public void execute(Scanner scanner) {
        // Gather all connections
        readAndProcessFiles(new File(ToolsConstants.MAIN_SOURCE_ROOT));

        // Prompt user for simplification of graph
        System.out.println("Do you want to remove classes that are not used as dependency elsewhere?");
        System.out.println("Specify the number of times to do this: [0=keep all]");
        int stripVerticesCount;
        try {
            stripVerticesCount = Integer.valueOf(scanner.nextLine());
        } catch (NumberFormatException e) {
            stripVerticesCount = 0;
        }

        // Perform simplification as per user's wish
        for (int i = 0; i < stripVerticesCount; ++i) {
            stripNodesWithNoOutgoingEdges();
        }

        // Create dot file content
        final String pattern = "\t\"%s\" -> \"%s\";";
        String dotFile = "";
        for (Map.Entry<Class<?>, String> entry : foundDependencies.entries()) {
            dotFile += "\n" + String.format(pattern, entry.getValue(), entry.getKey().getSimpleName());
        }

        // Write dot file
        try {
            Files.write(Paths.get(DOT_FILE), ("digraph G {\n" + dotFile + "\n}").getBytes());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        System.out.println("Graph file written");
        System.out.format("Run 'dot -Tpng %s -o graph.png' to generate image (requires GraphViz)%n", DOT_FILE);
    }

    /**
     * Recursively reads the given directory and processes the files.
     *
     * @param dir the directory to read
     */
    private void readAndProcessFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            throw new IllegalStateException("Cannot read folder '" + dir + "'");
        }
        for (File file : files) {
            if (file.isDirectory()) {
                readAndProcessFiles(file);
            } else if (file.isFile()) {
                Class<?> clazz = loadClass(file);
                if (clazz != null) {
                    processClass(clazz);
                }
            }
        }
    }

    private void processClass(Class<?> clazz) {
        List<String> dependencies = getDependencies(clazz);
        if (dependencies != null) {
            foundDependencies.putAll(clazz, dependencies);
        }
    }

    // Load Class object for the class in the given file
    private static Class<?> loadClass(File file) {
        final String fileName = file.getPath().replace(File.separator, ".");
        if (!fileName.endsWith(".java")) {
            return null;
        }
        final String className = fileName
            .substring(fileName.indexOf(ROOT_PACKAGE), fileName.length() - ".java".length());
        try {
            return DrawDependency.class.getClassLoader().loadClass(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> getDependencies(Class<?> clazz) {
        Injection<?> injection = ConstructorInjection.provide(clazz).get();
        if (injection != null) {
            return formatInjectionDependencies(injection);
        }
        injection = FieldInjection.provide(clazz).get();
        return injection == null ? null : formatInjectionDependencies(injection);
    }

    /**
     * Formats the dependencies returned by the given injection appropriately:
     * if the dependency has an annotation, the annotation will be returned;
     * otherwise the class name.
     *
     * @param injection the injection whose dependencies should be formatted
     * @return list of dependencies in a friendly format
     */
    private static List<String> formatInjectionDependencies(Injection<?> injection) {
        Class<?>[] dependencies = injection.getDependencies();
        Class<?>[] annotations = injection.getDependencyAnnotations();

        List<String> result = new ArrayList<>(dependencies.length);
        for (int i = 0; i < dependencies.length; ++i) {
            if (annotations[i] != null) {
                result.add("@" + annotations[i].getSimpleName());
            } else {
                result.add(dependencies[i].getSimpleName());
            }
        }
        return result;
    }

    /**
     * Removes all vertices in the graph that have no outgoing edges, i.e. all classes
     * in the graph that only receive dependencies but are not used as a dependency anywhere.
     * This process can be repeated multiple times.
     */
    private void stripNodesWithNoOutgoingEdges() {
        Map<String, Boolean> dependencies = new HashMap<>();
        for (Map.Entry<Class<?>, String> entry : foundDependencies.entries()) {
            final String className = entry.getKey().getSimpleName();
            dependencies.put(className, Boolean.FALSE);
            dependencies.put(entry.getValue(), Boolean.TRUE);
        }

        Iterator<Class<?>> it = foundDependencies.keys().iterator();
        while (it.hasNext()) {
            Class<?> clazz = it.next();
            if (Boolean.FALSE.equals(dependencies.get(clazz.getSimpleName()))) {
                it.remove();
            }
        }
    }
}