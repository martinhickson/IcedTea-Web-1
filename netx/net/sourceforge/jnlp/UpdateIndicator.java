// Copyright (C) 2026 Martin Hickson
//
// This library is free software; you can redistribute it and/or
// modify it under the terms of the GNU Lesser General Public
// License as published by the Free Software Foundation; either
// version 2.1 of the License, or (at your option) any later version.
//
// This library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
// Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public
// License along with this library; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.

package net.sourceforge.jnlp;

import java.io.File;

import net.sourceforge.jnlp.cache.CacheEntry;
import net.sourceforge.jnlp.cache.UpdatePolicy;

/**
 * Decides whether one cached jar is still the same bytes the server just
 * confirmed, and which update policy the other jars should use.
 */
public final class UpdateIndicator {

    /** The indicator was not cached, so it was not checked and is not tracked yet. */
    public static final int NOT_CACHED = 0;
    /** The server check replaced or rejected the cached jar. The jar is already tracked. */
    public static final int CHANGED = 1;
    /** The cached jar still matches. The jar is already tracked. */
    public static final int UNCHANGED = 2;

    private UpdateIndicator() {
    }

    /**
     * Identity of one cache slot. Equality of two snapshots means the download
     * left that slot alone.
     */
    public static final class Snapshot {
        public final boolean cached;
        public final String path;
        public final long length;
        public final long fileModified;
        public final long catalogModified;
        public final long contentLength;

        public Snapshot(boolean cached, String path, long length, long fileModified,
                long catalogModified, long contentLength) {
            this.cached = cached;
            this.path = path;
            this.length = length;
            this.fileModified = fileModified;
            this.catalogModified = catalogModified;
            this.contentLength = contentLength;
        }
    }

    public static Snapshot snapshot(CacheEntry entry) {
        File file = entry.getLocalFile();
        boolean present = file != null && file.isFile();
        return new Snapshot(
                entry.isCached(),
                present ? file.getPath() : null,
                present ? file.length() : 0L,
                present ? file.lastModified() : 0L,
                entry.getLastModified(),
                entry.getRemoteContentLength());
    }

    /**
     * @return true only when both sides are cached copies of the same file,
     *         with the same length, filesystem mtime, HTTP Last-Modified, and
     *         stored content length
     */
    public static boolean unchanged(Snapshot before, Snapshot after) {
        if (before == null || after == null || !before.cached || !after.cached) {
            return false;
        }
        if (before.path == null || !before.path.equals(after.path)) {
            return false;
        }
        if (before.length <= 0L || before.length != after.length) {
            return false;
        }
        return before.fileModified == after.fileModified
                && before.catalogModified == after.catalogModified
                && before.contentLength == after.contentLength;
    }

    /**
     * Policy for one jar after the indicator probe.
     *
     * @param cacheable whether this jar may be cached
     * @param indicatorJar whether this jar is the update indicator
     * @param othersUnchanged whether the indicator probe said the rest can skip
     * @return FORCE, NEVER, or null for the runtime default policy
     */
    public static UpdatePolicy policy(boolean cacheable, boolean indicatorJar, boolean othersUnchanged) {
        if (!cacheable) {
            return UpdatePolicy.FORCE;
        }
        if (othersUnchanged && !indicatorJar) {
            return UpdatePolicy.NEVER;
        }
        return null;
    }
}
