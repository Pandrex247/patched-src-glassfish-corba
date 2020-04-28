/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 1997-2018 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://oss.oracle.com/licenses/CDDL+GPL-1.1
 * or LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at LICENSE.txt.
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

//
// Created       : 2003 Apr 15 (Tue) 15:36:45 by Harold Carr.
// Last Modified : 2003 Apr 15 (Tue) 16:36:40 by Harold Carr.
//

package corba.orbconfigappserv;

import org.omg.CORBA.INITIALIZE;
import com.sun.corba.ee.spi.orb.ORB ;
import com.sun.corba.ee.spi.orb.ORBConfigurator;
import com.sun.corba.ee.spi.orb.ParserImplBase;
import com.sun.corba.ee.spi.orb.PropertyParser;
import com.sun.corba.ee.spi.orb.DataCollector;
import com.sun.corba.ee.spi.orb.OperationFactory;

public class UserORBConfiguratorImpl 
    implements 
        ORBConfigurator 
{
    public static String propertyName = "userConfigProperty";

    private static class ConfigParser extends ParserImplBase {
        private ORB orb ;

        public ConfigParser( ORB orb ) {
            this.orb = orb ;
        }

        public Class testclass = 
            corba.orbconfigappserv.UserORBConfiguratorImpl.class;

        public PropertyParser makeParser()
        {
            PropertyParser parser = new PropertyParser() ;
            parser.add( propertyName,
                        OperationFactory.classAction(orb.classNameResolver()), 
                        "testclass" ) ;
            return parser ;
        }
    }

    public void configure( DataCollector dc, ORB orb ) {
        ConfigParser parser = new ConfigParser( orb );
        parser.init( dc );
        Class theTestclass = parser.testclass;

        if (theTestclass != null) {
            try {
                Object o = theTestclass.newInstance();
                System.out.println("UserORBConfiguratorImpl.configure: " + o);
            } catch (Exception ex) {
                throw new org.omg.CORBA.INITIALIZE(ex.toString());
            }
        }
    }   
}
