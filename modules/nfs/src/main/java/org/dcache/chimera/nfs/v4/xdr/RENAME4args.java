/*
 * Automatically generated by jrpcgen 1.0.7 on 2/21/09 1:22 AM
 * jrpcgen is part of the "Remote Tea" ONC/RPC package for Java
 * See http://remotetea.sourceforge.net for details
 */
package org.dcache.chimera.nfs.v4.xdr;
import org.dcache.chimera.nfs.v4.*;
import org.dcache.xdr.*;
import java.io.IOException;

public class RENAME4args implements XdrAble {
    public component4 oldname;
    public component4 newname;

    public RENAME4args() {
    }

    public RENAME4args(XdrDecodingStream xdr)
           throws OncRpcException, IOException {
        xdrDecode(xdr);
    }

    @Override
    public void xdrEncode(XdrEncodingStream xdr)
           throws OncRpcException, IOException {
        oldname.xdrEncode(xdr);
        newname.xdrEncode(xdr);
    }

    @Override
    public void xdrDecode(XdrDecodingStream xdr)
           throws OncRpcException, IOException {
        oldname = new component4(xdr);
        newname = new component4(xdr);
    }

}
// End of RENAME4args.java