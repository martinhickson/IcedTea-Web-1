/* CacheDirectory.java -- Traverse the given directory and return the leafs.
   Copyright (C) 2010 Red Hat, Inc.

This file is part of IcedTea.

IcedTea is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License as published by
the Free Software Foundation, version 2.

IcedTea is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License
along with IcedTea; see the file COPYING.  If not, write to
the Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
02110-1301 USA.

Linking this library statically or dynamically with other modules is
making a combined work based on this library.  Thus, the terms and
conditions of the GNU General Public License cover the whole
combination.

As a special exception, the copyright holders of this library give you
permission to link this library with independent modules to produce an
executable, regardless of the license terms of these independent
modules, and to copy and distribute the resulting executable under
terms of your choice, provided that you also meet, for each linked
independent module, the terms and conditions of the license of that
module.  An independent module is a module which is not derived from
or based on this library.  If you modify this library, you may extend
this exception to your version of the library, but you are not
obligated to do so.  If you do not wish to do so, delete this
exception statement from your version.
*/
package net.sourceforge.jnlp.cache;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;

import net.sourceforge.jnlp.util.FileUtils;
import net.sourceforge.jnlp.util.logging.OutputController;

public final class CacheDirectory {

    /* Don't allow instantiation of this class */
    private CacheDirectory(){}
    
    /**
     * Legacy suffix. Do not create new {@code .info} sidecars — entry metadata
     * belongs on the cache catalog row.
     */
    public static final String INFO_SUFFIX = ".info";

    /**
     * Refuse to write a per-jar metadata sidecar. Callers that still try are
     * wrong: use {@link CacheLRUWrapper#putMeta(CacheEntryMeta)}.
     */
    public static void rejectInfoSidecar(File sidecar) {
        OutputController.getLogger().log(OutputController.Level.ERROR_ALL,
                "Refusing cache metadata file (use the catalog): " + sidecar);
    }

    /**
     * Cache-viewer rows from the catalog. Does not walk the cache tree or
     * read sidecar {@code .info} files.
     */
    public static ArrayList<Object[]> listViewerRows() {
        ArrayList<Object[]> data = new ArrayList<>();
        CacheLRUWrapper lru = CacheLRUWrapper.getInstance();
        synchronized (lru) {
            lru.lock();
            try {
                lru.load();
                for (CacheEntryMeta row : lru.listAllMeta()) {
                    if (row == null || row.path == null || row.path.isEmpty()) {
                        continue;
                    }
                    File f = new File(row.path);
                    String rel = row.resourceUrl;
                    if (rel == null || rel.isEmpty()) {
                        rel = CacheUtil.pathToURLPath(row.path, lru.getCacheDir().getFullPath());
                    }
                    String type = CacheUtil.protocolFromCacheRelativePath(rel);
                    String domain = CacheUtil.hostFromCacheRelativePath(rel);
                    long size = f.isFile() ? f.length()
                            : (row.contentLength == null ? 0L : row.contentLength.longValue());
                    Date modified = f.isFile() ? new Date(f.lastModified())
                            : new Date(row.lastModified == null ? 0L : row.lastModified.longValue());
                    DirectoryNode leaf = new DirectoryNode(f.getName(), f, null, row);
                    data.add(new Object[] {
                        leaf,
                        f.getParentFile(),
                        type == null ? "" : type,
                        domain == null ? "" : domain,
                        Long.valueOf(size),
                        modified,
                        row.jnlpPath
                    });
                }
            } finally {
                lru.unlock();
            }
        }
        return data;
    }

    /** Catalog, JNI extract, leftover sidecars — not cache resources. */
    static boolean isCacheInfrastructure(File f) {
        if (f == null) {
            return true;
        }
        String n = f.getName();
        if (n.endsWith(INFO_SUFFIX) || "native".equals(n)
                || n.equals(SqliteCacheCatalog.FAILED_MARKER)
                || n.startsWith(SqliteCacheCatalog.DB_FILE_NAME)) {
            return true;
        }
        return false;
    }

    /**
     * Removes empty folders in the current directory.
     * 
     * @param root File pointing at the beginning of directory.
     * @return True if something was deleted.
     */
    public static boolean cleanDir(File root) {
        boolean delete = true;
        for (File f : root.listFiles()) {
            if (f.isDirectory())
                cleanDir(f);
            else
                delete = false;
        }
        if (delete){
            OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL, "Delete -- " + root);
        }
        //            root.delete();
        return true;
    }

    /**
     * This will recursively remove the parent folders if they are empty. 
     * 
     * @param fileNode node of file which parent is going to be cleaned
     */
    public static void cleanParent(DirectoryNode fileNode) {
        if (fileNode == null || fileNode.getFile() == null) {
            return;
        }
        File cacheRoot = CacheLRUWrapper.getInstance().getCacheDir().getFile();
        cleanEmptyParents(fileNode.getFile().getParentFile(), cacheRoot);
    }

    /** Delete empty directories from {@code dir} up to, but not including, {@code cacheRoot}. */
    public static void cleanEmptyParents(File dir, File cacheRoot) {
        if (dir == null || cacheRoot == null) {
            return;
        }
        File stop = canonical(cacheRoot);
        File cur = dir;
        while (cur != null) {
            File canon = canonical(cur);
            if (canon.equals(stop)) {
                break;
            }
            if (!canon.getPath().startsWith(stop.getPath() + File.separator)
                    && !canon.getPath().startsWith(stop.getPath())) {
                break;
            }
            File[] kids = cur.listFiles();
            if (kids != null && kids.length > 0) {
                break;
            }
            File parent = cur.getParentFile();
            FileUtils.deleteWithErrMesg(cur);
            cur = parent;
        }
    }

    private static File canonical(File f) {
        try {
            return f.getCanonicalFile();
        } catch (java.io.IOException e) {
            return f.getAbsoluteFile();
        }
    }
}
