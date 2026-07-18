/* BasicExceptionDialogTest.java
   Copyright (C) 2026 IcedTea-Web contributors.

This file is part of IcedTea.

IcedTea is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2, or (at your option)
any later version.

IcedTea is distributed in the hope that it will be useful, but
WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License
along with IcedTea; see the file COPYING.  If not, write to the
Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
02110-1301 USA.
 */
package net.sourceforge.jnlp.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BasicExceptionDialogTest {

    @Test
    public void buildCopyTraceTextIncludesMessageAndStack() {
        RuntimeException cause = new RuntimeException("root-cause");
        Exception ex = new Exception("outer-error", cause);

        String text = BasicExceptionDialog.buildCopyTraceText(ex);

        assertTrue(text.contains("outer-error"));
        assertTrue(text.contains("root-cause"));
        assertTrue(text.contains("RuntimeException") || text.contains("java.lang.RuntimeException"));
        assertTrue(text.contains("BasicExceptionDialogTest"));
    }

    @Test
    public void buildCopyTraceTextNullIsEmpty() {
        assertEquals("", BasicExceptionDialog.buildCopyTraceText(null));
    }
}
