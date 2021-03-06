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
 * COMPONENT_NAME: idl.parser
 *
 * ORIGINS: 27
 *
 * Licensed Materials - Property of IBM
 * 5639-D57 (C) COPYRIGHT International Business Machines Corp. 1997, 1999
 * RMI-IIOP v1.0
 * US Government Users Restricted Rights - Use, duplication or
 * disclosure restricted by GSA ADP Schedule Contract with IBM Corp.
 *
 */

package com.sun.tools.corba.ee.idl;

// NOTES:

import java.io.PrintWriter;
import java.io.IOException;
import java.util.StringTokenizer;

public class Comment
{
  // Styles
  static final int UNKNOWN  = -1;
  static final int JAVA_DOC =  0;
  static final int C_BLOCK  =  1;
  static final int CPP_LINE =  2;

  // System-dependent line separator
  private static String _eol = System.getProperty ("line.separator");

  private String _text  = new String ("");
  private int    _style = UNKNOWN;

  Comment () {_text = new String (""); _style = UNKNOWN;} // ctor

  Comment (String text) {_text = text; _style = style (_text);} // ctor

  /** Sets comment text */
  public void text (String string) {_text = string; _style = style (_text);}

  /** Returns comment text */
  public String text () {return _text;}

  /** Returns the comment style of a string. */
  private int style (String text)
  {
    if (text == null)
      return UNKNOWN;
    else if (text.startsWith ("/**") && text.endsWith ("*/"))
      return JAVA_DOC;
    else if (text.startsWith ("/*") && text.endsWith ("*/"))
      return C_BLOCK;
    else if (text.startsWith ("//"))
      return CPP_LINE;
    else
      return UNKNOWN;
  } // style

  /** Writes comment text to standard output (debug). */
  public void write () {System.out.println (_text);}

  /** Writes comment text to the specified print stream in the appropriate format. */
  public void generate (String indent, PrintWriter printStream)
  {
    if (_text == null || printStream == null)
      return;
    if (indent == null)
      indent = new String ("");
    switch (_style)
    {
      case JAVA_DOC:
        //printJavaDoc (indent, printStream);
        print (indent, printStream);
        break;
      case C_BLOCK:
        //printCBlock (indent, printStream);
        print (indent, printStream);
        break;
      case CPP_LINE:
        //printCppLine (indent, printStream);
        print (indent, printStream);
        break;
      default:
        break;
    }
  } // generate

  /** Writes comment to the specified print stream without altering its format.
      This routine does not alter vertical or horizontal spacing of comment text,
      thus, it only works well for comments with a non-indented first line. */
  private void print (String indent, PrintWriter stream)
  {
    String text = _text.trim () + _eol;
    String line = null;

    int iLineStart = 0;
    int iLineEnd   = text.indexOf (_eol);
    int iTextEnd   = text.length () - 1;

    stream.println ();
    while (iLineStart < iTextEnd)
    {
      line = text.substring (iLineStart, iLineEnd);
      stream.println (indent + line);
      iLineStart = iLineEnd + _eol.length ();
      iLineEnd = iLineStart + text.substring (iLineStart).indexOf (_eol);
    }
  } // print

  /*
   *  The following routines print formatted comments of differing styles.
   *  Each routine will alter the horizontal spacing of the comment text,
   *  but not the vertical spacing.
   */

  /** Writes comment in JavaDoc-style to the specified print stream. */
  private void printJavaDoc (String indent, PrintWriter stream)
  {
    // Strip surrounding "/**", "*/", and whitespace; append sentinel
    String text = _text.substring (3, (_text.length () - 2)).trim () + _eol;
    String line = null;

    int iLineStart = 0;
    int iLineEnd   = text.indexOf (_eol);
    int iTextEnd   = text.length () - 1;   // index of last text character

    stream.println (_eol + indent + "/**");
    while (iLineStart < iTextEnd)
    {
      line = text.substring (iLineStart, iLineEnd).trim ();
      if (line.startsWith ("*"))
        // Strip existing "*<ws>" prefix
        stream.println (indent + " * " + line.substring (1, line.length ()).trim ());
      else
        stream.println (indent + " * " + line);
      iLineStart = iLineEnd + _eol.length ();
      iLineEnd = iLineStart + text.substring (iLineStart).indexOf (_eol);
    }
    stream.println (indent + " */");
  } // printJavaDoc

  /** Writes comment in c-block-style to the specified print stream. */
  private void printCBlock (String indent, PrintWriter stream)
  {
    // Strip surrounding "/*", "*/", and whitespace; append sentinel
    String text = _text.substring (2, (_text.length () - 2)).trim () + _eol;
    String line = null;

    int iLineStart = 0;
    int iLineEnd   = text.indexOf (_eol);
    int iTextEnd   = text.length () - 1;   // index of last text character

    stream.println (indent + "/*");
    while (iLineStart < iTextEnd)
    {
      line = text.substring (iLineStart, iLineEnd).trim ();
      if (line.startsWith ("*"))
        // Strip existing "*[ws]" prefix
        stream.println (indent + " * " + line.substring (1, line.length ()).trim ());
      else
        stream.println (indent + " * " + line);
      iLineStart = iLineEnd + _eol.length ();
      iLineEnd   = iLineStart + text.substring (iLineStart).indexOf (_eol);
    }
    stream.println (indent + " */");
  } // printCBlock

  /** Writes a line comment to the specified print stream. */
  private void printCppLine (String indent, PrintWriter stream)
  {
    stream.println (indent + "//");
    // Strip "//[ws]" prefix
    stream.println (indent + "// " + _text.substring (2).trim ());
    stream.println (indent + "//");
  } // printCppLine
} // class Comment


/*==================================================================================
  DATE<AUTHOR>   ACTION
  ----------------------------------------------------------------------------------
  11aug1997<daz> Initial version completed.
  18aug1997<daz> Modified generate to write comment unformatted.
  ==================================================================================*/

