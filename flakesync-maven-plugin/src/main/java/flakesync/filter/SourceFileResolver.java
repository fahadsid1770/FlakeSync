package flakesync.filter;

import org.apache.maven.project.MavenProject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves a fully-qualified class name (FlakeSync's location strings mix
 * both slash- and dot-separated forms in different places) to its .java
 * source file, by checking the Maven project's main and test compile
 * source roots.
 *
 * Returns null if not found in either root -- e.g. the class lives in a
 * dependency jar rather than this module's own source. Callers should
 * treat a null result as "can't filter this one, fall back to unfiltered."
 */
public final class SourceFileResolver {

    private SourceFileResolver() {
    }

    public static File resolve(String className, MavenProject mavenProject) {
        String normalized = className.replace('/', '.');
        // Inner/nested classes (Foo$Bar) live in their outer class's source
        // file, so strip the nested-class suffix before mapping to a path.
        int dollar = normalized.indexOf('$');
        if (dollar >= 0) {
            normalized = normalized.substring(0, dollar);
        }
        String relativePath = normalized.replace('.', File.separatorChar) + ".java";

        List<String> roots = new ArrayList<>();
        roots.addAll(mavenProject.getCompileSourceRoots());
        roots.addAll(mavenProject.getTestCompileSourceRoots());

        for (String root : roots) {
            File candidate = new File(root, relativePath);
            if (candidate.exists()) {
                return candidate;
            }
        }
        return null;
    }
}
