package org.omg.CORBA;

/**
* org/omg/CORBA/StructMemberHolder.java .
* IGNORE Generated by the IDL-to-Java compiler (portable), version "3.2"
* from idlj/src/main/java/com/sun/tools/corba/ee/idl/ir.idl
* IGNORE Sunday, January 21, 2018 1:54:22 PM EST
*/

public final class StructMemberHolder implements org.omg.CORBA.portable.Streamable
{
  public org.omg.CORBA.StructMember value = null;

  public StructMemberHolder ()
  {
  }

  public StructMemberHolder (org.omg.CORBA.StructMember initialValue)
  {
    value = initialValue;
  }

  public void _read (org.omg.CORBA.portable.InputStream i)
  {
    value = org.omg.CORBA.StructMemberHelper.read (i);
  }

  public void _write (org.omg.CORBA.portable.OutputStream o)
  {
    org.omg.CORBA.StructMemberHelper.write (o, value);
  }

  public org.omg.CORBA.TypeCode _type ()
  {
    return org.omg.CORBA.StructMemberHelper.type ();
  }

}
