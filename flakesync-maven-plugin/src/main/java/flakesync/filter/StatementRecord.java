package flakesync.filter;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * One statement's worth of static-dependency information, used by
 * {@link DependencyFilter} to decide whether a candidate barrier-point line
 * is worth trying dynamically.
 *
 * - line: 1-based source line number of this statement's start.
 * - endLine: 1-based source line number of this statement's end (may equal
 *   line for single-line statements).
 * - identifiers: variable/field/method-call names referenced by this
 *   statement (including anything folded in from an enclosing branch
 *   condition — see DependencyFilter for details).
 * - syncResource: if this statement sits inside a `synchronized (X)` block,
 *   this is a normalized string representation of X (the lock expression's
 *   own referenced identifiers, joined). Null if not inside any
 *   synchronized block.
 * - text: the raw source line, trimmed, kept only for human-readable
 *   debugging/logging output.
 */
public final class StatementRecord {
    public final int line;
    public final int endLine;
    public final Set<String> identifiers;
    public final String syncResource; // nullable
    public final String text;

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
