/*
 * Copyright (c) 1994, 2003, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.tools.java;

import org.glassfish.rmic.TypeCode;

/**
 * This class represents an Java class type.
 * It overrides the relevant methods in class Type.
 *
 * WARNING: The contents of this source file are not part of any
 * supported API.  Code that depends on them does so at its own risk:
 * they are subject to change or removal without notice.
 *
 * @author      Arthur van Hoff
 */
public final
class ClassType extends Type {
    private static final char QUOTE = '"';
    /**
     * The fully qualified class name.
     */
    Identifier className;

    /**
     * Construct a class type. Use Type.tClass to create
     * a new class type.
     */
    ClassType(String typeSig, Identifier className) {
        super(TypeCode.CLASS, typeSig);
        this.className = className;
    }

    public Identifier getClassName() {
        return className;
    }

    public String typeString(String id, boolean abbrev, boolean ret) {
        String s = (abbrev ? getClassName().getFlatName() :
                                Identifier.lookup(getClassName().getQualifier(),
                                                                  getClassName().getFlatName())).toString();
        return (id.length() > 0) ? s + " " + id : s;
    }

    @Override
    public String toStringValue(Object value) {
        if (value == null || isStringType()) {
            return null;
        } else {
            return QUOTE + value.toString() + QUOTE;
        }
    }

    private boolean isStringType() {
        return !className.toString().equals(String.class.getName());
    }
}
