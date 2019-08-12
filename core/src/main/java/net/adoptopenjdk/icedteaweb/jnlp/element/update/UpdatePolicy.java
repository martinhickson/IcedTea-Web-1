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
 * The policy attribute indicates the preference for how the JNLP Client should handle an application
 * update when it is known an update is available before the application is launched, and can have
 * one of the following three values: "always", "prompt-update", and "prompt-run".
 * <p/>
 * @implSpec See <b>JSR-56, Section 3.6 Application Update</b>
 * for a detailed specification of this class.
 */
public enum UpdatePolicy {
    /**
     * A value of "always" (default) means to always download updates without any prompt.
     */
    ALWAYS("always"),

    /**
     * A value of "prompt-update" means to ask the user if he/she wants to download and run the
     * updated version, or launch the cached version.
     */
    PROMPT_UPDATE("prompt-update"),

    /**
     * A value of "prompt-run" means to ask the user if he/she wants to download and run the updated
     * version, or cancel and abort running the application.
     */
    PROMPT_RUN("prompt-run");

    private final String value;

    UpdatePolicy(final String value) {
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
