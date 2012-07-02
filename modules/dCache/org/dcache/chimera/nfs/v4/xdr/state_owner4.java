/*
 * Automatically generated by jrpcgen 1.0.7 on 2/21/09 1:22 AM
 * jrpcgen is part of the "Remote Tea" ONC/RPC package for Java
 * See http://remotetea.sourceforge.net for details
 */
package org.dcache.chimera.nfs.v4.xdr;
import org.dcache.xdr.*;
import java.io.IOException;

public class state_owner4 implements XdrAble {
    public clientid4 clientid;
    public byte [] owner;

    public state_owner4() {
    }

    public state_owner4(XdrDecodingStream xdr)
           throws OncRpcException, IOException {
        xdrDecode(xdr);
    }

    public void xdrEncode(XdrEncodingStream xdr)
           throws OncRpcException, IOException {
        clientid.xdrEncode(xdr);
        xdr.xdrEncodeDynamicOpaque(owner);
    }

    public void xdrDecode(XdrDecodingStream xdr)
           throws OncRpcException, IOException {
        clientid = new clientid4(xdr);
        owner = xdr.xdrDecodeDynamicOpaque();
    }

}
// End of state_owner4.java