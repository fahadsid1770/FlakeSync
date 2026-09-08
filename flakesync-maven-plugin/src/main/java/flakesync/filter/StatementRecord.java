package flakesync.filter;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * One statement's worth of static-dependency information, used by
 * {@link DependencyFilter} to decide whether a candidate barrier-point line
 * is worth trying dynamically.
 */
public final class StatementRecord {
    public final int line;        // 1-based start line of the statement
    public final int endLine;     // 1-based end line (may equal line)
    public final Set<String> identifiers; // variable/field/method-call names referenced
    public final String syncResource; // normalized lock expression, or null if not in a synchronized block
    public final String text;     // raw source line, trimmed, for logging

    public StatementRecord(int line, int endLine, Set<String> identifiers,
                          String syncResource, String text) {
        this.line = line;
        this.endLine = endLine;
        this.identifiers = identifiers == null
                ? Collections.emptySet() : new HashSet<>(identifiers);
        this.syncResource = syncResource;
        this.text = text == null ? "" : text;
    }

    @Override
    public String toString() {
        return "line=" + line + "-" + endLine + " sync=" + syncResource + " ids=" + identifiers;
    }
}
