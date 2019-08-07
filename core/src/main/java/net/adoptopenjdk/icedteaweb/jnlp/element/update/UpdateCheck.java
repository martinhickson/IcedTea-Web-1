// Copyright (C) 2019 Karakun AG
//
// This library is free software; you can redistribute it and/or
// modify it under the terms of the GNU Lesser General Public
// License as published by the Free Software Foundation; either
// version 2.1 of the License, or (at your option) any later version.
//
// This library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
// Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public
// License along with this library; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
//
package net.adoptopenjdk.icedteaweb.jnlp.element.update;

import net.sourceforge.jnlp.Parser;

/**
 * The check attribute indicates the preference for when the JNLP Client should check for updates, and can have
 * one of the three values: "always", "timeout", and "background".
 * <p/>
 * @implSpec See <b>JSR-56, Section 3.6 Application Update</b>
 * for a detailed specification of this class.
 */
public enum UpdateCheck {
    /**
     * A value of "always" means to always check for updates before launching the application.
     */
    ALWAYS("always"),

    /**
     * A value of "timeout" (default) means to check for updates until timeout before launching the
     * application. If the update check is not completed before the timeout, the application is
     * launched, and the update check will continue in the background.
     */
    TIMEOUT("timeout"),

    /**
     * A value of "background" means to launch the application while checking for updates in the background.
     */
    BACKGROUND("background");

    private String value;

    UpdateCheck(final String value) {
        this.value = value;
    }

    /**
     * The attribute value name as used in the JSR-56 specification or the {@link Parser}.
     *
     * @return the attribute value name
     */
    public String getValue() {
        return value;
    }
}
