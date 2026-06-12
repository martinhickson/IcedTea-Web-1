/* Main.java
   Copyright (C) 2008 Red Hat, Inc.

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

package net.sourceforge.jnlp.about;

import static net.sourceforge.jnlp.runtime.Translator.R;

import java.awt.Frame;

import javax.swing.JDialog;
import javax.swing.border.EmptyBorder;

import net.sourceforge.jnlp.util.ScreenFinder;
import net.sourceforge.swing.SwingUtils;

/**
 * Standalone about dialog for javaws, policy editor, splash screens, etc.
 * Control panel embeds {@link AboutContentPanel} directly in the About tab.
 */
public final class AboutDialog implements Runnable {

    /** @deprecated use {@link AboutContentPanel.ShowPage} */
    public enum ShowPage {
        ABOUT,
        AUTHORS,
        NEWS,
        CHANGELOG,
        LICENSE,
        HELP,
        FAQ;

        private AboutContentPanel.ShowPage toContentPage() {
            return AboutContentPanel.ShowPage.valueOf(name());
        }
    }

    private final boolean modal;
    private final String app;
    private final ShowPage showPage;

    private AboutDialog(boolean modal, String app, ShowPage showPage) {
        this.modal = modal;
        this.app = app;
        this.showPage = showPage;
    }

    @Override
    public void run() {
        JDialog frame = new JDialog((Frame) null, R("AboutDialogueTabAbout") + " IcedTea-Web", modal);
        frame.setName("AboutDialog");
        SwingUtils.info(frame);
        AboutContentPanel content = new AboutContentPanel(app, showPage.toContentPage());
        content.setBorder(new EmptyBorder(8, 8, 8, 8));
        frame.setContentPane(content);
        frame.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        frame.pack();
        ScreenFinder.centerWindowsToCurrentScreen(frame);
        frame.setVisible(true);
    }

    public static void display(String app) {
        display(false, app);
    }

    public static void display(boolean modal, String app) {
        display(modal, app, ShowPage.ABOUT);
    }

    public static void display(boolean modal, String app, ShowPage showPage) {
        SwingUtils.invokeLater(new AboutDialog(modal, app, showPage));
    }
}
