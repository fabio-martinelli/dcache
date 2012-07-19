/*
 * Automatically generated by jrpcgen 1.0.7 on 2/21/09 1:22 AM
 * jrpcgen is part of the "Remote Tea" ONC/RPC package for Java
 * See http://remotetea.sourceforge.net for details
 */
package org.dcache.chimera.nfs.v3.xdr;
import org.dcache.xdr.*;
import java.io.IOException;

public class exportnode implements XdrAble {
    public dirpath ex_dir;
    public groups ex_groups;
    public exports ex_next;

    public exportnode() {
    }

    public exportnode(XdrDecodingStream xdr)
           throws OncRpcException, IOException {
        xdrDecode(xdr);
    }

    @Override
    public void xdrEncode(XdrEncodingStream xdr)
           throws OncRpcException, IOException {
        ex_dir.xdrEncode(xdr);
        ex_groups.xdrEncode(xdr);
        ex_next.xdrEncode(xdr);
    }

    @Override
    public void xdrDecode(XdrDecodingStream xdr)
           throws OncRpcException, IOException {
        ex_dir = new dirpath(xdr);
        ex_groups = new groups(xdr);
        ex_next = new exports(xdr);
    }

}
// End of exportnode.java