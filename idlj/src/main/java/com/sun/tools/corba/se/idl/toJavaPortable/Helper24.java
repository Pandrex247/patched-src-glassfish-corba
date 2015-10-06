/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright (c) 1997-2010 Oracle and/or its affiliates. All rights reserved.
 * 
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 * 
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * 
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 * 
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 * 
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
/*
 * COMPONENT_NAME: idl.toJava
 *
 * ORIGINS: 27
 *
 * Licensed Materials - Property of IBM
 * 5639-D57 (C) COPYRIGHT International Business Machines Corp. 1997, 1999
 * RMI-IIOP v1.0
 * US Government Users Restricted Rights - Use, duplication or
 * disclosure restricted by GSA ADP Schedule Contract with IBM Corp.
 */

package com.sun.tools.corba.se.idl.toJavaPortable;

// NOTES:
// -D62023   <klr> New file to implement CORBA 2.4 RTF

import java.io.PrintWriter;

import java.util.Enumeration;
import java.util.Vector;

import com.sun.tools.corba.se.idl.GenFileStream;
import com.sun.tools.corba.se.idl.InterfaceEntry;
import com.sun.tools.corba.se.idl.MethodEntry;
import com.sun.tools.corba.se.idl.ParameterEntry;
import com.sun.tools.corba.se.idl.SymtabEntry;
import com.sun.tools.corba.se.idl.ValueEntry;
import com.sun.tools.corba.se.idl.ValueBoxEntry;
import com.sun.tools.corba.se.idl.TypedefEntry;
import com.sun.tools.corba.se.idl.InterfaceState;
import com.sun.tools.corba.se.idl.PrimitiveEntry;
import com.sun.tools.corba.se.idl.StructEntry;

/**
 *
 **/
public class Helper24 extends Helper
{
  /**
   * Public zero-argument constructor.
   **/
  public Helper24 ()
  {
  } // ctor

  /**
   * Generate the heading, including package, imports, class statements,
   * and open curly.
   * <d62023> - don't implement ValueHelper, make non-boxed helpers abstract
   **/
  protected void writeHeading ()
  {
    Util.writePackage (stream, entry, Util.HelperFile);
    Util.writeProlog (stream, stream.name ());

    // Transfer comment to target <30jul1997daz>.
    if (entry.comment () != null)
      entry.comment ().generate ("", stream);

    if (entry instanceof ValueBoxEntry) {
        stream.print   ("public final class " + helperClass);
        stream.println (" implements org.omg.CORBA.portable.BoxedValueHelper");
    }
    else
        stream.println ("abstract public class " + helperClass);
    stream.println ('{');
  }

  /**
   * Generate the instance variables.
   * <d62023> - no helper instance except for boxed valuetypes.
   *          - move truncatable_ids to mapped class.
   **/
  protected void writeInstVars ()
  {
    stream.println ("  private static String  _id = \"" + Util.stripLeadingUnderscoresFromID (entry.repositoryID ().ID ()) + "\";");
    if (entry instanceof ValueEntry)
    {
      stream.println ();
      if (entry instanceof ValueBoxEntry) {
          stream.println ("  private static " + helperClass + " _instance = new " + helperClass + " ();");
          stream.println ();
      }
    }
    stream.println ();
  } // writeInstVars

  /**
   * <d62023> generate members of BoxedValueHelper interface if boxed
   *
   * <d62023> Hook in here to write factory methods for non-boxed ValueTypes 
   *          into Helper.
   **/
  protected void writeValueHelperInterface ()
  {
    if (entry instanceof ValueBoxEntry) {
        writeGetID ();
    } else if (entry instanceof ValueEntry) {
        writeHelperFactories ();
    }
  } // writeValueHelperInterface

  /**
   *
   **/
  protected void writeHelperFactories ()
  {
    Vector init = ((ValueEntry)entry).initializers ();
    if (init != null)
    {
      stream.println ();
      for (int i = 0; i < init.size (); i++)
      {
        MethodEntry element = (MethodEntry) init.elementAt (i);
        element.valueMethod (true); //tag value method if not tagged previously
        ((MethodGen24) element.generator ()). helperFactoryMethod (symbolTable, element, entry, stream);
      }
    }
  } // writeHelperFactories

  /**
   * <d62023> Generate constructors only for boxed valuetype helpers
   *            All other helpers are abstract.
   **/
  protected void writeCtors ()
  {
    if (entry instanceof ValueBoxEntry) {
        stream.println ("  public " + helperClass + "()");
        stream.println ("  {");
        stream.println ("  }");
        stream.println ();
    }
  } // writeCtors
}
