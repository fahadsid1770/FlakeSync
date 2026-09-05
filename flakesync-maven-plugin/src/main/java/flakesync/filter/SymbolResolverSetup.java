package flakesync.filter;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.apache.maven.project.MavenProject;

import java.io.File;

public final class SymbolResolverSetup {

    private static volatile boolean configured = false;

    private SymbolResolverSetup() {
    }

    public static synchronized void configure(MavenProject mavenProject) {
        if (configured) {
            return;
        }
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());
        for (String root : mavenProject.getCompileSourceRoots()) {
            typeSolver.add(new JavaParserTypeSolver(new File(root)));
        }
        for (String root : mavenProject.getTestCompileSourceRoots()) {
            typeSolver.add(new JavaParserTypeSolver(new File(root)));
        }
        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
        StaticJavaParser.getConfiguration().setSymbolResolver(symbolSolver);
        configured = true;
    }
}
